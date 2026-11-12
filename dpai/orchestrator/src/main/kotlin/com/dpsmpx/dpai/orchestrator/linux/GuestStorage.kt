package com.dpsmpx.dpai.orchestrator.linux

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.storage.vfs.Vfs
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Одно хранилище терминала, IDE и ИИ (`DPmetaApp:D-111`, `DPmetaApp:D-113`; D-143):
 * разделы домашней папки гостя `/root` — проекты, инструменты, слоты ИИ, — их
 * перенос из прежних мест с относительными ссылками и карта `~/STORAGE.md`.
 * Пути — от домашней папки гостя; работа — на стороне хоста, с файлами корневой
 * системы, по `java.nio` без перехода по ссылкам.
 */
object GuestStorage {
    const val PROJECTS = "projects"
    const val LOCAL = "projects/local"
    const val TOOLS = "tools"
    const val BIN = "$TOOLS/bin"
    const val IDE = "$TOOLS/ide"
    const val MODELS = "$TOOLS/models"
    const val AI = "ai"
    const val MAP = "STORAGE.md"

    /** `~/tools/bin` в `PATH` гостя — первым: поставленное IDE и ИИ видно терминалу. */
    const val GUEST_BIN = "/root/$BIN"

    /** Каталоги разделов, которые есть всегда (их создаёт [ensure]). */
    val SECTIONS: List<String> = listOf(PROJECTS, LOCAL, TOOLS, BIN, IDE, MODELS, AI)

    private val SLOT = Regex("^\\.(claude|codex|gemini)-slot-([0-9]{1,2})$")

    /**
     * Перенос [from] → [to] — пути от общего корня хоста (каталог данных приложения), с `/`;
     * [link] — оставить на старом месте относительную ссылку.
     */
    data class Move(val from: String, val to: String, val link: Boolean = true)

    /** Итог переноса: перенесено, оставлены ссылки, пропущено (с причиной). */
    data class Migrated(val moved: List<String>, val linked: List<String>, val skipped: List<String>)

    /**
     * Прежние места в домашней папке гостя [home] (путь от [root]): `/root/repos` (клоны),
     * `/root/models`, `~/.local/share/dpai` (службы IDE), `~/.<исполнитель>-slot-N` (слоты).
     */
    fun legacyMoves(root: Path, home: String): List<Move> {
        fun at(rel: String) = Vfs.join(home, rel)
        val out = mutableListOf(
            Move(at("repos"), at(PROJECTS)),
            Move(at("models"), at(MODELS)),
            Move(at(".local/share/dpai"), at(IDE)),
        )
        val names = try {
            Files.list(root.resolve(home)).use { s -> s.iterator().asSequence().map { it.fileName.toString() }.toList() }
        } catch (_: IOException) {
            emptyList()
        }
        for (name in names.sorted()) {
            val m = SLOT.matchEntire(name) ?: continue
            out += Move(at(name), at("$AI/${m.groupValues[1]}-${m.groupValues[2]}"))
        }
        return out
    }

    /** Новый слот (D-143): `~/ai/<исполнитель>-N`. */
    fun slotDir(key: String, n: Int): String {
        require(n in 1..99) { "номер слота — 1…99" }
        return "/root/$AI/$key-$n"
    }

    /** Каталоги разделов в домашней папке [home] (путь от [root]): проекты, свои, инструменты, `bin`, службы IDE, модели, слоты ИИ. */
    fun ensure(root: Path, home: String): Outcome<Unit> {
        val vfs = NioVfs(root)
        return try {
            for (s in SECTIONS) {
                val p = Vfs.join(home, s)
                if (vfs.isSymlink(p)) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "~/$s — символьная ссылка: раздел не создан", p)
                vfs.mkdirs(p)
            }
            Outcome.success(Unit)
        } catch (e: IOException) {
            Outcome.Failure(listOf(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "разделы хранилища не созданы", e, home)))
        }
    }

    /**
     * Перенести [moves] по порядку внутри [root] — переименованиями через `Vfs` хранилища
     * (D-24), без копирования. Уже ссылка — пропуск (перенос был); пустой каталог на старом
     * месте (точка прежней привязки) — убирается и становится ссылкой; новое место занято —
     * переносится то, чего там нет, совпадения остаются на месте с причиной. Ссылки —
     * относительные: верны и с хоста, и из гостя.
     */
    fun migrate(root: Path, moves: List<Move>): Outcome<Migrated> {
        val vfs = NioVfs(root)
        val moved = mutableListOf<String>()
        val linked = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        try {
            for (m in moves) {
                require(Vfs.isSafeRelative(m.from) && Vfs.isSafeRelative(m.to)) { "перенос — внутри корня" }
                val from = root.resolve(m.from)
                if (vfs.isSymlink(m.from) || !Files.exists(from, LinkOption.NOFOLLOW_LINKS)) continue
                if (!vfs.isDirectory(m.from)) {
                    skipped += "${m.from} — не каталог"
                    continue
                }
                if (vfs.isSymlink(m.to)) {
                    skipped += "${m.to} — символьная ссылка"
                    continue
                }
                vfs.mkdirs(Vfs.parentOf(m.to))
                if (!Files.exists(root.resolve(m.to), LinkOption.NOFOLLOW_LINKS)) {
                    if (children(from).isEmpty()) {
                        vfs.mkdirs(m.to)
                        vfs.delete(m.from)
                    } else {
                        vfs.rename(m.from, m.to)
                        moved += "${m.from} → ${m.to}"
                    }
                } else {
                    for (name in children(from)) {
                        val child = Vfs.join(m.from, name)
                        val target = Vfs.join(m.to, name)
                        if (Files.exists(root.resolve(target), LinkOption.NOFOLLOW_LINKS)) {
                            skipped += "$child — в ${m.to} уже есть $name"
                        } else {
                            vfs.rename(child, target)
                            moved += "$child → $target"
                        }
                    }
                    if (children(from).isNotEmpty()) continue
                    vfs.delete(m.from)
                }
                if (m.link) {
                    val rel = from.parent.relativize(root.resolve(m.to)).toString()
                    vfs.symlink(m.from, rel)
                    linked += "${m.from} → $rel"
                }
            }
        } catch (e: IOException) {
            return Outcome.Failure(listOf(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "перенос хранилища прерван — перенесённое осталось на новом месте", e, moves.firstOrNull()?.from)))
        }
        return Outcome.success(Migrated(moved, linked, skipped))
    }

    /** Имена записей каталога — файлов, каталогов и ссылок (`Vfs.list` видит только файлы). */
    private fun children(dir: Path): List<String> = Files.list(dir).use { s -> s.iterator().asSequence().map { it.fileName.toString() }.sorted().toList() }

    /** Проект в `~/projects`: путь от раздела (`dpsmpx/DPmetaApp`, `local/sandbox`) и ветка (`null` — без Git или отделённая голова). */
    data class Project(val path: String, val branch: String?, val git: Boolean)

    /** Модель в `~/tools/models`: имя файла и размер. */
    data class Model(val name: String, val size: Long)

    /** Секрет для карты: имя, переменная окружения и выдаётся ли ИИ — без значения. */
    data class SecretLine(val name: String, val variable: String?, val toAi: Boolean)

    /**
     * Что знает карта. Размеры разделов — `null`, если не посчитаны; [approx] — счёт
     * остановлен на пределе файлов (размер не меньше показанного).
     */
    data class Facts(
        val at: String,
        val sizes: Map<String, Long?>,
        val approx: Set<String>,
        val projects: List<Project>,
        val bin: List<String>,
        val ide: List<String>,
        val sdk: List<String>,
        val models: List<Model>,
        val packages: Int?,
        val slots: List<String>,
        val secrets: List<SecretLine>,
        val free: Long?,
        val limit: Long,
    )

    /** Предел файлов при счёте размера раздела: дальше — «не меньше». */
    const val COUNT_LIMIT = 200_000

    /**
     * Факты из файлов гостя: размеры разделов, проекты и ветки, `~/tools/bin`, службы IDE,
     * платформы SDK, модели, число пакетов Alpine, слоты. Секреты, время и место —
     * от оболочки ([at], [secrets], [free], [limit]).
     */
    fun scan(home: Path, rootfs: Path, at: String, secrets: List<SecretLine>, free: Long?, limit: Long): Facts {
        val sizes = linkedMapOf<String, Long?>()
        val approx = mutableSetOf<String>()
        for (s in listOf(PROJECTS, TOOLS, AI)) {
            val (size, cut) = size(home.resolve(s))
            sizes[s] = size
            if (cut) approx += s
        }
        return Facts(
            at = at,
            sizes = sizes,
            approx = approx,
            projects = projects(home.resolve(PROJECTS)),
            bin = names(home.resolve(BIN)),
            ide = names(home.resolve(IDE)).filter { !it.endsWith("-system") },
            sdk = names(home.resolve("$IDE/android-sdk/platforms")),
            models = files(home.resolve(MODELS)).map { Model(it.fileName.toString(), runCatching { Files.size(it) }.getOrDefault(0L)) },
            packages = runCatching { Files.readAllLines(rootfs.resolve("lib/apk/db/installed")).count { it.startsWith("P:") } }.getOrNull(),
            slots = names(home.resolve(AI)),
            secrets = secrets,
            free = free,
            limit = limit,
        )
    }

    private fun names(dir: Path): List<String> = try {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) emptyList() else children(dir).filter { !it.startsWith(".") }
    } catch (_: IOException) {
        emptyList()
    }

    private fun files(dir: Path): List<Path> = try {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) emptyList() else children(dir).map(dir::resolve).filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
    } catch (_: IOException) {
        emptyList()
    }

    /** Проекты: `<владелец>/<имя>` и `local/<имя>`; ветка — из `.git/HEAD`. */
    private fun projects(root: Path): List<Project> {
        val out = mutableListOf<Project>()
        for (owner in names(root)) {
            for (name in names(root.resolve(owner))) {
                val dir = root.resolve(owner).resolve(name)
                if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) continue
                val head = dir.resolve(".git/HEAD")
                val git = Files.isDirectory(dir.resolve(".git"), LinkOption.NOFOLLOW_LINKS)
                val branch = runCatching { Files.readAllLines(head).firstOrNull()?.trim() }.getOrNull()?.takeIf { it.startsWith("ref: refs/heads/") }?.removePrefix("ref: refs/heads/")
                out += Project("$owner/$name", branch, git)
            }
        }
        return out
    }

    /** Размер каталога без перехода по ссылкам; дальше [COUNT_LIMIT] файлов — остановка (второе — `true`). */
    private fun size(dir: Path): Pair<Long?, Boolean> {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return null to false
        var total = 0L
        var count = 0
        var cut = false
        return try {
            Files.walkFileTree(
                dir,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (attrs.isRegularFile) total += attrs.size()
                        if (++count >= COUNT_LIMIT) {
                            cut = true
                            return FileVisitResult.TERMINATE
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
                },
            )
            total to cut
        } catch (_: IOException) {
            null to false
        }
    }

    private const val BEGIN = "<!-- dpmetaapp:storage -->"
    private const val END = "<!-- /dpmetaapp:storage -->"

    /** Строка в инструкции слота ИИ: где карта и куда что ставить (DPmetaApp:D-111). */
    const val POINTER: String = "Карта хранилища — `~/STORAGE.md` (разделы, проекты, инструменты, слоты, имена секретов). " +
        "Прочитай её, прежде чем искать файлы и программы. Проекты — в `~/projects`, инструменты — в `~/tools` (`~/tools/bin` — в `PATH`). " +
        "Каталоги `~/ai` — входы ИИ: не читай и не копируй их."

    /**
     * Файл общих инструкций исполнителя в каталоге его слота [slot] (`claude-1`, `codex-2`):
     * Claude — `CLAUDE.md` (`CLAUDE_CONFIG_DIR`), Codex — AGENTS.md (`CODEX_HOME`),
     * Gemini — `.gemini/GEMINI.md` (`GEMINI_CLI_HOME`); чужое имя — `null`.
     */
    fun instructionFile(slot: String): String? = when (slot.substringBefore('-')) {
        "claude" -> "CLAUDE.md"
        "codex" -> "AGENTS.md"
        "gemini" -> ".gemini/GEMINI.md"
        else -> null
    }

    /** Текст инструкции с блоком карты: блок заменяется на месте, нет — дописывается; остальное не трогается. */
    fun withPointer(existing: String?): String {
        val block = "$BEGIN\n$POINTER\n$END\n"
        val text = existing.orEmpty()
        val a = text.indexOf(BEGIN)
        val b = text.indexOf(END)
        if (a >= 0 && b > a) {
            val tail = text.substring(b + END.length).removePrefix("\n")
            return text.substring(0, a) + block + tail
        }
        return if (text.isEmpty()) block else text.trimEnd('\n') + "\n\n" + block
    }

    /** `/etc/profile.d` гостя: `~/tools/bin` первым и в оболочке входа (Alpine пересобирает `PATH` в `/etc/profile`). */
    const val PROFILE_SCRIPT: String = "# Пишет DPmetaApp (D-143): инструменты хранилища — в PATH терминала, IDE и ИИ.\n" +
        "case \":${'$'}PATH:\" in\n  *:/root/tools/bin:*) ;;\n  *) export PATH=\"/root/tools/bin:${'$'}PATH\" ;;\nesac\n"

    private fun gb(b: Long?): String = when {
        b == null -> "нет данных"
        b < (1L shl 30) -> "${(b + (1L shl 20) - 1) shr 20} МБ"
        else -> (b * 10 / (1L shl 30)).let { "${it / 10},${it % 10} ГБ" }
    }

    /**
     * Карта `~/STORAGE.md` — коротко, для ИИ и человека: разделы с размерами, проекты,
     * инструменты, слоты, имена секретов без значений, место и правило запаса.
     */
    fun render(f: Facts): String = buildString {
        fun size(s: String) = (if (s in f.approx) "≥ " else "") + gb(f.sizes[s])
        appendLine("# Хранилище — карта")
        appendLine()
        appendLine("Обновлено: ${f.at}. Пишет DPmetaApp — не правьте: файл перезапишется.")
        appendLine("Терминал, IDE и ИИ работают в одной папке `/root` и видят одно и то же.")
        appendLine()
        appendLine("| Раздел | Путь | Что | Размер |")
        appendLine("|---|---|---|---|")
        appendLine("| Проекты | `~/projects` | клоны GitHub — `~/projects/<владелец>/<имя>`, свои — `~/projects/local/<имя>` | ${size(PROJECTS)} |")
        appendLine("| Инструменты | `~/tools` | службы IDE — `~/tools/ide`, модели — `~/tools/models`; `~/tools/bin` — в `PATH` | ${size(TOOLS)} |")
        appendLine("| Слоты ИИ | `~/ai` | каталоги входа исполнителей; содержимое — секрет, не читать и не копировать | ${size(AI)} |")
        appendLine("| Секреты | — | в Keystore приложения; на диске их нет | ${f.secrets.size} |")
        appendLine()
        appendLine("## Проекты")
        if (f.projects.isEmpty()) appendLine("- нет")
        for (p in f.projects) {
            appendLine("- `~/projects/${p.path}` — " + when {
                !p.git -> "без Git"
                p.branch == null -> "Git, ветка не выбрана"
                else -> "ветка `${p.branch}`"
            })
        }
        appendLine()
        appendLine("## Инструменты")
        appendLine("- в `PATH` (`~/tools/bin`): " + f.bin.joinToString(", ").ifEmpty { "пусто" })
        appendLine("- службы IDE (`~/tools/ide`): " + f.ide.joinToString(", ").ifEmpty { "нет" })
        if (f.sdk.isNotEmpty()) appendLine("- платформы Android SDK: " + f.sdk.joinToString(", "))
        appendLine("- модели (`~/tools/models`): " + f.models.joinToString(", ") { "${it.name} (${gb(it.size)})" }.ifEmpty { "нет" })
        appendLine("- пакеты Alpine: " + (f.packages?.toString() ?: "нет данных") + " — список: `apk info`; программы — в `/usr/bin`")
        appendLine()
        appendLine("## Слоты ИИ")
        appendLine("- " + f.slots.joinToString(", ").ifEmpty { "нет" })
        appendLine()
        appendLine("## Секреты")
        if (f.secrets.isEmpty()) appendLine("- нет")
        for (s in f.secrets) {
            appendLine("- ${s.name}" + (s.variable?.let { " — `$it`" } ?: "") + if (s.toAi) " — выдаётся ИИ" else " — ИИ не выдаётся")
        }
        appendLine()
        appendLine("## Место")
        appendLine("- предел: " + if (f.limit > 0) gb(f.limit) else "всё свободное место телефона")
        appendLine("- свободно на телефоне: ${gb(f.free)}")
        appendLine("- загрузка, установка, клон и сборка не начнутся, если после них останется меньше 1 ГБ")
    }
}
