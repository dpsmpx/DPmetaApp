package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Redactor
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNull
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.execution.process.ProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.run
import kotlin.time.Duration.Companion.seconds

/** Живые пробы D-216: база пакетов не доказывает, что JVM или компоновщик запускаются. */
object ToolchainDoctor {
    data class Probe(val name: String, val command: List<String>, val seconds: Int = 30)

    private val cpp = """
        set -eu
        d=${'$'}(mktemp -d /tmp/dpai-cpp-probe.XXXXXX)
        trap 'rm -rf "${'$'}d"' EXIT
        printf '#include <iostream>\nint main(){std::cout << "DPAI_CPP_OK";return 0;}\n' > "${'$'}d/main.cpp"
        c++ "${'$'}d/main.cpp" -o "${'$'}d/app"
        "${'$'}d/app"
    """.trimIndent()
    private val jdk = """
        set -eu
        d=${'$'}(mktemp -d /tmp/dpai-jdk-probe.XXXXXX)
        trap 'rm -rf "${'$'}d"' EXIT
        printf 'class DpaiProbe{public static void main(String[]a){System.out.print("DPAI_JDK_OK");}}\n' > "${'$'}d/DpaiProbe.java"
        javac -J-Xmx256m -d "${'$'}d" "${'$'}d/DpaiProbe.java"
        java -Xmx128m -cp "${'$'}d" DpaiProbe
    """.trimIndent()
    private val definitions = listOf(
        Probe("java", listOf("java", "-Xmx128m", "-version")),
        Probe("jdk", listOf("/bin/sh", "-c", jdk), 60),
        Probe("javac", listOf("javac", "-version")),
        Probe("python3", listOf("python3", "-I", "-B", "-c", "import sys,ssl,sqlite3; print(sys.version); print(ssl.OPENSSL_VERSION); print('sqlite', sqlite3.sqlite_version)")),
        Probe("git", listOf("git", "--version")),
        Probe("c++", listOf("/bin/sh", "-c", cpp), 60),
    ) + listOf("cc", "gcc", "g++", "clang", "clang++", "cmake", "make", "ninja", "node", "npm", "rustc", "cargo", "go", "mvn")
        .map { Probe(it, listOf(it, if (it == "go") "version" else "--version")) }
    val choices: List<String> = listOf("project", "all") + definitions.map { it.name }

    fun select(name: String, wanted: Collection<String>): Outcome<List<Probe>> {
        if (name !in choices) return Outcome.failure(DiagCode.VALUE_INVALID, "неизвестная проба инструмента", name)
        val selected = when (name) {
            "all" -> definitions
            "project" -> {
                val names = wanted.toSet() + "git" + if ("java" in wanted) setOf("jdk") else emptySet()
                definitions.filter { it.name in names }
            }
            else -> definitions.filter { it.name == name }
        }
        return Outcome.success(selected)
    }

    /** Команды фиксированы приложением; произвольный shell через эту пробу не принимается. */
    fun run(host: ProcessHost, probes: List<Probe>, root: String, cancelled: () -> Boolean): Outcome<JsonObject> {
        val known = definitions.associateBy { it.name }
        if (probes.any { known[it.name] != it } || probes.size > definitions.size) return Outcome.failure(DiagCode.VALUE_INVALID, "проба не из каталога приложения")
        val results = arrayListOf<JsonObject>()
        for (probe in probes.distinct()) {
            if (cancelled()) return Outcome.failure(DiagCode.CANCELLED_BY_USER, "диагностика отменена")
            when (val result = host.run(ProcessSpec(probe.command, root), probe.seconds.seconds,
                maxStdoutChars = 6000, maxStderrChars = 6000, cancelled = cancelled)) {
                is Outcome.Failure -> {
                    if (result.errors.any { it.code == DiagCode.CANCELLED_BY_USER }) return result
                    results += JsonObject.of("tool" to JsonString(probe.name), "working" to JsonBool(false), "exitCode" to JsonNull,
                        "diagnostics" to JsonArray(result.diagnostics.map { JsonObject.of("code" to JsonString(it.code.name), "message" to JsonString(Redactor.STANDARD.redact(it.message))) }))
                }
                is Outcome.Success -> {
                    val done = result.value
                    val out = Redactor.STANDARD.redact(done.stdout.joinToString("\n"))
                    val err = Redactor.STANDARD.redact(done.stderr.joinToString("\n"))
                    val issue = explain(out + "\n" + err)
                    results += JsonObject.of("tool" to JsonString(probe.name), "working" to JsonBool(done.ok), "exitCode" to JsonNumber.of(done.exitCode),
                        "stdout" to JsonString(out.take(3000)), "stderr" to JsonString(err.take(3000)),
                        "outputTruncated" to JsonBool(done.truncated || out.length > 3000 || err.length > 3000),
                        "capturedOutputHash" to JsonString("sha256:" + Hashing.sha256(out + "\u0000" + err).hex),
                        "finding" to (issue?.let { JsonString(it.first) } ?: JsonNull), "explanation" to (issue?.let { JsonString(it.second) } ?: JsonNull))
                }
            }
        }
        return Outcome.success(JsonObject.of("commandsProbed" to JsonBool(true), "probes" to JsonArray(results),
            "allSelectedToolsWork" to JsonBool(results.isNotEmpty() && results.all { it["working"] == JsonBool(true) }),
            "projectBuildVerified" to JsonBool(false), "note" to JsonString("Проверены только перечисленные пробы. Версии и требования проекта сверяются отдельно; успешная проба не заменяет сборку и тесты.")))
    }

    /** HotSpot check_pax проверяет mmap/mprotect, но не определяет установленный модуль PaX. */
    fun explain(output: String): Pair<String, String>? = when {
        output.contains("Failed to mark memory page as executable", ignoreCase = true) -> "JVM_EXECUTABLE_MEMORY" to
            "JVM получила отказ при переводе памяти в исполняемую (mprotect). Причина ещё не установлена: это не доказательство PaX/grsecurity. Нужны errno и проверка Android/proot; paxctl или смена ядра без этих данных не являются подтверждённым исправлением."
        output.contains("Could not reserve enough space", ignoreCase = true) || output.contains("Native memory allocation", ignoreCase = true) -> "JVM_MEMORY" to
            "JVM не смогла выделить память; проверьте доступную память и пределы heap, затем повторите пробу."
        else -> null
    }
}
