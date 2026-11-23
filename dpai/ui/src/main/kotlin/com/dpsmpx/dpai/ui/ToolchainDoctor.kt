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

    /** Только распознаваемые JVM-launcher'ы. Произвольные shell-скрипты по имени не угадываем. */
    fun needsJvm(profile: ManualProfile): Boolean = profile.kind != ManualProfileKind.DEPENDENCIES && profile.steps.any { step ->
        val words = step.command
        val first = words.firstOrNull()?.substringAfterLast('/')
        val command = if (first == "env") words.drop(1).dropWhile { '=' in it } else words
        val tool = command.firstOrNull()?.substringAfterLast('/')
        val launchers = setOf("java", "javac", "gradle", "gradlew", "mvn", "mvnw", "kotlinc")
        when {
            tool in launchers -> true
            tool in setOf("sh", "bash", "dash") -> {
                val script = command.drop(1).firstOrNull { !it.startsWith('-') }?.substringAfterLast('/')
                script in setOf("gradlew", "mvnw")
            }
            else -> false
        }
    }

    /** Встроенная проверка перед Gradle в Linux приложения: JVM обязана реально стартовать. */
    fun buildJvmPreflight(host: ProcessHost, installedTargetSdk: Int? = null, cancelled: () -> Boolean): Outcome<Unit> {
        val result = when (val probe = run(host, definitions.filter { it.name == "java" }, "/", cancelled)) {
            is Outcome.Failure -> return probe
            is Outcome.Success -> probe.value
        }
        val java = (result["probes"] as? JsonArray)?.items?.firstOrNull() as? JsonObject
            ?: return Outcome.failure(DiagCode.TARGET_CHECK_FAILED, "Проверка JVM не вернула результат; BUILD не запускался")
        if (java["working"] == JsonBool(true)) return Outcome.success(Unit)
        val message = (java["stderr"] as? JsonString)?.value.orEmpty().ifBlank { (java["stdout"] as? JsonString)?.value.orEmpty() }.take(400)
        val finding = (java["finding"] as? JsonString)?.value
        val memoryReport = if (finding == "JVM_EXECUTABLE_MEMORY") {
            when (val memory = run(host, definitions.filter { it.name == "memory" }, "/", cancelled)) {
                is Outcome.Failure -> return memory
                is Outcome.Success -> (memory.value["probes"] as? JsonArray)?.items?.firstOrNull() as? JsonObject
            }
        } else null
        if (cancelled()) return Outcome.failure(DiagCode.CANCELLED_BY_USER, "проверка JVM отменена")
        val selinux = (memoryReport?.get("finding") as? JsonString)?.value == "ANDROID_SELINUX_EXECMEM"
        val memoryOutput = (memoryReport?.get("stdout") as? JsonString)?.value?.take(300).orEmpty()
        val targetHint = when {
            installedTargetSdk == null -> "Проверьте targetSdk установленного APK, а не исходников."
            installedTargetSdk > 28 -> "Установленное приложение имеет targetSdk=$installedTargetSdk: обновление Git-исходников не меняет APK. Установите основной APK с targetSdk=28, подписанный совместимым ключом, и повторите пробы."
            else -> "Установленное приложение имеет targetSdk=$installedTargetSdk (режим совместимости); это само по себе не снимает запреты mprotect/execmem."
        }
        val reason = when {
            selinux -> "JVM не стартует: Android SELinux untrusted_app запрещает исполняемую память (execmem)."
            finding == "JVM_EXECUTABLE_MEMORY" -> "JVM не стартует: HotSpot получил отказ mprotect при выделении исполняемой памяти; проба memory не подтвердила источник отказа. -Xint не обходит первоначальную проверку HotSpot."
            else -> "JVM не стартует: java -version завершился с ошибкой."
        }
        return Outcome.failure(DiagCode.EXECUTION_HOST_UNAVAILABLE,
            "PRECHECK_FAILED: $reason Gradle не запускался; это не конфликт Git. $targetHint Для самостоятельной сборки нужен запускающийся JDK 21 и Android SDK в Linux приложения. Альтернатива — SSH-target. Проба памяти: $memoryOutput. Вывод java: $message")
    }

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
        Probe("java_debug", listOf("java", "-Xmx128m", "-Xlog:os=debug", "-version")),
        Probe("memory", listOf("python3", "-I", "-B", "-c", """
            import ctypes, mmap, os, json, sys
            lib = ctypes.CDLL(None, use_errno=True)
            lib.mmap.restype = ctypes.c_void_p
            lib.mmap.argtypes = [ctypes.c_void_p, ctypes.c_size_t, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_long]
            lib.mprotect.argtypes = [ctypes.c_void_p, ctypes.c_size_t, ctypes.c_int]
            lib.munmap.argtypes = [ctypes.c_void_p, ctypes.c_size_t]
            n = os.sysconf('SC_PAGE_SIZE')
            selinux = None
            try:
                with open('/proc/self/attr/current', 'r', encoding='utf-8') as label:
                    selinux = label.read(256).strip()
            except OSError:
                pass
            p = lib.mmap(None, n, mmap.PROT_READ | mmap.PROT_WRITE, mmap.MAP_PRIVATE | mmap.MAP_ANONYMOUS, -1, 0)
            if p == ctypes.c_void_p(-1).value:
                print(json.dumps({'pageSize':n, 'mmapErrno':ctypes.get_errno(), 'selinuxContext':selinux})); sys.exit(1)
            try:
                ctypes.set_errno(0)
                rwx = lib.mprotect(p, n, mmap.PROT_READ | mmap.PROT_WRITE | mmap.PROT_EXEC)
                rwx_error = ctypes.get_errno() if rwx else 0
                ctypes.set_errno(0)
                rx = lib.mprotect(p, n, mmap.PROT_READ | mmap.PROT_EXEC)
                print(json.dumps({'pageSize':n, 'rwxErrno':rwx_error, 'rxErrno':ctypes.get_errno() if rx else 0, 'selinuxContext':selinux}))
                sys.exit(1 if rwx else 0)
            finally:
                lib.munmap(p, n)
        """.trimIndent())),
        Probe("jdk", listOf("/bin/sh", "-c", jdk), 60),
        Probe("javac", listOf("javac", "-version")),
        Probe("aapt2", listOf("aapt2", "version")),
        Probe("android_project", listOf("sh", "-c", """
            sdk=${'$'}{ANDROID_HOME:-${'$'}{ANDROID_SDK_ROOT:-}}
            if test -z "${'$'}sdk"; then echo 'ANDROID_HOME и ANDROID_SDK_ROOT не заданы для SSH-сессии' >&2; exit 1; fi
            if ! test -d "${'$'}sdk/platforms"; then echo 'Android SDK: нет каталога platforms' >&2; exit 1; fi
            if ! test -d "${'$'}sdk/build-tools"; then echo 'Android SDK: нет каталога build-tools' >&2; exit 1; fi
            printf 'ANDROID_HOME=%s\n' "${'$'}sdk"
        """.trimIndent())),
        Probe("python3", listOf("python3", "-I", "-B", "-c", "import sys,ssl,sqlite3; print(sys.version); print(ssl.OPENSSL_VERSION); print('sqlite', sqlite3.sqlite_version)")),
        Probe("git", listOf("git", "--version")),
        Probe("c++", listOf("/bin/sh", "-c", cpp), 60),
        Probe("android_sdk", listOf("/bin/sh", "-c", """
            set -eu
            sdk=${'$'}{ANDROID_HOME:-/root/android-sdk}
            printf 'SDK=%s\n' "${'$'}sdk"
            "${'$'}sdk/cmdline-tools/latest/bin/sdkmanager" --version
            test -d "${'$'}sdk/platforms"
            aapt=${'$'}sdk/dpai/aapt2
            if ! test -x "${'$'}aapt"; then
                for candidate in "${'$'}sdk"/build-tools/*/aapt2; do test ! -x "${'$'}candidate" || aapt=${'$'}candidate; done
            fi
            "${'$'}aapt" version
        """.trimIndent()), 60),
    ) + listOf("cc", "gcc", "g++", "clang", "clang++", "cmake", "make", "ninja", "node", "npm", "rustc", "cargo", "go", "mvn")
        .map { Probe(it, listOf(it, if (it == "go") "version" else "--version")) }
    val choices: List<String> = listOf("project", "all") + definitions.map { it.name }
    /** Безопасные фиксированные пробы POSIX SSH-target: входят только через известный ключ хоста. */
    fun sshBuildProbes(): List<Probe> = definitions.filter { it.name in setOf("java", "javac", "git", "aapt2", "android_project") }
    val sourceHash: String get() = Hashing.sha256(JsonArray(definitions.map { p -> JsonObject.of("name" to JsonString(p.name),
        "argv" to JsonArray(p.command.map(::JsonString)), "seconds" to JsonNumber.of(p.seconds)) })).hex

    /** Установка инструмента проверяет запуск сразу тем же штатным профилем. */
    fun forPackages(packages: List<String>): List<Probe> {
        val names = packages.map { it.substringBefore('=').substringBefore('>').substringBefore('<') }
        val wanted = linkedSetOf<String>()
        if (names.any { it.startsWith("openjdk") }) wanted += "java"
        if (names.any { it.startsWith("openjdk") && it.endsWith("-jdk") }) wanted += "jdk"
        if (names.any { it == "python3" || it.startsWith("py3-") }) wanted += "python3"
        if ("git" in names) wanted += "git"
        if ("g++" in names || "build-base" in names) wanted += "c++"
        return definitions.filter { it.name in wanted }
    }

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
    fun run(host: ProcessHost, probes: List<Probe>, root: String?, cancelled: () -> Boolean): Outcome<JsonObject> {
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
        output.contains("u:r:untrusted_app:") && Regex("""["]rwxErrno["]\s*:\s*(1|13)\b""").containsMatchIn(output) ->
            "ANDROID_SELINUX_EXECMEM" to "Проба mprotect RW→RWX получила EPERM/EACCES в домене Android untrusted_app. Это согласуется с запретом исполняемой памяти политикой SELinux; proot и uid=0 не меняют домен. Обычная JVM и Gradle здесь не запустятся: выберите execution target с работающей JVM (например SSH ПК)."
        output.contains("Failed to mark memory page as executable", ignoreCase = true) -> "JVM_EXECUTABLE_MEMORY" to
            "JVM получила отказ при переводе памяти в исполняемую (mprotect). Причина ещё не установлена: это не доказательство PaX/grsecurity. Запросите java_debug и memory для errno и размера страниц. Paxctl или смена ядра без этих данных не являются подтверждённым исправлением."
        output.contains("Could not reserve enough space", ignoreCase = true) || output.contains("Native memory allocation", ignoreCase = true) -> "JVM_MEMORY" to
            "JVM не смогла выделить память; проверьте доступную память и пределы heap, затем повторите пробу."
        else -> null
    }
}
