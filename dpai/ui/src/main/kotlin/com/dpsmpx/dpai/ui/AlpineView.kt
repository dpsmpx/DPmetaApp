package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.orchestrator.linux.AlpinePackages
import com.dpsmpx.dpai.orchestrator.linux.LinuxEnvironment
import java.time.Instant
import java.time.ZoneOffset

/**
 * Экран «Окружение», раздел «Alpine» (D-191; `DPmetaApp:D-171`, шаг 1): что показать о
 * пакетах гостя и какие команды `apk` предложить. Смысл — здесь, на JVM с тестами; оболочка
 * только рисует. Неизвестный размер — «неизвестно», а не ноль (D-13).
 */
object AlpineView {
    enum class Filter(val title: String) { ALL("Все"), MINE("Ваши"), PROBLEMS("С проблемами") }

    enum class Sort(val title: String) { NAME("По имени"), SIZE("По размеру") }

    /** Строка списка: [mine] — поставлен вами (`world`), [system] — часть системы Alpine. */
    data class Row(
        val name: String,
        val version: String,
        val size: String,
        val mine: Boolean,
        val system: Boolean,
        val problems: List<String>,
        val requiredBy: List<String>,
    )

    /** Шаг починки: команда `apk` и для каких пакетов. */
    data class Repair(val action: LinuxEnvironment.Apk, val packages: List<String>, val title: String)

    /** Размер для человека: КБ до мегабайта, дальше МБ и ГБ с десятой; неизвестно — так и сказано. */
    fun size(bytes: Long?): String = when {
        bytes == null -> "неизвестно"
        bytes < 1L shl 20 -> "${(bytes + 1023) / 1024} КБ"
        bytes < 1L shl 30 -> (bytes * 10 / (1L shl 20)).let { "${it / 10},${it % 10} МБ" }
        else -> (bytes * 10 / (1L shl 30)).let { "${it / 10},${it % 10} ГБ" }
    }

    /** Сводка над списком. */
    fun summary(inv: AlpinePackages.Inventory, problems: List<AlpinePackages.Problem>): String {
        val known = inv.packages.mapNotNull { it.installedSize }.sum()
        val unknown = inv.packages.count { it.installedSize == null }
        val total = if (unknown == 0) size(known) else "не меньше ${size(known)} (у $unknown размер неизвестен)"
        val mine = inv.packages.count { it.name in inv.worldNames }
        val issues = problems.map { it.pkg }.distinct().size
        return "Alpine ${inv.release ?: "неизвестной версии"} · пакетов ${inv.packages.size}, ваших $mine · занято $total · " +
            if (issues == 0) "проблем нет" else "с проблемами: $issues"
    }

    /** Строки по поиску [query] (имя или описание), фильтру и порядку. */
    fun rows(inv: AlpinePackages.Inventory, problems: List<AlpinePackages.Problem>, query: String, filter: Filter, sort: Sort): List<Row> {
        val byPkg = problems.groupBy({ it.pkg }, { it.detail })
        val q = query.trim().lowercase()
        val picked = inv.packages.filter { p ->
            (q.isEmpty() || q in p.name.lowercase() || p.description?.lowercase()?.contains(q) == true) &&
                when (filter) {
                    Filter.ALL -> true
                    Filter.MINE -> p.name in inv.worldNames
                    Filter.PROBLEMS -> p.name in byPkg
                }
        }
        val sorted = when (sort) {
            Sort.NAME -> picked.sortedBy { it.name }
            Sort.SIZE -> picked.sortedWith(compareByDescending<AlpinePackages.Package> { it.installedSize ?: -1L }.thenBy { it.name })
        }
        val rows = sorted.map { p ->
            Row(p.name, p.version, size(p.installedSize), p.name in inv.worldNames, p.name in AlpinePackages.SYSTEM, byPkg[p.name].orEmpty(), inv.requiredBy[p.name].orEmpty())
        }
        // `world` без пакета — строка-призрак: иначе проблему не увидеть в списке.
        val ghosts = byPkg.keys.filter { it !in inv.byName && (q.isEmpty() || q in it.lowercase()) }.sorted()
            .map { Row(it, "—", "—", true, false, byPkg[it].orEmpty(), emptyList()) }
        return rows + ghosts
    }

    /** Подробности пакета: подпись → значение; пустое не показывается. */
    fun details(p: AlpinePackages.Package, inv: AlpinePackages.Inventory): List<Pair<String, String>> = listOfNotNull(
        "Версия" to p.version,
        p.arch?.let { "Архитектура" to it },
        "Занимает" to size(p.installedSize),
        p.size?.let { "Файл пакета" to size(it) },
        p.description?.let { "Описание" to it },
        p.license?.let { "Лицензия" to it },
        p.url?.let { "Сайт" to it },
        p.origin?.takeIf { it != p.name }?.let { "Исходный пакет" to it },
        p.checksum?.let { "Сумма apk (SHA-1 в base64)" to it },
        p.buildTime?.let { "Собран" to Instant.ofEpochSecond(it).atOffset(ZoneOffset.UTC).toLocalDate().toString() },
        "Файлов" to p.files.toString(),
        "Поставлен" to if (p.name in inv.worldNames) "вами" else "зависимостью",
        inv.requiredBy[p.name]?.takeIf { it.isNotEmpty() }?.let { "Нужен для" to it.joinToString() },
        p.depends.takeIf { it.isNotEmpty() }?.let { d -> "Зависит от" to d.joinToString { it.raw } },
    )

    /** Текст окна «Убрать?». */
    fun removalText(r: AlpinePackages.Removal): String = when {
        !r.allowed -> "Убрать «${r.name}» нельзя: ${r.reason}."
        r.gone.isEmpty() -> "«${r.name}» уйдёт из списка ваших, но останется: он нужен для ${r.keptFor.joinToString()}. Место не освободится."
        else -> buildString {
            append("Уйдут пакеты (${r.gone.size}): ")
            append(r.gone.take(MAX_LISTED).joinToString { "${it.name} (${size(it.installedSize)})" })
            if (r.gone.size > MAX_LISTED) append(" и ещё ${r.gone.size - MAX_LISTED}")
            append(". Освободится: ${size(r.freed)}.")
        }
    }

    /**
     * Починка по проблемам — без ИИ (`DPmetaApp:D-168`): повреждённые — `apk fix` тех же
     * пакетов; нет зависимости или не та версия — `apk fix --depends` пакета; в списке
     * ваших, но не стоит, — `apk add`. Конфликт сам не чинится: решает человек.
     */
    fun repairs(problems: List<AlpinePackages.Problem>): List<Repair> {
        fun names(vararg kinds: AlpinePackages.ProblemKind) =
            problems.filter { it.kind in kinds }.map { it.pkg }.distinct().filter { LinuxEnvironment.PACKAGE.matches(it) }.take(LinuxEnvironment.MAX_PACKAGES)
        return listOfNotNull(
            names(AlpinePackages.ProblemKind.BROKEN).takeIf { it.isNotEmpty() }?.let { Repair(LinuxEnvironment.Apk.REPAIR, it, "Починить повреждённые (${it.size})") },
            names(AlpinePackages.ProblemKind.MISSING, AlpinePackages.ProblemKind.VERSION).takeIf { it.isNotEmpty() }?.let {
                Repair(LinuxEnvironment.Apk.FIX, it, "Доставить зависимости (${it.size})")
            },
            names(AlpinePackages.ProblemKind.WORLD).takeIf { it.isNotEmpty() }?.let { Repair(LinuxEnvironment.Apk.ADD, it, "Поставить недостающие ваши (${it.size})") },
        )
    }

    private const val MAX_LISTED = 12
}
