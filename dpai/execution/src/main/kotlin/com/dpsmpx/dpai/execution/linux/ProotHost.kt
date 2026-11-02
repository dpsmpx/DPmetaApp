package com.dpsmpx.dpai.execution.linux

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.OutputLine
import com.dpsmpx.dpai.execution.process.ProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.RunningProcess

/**
 * Файлы Linux-среды в приложении на стороне хоста (D-75, D-82): [proot] и
 * [loader] — из каталога библиотек приложения (запуск из каталога данных
 * при targetSdk 29+ запрещён, `docs/research/LINUX_IN_APP.md`, §1),
 * [rootfs] — распакованный Alpine, [tmp] — служебный каталог proot.
 * [binds] — каталоги хоста, видимые гостю (рабочие деревья, каталог слота).
 */
data class ProotLayout(
    val proot: String,
    val loader: String,
    val rootfs: String,
    val tmp: String,
    val binds: List<Bind> = emptyList(),
) {
    data class Bind(val host: String, val guest: String)
}

/**
 * Процессы внутри Alpine под proot (D-82): команда [ProcessSpec] — команда
 * гостя, [ProcessSpec.workDir] — каталог гостя, [ProcessSpec.environment] —
 * окружение гостя целиком (`null` — [GUEST_ENVIRONMENT]); окружение
 * приложения Android гостю не передаётся.
 *
 * proot — с `--kill-on-exit`: процессы, оставленные командой в фоне,
 * завершаются вместе с ней. Без этого proot ждёт всех трассируемых, и
 * выход хода не виден, пока жив фоновый сервер, запущенный Claude.
 *
 * `apk` — без `/proc` у гостя: apk-tools 3 создаёт файлы через `O_TMPFILE`
 * и `linkat` из `/proc/self/fd`, а это жёсткая ссылка, которую Android
 * запрещает; без `/proc` apk пишет временный файл и переименовывает его
 * (`docs/research/LINUX_IN_APP.md`, §9, третий прогон пробника).
 *
 * proot — не песочница: гость работает под UID приложения (угроза T13).
 * Поэтому из `/dev` хоста гостю видны только [DEVICES], `/sys` — не виден
 * вовсе (D-93): `/dev/binder` — путь к системным службам от имени
 * приложения, коду из npm и репозитория он не нужен.
 */
class ProotHost(private val host: ProcessHost, private val layout: ProotLayout) : ProcessHost {
    override fun start(spec: ProcessSpec, onOutput: (OutputLine) -> Unit): Outcome<RunningProcess> = host.start(wrap(spec), onOutput)

    /** Команда хоста, которая запускает [spec] в госте. */
    fun wrap(spec: ProcessSpec): ProcessSpec {
        val proc = !needsNoProc(spec.command)
        val argv = buildList {
            add(layout.proot)
            add("--kill-on-exit")
            // Root-подделка: apk и права файлов корневой системы ждут uid 0.
            add("-0")
            add("-r")
            add(layout.rootfs)
            for (dev in DEVICES) {
                add("-b")
                add(dev)
            }
            if (proc) {
                add("-b")
                add("/proc")
                for ((from, to) in FD_LINKS) {
                    add("-b")
                    add("$from:$to")
                }
            }
            for (b in layout.binds) {
                add("-b")
                add("${b.host}:${b.guest}")
            }
            add("-w")
            add(spec.workDir ?: GUEST_HOME)
            addAll(spec.command)
        }
        val environment = (spec.environment ?: GUEST_ENVIRONMENT) + mapOf(
            "PROOT_LOADER" to layout.loader,
            "PROOT_TMP_DIR" to layout.tmp,
        )
        return ProcessSpec(argv, workDir = null, environment = environment, stdin = spec.stdin)
    }

    companion object {
        /** Узлы `/dev` хоста, которые видит гость (D-93): терминал, пустые и случайные устройства — и только. */
        val DEVICES: List<String> = listOf("/dev/null", "/dev/zero", "/dev/full", "/dev/random", "/dev/urandom", "/dev/tty", "/dev/ptmx", "/dev/pts")

        /** Дескрипторы процесса — как в обычном Linux, из `/proc/self/fd`; без `/proc` (у `apk`) их нет. */
        val FD_LINKS: List<Pair<String, String>> = listOf(
            "/proc/self/fd" to "/dev/fd",
            "/proc/self/fd/0" to "/dev/stdin",
            "/proc/self/fd/1" to "/dev/stdout",
            "/proc/self/fd/2" to "/dev/stderr",
        )

        const val GUEST_HOME: String = "/root"

        val GUEST_ENVIRONMENT: Map<String, String> = mapOf(
            "HOME" to GUEST_HOME,
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LANG" to "C.UTF-8",
            "TMPDIR" to "/tmp",
        )

        /** Команда — `apk` (по имени программы, с путём или без). */
        fun needsNoProc(command: List<String>): Boolean = command.first().substringAfterLast('/') == "apk"
    }
}
