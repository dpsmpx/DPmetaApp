package com.dpsmpx.dpai.orchestrator.linux

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.storage.vfs.Vfs
import java.io.IOException

/**
 * Пакеты Alpine гостя целиком (D-191; `DPmetaApp:D-171`, шаг 1): база `apk`
 * (`/lib/apk/db/installed`), поставленное автором (`/etc/apk/world`), репозитории и выпуск —
 * читаются с хоста, без процесса гостя и без сети. Отсюда — список с размерами и суммами,
 * для кого нужен пакет, совместимость пакетов между собой и что уйдёт при удалении.
 *
 * Сумма `C:` — то, что записал сам `apk`: `Q1` и SHA-1 управляющей части пакета в base64;
 * подписи пакетов `apk` проверяет при установке по ключам корневой системы (T11). Версии
 * ограничений — по правилам apk-tools ([AlpineVersion]); неверная запись — «не сверено», а
 * не «несовместимо» (D-05).
 */
object AlpinePackages {
    /** Ограничение зависимости: `имя`, `имя>=версия`, `!имя` (конфликт), `so:…`, `cmd:…`, `/путь`. */
    data class Dependency(val raw: String, val name: String, val op: String?, val version: String?, val conflict: Boolean) {
        /** Зависимость от файла (`/bin/sh`): её даёт пакет, которому файл принадлежит. */
        val path: Boolean get() = name.startsWith('/')
    }

    /** Что пакет даёт кроме своего имени: `so:libz.so.1=1.3`, `cmd:git=2.54.0-r0`. */
    data class Provide(val name: String, val version: String?)

    data class Package(
        val name: String,
        val version: String,
        val arch: String?,
        /** Размер файла пакета (`S:`), байты. */
        val size: Long?,
        /** Сколько занимает установленный (`I:`), байты; неизвестно — `null`, а не ноль. */
        val installedSize: Long?,
        val description: String?,
        val url: String?,
        val license: String?,
        val origin: String?,
        /** Сумма из базы `apk` (`C:`). */
        val checksum: String?,
        /** Время сборки (`t:`), секунды Unix. */
        val buildTime: Long?,
        val commit: String?,
        val depends: List<Dependency>,
        val provides: List<Provide>,
        val installIf: List<Dependency>,
        val broken: ApkDatabase.Broken?,
        val files: Int,
    )

    /** Пакеты гостя и их окружение; [paths] — файл → владелец (для зависимостей от файла). */
    class Inventory(
        val packages: List<Package>,
        val world: List<Dependency>,
        val repositories: List<String>,
        val release: String?,
        internal val paths: Map<String, String>,
    ) {
        val byName: Map<String, Package> = packages.associateBy { it.name }

        /** Имена из `world` — поставленное автором (или приложением по его действию). */
        val worldNames: Set<String> = world.filter { !it.conflict && !it.path }.map { it.name }.toSet()

        private val providers: Map<String, List<Pair<Package, String?>>> = buildMap<String, MutableList<Pair<Package, String?>>> {
            for (p in packages) {
                getOrPut(p.name) { mutableListOf() } += p to p.version
                for (pr in p.provides) getOrPut(pr.name) { mutableListOf() } += p to pr.version
            }
        }

        /** Установленные пакеты, что удовлетворяют [d]; версия, которую не сверить, — не помеха. */
        fun satisfying(d: Dependency): List<Package> {
            if (d.path) return listOfNotNull(paths[d.name.removePrefix("/")]?.let(byName::get))
            val all = providers[d.name].orEmpty()
            if (d.op == null || d.version == null) return all.map { it.first }.distinct()
            return all.filter { (_, v) -> v != null && AlpineVersion.satisfies(v, d.op, d.version) != false }.map { it.first }.distinct()
        }

        /** Кто стоит под этим именем вообще, без сверки версии. */
        internal fun anyProvider(name: String): List<Package> = providers[name].orEmpty().map { it.first }.distinct()

        /** Для кого нужен пакет: имя → пакеты, чьи зависимости он удовлетворяет. */
        val requiredBy: Map<String, List<String>> by lazy {
            val out = HashMap<String, MutableSet<String>>()
            for (p in packages) for (d in p.depends) if (!d.conflict) for (q in satisfying(d)) if (q.name != p.name) out.getOrPut(q.name) { sortedSetOf() } += p.name
            out.mapValues { it.value.toList() }
        }
    }

    /** Что не так с пакетами между собой. */
    enum class ProblemKind { MISSING, VERSION, CONFLICT, BROKEN, WORLD }

    data class Problem(val kind: ProblemKind, val pkg: String, val detail: String)

    /** Что будет, если убрать пакет автора [name]. */
    data class Removal(
        val name: String,
        /** Можно ли убирать с экрана; нет — [reason]. */
        val allowed: Boolean,
        val reason: String?,
        /** Пакеты, которые уйдут, — больший первым. */
        val gone: List<Package>,
        /** Освободится, байты; у кого-то размер неизвестен — `null`. */
        val freed: Long?,
        /** Пакет останется: нужен этим пакетам. */
        val keptFor: List<String>,
    )

    /** Части системы: без них Alpine и `apk` не работают. */
    val CORE: Set<String> = setOf("musl", "busybox", "apk-tools", "alpine-baselayout", "alpine-keys")

    /** Что minirootfs ставит сама: не убирается с экрана. */
    val SYSTEM: Set<String> = CORE + setOf("alpine-baselayout-data", "alpine-release", "musl-utils", "busybox-binsh", "ca-certificates-bundle", "libc-utils")

    /** Больше — не база среды приложения. */
    const val MAX_PACKAGES = 20_000
    const val MAX_TEXT = 1 shl 20

    private val DEP = Regex("^(!)?([^<>=~@\\s]+)(?:@[A-Za-z0-9._-]+)?(?:(<=|>=|=<|=>|=~|~=|<|>|=|~)(\\S+))?$")

    /** Разбор одного ограничения; неразборчивое — `null`. */
    fun dependency(raw: String): Dependency? {
        val m = DEP.matchEntire(raw.trim()) ?: return null
        val (bang, name, op, version) = m.destructured
        return Dependency(raw.trim(), name, op.ifEmpty { null }, version.ifEmpty { null }, bang.isNotEmpty())
    }

    private fun provide(raw: String): Provide {
        val i = raw.indexOf('=')
        return if (i > 0) Provide(raw.substring(0, i), raw.substring(i + 1)) else Provide(raw, null)
    }

    /**
     * Разбор базы [installed], списка [world] (имена через пробел или строки),
     * [repositories] (строки; `#` — заметка) и [release] (`/etc/alpine-release`).
     */
    fun parse(installed: String, world: String?, repositories: String?, release: String?): Inventory {
        val packages = ArrayList<Package>()
        val paths = HashMap<String, String>()
        val fields = HashMap<Char, String>()
        val depends = ArrayList<Dependency>()
        val provides = ArrayList<Provide>()
        val installIf = ArrayList<Dependency>()
        val files = ArrayList<String>()
        var dir = ""

        fun flush() {
            val name = fields['P']
            val version = fields['V']
            if (name != null && version != null && packages.size < MAX_PACKAGES) {
                val flags = fields['f']
                val broken = flags?.let { ApkDatabase.Broken(name, version, 'f' in it, 's' in it, 'x' in it) }
                packages += Package(
                    name, version, fields['A'], fields['S']?.toLongOrNull(), fields['I']?.toLongOrNull(),
                    fields['T'], fields['U'], fields['L'], fields['o'], fields['C'], fields['t']?.toLongOrNull(), fields['c'],
                    depends.toList(), provides.toList(), installIf.toList(), broken, files.size,
                )
                for (f in files) paths.putIfAbsent(f, name)
            }
            fields.clear(); depends.clear(); provides.clear(); installIf.clear(); files.clear(); dir = ""
        }

        for (raw in installed.lineSequence()) {
            val line = raw.trimEnd('\r')
            if (line.isEmpty()) { flush(); continue }
            if (line.length < 2 || line[1] != ':') continue
            val key = line[0]
            val value = line.substring(2)
            when (key) {
                'D' -> value.split(' ').filter { it.isNotEmpty() }.mapNotNullTo(depends, ::dependency)
                'p' -> value.split(' ').filter { it.isNotEmpty() }.mapTo(provides, ::provide)
                'i' -> value.split(' ').filter { it.isNotEmpty() }.mapNotNullTo(installIf, ::dependency)
                'F' -> dir = value
                'R' -> files += if (dir.isEmpty()) value else "$dir/$value"
                else -> fields.putIfAbsent(key, value)
            }
        }
        flush()
        val worldDeps = world.orEmpty().split(' ', '\n', '\t').filter { it.isNotBlank() }.mapNotNull(::dependency)
        val repos = repositories.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith('#') }
        return Inventory(packages.sortedBy { it.name }, worldDeps, repos, release?.trim()?.ifEmpty { null }, paths)
    }

    /** Совместимость пакетов между собой — без сети и процесса гостя. */
    fun problems(inv: Inventory): List<Problem> {
        val out = mutableListOf<Problem>()
        for (p in inv.packages) {
            p.broken?.let { out += Problem(ProblemKind.BROKEN, p.name, "повреждён: ${it.why}") }
            for (d in p.depends) {
                if (d.conflict) {
                    val clash = inv.satisfying(d.copy(conflict = false)).filter { it.name != p.name }
                    if (clash.isNotEmpty()) out += Problem(ProblemKind.CONFLICT, p.name, "несовместим с ${clash.joinToString { it.name }} (${d.raw})")
                    continue
                }
                if (inv.satisfying(d).isNotEmpty()) continue
                val any = if (d.path) emptyList() else inv.anyProvider(d.name)
                out += if (any.isNotEmpty()) {
                    Problem(ProblemKind.VERSION, p.name, "нужен ${d.raw}, стоит ${any.joinToString { "${it.name} ${it.version}" }}")
                } else {
                    Problem(ProblemKind.MISSING, p.name, "нужен ${d.raw} — его нет")
                }
            }
        }
        for (w in inv.world) if (!w.conflict && inv.satisfying(w).isEmpty()) out += Problem(ProblemKind.WORLD, w.name, "в списке поставленных (${w.raw}), но не стоит")
        return out
    }

    /** Пакеты, нужные списку [roots]: зависимости и `install_if` до неподвижной точки. */
    fun closure(inv: Inventory, roots: List<Dependency>): Set<String> {
        val needed = LinkedHashSet<String>()
        val queue = ArrayDeque<Package>()
        fun need(d: Dependency) {
            if (d.conflict) return
            for (q in inv.satisfying(d)) if (needed.add(q.name)) queue += q
        }
        roots.forEach(::need)
        while (true) {
            while (queue.isNotEmpty()) queue.removeFirst().depends.forEach(::need)
            // install_if: пакет нужен, когда все его условия удовлетворены уже нужными пакетами.
            val more = inv.packages.filter { p ->
                p.name !in needed && p.installIf.isNotEmpty() && p.installIf.all { c -> inv.satisfying(c).any { it.name in needed } }
            }
            if (more.isEmpty()) return needed
            for (p in more) if (needed.add(p.name)) queue += p
        }
    }

    /** Пакеты, которые не нужны ничему из `world`: `apk` уберёт их при следующей команде. */
    fun orphans(inv: Inventory): List<Package> {
        val needed = closure(inv, inv.world)
        return inv.packages.filter { it.name !in needed }
    }

    /** Что уйдёт, если убрать [name] из поставленного автором. */
    fun removal(inv: Inventory, name: String): Removal {
        val pkg = inv.byName[name]
        fun refuse(why: String) = Removal(name, false, why, emptyList(), null, inv.requiredBy[name].orEmpty())
        if (pkg == null) return refuse("пакета нет")
        if (name in SYSTEM) return refuse("часть системы Alpine: без него среда не работает")
        if (name !in inv.worldNames) return refuse("поставлен зависимостью — уйдёт сам, когда уберут: ${inv.requiredBy[name].orEmpty().joinToString().ifEmpty { "—" }}")
        val before = closure(inv, inv.world)
        val after = closure(inv, inv.world.filter { it.conflict || it.path || it.name != name })
        val gone = inv.packages.filter { it.name in before && it.name !in after }.sortedByDescending { it.installedSize ?: -1L }
        gone.firstOrNull { it.name in CORE }?.let { return refuse("вместе с ним ушёл бы ${it.name} — без него Alpine не работает") }
        val freed = if (gone.any { it.installedSize == null }) null else gone.sumOf { it.installedSize!! }
        val kept = if (name in after) inv.requiredBy[name].orEmpty().filter { it in after } else emptyList()
        return Removal(name, true, null, gone, freed, kept)
    }

    /**
     * Прочитать пакеты гостя с корня [rootfs]. Базы нет — `null` (среда не установлена или
     * `apk` ещё ничего не ставил). Символьная ссылка на пути к файлу — отказ: эти файлы пишет
     * гость, и ссылкой он мог бы показать на экране чужой файл приложения (A-3).
     */
    fun read(vfs: Vfs, rootfs: String): Outcome<Inventory?> {
        val installed = when (val r = ApkDatabase.read(vfs, rootfs)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value ?: return Outcome.success(null)
        }
        val world = text(vfs, rootfs, "etc/apk/world").let { if (it is Outcome.Failure) return it else (it as Outcome.Success).value }
        val repos = text(vfs, rootfs, "etc/apk/repositories").let { if (it is Outcome.Failure) return it else (it as Outcome.Success).value }
        val release = text(vfs, rootfs, "etc/alpine-release").let { if (it is Outcome.Failure) return it else (it as Outcome.Success).value }
        return Outcome.success(parse(installed, world, repos, release))
    }

    /** Текстовый файл гостя [rel] от [rootfs]: нет — `null`; ссылка на пути или больше [MAX_TEXT] — отказ. */
    internal fun text(vfs: Vfs, rootfs: String, rel: String): Outcome<String?> {
        var at = rootfs
        for (part in rel.split('/')) {
            at = Vfs.join(at, part)
            if (vfs.isSymlink(at)) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "файл гостя — символьная ссылка", at)
        }
        return try {
            val size = vfs.size(at) ?: return Outcome.success(null)
            if (size > MAX_TEXT) return Outcome.failure(DiagCode.VALUE_INVALID, "файл гостя больше ${MAX_TEXT shr 10} КБ", at)
            Outcome.success(vfs.read(at)?.toString(Charsets.UTF_8))
        } catch (e: IOException) {
            Outcome.Failure(listOf(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "файл гостя не прочитан", e, at)))
        }
    }
}
