package com.dpsmpx.dpai.android

import android.content.Context
import com.dpsmpx.dpai.automation.AutomationApi
import com.dpsmpx.dpai.automation.AutomationJson
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNull
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.orchestrator.control.ControlResponse

/** HTTP принимает только структурированный запрос; запуск не удерживает сетевое соединение. */
internal object AutomationControl {
    fun handle(context: Context, request: JsonObject): ControlResponse {
        fun bad(message: String) = response(Outcome.failure(DiagCode.VALUE_INVALID, message))
        if (request.fields.keys != setOf("project", "action", "input")) return bad("неверные поля запроса автоматизации")
        val project = ((request["project"] as? JsonString)?.value)?.let { com.dpsmpx.dpai.core.ids.IdKinds.PROJECT.parse(it).valueOrNull() }
            ?: return bad("нужен полный id проекта")
        val action = (request["action"] as? JsonString)?.value ?: return bad("нет действия")
        val input = request["input"] as? JsonObject ?: return bad("input — объект JSON")
        val store = AutomationWorkflows.store(context, project)
        if (Controller.get(context.filesDir).project()?.project != project) return response(
            Outcome.failure(DiagCode.STATE_CONFLICT, "откройте этот рабочий проект в приложении; CLI не переключает проект"))
        when (action) {
            "status" -> {
                if (input.fields.keys.any { it !in setOf("run", "workflow") }) return bad("неверный запрос результата")
                val expected = (input["run"] as? JsonString)?.value ?: return bad("нужен id запуска")
                val job = LongJobs.get(context).state.value[AutomationUiCalls.key(project)]
                if (job?.runId == expected) return ControlResponse(200, AutomationJson.forApi(JsonObject.of(
                    "run" to JsonString(expected), "status" to JsonString(if (job.running) "RUNNING" else if (job.failed) "FAILED" else "SUCCEEDED"),
                    "note" to (job.note?.let(::JsonString) ?: JsonNull),
                    "result" to (job.output.firstOrNull()?.let { JsonParser().parse(it).valueOrNull() } ?: JsonNull))))
                val workflow = (input["workflow"] as? JsonString)?.value
                if (workflow != null) when (val r = store.result(workflow, expected)) {
                    is Outcome.Failure -> return response(r)
                    is Outcome.Success -> r.value?.let { value ->
                        return ControlResponse(200, if (value["status"] == JsonString("RUNNING"))
                            JsonObject(value.fields + ("status" to JsonString("INCOMPLETE"))) else value)
                    }
                }
                return ControlResponse(200, JsonObject.of("run" to JsonString(expected), "status" to JsonString("INCOMPLETE"),
                    "note" to JsonString("запуск не найден в памяти; сверьте историю инструмента, команда сама не повторяется")))
            }
            "stop" -> {
                if (input.fields.keys != setOf("run")) return bad("нужен id останавливаемого запуска")
                val job = LongJobs.get(context).state.value[AutomationUiCalls.key(project)]
                if (job == null || job.runId != (input["run"] as? JsonString)?.value || !job.running)
                    return response(Outcome.failure(DiagCode.STATE_CONFLICT, "этот запуск уже не выполняется"))
                AutomationUiCalls.stop(project, expected = job.runId)
                return ControlResponse(200, JsonObject.of("status" to JsonString("STOP_REQUESTED")))
            }
            "workflows" -> {
                return when (val r = store.list()) {
                    is Outcome.Failure -> response(r)
                    is Outcome.Success -> response(com.dpsmpx.dpai.automation.WorkflowApi.list(r.value, input))
                }
            }
            "import" -> {
                // Из канала только создание выключенного сценария; включение и замена — человеком в UI.
                return response(AutomationWorkflows.save(context, project, input, null, imported = true))
            }
            "workflow-run" -> {
                if (input.fields.keys.any { it !in setOf("id", "revision", "event") }) return bad("нужны id и ревизия сценария; event необязателен")
                val id = (input["id"] as? JsonString)?.value ?: return bad("нет id")
                val revision = (input["revision"] as? JsonString)?.value ?: return bad("нет ревизии")
                val eventId = input["event"]?.let { (it as? JsonString)?.value ?: return bad("event — строка") }
                val event = eventId?.let {
                    val definition = when (val r = store.workflow(id)) {
                        is Outcome.Failure -> return response(r)
                        is Outcome.Success -> r.value ?: return bad("сценарий не найден")
                    }
                    com.dpsmpx.dpai.automation.AutomationEvent(it, com.dpsmpx.dpai.automation.AutomationTrigger.MANUAL,
                        definition.context, com.dpsmpx.dpai.core.hashing.Hashing.sha256(revision).hex)
                        .also { e -> if (!e.valid()) return bad("неверный event") }
                }
                var receipt = ""
                if (!AutomationUiCalls.startWorkflow(context, project, id, revision, event, cli = true, receipt = { receipt = it }))
                    return response(Outcome.failure(DiagCode.WORKTREE_OWNED, "автоматизация проекта занята"))
                val response = accepted(receipt)
                return response.copy(body = JsonObject(response.body.fields + ("event" to JsonString(eventId ?: receipt))))
            }
        }
        val opened = ApiProjectRuntime.open(context, project, AutomationUiCalls.key(project))
        val session = when (opened) { is Outcome.Failure -> return response(opened); is Outcome.Success -> opened.value }
        return session.use {
            val snapshot = when (val r = session.catalog()) { is Outcome.Failure -> return@use response(r); is Outcome.Success -> r.value }
            when (action) {
                "list" -> response(AutomationApi.list(snapshot.catalog, input))
                "describe" -> response(AutomationApi.describe(snapshot.catalog, input))
                "run" -> {
                    val call = when (val r = AutomationApi.call(input)) { is Outcome.Failure -> return@use response(r); is Outcome.Success -> r.value }
                    var receipt = ""
                    if (!AutomationUiCalls.start(context, project, call.id, call.revision, call.parameters, cli = true, receipt = { receipt = it }))
                        response(Outcome.failure(DiagCode.WORKTREE_OWNED, "автоматизация проекта занята"))
                    else accepted(receipt)
                }
                else -> bad("неизвестное действие автоматизации")
            }
        }
    }

    private fun accepted(receipt: String) = ControlResponse(202, JsonObject.of(
        "accepted" to JsonBool(true), "run" to JsonString(receipt),
        "note" to JsonString("подтверждения — в автоматизациях проекта на телефоне; результат запросите по run")))

    private fun response(result: Outcome<*>): ControlResponse = when (result) {
        is Outcome.Failure -> ControlResponse(409, JsonObject.of("status" to JsonString("FAILED"),
            "diagnostics" to JsonArray(result.diagnostics.map { JsonObject.of("code" to JsonString(it.code.name),
                "message" to JsonString(com.dpsmpx.dpai.core.diagnostics.Redactor.STANDARD.redact(it.message))) })))
        is Outcome.Success -> ControlResponse(200, result.value as? JsonObject ?: JsonObject.of("status" to JsonString("SUCCEEDED")))
    }
}
