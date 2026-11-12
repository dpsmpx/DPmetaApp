package com.dpsmpx.dpai.orchestrator.linux

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.storage.vfs.Vfs
import java.io.IOException
import java.io.InputStream

/**
 * Linux-среда в приложении (D-75, D-82; шаг 5): корневая система Alpine в
 * каталоге приложения, DNS гостя и пакеты.
 *
 * - **Установка** — распаковка minirootfs из APK ([RootfsInstaller]);
 *   признак готовности [READY] пишется последним, поэтому оборванная
 *   установка (закрыли приложение, кончилось место) повторяется целиком, а
 *   не выдаёт половину системы за готовую. Распаковка поверх неполной
 *   безопасна: файлы и ссылки перезаписываются. Готовая среда второй раз
 *   не распаковывается — в ней пакеты и вход Claude человека; обновление
 *   Alpine — `apk upgrade` командой пакетов.
 * - **DNS** — серверы сети телефона в `/etc/resolv.conf` гостя: гость
 *   спрашивает тех же, что и само приложение, новых адресатов в сети нет
 *   (`docs/research/LINUX_IN_APP.md`, §9, второй прогон).
 * - **Пакеты** — `apk` из репозитория Alpine с проверкой подписей самим
 *   `apk` по ключам корневой системы (T11); запускается без `/proc` у гостя
 *   (D-82, [com.dpsmpx.dpai.execution.linux.ProotHost]).
 */
class LinuxEnvironment(private val vfs: Vfs, dir: String = DIR) {
    /** Корневая система — каталог [com.dpsmpx.dpai.execution.linux.ProotLayout.rootfs]. */
    val rootfs: String = Vfs.join(dir, "rootfs")

    private val ready = Vfs.join(dir, READY)

    /** Черновик `resolv.conf` — вне корня гостя: гость его не видит и ссылкой не подменит. */
    private val dnsDraft = Vfs.join(dir, "resolv.conf.draft")
    private val keyDraft = Vfs.join(dir, "llama-key.draft")
    private val profileDraft = Vfs.join(dir, "profile.draft")

    /** Версия установленной корневой системы; `null` — не установлена или установка оборвалась. */
    fun installed(): String? = vfs.read(ready)?.toString(Charsets.UTF_8)?.trim()?.ifEmpty { null }

    /**
     * Установить корневую систему [version] из [archive], если её ещё нет.
     * Уже установлена — `null`, архив не открывается.
     */
    fun install(version: String, archive: () -> InputStream): Outcome<RootfsInstaller.Summary?> {
        if (installed() != null) return Outcome.success(null)
        val unpacked = try {
            archive().use { RootfsInstaller.unpack(it, vfs, rootfs) }
        } catch (e: IOException) {
            return Outcome.Failure(listOf(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "архив корневой системы не открылся", e, rootfs)))
        }
        val summary = when (unpacked) {
            is Outcome.Failure -> return unpacked
            is Outcome.Success -> unpacked.value
        }
        return try {
            vfs.write(ready, "$version\n".toByteArray())
            vfs.fsync(ready)
            val skipped = summary.skipped.map { Diagnostic.warning(DiagCode.VALUE_INVALID, "запись архива пропущена: $it", rootfs) }
            Outcome.success(summary, skipped)
        } catch (e: IOException) {
            Outcome.Failure(listOf(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "признак готовности корневой системы не записан", e, ready)))
        }
    }

    /**
     * DNS гостя — серверы сети телефона ([servers], адреса IPv4 и IPv6).
     * Сети нет — серверов нет, и файл не трогается: прежний лучше пустого.
     *
     * `/etc` гостя пишет и сам гость: ссылку на месте `resolv.conf` он может
     * направить на файл приложения (аудит безопасности 1 ноября 2026, A-3).
     * Поэтому файл собирается рядом с корневой системой, куда гостю не
     * дотянуться, и встаёт на место переименованием — оно заменяет запись
     * каталога, ссылку тоже, а не цель ссылки; `/etc`-ссылка — отказ.
     */
    /**
     * `~/tools/bin` в `PATH` и оболочки входа (D-143): `/etc/profile` Alpine задаёт свой
     * `PATH`, поэтому строка — в `/etc/profile.d`. Как у [writeDns]: черновик вне корня
     * гостя и переименование; `/etc` или `/etc/profile.d` — ссылка — отказ.
     */
    fun writeProfile(): Outcome<Unit> {
        val etc = Vfs.join(rootfs, "etc")
        val dir = Vfs.join(etc, "profile.d")
        val path = Vfs.join(dir, "dpmetaapp-tools.sh")
        if (vfs.isSymlink(etc) || vfs.isSymlink(dir)) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "/etc гостя — символьная ссылка: PATH для оболочки входа не записан", dir)
        return try {
            vfs.mkdirs(dir)
            vfs.write(profileDraft, GuestStorage.PROFILE_SCRIPT.toByteArray())
            vfs.rename(profileDraft, path)
            Outcome.success(Unit)
        } catch (e: IOException) {
            Outcome.Failure(listOf(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "/etc/profile.d гостя не записан", e, path)))
        }
    }

    fun writeDns(servers: List<String>): Outcome<Int> {
        val good = servers.map { it.trim().substringBefore('%') }.filter(::isIpLiteral).distinct().take(MAX_DNS)
        if (good.isEmpty()) return Outcome.failure(DiagCode.VALUE_INVALID, "нет адреса DNS сети телефона: /etc/resolv.conf гостя не изменён", rootfs)
        val etc = Vfs.join(rootfs, "etc")
        val path = Vfs.join(etc, "resolv.conf")
        if (vfs.isSymlink(etc)) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "/etc гостя — символьная ссылка: /etc/resolv.conf не записан", etc)
        return try {
            vfs.mkdirs(etc)
            vfs.write(dnsDraft, good.joinToString("") { "nameserver $it\n" }.toByteArray())
            vfs.rename(dnsDraft, path)
            Outcome.success(good.size)
        } catch (e: IOException) {
            Outcome.Failure(listOf(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "/etc/resolv.conf гостя не записан", e, path)))
        }
    }

    /**
     * Ключ API сервера локальной модели в госте (D-105, T18), как
     * [writeDns] (A-3): каталоги `~/.config` и `~/.config/llama` пишет и гость
     * — ссылка на их месте — отказ; ключ пишется черновиком вне корня гостя
     * и встаёт на место переименованием: ссылка на месте файла заменяется
     * файлом, её цель не трогается.
     */
    fun writeLlamaKey(key: ByteArray): Outcome<Unit> {
        val rel = LLAMA_KEY_FILE.removePrefix("/")
        val path = Vfs.join(rootfs, rel)
        val dirs = rel.split('/').dropLast(1).runningFold(rootfs) { acc, part -> Vfs.join(acc, part) }.drop(1)
        return try {
            for (d in dirs) {
                if (vfs.isSymlink(d)) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "каталог гостя — символьная ссылка: ключ сервера не записан", d)
                vfs.mkdirs(d)
                if (vfs.isSymlink(d)) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "каталог гостя — символьная ссылка: ключ сервера не записан", d)
            }
            vfs.write(keyDraft, key)
            vfs.rename(keyDraft, path)
            Outcome.success(Unit)
        } catch (e: IOException) {
            Outcome.Failure(listOf(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "ключ сервера не записан", e, path)))
        }
    }

    /** Что сделать с пакетами Alpine. */
    enum class Apk(internal val args: List<String>, internal val needsPackages: Boolean) {
        /** Поставить: индекс обновляется тем же вызовом (`-U`). */
        ADD(listOf("add", "-U", "--no-progress"), true),

        /** Переустановить выбранные пакеты и зависимости после частичной ошибки; данные проектов не трогаются (D-140). */
        FIX(listOf("fix", "-U", "--no-progress", "--depends"), true),

        /** Убрать. */
        DEL(listOf("del", "--no-progress"), true),

        /** Обновить всё установленное — и саму Alpine. */
        UPGRADE(listOf("upgrade", "-U", "--no-progress"), false),
    }

    companion object {
        /** Каталог среды в данных приложения. */
        const val DIR = "linux"

        /** Признак готовности рядом с корневой системой: версия minirootfs. */
        const val READY = "rootfs.ready"

        /** Больше трёх серверов `resolv.conf` musl не читает. */
        const val MAX_DNS = 3

        /** Пакетов в одной команде — не больше. */
        const val MAX_PACKAGES = 50

        /** Имя пакета Alpine: строчные, цифры, `+ . _ -`; первый знак — не `-` (иначе это ключ `apk`). */
        val PACKAGE = Regex("[a-z0-9][a-z0-9+._-]{0,99}")

        /**
         * Команда гостя для пакетов: имена — только по [PACKAGE], ключи
         * задаёт [action]. Выполнять — через `ProotHost`: `apk` у него идёт
         * без `/proc` (D-82).
         */
        fun apk(action: Apk, packages: List<String>): Outcome<ProcessSpec> {
            val names = packages.map { it.trim() }.filter { it.isNotEmpty() }
            if (action.needsPackages && names.isEmpty()) return Outcome.failure(DiagCode.FIELD_MISSING, "назовите пакеты: например, git nodejs npm")
            if (!action.needsPackages && names.isNotEmpty()) return Outcome.failure(DiagCode.VALUE_INVALID, "обновление — для всех пакетов сразу, без имён")
            if (names.size > MAX_PACKAGES) return Outcome.failure(DiagCode.VALUE_INVALID, "пакетов в одной команде — не больше $MAX_PACKAGES")
            names.firstOrNull { !PACKAGE.matches(it) }?.let {
                return Outcome.failure(DiagCode.VALUE_INVALID, "«$it» — не имя пакета Alpine: строчные буквы, цифры, + . _ -")
            }
            return Outcome.success(ProcessSpec(listOf("apk") + action.args + names))
        }

        /**
         * Каталог слота в госте (`CLAUDE_CONFIG_DIR`, `CODEX_HOME`,
         * `GEMINI_CLI_HOME` — D-29, D-100): вход — в нём, по разу на слот.
         */
        fun slotDir(n: Int, executor: com.dpsmpx.dpai.core.ids.Executor = com.dpsmpx.dpai.core.ids.Executor.CLAUDE): String {
            // Слоты — в разделе `~/ai` (D-143, DPmetaApp:D-113); прежние `~/.<исполнитель>-slot-N` — ссылками.
            return GuestStorage.slotDir(executor.key, n)
        }

        /** Пакеты npm исполнителей Codex и Gemini (D-100): ставятся в госте глобально. */
        val EXECUTOR_PACKAGES: List<String> = listOf("@openai/codex@0.167.1", "@google/gemini-cli@0.68.0")

        /**
         * Пакет npm Claude Code: ставит готовый бинарник платформы (`linux-arm64-musl` на
         * телефоне) — Node.js нужен только установщику. Бинарник ~330 МБ; жёсткую ссылку
         * Android запрещает, и установщик пакета его копирует — на диске около 0,7 ГБ.
         */
        const val CLAUDE_PACKAGE: String = "@anthropic-ai/claude-code@2.1.322"

        /** Шаг `apk add` с именами из кода (они заведомо проходят [PACKAGE]). */
        private fun apkAdd(vararg names: String): ProcessSpec =
            (apk(Apk.ADD, names.toList()) as Outcome.Success).value

        /**
         * Установка Claude Code в госте (D-75, шаг 5): Git, Node.js, npm и bash из Alpine —
         * отдельным шагом `apk` без `/proc` (D-82), затем пакет npm оболочкой гостя; в конце —
         * версия. bash — оболочка команд Claude Code: без bash или zsh он их не выполняет
         * («No suitable shell found»), а в minirootfs Alpine есть только `sh` busybox.
         * Вход — по разу на слот, в терминале (D-27, D-29).
         */
        fun claudeInstall(): List<ProcessSpec> = listOf(
            apkAdd("git", "nodejs", "npm", "bash"),
            ProcessSpec(listOf("/bin/sh", "-lc", "npm install -g $CLAUDE_PACKAGE && claude --version")),
        )

        /**
         * Установка Codex CLI и Gemini CLI в госте (D-100): Node.js и npm из
         * Alpine — шагом `apk` без `/proc` (D-82), затем пакеты npm; в конце — их версии.
         */
        fun executorsInstall(): List<ProcessSpec> = listOf(
            apkAdd("nodejs", "npm"),
            ProcessSpec(listOf("/bin/sh", "-lc", "npm install -g ${EXECUTOR_PACKAGES.joinToString(" ")} && codex --version && gemini --version")),
        )

        /**
         * Установка llama.cpp (D-105): сервер модели из пакетов Alpine
         * (`community`, aarch64) — шагом `apk` без `/proc` (D-82); в конце — его версия.
         */
        fun llamaInstall(): List<ProcessSpec> = listOf(apkAdd("llama-server"), ProcessSpec(listOf("llama-server", "--version")))

        /** Каталоги гостя, откуда берётся модель (D-105): свои модели, копии папок (D-99) и всё хранилище. */
        val MODEL_DIRS: List<String> = listOf("/root/tools/models/", "/root/models/", "/root/files/", "/root/storage/shared/")

        /** Путь модели: буквы любого алфавита (имена папок автора бывают русскими), цифры, пробел и `._()+@-` — аргумент идёт без оболочки. */
        private val MODEL_PATH = Regex("/[\\p{L}\\p{N}._/ ()+@-]+\\.gguf")

        /** Файл ключа API сервера в госте (D-105, T18): права владельца, не в аргументах процесса. */
        const val LLAMA_KEY_FILE = "/root/.config/llama/api-key"

        /** Что не так с путём модели (D-105); годится — `null`. */
        fun modelPathProblem(model: String): String? = when {
            model.split('/').any { it == ".." || it == "." } -> "в пути модели — «..» или «.»"
            !model.endsWith(".gguf") -> "модель — файл .gguf"
            !MODEL_PATH.matches(model) -> "в пути модели — знаки вне букв, цифр, пробела и «._()+@-»"
            MODEL_DIRS.none { model.startsWith(it) } -> "модель — из ${MODEL_DIRS.joinToString(", ")}"
            else -> null
        }

        /**
         * Запуск сервера модели (D-105, T18): только `127.0.0.1` (это все
         * приложения телефона — поэтому ключ API из [LLAMA_KEY_FILE], без веб-
         * интерфейса и `/slots`); один слот — окно контекста не делится;
         * модель — файл `.gguf` из [MODEL_DIRS], без `..`; путь — отдельным
         * аргументом, без оболочки.
         */
        fun llamaServe(model: String, port: Int, contextTokens: Int, threads: Int): Outcome<ProcessSpec> {
            modelPathProblem(model)?.let { return Outcome.failure(DiagCode.VALUE_INVALID, it, model) }
            return when {
                port !in 1024..65535 -> Outcome.failure(DiagCode.VALUE_INVALID, "порт — 1024…65535", "$port")
                contextTokens !in 512..131_072 -> Outcome.failure(DiagCode.VALUE_INVALID, "контекст — 512…131072 токенов", "$contextTokens")
                threads !in 1..16 -> Outcome.failure(DiagCode.VALUE_INVALID, "потоков — 1…16", "$threads")
                else -> Outcome.success(
                    ProcessSpec(
                        listOf(
                            "llama-server", "-m", model, "--host", "127.0.0.1", "--port", "$port",
                            "--ctx-size", "$contextTokens", "--threads", "$threads", "--parallel", "1",
                            "--api-key-file", LLAMA_KEY_FILE, "--no-webui", "--no-slots",
                        ),
                    ),
                )
            }
        }

        /** Адрес IPv4 (четыре числа 0…255) или IPv6 (шестнадцатеричные группы через `:`). */
        fun isIpLiteral(s: String): Boolean {
            if (IPV4.matches(s)) return s.split('.').all { it.toInt() in 0..255 && (it == "0" || !it.startsWith("0")) }
            return s.length in 2..45 && s.count { it == ':' } >= 2 && IPV6.matches(s)
        }

        private val IPV4 = Regex("\\d{1,3}(\\.\\d{1,3}){3}")
        private val IPV6 = Regex("[0-9a-fA-F:.]+")
    }
}
