package com.dpsmpx.dpai.execution.claude

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.ProviderSessionId
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.json
import com.dpsmpx.dpai.execution.agent.AgentClient
import com.dpsmpx.dpai.execution.agent.AgentReply
import com.dpsmpx.dpai.execution.agent.ExitReason
import com.dpsmpx.dpai.execution.agent.MockAgentHost
import com.dpsmpx.dpai.execution.agent.RunEventSource
import com.dpsmpx.dpai.execution.agent.RunSpec

/**
 * Сценарии `MockRunner` — ТЗ, §89: успех, отказ, тайм-аут, падение,
 * медленный вывод, повтор и пропуск событий, предел, ошибка входа,
 * частичное завершение.
 */
enum class MockScenario {
    SUCCESS, FAILURE, TIMEOUT, CRASH, SLOW_OUTPUT, DUPLICATE_EVENTS, MISSING_EVENTS, RATE_LIMIT, AUTH_ERROR, PARTIAL_COMPLETION,

    /** Как [SLOW_OUTPUT], но в начале хода — вызов инструмента `Read` (замер D-41 подхваченного хода, ревью A-14). */
    SLOW_TOOLS,

    /** Ход ревью (D-59) с ответом по `--json-schema`: одно серьёзное замечание, блокирующих нет. */
    REVIEW_PASS,

    /** Ход ревью с блокирующей находкой. */
    REVIEW_BLOCK,

    /** Ход ревью без ответа по схеме — только текст. */
    REVIEW_NO_SCHEMA,
}

/**
 * Раннер без Claude и без процессов (ТЗ, §12, §89): настоящий
 * [ClaudeCodeCliRunner] поверх [MockAgentHost], у которого «процесс» хода
 * пишет поток `stream-json` по сценарию — в формате, записанном с Claude
 * Code (`tests/data/claude-stream/`). Так сценарии проверяют ту же цепочку
 * «агент → канал → разбор → итог хода», что и с настоящим Claude.
 *
 * `SLOW_OUTPUT` отдаёт по строке на каждый [advance]; `MISSING_EVENTS`
 * теряет событие в первом ответе агента — канал закрывает пропуск сверкой.
 */
class MockRunner private constructor(
    private val runner: ClaudeCodeCliRunner,
    val host: MockAgentHost,
) : ClaudeRunner by runner {
    /** Следующий шаг медленных сценариев: одна строка вывода или выход. */
    fun advance(session: SessionId): Boolean = host.advance(session)

    companion object {
        /**
         * [host] — target; передать тот же хост новому раннеру — значит
         * перезапустить оркестратор, а target с его запусками оставить.
         */
        fun create(
            clock: Clock,
            random: RandomSource,
            host: MockAgentHost = MockAgentHost(clock, IdGenerator(clock, random)),
            scenarioFor: (SessionId, Int) -> MockScenario,
        ): MockRunner {
            var dropOnce = HashSet<SessionId>()
            val client = AgentClient(host, listOf("dpai"), null, "target mock")
            // Пропуск событий: первый ответ агента для такой сессии теряет третье событие.
            val lossy = object : RunEventSource {
                override fun events(run: SessionId, after: Long, limit: Int): Outcome<AgentReply> =
                    client.events(run, after, limit).let { r ->
                        if (r is Outcome.Success && run in dropOnce && r.value.events.size >= 3) {
                            dropOnce = HashSet(dropOnce - run)
                            Outcome.success(r.value.copy(events = r.value.events.filterIndexed { i, _ -> i != 2 }))
                        } else {
                            r
                        }
                    }
            }
            val runner = ClaudeCodeCliRunner(client, { ProviderSessionId.newUuid(random) }, eventSource = lossy)
            host.onStart = { run, spec, turn ->
                val scenario = scenarioFor(run, turn)
                val steps = ArrayDeque<() -> Unit>()
                for (l in script(scenario, sessionOf(spec))) steps.addLast { host.output(run, l) }
                val exitCode: Int? = when (scenario) {
                    MockScenario.TIMEOUT -> null
                    MockScenario.CRASH -> 137
                    MockScenario.FAILURE, MockScenario.RATE_LIMIT, MockScenario.AUTH_ERROR, MockScenario.PARTIAL_COMPLETION -> 1
                    else -> 0
                }
                val reason = if (scenario == MockScenario.TIMEOUT) ExitReason.TIMEOUT else ExitReason.EXITED
                steps.addLast { host.exit(run, exitCode, reason) }
                if (scenario == MockScenario.MISSING_EVENTS) dropOnce = HashSet(dropOnce + run)
                if (scenario == MockScenario.SLOW_OUTPUT || scenario == MockScenario.SLOW_TOOLS) {
                    host.schedule(run, steps)
                } else {
                    while (steps.isNotEmpty()) steps.removeFirst()()
                }
            }
            return MockRunner(runner, host)
        }

        private fun sessionOf(spec: RunSpec): String {
            val c = spec.command
            val i = c.indexOfFirst { it == "--session-id" || it == "--resume" }
            return c[i + 1]
        }

        private fun line(vararg pairs: Pair<String, com.dpsmpx.dpai.core.serialization.JsonValue>): String = CanonicalJson.write(JsonObject.of(*pairs))

        private fun init(sid: String, uuid: String) =
            line("type" to json("system"), "subtype" to json("init"), "session_id" to json(sid), "model" to json("mock"), "apiKeySource" to json("none"), "uuid" to json(uuid))

        private fun text(sid: String, uuid: String, text: String, error: String? = null): String {
            val content = JsonArray(listOf(JsonObject.of("type" to json("text"), "text" to json(text))))
            val pairs = mutableListOf("type" to json("assistant"), "message" to JsonObject.of("content" to content), "session_id" to json(sid), "uuid" to json(uuid))
            error?.let { pairs += "error" to json(it) }
            return line(*pairs.toTypedArray())
        }

        private fun result(sid: String, uuid: String, isError: Boolean, subtype: String, text: String, structured: JsonObject? = null) = line(
            *listOfNotNull(
                "type" to json("result"), "subtype" to json(subtype), "is_error" to JsonBool(isError), "result" to json(text),
                "session_id" to json(sid), "uuid" to json(uuid), "num_turns" to json(1),
                "usage" to JsonObject.of("input_tokens" to json(10), "output_tokens" to json(5)),
                structured?.let { "structured_output" to it },
            ).toTypedArray(),
        )

        /** Ответ ревью по схеме D-59: одна находка уровня [severity]. */
        private fun findings(severity: String, title: String) = JsonObject.of(
            "findings" to JsonArray(listOf(JsonObject.of("severity" to json(severity), "title" to json(title), "file" to json("src/a.kt"), "line" to json(3)))),
        )

        private fun rateLimit(sid: String, uuid: String, status: String) = line(
            "type" to json("rate_limit_event"), "session_id" to json(sid), "uuid" to json(uuid),
            "rate_limit_info" to JsonObject.of("status" to json(status), "rateLimitType" to json("five_hour"), "resetsAt" to json(1_767_243_600L)),
        )

        /** Поток хода по сценарию; формы событий — как в корпусе `tests/data/claude-stream/`. */
        fun script(s: MockScenario, sid: String): List<String> = when (s) {
            MockScenario.SUCCESS, MockScenario.SLOW_OUTPUT, MockScenario.MISSING_EVENTS ->
                listOf(init(sid, "u1"), text(sid, "u2", "работаю"), text(sid, "u3", "готово"), result(sid, "u4", false, "success", "готово"))
            MockScenario.DUPLICATE_EVENTS -> listOf(init(sid, "u1"), text(sid, "u2", "работаю"), text(sid, "u2", "работаю"), result(sid, "u4", false, "success", "готово"))
            MockScenario.FAILURE -> listOf(init(sid, "u1"), result(sid, "u2", true, "error_during_execution", "сбой"))
            MockScenario.TIMEOUT -> listOf(init(sid, "u1"), text(sid, "u2", "долго думаю"))
            MockScenario.CRASH -> listOf(init(sid, "u1"), text(sid, "u2", "работаю"))
            MockScenario.RATE_LIMIT -> listOf(init(sid, "u1"), rateLimit(sid, "u2", "rejected"), text(sid, "u3", "limit", "rate_limit"), result(sid, "u4", true, "success", "limit"))
            MockScenario.AUTH_ERROR -> listOf(init(sid, "u1"), text(sid, "u2", "Not logged in", "authentication_failed"), result(sid, "u3", true, "success", "Not logged in"))
            MockScenario.PARTIAL_COMPLETION -> listOf(init(sid, "u1"), text(sid, "u2", "часть сделана"), result(sid, "u3", true, "error_max_turns", ""))
            MockScenario.SLOW_TOOLS -> listOf(
                init(sid, "u1"),
                line(
                    "type" to json("assistant"), "session_id" to json(sid), "uuid" to json("u2"),
                    "message" to JsonObject.of("content" to JsonArray(listOf(JsonObject.of("type" to json("tool_use"), "id" to json("t1"), "name" to json("Read"), "input" to JsonObject.of("file_path" to json("a.kt")))))),
                ),
                text(sid, "u3", "работаю"), text(sid, "u4", "готово"), result(sid, "u5", false, "success", "готово"),
            )
            MockScenario.REVIEW_PASS ->
                listOf(init(sid, "u1"), result(sid, "u2", false, "success", "ревью готово", findings("MAJOR", "нет теста на пустой ввод")))
            MockScenario.REVIEW_BLOCK ->
                listOf(init(sid, "u1"), result(sid, "u2", false, "success", "ревью готово", findings("BLOCKING", "деление на ноль при пустом списке")))
            MockScenario.REVIEW_NO_SCHEMA -> listOf(init(sid, "u1"), text(sid, "u2", "замечаний нет"), result(sid, "u3", false, "success", "замечаний нет"))
        }
    }
}
