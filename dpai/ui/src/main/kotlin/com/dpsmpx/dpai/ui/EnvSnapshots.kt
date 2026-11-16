package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpai.orchestrator.linux.AlpinePackages
import com.dpsmpx.dpai.orchestrator.linux.AlpineVersion
import com.dpsmpx.dpai.orchestrator.linux.LinuxEnvironment

/**
 * Снимки окружения и откат (D-194; `DPmetaApp:D-171`, шаг 4). Снимок — перед каждой
 * правкой: `world`, репозитории, версии пакетов, свои переменные (`dpai.env-snapshot` v1, в
 * данных приложения, последние [KEEP]). Файлы пакетов — кеш apk гостя (`/var/cache/apk`):
 * старая версия, которой уже нет в зеркале, возвращается только оттуда. План отката заранее
 * называет, что вернётся наверняка (кеш), что — только если зеркало ещё даёт версию, и что
 * уйдёт; после отката [leftover] сверяет итог со снимком (сверено с apk-tools 3.0.8 в госте:
 * `world` и `apk fix` возвращают и убирают пакеты, файл из кеша ставит точную версию, и
 * `apk fix` её потом не поднимает).
 */
object EnvSnapshots {
    const val FORMAT = "dpai.env-snapshot"
    const val VERSION = 1
    const val KEEP = 10

    data class Snapshot(
        val id: String,
        /** Миллисекунды Unix по `Clock`. */
        val at: Long,
        /** Что за правка шла следом: «apk add git», «Репозитории: edge». */
        val label: String,
        val release: String?,
        val repositories: List<String>,
        val world: List<String>,
        val packages: Map<String, String>,
        val vars: List<OwnVariables.Var>,
    ) {
        /** Состояние то же (без id, времени и подписи). */
        fun sameState(o: Snapshot): Boolean = repositories == o.repositories && world == o.world && packages == o.packages && vars == o.vars
    }

    fun take(id: String, at: Long, label: String, inv: AlpinePackages.Inventory, vars: List<OwnVariables.Var>): Snapshot =
        Snapshot(id, at, label.take(MAX_LABEL), inv.release, inv.repositories, inv.world.map { it.raw }, inv.packages.associate { it.name to it.version }.toSortedMap(), vars)

    /** Подпись снимка по команде apk, которая идёт следом. */
    fun label(action: LinuxEnvironment.Apk, packages: List<String>): String {
        val what = when (action) {
            LinuxEnvironment.Apk.ADD -> "Поставить"
            LinuxEnvironment.Apk.DEL -> "Убрать"
            LinuxEnvironment.Apk.UPGRADE -> "Обновить всё"
            LinuxEnvironment.Apk.UPGRADE_SOME -> "Обновить"
            LinuxEnvironment.Apk.UPGRADE_AVAILABLE, LinuxEnvironment.Apk.UPGRADE_AVAILABLE_SOME -> "Привести к ветке"
            LinuxEnvironment.Apk.FIX, LinuxEnvironment.Apk.REPAIR -> "Починить"
            LinuxEnvironment.Apk.APPLY_WORLD -> "Применить список"
            LinuxEnvironment.Apk.UPDATE, LinuxEnvironment.Apk.LIST_UPGRADES -> "Проверить обновления"
        }
        return (what + if (packages.isEmpty()) "" else ": " + packages.joinToString(" ")).take(MAX_LABEL)
    }

    /** Файл пакета в кеше: `имя-версия.хеш8.apk`. */
    data class CacheFile(val name: String, val version: String, val file: String)

    private val CACHE_FILE = Regex("^(.+)-([^-]+-r\\d+)\\.([0-9a-f]{8})\\.apk$")

    fun cacheFile(file: String): CacheFile? {
        val m = CACHE_FILE.matchEntire(file) ?: return null
        val (name, version) = m.destructured
        if (!LinuxEnvironment.PACKAGE.matches(name) || !AlpineVersion.valid(version)) return null
        return CacheFile(name, version, file)
    }

    /** Версия, которую надо вернуть: [now] — что стоит (`null` — нет), [cached] — файл в кеше. */
    data class VersionBack(val name: String, val now: String?, val want: String, val cached: String?)

    data class Plan(
        val repositories: Boolean,
        val vars: Boolean,
        val worldAdd: List<String>,
        val worldRemove: List<String>,
        /** Уйдут: стоят сейчас, в снимке их не было. */
        val extra: List<String>,
        /** Вернутся к версии снимка. */
        val versions: List<VersionBack>,
        /** Откат опасен — почему ([unsafe]). */
        val unsafe: String? = null,
    ) {
        val empty: Boolean get() = !repositories && !vars && worldAdd.isEmpty() && worldRemove.isEmpty() && extra.isEmpty() && versions.isEmpty()
    }

    fun plan(s: Snapshot, inv: AlpinePackages.Inventory, vars: List<OwnVariables.Var>, cache: List<CacheFile>): Plan {
        val now = inv.packages.associate { it.name to it.version }
        val world = inv.world.map { it.raw }
        val byKey = cache.associateBy { it.name to it.version }
        val versions = s.packages.filter { (n, v) -> now[n] != v }.map { (n, v) -> VersionBack(n, now[n], v, byKey[n to v]?.file) }
        return Plan(
            repositories = s.repositories != inv.repositories,
            vars = s.vars != vars,
            worldAdd = s.world.filter { it !in world },
            worldRemove = world.filter { it !in s.world },
            extra = now.keys.filter { it !in s.packages }.sorted(),
            versions = versions.sortedBy { it.name },
            unsafe = unsafe(s),
        )
    }

    /**
     * Почему откат к [s] опасен; `null` — можно. Снимок без основы Alpine в `world` (файла не
     * было, база повреждена) увёл бы `apk fix` к пустой системе.
     */
    fun unsafe(s: Snapshot): String? {
        val names = s.world.mapNotNull { com.dpsmpx.dpai.orchestrator.linux.AlpinePackages.dependency(it)?.name }.toSet()
        val missing = listOf("alpine-baselayout", "apk-tools", "busybox").filter { it !in names }
        return if (missing.isEmpty()) null else "в снимке нет основы Alpine (${missing.joinToString()}) — откат к нему опасен"
    }

    /** Текст окна «Откатить?»: что вернётся наверняка, что — если зеркало даст, что уйдёт. */
    fun planText(p: Plan): String = buildString {
        if (p.empty) { append("Окружение уже такое, как в снимке."); return@buildString }
        p.unsafe?.let { append("Откатить нельзя: $it."); return@buildString }
        if (p.repositories) appendLine("• Репозитории — как в снимке.")
        if (p.vars) appendLine("• Свои переменные — как в снимке.")
        if (p.worldAdd.isNotEmpty() || p.worldRemove.isNotEmpty()) {
            appendLine("• Ваши пакеты: " + (p.worldAdd.map { "+$it" } + p.worldRemove.map { "−$it" }).take(MAX_LISTED).joinToString(" "))
        }
        if (p.extra.isNotEmpty()) appendLine("• Уйдут, если не нужны другим: ${p.extra.take(MAX_LISTED).joinToString()}" + more(p.extra.size))
        val cached = p.versions.filter { it.cached != null }
        val mirror = p.versions.filter { it.cached == null }
        if (cached.isNotEmpty()) appendLine("• Версии из кеша (вернутся): ${cached.take(MAX_LISTED).joinToString { "${it.name} ${it.want}" }}" + more(cached.size))
        if (mirror.isNotEmpty()) {
            appendLine("• Только если зеркало ещё даёт эту версию (файла в кеше нет): ${mirror.take(MAX_LISTED).joinToString { "${it.name} ${it.want}" }}" + more(mirror.size))
        }
        append("Перед откатом — снимок нынешнего окружения: откат тоже можно отменить.")
    }

    /** Чем итог отличается от снимка — после отката; пусто — вернулось всё. */
    fun leftover(s: Snapshot, inv: AlpinePackages.Inventory, vars: List<OwnVariables.Var>): List<String> {
        val now = inv.packages.associate { it.name to it.version }
        return buildList {
            if (s.repositories != inv.repositories) add("репозитории не те")
            if (s.vars != vars) add("свои переменные не те")
            for ((n, v) in s.packages) {
                when (val got = now[n]) {
                    null -> add("$n $v — не стоит")
                    v -> Unit
                    else -> add("$n — $got вместо $v")
                }
            }
            for (n in now.keys) if (n !in s.packages) add("$n — лишний")
        }
    }

    /** Какие снимки оставить (новые первыми, [KEEP]) и какие убрать. */
    fun prune(all: List<Snapshot>): Pair<List<Snapshot>, List<Snapshot>> {
        val sorted = all.sortedByDescending { it.at }
        return sorted.take(KEEP) to sorted.drop(KEEP)
    }

    /** Файлы кеша, которые не нужны ни одному оставленному снимку и ни нынешним версиям. */
    fun prunableCache(keep: List<Snapshot>, inv: AlpinePackages.Inventory?, cache: List<CacheFile>): List<CacheFile> {
        val needed = HashSet<Pair<String, String>>()
        for (s in keep) for ((n, v) in s.packages) needed += n to v
        inv?.packages?.forEach { needed += it.name to it.version }
        return cache.filter { (it.name to it.version) !in needed }
    }

    fun encode(s: Snapshot): String = CanonicalJson.write(
        JsonObject.of(
            "format" to JsonString(FORMAT), "version" to JsonNumber.of(VERSION),
            "id" to JsonString(s.id), "at" to JsonNumber.of(s.at), "label" to JsonString(s.label),
            "release" to (s.release?.let(::JsonString) ?: com.dpsmpx.dpai.core.serialization.JsonNull),
            "repositories" to JsonArray(s.repositories.map(::JsonString)),
            "world" to JsonArray(s.world.map(::JsonString)),
            "packages" to JsonObject(s.packages.mapValues { JsonString(it.value) as JsonValue }),
            "vars" to JsonArray(s.vars.map { JsonObject.of("name" to JsonString(it.name), "value" to JsonString(it.value)) }),
        ),
    )

    fun decode(text: String): Outcome<Snapshot> {
        val o = (JsonParser().parse(text) as? Outcome.Success)?.value as? JsonObject ?: return Outcome.failure(DiagCode.VALUE_INVALID, "снимок окружения не разобран")
        if ((o["format"] as? JsonString)?.value != FORMAT) return Outcome.failure(DiagCode.FORMAT_MISMATCH, "снимок другого формата")
        if ((o["version"] as? JsonNumber)?.toLongExact() != VERSION.toLong()) return Outcome.failure(DiagCode.FORMAT_VERSION_UNSUPPORTED, "версия снимка не поддерживается")
        fun str(k: String) = (o[k] as? JsonString)?.value
        fun list(k: String) = (o[k] as? JsonArray)?.items?.mapNotNull { (it as? JsonString)?.value }.orEmpty()
        val id = str("id") ?: return Outcome.failure(DiagCode.FIELD_MISSING, "у снимка нет id")
        val at = (o["at"] as? JsonNumber)?.toLongExact() ?: return Outcome.failure(DiagCode.FIELD_MISSING, "у снимка нет времени")
        val packages = (o["packages"] as? JsonObject)?.fields?.mapNotNull { (k, v) -> (v as? JsonString)?.value?.let { k to it } }?.toMap()?.toSortedMap() ?: sortedMapOf()
        val vars = (o["vars"] as? JsonArray)?.items?.mapNotNull { item ->
            val v = item as? JsonObject ?: return@mapNotNull null
            val n = (v["name"] as? JsonString)?.value ?: return@mapNotNull null
            val value = (v["value"] as? JsonString)?.value ?: return@mapNotNull null
            OwnVariables.Var(n, value)
        }.orEmpty()
        return Outcome.success(Snapshot(id, at, str("label").orEmpty(), str("release"), list("repositories"), list("world"), packages, vars))
    }

    private fun more(n: Int): String = if (n > MAX_LISTED) " и ещё ${n - MAX_LISTED}" else ""

    private const val MAX_LISTED = 12
    private const val MAX_LABEL = 120
}
