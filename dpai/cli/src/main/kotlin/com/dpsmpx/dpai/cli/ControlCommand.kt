package com.dpsmpx.dpai.cli

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.storage.vfs.Vfs
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Paths
import java.time.Duration

/** Ответ канала: код HTTP и тело (null — канал недоступен, [error] — почему). */
data class HttpReply(val status: Int, val body: JsonObject?, val error: String? = null)

/** Транспорт к приложению: метод, путь, токен, тело. Подменяется в тестах. */
fun interface ControlTransport {
    fun call(method: String, path: String, token: String?, body: JsonObject?): HttpReply
}

/** HTTP к `127.0.0.1:<port>` (или `DPAI_CONTROL_URL`, например туннель SSH с ПК). */
class HttpTransport(private val base: String) : ControlTransport {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    override fun call(method: String, path: String, token: String?, body: JsonObject?): HttpReply = try {
        val b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(60)).header("Content-Type", "application/json")
        token?.let { b.header("Authorization", "Bearer $it") }
        val publisher = body?.let { HttpRequest.BodyPublishers.ofString(CanonicalJson.write(it)) } ?: HttpRequest.BodyPublishers.noBody()
        val r = client.send(b.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        HttpReply(r.statusCode(), JsonParser().parse(r.body()).valueOrNull() as? JsonObject)
    } catch (e: Exception) {
        HttpReply(0, null, "${e.javaClass.simpleName}: ${e.message}")
    }
}

/**
 * `dpai` как пульт приложения (D-47): те же команды, что на экране, —
 * через канал на loopback. Сопряжение — `dpai control pair <код>`; токен —
 * в `~/.config/dpai/control.json` с правами `0600`. Опасное — только с
 * `--yes` (ТЗ, §68). Id можно сокращать до однозначного начала или конца.
 */
class ControlCommand(
    private val out: (String) -> Unit,
    private val err: (String) -> Unit,
    private val config: Vfs = defaultConfig(),
    private val transport: (Int) -> ControlTransport = { port -> HttpTransport(System.getenv("DPAI_CONTROL_URL") ?: "http://127.0.0.1:$port") },
) {
    private val keys = IdGenerator(Clock.SYSTEM, RandomSource.secure())
    private var json = false

    fun run(cmd: String, all: List<String>): Int {
        json = "--json" in all
        val args = all.filter { it != "--json" }
        return when (cmd) {
            "control" -> control(args)
            "status" -> status(args)
            "task" -> task(args)
            "session" -> session(args)
            "requests" -> requests()
            "answer" -> answer(args)
            "limit" -> limit(args)
            "target", "slot" -> toggle(cmd, args)
            "sessions-limit" -> sessionsLimit(args)
            else -> usage("неизвестная команда: $cmd")
        }
    }

    // ---- сопряжение ----

    private fun control(args: List<String>): Int = when (args.firstOrNull()) {
        "pair" -> {
            val code = args.getOrNull(1) ?: return usage("dpai control pair <код> [--port N]")
            val port = opt(args, "--port")?.toIntOrNull() ?: DEFAULT_PORT
            val r = transport(port).call("POST", "/v1/pair", null, JsonObject.of("code" to JsonString(code)))
            val token = (r.body?.get("token") as? JsonString)?.value
            if (r.status != 200 || token == null) {
                fail(r)
            } else {
                config.write(FILE, CanonicalJson.write(JsonObject.of("port" to JsonNumber.of(port), "token" to JsonString(token))).toByteArray())
                config.fsync(FILE)
                restrict()
                out("сопряжено: 127.0.0.1:$port, токен — в ~/.config/dpai/$FILE")
                OK
            }
        }
        "forget" -> {
            config.delete(FILE)
            out("токен удалён")
            OK
        }
        else -> usage("dpai control pair <код> [--port N] | dpai control forget")
    }

    // ---- чтение ----

    private fun status(args: List<String>): Int {
        val s = state() ?: return FAILED
        if (json) return print(s)
        val all = list(s, "tasks")
        val tasks = all.filter { !bool(it, "archived") || "--all" in args }
        out("Задачи (${tasks.size}${if (tasks.size < all.size) ", в архиве ещё ${all.size - tasks.size}: dpai status --all" else ""}):")
        for (t in tasks) {
            val extra = listOfNotNull(
                "архив".takeIf { bool(t, "archived") }, "пауза".takeIf { bool(t, "held") }, str(t, "model"), str(t, "reason"), str(t, "waiting")?.let { "ждёт: $it" },
            ).joinToString(" · ")
            out("  ${short(str(t, "id"))}  ${str(t, "state")}  ${str(t, "priority")}  ${str(t, "title")}${if (extra.isNotEmpty()) "  [$extra]" else ""}")
        }
        val live = list(s, "sessions").filter { str(it, "state") !in setOf("COMPLETED", "FAILED", "CANCELLED") }
        out("Сессии идут (${live.size}):")
        for (x in live) out("  ${short(str(x, "id"))}  ${str(x, "state")}  ход ${num(x, "turn")}  задача ${short(str(x, "task"))}")
        val reqs = list(s, "requests")
        if (reqs.isNotEmpty()) out("Запросы ждут ответа: ${reqs.size} (dpai requests)")
        for (sub in list(s, "subscriptions")) {
            val windows = list(sub, "windows").joinToString(", ") { w ->
                val name = if (str(w, "window") == "SESSION") "5 ч" else "неделя"
                "$name ${num(w, "percent")?.let { "$it %" } ?: "?"}"
            }
            out("Подписка «${str(sub, "label")}»: $windows; порог ${num(sub, "nearPercent")} %${if (bool(sub, "near")) " — у предела" else ""}")
        }
        val control = s["control"] as? JsonObject
        control?.let { c -> num(c, "maxSessions")?.let { out("Предел сессий контура: $it") } }
        return OK
    }

    private fun requests(): Int {
        val s = state() ?: return FAILED
        val reqs = list(s, "requests")
        if (json) return print(JsonObject.of("requests" to JsonArray(reqs)))
        if (reqs.isEmpty()) out("ждущих запросов нет")
        for (r in reqs) {
            out("${short(str(r, "id"))}  ${str(r, "kind")}  ${str(r, "title")}")
            str(r, "body")?.let { out("    $it") }
            for (o in list(r, "options")) out("    ${str(o, "id")}: ${str(o, "label")}${if (bool(o, "needsText")) " (нужен --text)" else ""}${if (str(o, "role") == "DESTRUCTIVE") " (опасно: --yes)" else ""}")
        }
        return OK
    }

    // ---- команды ----

    private fun task(args: List<String>): Int {
        if (args.firstOrNull() == "archive-done") return send(linkedMapOf("command" to JsonString("tasks.archive-done")))
        if (args.firstOrNull() == "add") {
            val title = args.getOrNull(1) ?: return usage(TASK_USAGE)
            val objective = args.getOrNull(2)?.takeIf { !it.startsWith("--") } ?: return usage(TASK_USAGE)
            val c = linkedMapOf<String, JsonValue>("command" to JsonString("task.add"), "title" to JsonString(title), "objective" to JsonString(objective))
            c["priority"] = JsonString(opt(args, "--priority")?.uppercase() ?: "NORMAL")
            c["taskClass"] = JsonString(opt(args, "--class")?.uppercase() ?: "NORMAL_ENGINEERING")
            opt(args, "--model")?.let { c["model"] = JsonString(it) }
            opt(args, "--effort")?.let { c["effort"] = JsonString(it) }
            opt(args, "--budget")?.let { budget(it) ?: return usage("--budget — доллары, например 0.25") }?.let { c["budgetCents"] = JsonNumber.of(it) }
            return send(c)
        }
        val id = args.getOrNull(0) ?: return usage(TASK_USAGE)
        val action = args.getOrNull(1) ?: return usage(TASK_USAGE)
        if (action == "messages") return messages(id)
        val task = resolve(id, "tasks") ?: return FAILED
        val c = linkedMapOf<String, JsonValue>("task" to JsonString(task))
        when (action) {
            "hold", "resume", "cancel", "verify" -> c["command"] = JsonString("task.$action")
            "archive", "unarchive" -> {
                c["command"] = JsonString("task.archive")
                c["archived"] = JsonBool(action == "archive")
            }
            "retry" -> {
                c["command"] = JsonString("task.retry")
                opt(args, "--text")?.let { c["text"] = JsonString(it) }
            }
            "waive" -> {
                c["command"] = JsonString("task.waive")
                c["reason"] = JsonString(opt(args, "--reason") ?: return usage("dpai task <id> waive --reason «почему» --yes"))
            }
            "priority" -> {
                c["command"] = JsonString("task.priority")
                c["priority"] = JsonString(args.getOrNull(2)?.uppercase() ?: return usage("dpai task <id> priority CRITICAL|HIGH|NORMAL|LOW|BACKGROUND"))
            }
            "policy" -> {
                c["command"] = JsonString("task.policy")
                c["taskClass"] = JsonString(opt(args, "--class")?.uppercase() ?: return usage("dpai task <id> policy --class <класс> [--model m] [--effort e] [--budget $]"))
                opt(args, "--model")?.let { c["model"] = JsonString(it) }
                opt(args, "--effort")?.let { c["effort"] = JsonString(it) }
                opt(args, "--budget")?.let { budget(it) ?: return usage("--budget — доллары") }?.let { c["budgetCents"] = JsonNumber.of(it) }
            }
            else -> return usage(TASK_USAGE)
        }
        return send(c, confirmed = "--yes" in args)
    }

    private fun messages(id: String): Int {
        val task = resolve(id, "tasks") ?: return FAILED
        val r = call("GET", "/v1/tasks/$task/messages", null) ?: return FAILED
        if (json) return print(r)
        val ms = list(r, "messages")
        if (ms.isEmpty()) out("итоговых сообщений ходов пока нет")
        for (m in ms) {
            out("── ход ${num(m, "turn")} · ${str(m, "status")}${str(m, "model")?.let { " · $it" } ?: ""}")
            out(str(m, "text").orEmpty())
        }
        return OK
    }

    private fun session(args: List<String>): Int {
        val id = args.getOrNull(0) ?: return usage(SESSION_USAGE)
        val session = resolve(id, "sessions") ?: return FAILED
        val c = linkedMapOf<String, JsonValue>("session" to JsonString(session))
        when (args.getOrNull(1)) {
            "stop" -> c["command"] = JsonString("session.stop")
            "send" -> {
                c["command"] = JsonString("session.send")
                c["text"] = JsonString(args.getOrNull(2)?.takeIf { !it.startsWith("--") } ?: return usage(SESSION_USAGE))
            }
            "fresh" -> {
                c["command"] = JsonString("session.fresh")
                args.getOrNull(2)?.takeIf { !it.startsWith("--") }?.let { c["text"] = JsonString(it) }
            }
            else -> return usage(SESSION_USAGE)
        }
        opt(args, "--model")?.let { c["model"] = JsonString(it) }
        opt(args, "--effort")?.let { c["effort"] = JsonString(it) }
        return send(c)
    }

    private fun answer(args: List<String>): Int {
        val id = args.getOrNull(0) ?: return usage("dpai answer <запрос> <вариант> [--text «…»] [--yes]")
        val option = args.getOrNull(1) ?: return usage("dpai answer <запрос> <вариант> [--text «…»] [--yes]")
        val s = state() ?: return FAILED
        val req = pick(list(s, "requests"), id, "запрос") ?: return FAILED
        val uri = list(req, "options").firstOrNull { str(it, "id") == option }?.let { str(it, "uri") }
            ?: return fail("у запроса нет варианта «$option»: ${list(req, "options").joinToString { str(it, "id").orEmpty() }}")
        val c = linkedMapOf<String, JsonValue>("command" to JsonString("request.answer"), "uri" to JsonString(uri), "channel" to JsonString("cli"))
        opt(args, "--text")?.let { c["text"] = JsonString(it) }
        return send(c, confirmed = "--yes" in args, key = false)
    }

    private fun limit(args: List<String>): Int {
        val percent = args.lastOrNull()?.removeSuffix("%")?.toIntOrNull() ?: return usage("dpai limit [<подписка>] <процент>")
        val s = state() ?: return FAILED
        val subs = list(s, "subscriptions")
        val sub = if (args.size >= 2) pick(subs, args[0], "подписка") else subs.singleOrNull() ?: return fail("подписок несколько: укажите её id")
        sub ?: return FAILED
        return send(linkedMapOf("command" to JsonString("subscription.near-percent"), "subscription" to JsonString(str(sub, "id")!!), "percent" to JsonNumber.of(percent)))
    }

    private fun toggle(kind: String, args: List<String>): Int {
        val id = args.getOrNull(0) ?: return usage("dpai $kind <id> on|off")
        val on = when (args.getOrNull(1)) {
            "on" -> true
            "off" -> false
            else -> return usage("dpai $kind <id> on|off")
        }
        val full = if (kind == "slot") {
            pick(list(state() ?: return FAILED, "slots"), id, "слот")?.let { str(it, "id") }
        } else {
            val s = state() ?: return FAILED
            val known = (list(s, "sessions").mapNotNull { str(it, "target") } + list(s, "slots").mapNotNull { str(it, "target") }).distinct()
            pickId(known, id, "target")
        } ?: return FAILED
        return send(linkedMapOf("command" to JsonString(if (kind == "slot") "slot.enabled" else "target.enabled"), kind to JsonString(full), "enabled" to JsonBool(on)))
    }

    private fun sessionsLimit(args: List<String>): Int {
        val v = args.firstOrNull() ?: return usage("dpai sessions-limit <1…64|off>")
        val c = linkedMapOf<String, JsonValue>("command" to JsonString("control.session-limit"))
        if (v != "off") c["limit"] = JsonNumber.of(v.toIntOrNull() ?: return usage("dpai sessions-limit <1…64|off>"))
        return send(c)
    }

    // ---- общее ----

    /** Команда в канал: с ключом нажатия (повтор не делает второго действия, D-42), опасное — с `--yes`. */
    private fun send(c: MutableMap<String, JsonValue>, confirmed: Boolean = false, key: Boolean = true): Int {
        if (key) c["key"] = JsonString(keys.next(IdKinds.OPERATION).value)
        if (confirmed) c["confirmed"] = JsonBool(true)
        val r = call("POST", "/v1/commands", JsonObject(c)) ?: return FAILED
        if (json) return print(r)
        out("${str(r, "status")}: ${str(r, "operation")}${str(r, "value")?.let { " → $it" } ?: ""}")
        list(r, "diagnostics").forEach { out("  ${str(it, "code")}: ${str(it, "message")}") }
        return if (str(r, "status") == "FAILED") FAILED else OK
    }

    private fun state(): JsonObject? = call("GET", "/v1/state", null)

    /** Вызов с токеном; отказ — сообщение и `null`. */
    private fun call(method: String, path: String, body: JsonObject?): JsonObject? {
        val cfg = config.read(FILE)?.let { JsonParser().parse(String(it, Charsets.UTF_8)).valueOrNull() as? JsonObject }
            ?: return null.also { err("нет сопряжения: в приложении Targets → «Командная строка» → «Показать код», затем dpai control pair <код>") }
        val port = (cfg["port"] as? JsonNumber)?.toLongExact()?.toInt() ?: DEFAULT_PORT
        val r = transport(port).call(method, path, str(cfg, "token"), body)
        if (r.status == 200 && r.body != null) return r.body
        fail(r)
        return null
    }

    private fun fail(r: HttpReply): Int {
        when {
            r.body == null -> err("канал недоступен (${r.error ?: "код ${r.status}"}): приложение открыто и служба контроллера работает?")
            r.status == 401 -> err("токен не подходит: dpai control pair <новый код>")
            r.status == 409 -> err("опасное действие: повторите с --yes")
            else -> list(r.body, "diagnostics").forEach { err("${str(it, "code")}: ${str(it, "message")}") }
        }
        if (r.body != null && r.status == 200) return OK
        return FAILED
    }

    private fun fail(message: String): Int = FAILED.also { err(message) }

    /** Полный id по однозначному началу или концу; иначе — сообщение. */
    private fun resolve(id: String, listName: String): String? = pickId(list(state() ?: return null, listName).mapNotNull { str(it, "id") }, id, listName)

    private fun pickId(ids: List<String>, id: String, what: String): String? {
        val hits = ids.filter { it == id || it.startsWith(id) || it.endsWith(id) }.distinct()
        return when (hits.size) {
            1 -> hits.single()
            0 -> null.also { err("$what: нет «$id»") }
            else -> null.also { err("$what: «$id» неоднозначно (${hits.size}): уточните") }
        }
    }

    private fun pick(items: List<JsonObject>, id: String, what: String): JsonObject? =
        pickId(items.mapNotNull { str(it, "id") }, id, what)?.let { full -> items.first { str(it, "id") == full } }

    private fun print(o: JsonObject): Int = OK.also { out(CanonicalJson.write(o)) }

    private fun usage(m: String): Int = USAGE_ERROR.also { err(m) }

    private fun restrict() {
        val home = System.getProperty("user.home") ?: return
        runCatching {
            java.nio.file.Files.setPosixFilePermissions(Paths.get(home, ".config", "dpai", FILE), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
        }
    }

    companion object {
        const val DEFAULT_PORT = 47777
        const val FILE = "control.json"
        const val OK = 0
        const val FAILED = 1
        const val USAGE_ERROR = 2

        private const val TASK_USAGE = "dpai task add «название» «что сделать» [--priority HIGH] [--class trivial] [--model haiku] [--effort low] [--budget 0.25]\n" +
            "dpai task <id> hold|resume|retry [--text «…»]|verify|messages|priority <P>|policy --class <к> …|archive|unarchive|cancel --yes|waive --reason «…» --yes\n" +
            "dpai task archive-done   — все завершённые в архив"
        private const val SESSION_USAGE = "dpai session <id> stop | send «запрос» [--model m] [--effort e] | fresh [«запрос»]"

        val USAGE = """
            |  dpai control pair <код>   сопряжение с приложением (код — Targets → «Командная строка»)
            |  dpai status [--all]       задачи (без архива), сессии, запросы, загрузка подписки
            |  dpai task …               add | archive-done | <id> hold|resume|cancel|retry|verify|waive|priority|policy|archive|unarchive|messages
            |  dpai session <id> …       stop | send «запрос» | fresh
            |  dpai requests             ждущие запросы;  dpai answer <запрос> <вариант> [--text] [--yes]
            |  dpai limit <процент>      порог «у предела»;  dpai sessions-limit <n|off>
            |  dpai target|slot <id> on|off
            |  --json — ответ как есть; опасное — с --yes
        """.trimMargin()

        private fun defaultConfig(): Vfs = NioVfs(Paths.get(System.getProperty("user.home") ?: ".", ".config", "dpai").also { it.toFile().mkdirs() })

        private fun opt(args: List<String>, name: String): String? = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }

        private fun budget(v: String): Long? = v.replace(',', '.').toBigDecimalOrNull()?.movePointRight(2)?.toLong()?.takeIf { it > 0 }

        private fun str(o: JsonObject, k: String): String? = (o[k] as? JsonString)?.value

        private fun num(o: JsonObject, k: String): Long? = (o[k] as? JsonNumber)?.toLongExact()

        private fun bool(o: JsonObject, k: String): Boolean = (o[k] as? JsonBool)?.value == true

        private fun list(o: JsonObject, k: String): List<JsonObject> = (o[k] as? JsonArray)?.items.orEmpty().filterIsInstance<JsonObject>()

        private fun short(id: String?): String = id?.let { if (it.length > 12) it.take(4) + "…" + it.takeLast(6) else it } ?: "?"
    }
}
