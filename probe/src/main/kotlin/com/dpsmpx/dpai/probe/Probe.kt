package com.dpsmpx.dpai.probe

import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Проверки Linux-среды в приложении (`docs/research/LINUX_IN_APP.md`, §8;
 * D-61, шаг 1): proot и его загрузчик — из `nativeLibraryDir` (запуск из
 * каталога данных при targetSdk 29+ запрещён, §1), корневая система
 * Alpine — в каталоге данных. Каждая проверка пишет в отчёт команду, код
 * выхода, время и вывод, а итог — строкой «ИТОГ»: по отчёту решается, идёт
 * ли B2 дальше или D-61 пересматривается.
 */
class Probe(private val context: Context, private val report: (String) -> Unit) {
    private val libDir = File(context.applicationInfo.nativeLibraryDir)
    private val proot = File(libDir, "libproot.so")
    private val loader = File(libDir, "libproot-loader.so")
    val rootfs = File(context.filesDir, "rootfs")
    private val tmp = File(context.cacheDir, "proot-tmp")

    /**
     * Нужен ли `PROOT_NO_SECCOMP=1`: решает п. 1, им пользуются остальные
     * проверки и проба фона — поэтому отметкой в каталоге данных.
     */
    private var noSeccomp: Boolean
        get() = File(context.filesDir, "no-seccomp").isFile
        set(v) {
            val f = File(context.filesDir, "no-seccomp")
            if (v) f.writeText("1") else f.delete()
        }

    data class Run(val code: Int?, val output: String, val millis: Long) {
        val ok: Boolean get() = code == 0
    }

    /** Процесс с выводом (stderr — вместе со stdout) и пределом времени. */
    fun exec(argv: List<String>, env: Map<String, String> = emptyMap(), timeoutSeconds: Long = 120): Run {
        val start = System.nanoTime()
        fun ms() = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        return try {
            val pb = ProcessBuilder(argv).redirectErrorStream(true)
            pb.environment().putAll(env)
            val p = pb.start()
            p.outputStream.close()
            val out = StringBuilder()
            val reader = thread(name = "probe-out") {
                p.inputStream.bufferedReader().forEachLine { line -> synchronized(out) { if (out.length < OUTPUT_LIMIT) out.appendLine(line) } }
            }
            val done = p.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!done) {
                p.destroyForcibly()
                p.waitFor(10, TimeUnit.SECONDS)
            }
            reader.join(TimeUnit.SECONDS.toMillis(5))
            val text = synchronized(out) { out.toString() }
            Run(if (done) p.exitValue() else null, if (done) text else text + "[не уложился в $timeoutSeconds с — остановлен]\n", ms())
        } catch (e: IOException) {
            Run(null, "не запустился: ${e.message}\n", ms())
        }
    }

    fun prootEnvironment(): Map<String, String> = buildMap {
        put("PROOT_LOADER", loader.path)
        put("PROOT_TMP_DIR", tmp.apply { mkdirs() }.path)
        put("HOME", "/root")
        put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        put("LANG", "C.UTF-8")
        put("TMPDIR", "/tmp")
        if (noSeccomp) put("PROOT_NO_SECCOMP", "1")
    }

    /** Команда оболочки Alpine под proot: root-подделка (`-0`), `/dev`, `/proc`, `/sys` хоста. */
    fun inProot(script: String, extra: List<String> = emptyList(), env: Map<String, String> = emptyMap(), timeoutSeconds: Long = 120): Run =
        exec(prootArgv(script, extra), prootEnvironment() + env, timeoutSeconds)

    fun prootArgv(script: String, extra: List<String> = emptyList()): List<String> =
        listOf(proot.path, "--kill-on-exit") + extra +
            listOf("-0", "-r", rootfs.path, "-b", "/dev", "-b", "/proc", "-b", "/sys", "-w", "/root", "/bin/sh", "-c", script)

    private fun show(title: String, r: Run) {
        report("\$ $title")
        report("  код ${r.code ?: "—"}, ${r.millis} мс")
        r.output.lineSequence().filter { it.isNotEmpty() }.take(OUTPUT_LINES).forEach { report("  | $it") }
    }

    private fun verdict(item: String, ok: Boolean, note: String) = report("ИТОГ $item: ${if (ok) "ДА" else "НЕТ"} — $note")

    fun device() {
        report("== Устройство ==")
        report("${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT}")
        report("ABI: ${Build.SUPPORTED_ABIS.joinToString()}; ядро ${System.getProperty("os.version")}")
        report("страница памяти: ${Os.sysconf(OsConstants._SC_PAGESIZE)} байт")
        report("targetSdk ${context.applicationInfo.targetSdkVersion}; nativeLibraryDir ${libDir.path}")
        for (f in listOf(proot, loader)) report("  ${f.name}: ${if (f.isFile) "${f.length()} байт, запуск ${if (f.canExecute()) "разрешён" else "НЕ разрешён"}" else "НЕТ ФАЙЛА"}")
    }

    /** Корневая система из APK, если её ещё нет. */
    fun unpack(): Boolean {
        report("== Корневая система Alpine ==")
        if (File(rootfs, "etc/alpine-release").isFile) {
            report("уже распакована: ${rootfs.path}")
            return true
        }
        val start = System.nanoTime()
        rootfs.deleteRecursively()
        val s = context.assets.open("rootfs.tgz").use { Rootfs.unpack(it, rootfs) }
        val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        report("файлов ${s.files}, каталогов ${s.dirs}, ссылок ${s.symlinks}, ${s.bytes / 1024} КиБ за $ms мс")
        s.skipped.take(20).forEach { report("  пропущено: $it") }
        return File(rootfs, "etc/alpine-release").isFile
    }

    /** П. 1, 2, 3, 5 исследования: запуск, жёсткие ссылки, скорость, страницы. */
    fun basic() {
        device()
        report("")
        report("== п. 1: proot из nativeLibraryDir ==")
        val version = exec(listOf(proot.path, "--version"), prootEnvironment())
        show("libproot.so --version", version)
        verdict("п. 1а запуск proot из nativeLibraryDir", version.code != null, "код ${version.code}")
        report("")
        if (!unpack()) {
            report("корневая система не распакована — дальше нечего проверять")
            return
        }
        report("")
        val hello = "uname -a; cat /etc/alpine-release; id -u; echo PROBE_OK"
        noSeccomp = false
        val plain = inProot(hello)
        show("proot: оболочка Alpine", plain)
        noSeccomp = true
        val noSec = inProot(hello)
        show("proot с PROOT_NO_SECCOMP=1: оболочка Alpine", noSec)
        val plainOk = "PROBE_OK" in plain.output
        val noSecOk = "PROBE_OK" in noSec.output
        noSeccomp = !plainOk && noSecOk
        verdict("п. 1б оболочка Alpine под proot", plainOk || noSecOk, if (plainOk) "без PROOT_NO_SECCOMP" else if (noSecOk) "только с PROOT_NO_SECCOMP=1" else "не запускается ни так, ни так")
        if (!plainOk && !noSecOk) return
        report("")
        report("== п. 2: жёсткие ссылки ==")
        val link = "cd /tmp && rm -f a b && echo x > a && ln a b && ls -li a b && echo LINK_OK"
        val hard = inProot(link)
        show("ln без --link2symlink", hard)
        val sym = inProot(link, listOf("--link2symlink"))
        show("ln с --link2symlink", sym)
        verdict("п. 2 жёсткие ссылки", "LINK_OK" in hard.output || "LINK_OK" in sym.output, if ("LINK_OK" in hard.output) "работают без --link2symlink" else if ("LINK_OK" in sym.output) "только с --link2symlink" else "не работают")
        report("")
        report("== п. 3: скорость ==")
        val loop = "i=0; while [ \$i -lt 100000 ]; do i=\$((i+1)); done; echo \$i"
        val native = exec(listOf("/system/bin/sh", "-c", loop))
        show("цикл 100000 в /system/bin/sh без proot", native)
        val guest = inProot(loop)
        show("тот же цикл в sh Alpine под proot", guest)
        val starts = (1..10).map { inProot("true").millis }
        report("запуск proot с /bin/true ×10: ${starts.joinToString()} мс, среднее ${starts.average().toLong()} мс")
        show("find / -xdev под proot", inProot("find / -xdev 2>/dev/null | wc -l"))
        verdict("п. 3 скорость (записать)", guest.ok, "цикл ${guest.millis} мс против ${native.millis} мс без proot, запуск ~${starts.average().toLong()} мс")
        report("")
        report("== п. 5: страницы памяти ==")
        val page = Os.sysconf(OsConstants._SC_PAGESIZE)
        verdict("п. 5 страницы 16 КБ", true, "здесь страница $page байт${if (page == 16384L) " — proot работает на ядре с 16 КБ" else " — 16 КБ этим устройством не проверить"}")
    }

    /** П. 2 и 3 на деле: `apk add git`, Git под proot, `git status` на 2000 файлов. Нужен интернет. */
    fun network() {
        report("== Сеть: apk и git ==")
        if (!unpack()) return
        // У Android нет /etc/resolv.conf: адреса DNS для гостя — явно.
        File(rootfs, "etc/resolv.conf").writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        show("apk update", inProot("apk update", timeoutSeconds = 300))
        var add = inProot("apk add --no-progress git", timeoutSeconds = 600)
        show("apk add git", add)
        var mode = emptyList<String>()
        if (!add.ok) {
            mode = listOf("--link2symlink")
            add = inProot("apk add --no-progress git", mode, timeoutSeconds = 600)
            show("apk add git с --link2symlink", add)
        }
        verdict("п. 2 apk add", add.ok, if (mode.isEmpty()) "без --link2symlink" else "с --link2symlink: ${if (add.ok) "работает" else "не работает"}")
        if (!add.ok) return
        val repo = "cd /tmp && rm -rf r && git init -q r && cd r && git config user.email probe@example.org && git config user.name probe && " +
            "echo a > f && git add f && git commit -qm t && git gc -q && git log --oneline && echo GIT_OK"
        val git = inProot(repo, mode, timeoutSeconds = 300)
        show("git init, commit, gc", git)
        verdict("п. 2 git", "GIT_OK" in git.output, if (mode.isEmpty()) "без --link2symlink" else "с --link2symlink")
        val big = "cd /tmp && rm -rf big && mkdir big && cd big && git init -q && i=0; while [ \$i -lt 2000 ]; do echo \$i > f\$i; i=\$((i+1)); done; " +
            "git add . && git -c user.email=p@example.org -c user.name=p commit -qm init && echo READY"
        show("дерево 2000 файлов и коммит", inProot(big, mode, timeoutSeconds = 600))
        val status = (1..3).map { inProot("cd /tmp/big && git status --short | wc -l", mode) }
        show("git status на 2000 файлах (последний из 3)", status.last())
        verdict("п. 3 git status (записать)", status.all { it.ok }, "${status.joinToString { "${it.millis}" }} мс, с запуском proot")
    }

    /** П. 3: JVM агента под proot — `java -version`, холодный и тёплый запуск. Около 180 МБ. */
    fun java() {
        report("== JVM под proot ==")
        if (!unpack()) return
        File(rootfs, "etc/resolv.conf").writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        val add = inProot("apk add --no-progress openjdk17-jre-headless", timeoutSeconds = 1200)
        show("apk add openjdk17-jre-headless", add)
        if (!add.ok) {
            verdict("п. 3 JVM", false, "Java не установилась")
            return
        }
        val runs = (1..3).map { inProot("java -version") }
        show("java -version (последний из 3)", runs.last())
        verdict("п. 3 JVM (записать)", runs.all { it.ok }, "запуск ${runs.joinToString { "${it.millis}" }} мс")
    }

    companion object {
        private const val OUTPUT_LIMIT = 64 * 1024
        private const val OUTPUT_LINES = 40
    }
}
