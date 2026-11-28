package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpai.core.serialization.json
import com.dpsmpx.dpai.files.FileMeta
import com.dpsmpx.dpai.files.FileNames
import com.dpsmpx.dpai.files.FileTree
import com.dpsmpx.dpai.files.TextFile

/** Ограниченный обзор без исходных секретов, перехода по ссылкам и запуска build-скриптов (D-223). */
internal class ProjectSurvey(private val tree: FileTree, private val cancelled: () -> Boolean, private val progress: (Int) -> Unit = {}) {
    data class Snapshot(val files: Map<String, FileMeta>, val incomplete: List<String>) {
        val hash: String get() = Hashing.sha256(JsonObject(files.mapValues { (_, m) ->
            JsonArray(listOf(JsonNumber.of(m.size), JsonNumber.of(m.modified))) })).hex
    }

    fun scan(): Outcome<Snapshot> {
        val files = sortedMapOf<String, FileMeta>()
        val issues = arrayListOf<String>()
        val dirs = ArrayDeque(listOf(""))
        var visited = 0
        while (dirs.isNotEmpty()) {
            if (cancelled()) return Outcome.failure(DiagCode.CANCELLED_BY_USER, "обзор проекта отменён")
            if (++visited > 1000 || files.size >= 5000) { issues += "scan_limit"; break }
            val dir = dirs.removeFirst()
            val entries = when (val r = tree.list(dir)) {
                is Outcome.Failure -> { issues += "unread_directory"; continue }
                is Outcome.Success -> r.value
            }
            val seen = hashSetOf<String>()
            if (entries.size > 6000) issues += "entries_limit"
            for (e in entries.take(6000).sortedBy { it.name }) {
                if (cancelled()) return Outcome.failure(DiagCode.CANCELLED_BY_USER, "обзор проекта отменён")
                val path = FileNames.join(dir, e.name)
                if (!FileNames.segmentValid(e.name) || !seen.add(e.name)) { issues += "invalid_entry"; continue }
                if (ApiProjectFiles.hidden(path)) continue
                if (e.dir) {
                    if (path.count { it == '/' } >= 24) issues += "depth_limit"
                    else if (dirs.size < 1000) dirs += path else issues += "directory_limit"
                } else if (files.size < 5000) files[path] = e.meta else issues += "file_limit"
            }
            progress(files.size)
        }
        return Outcome.success(Snapshot(files, issues.distinct()))
    }

    fun structure(s: Snapshot): JsonObject {
        val markers = s.files.keys.filter { it.substringAfterLast('/') in BUILD_FILES }
        val modules = markers.map { FileNames.parent(it) }.distinct().sorted()
        val extensions = s.files.keys.groupingBy { it.substringAfterLast('.', "other").lowercase().take(20) }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(30)
        return JsonObject.of("files" to json(s.files.size), "modules" to strings(modules.take(80)),
            "buildFiles" to strings(markers.take(80)), "languages" to JsonObject(extensions.associate { it.key to json(it.value) }),
            "moduleDiscovery" to json("BUILD_FILE_DIRECTORIES"), "metadataHash" to json(s.hash),
            "contentSnapshot" to json(false), "truncated" to json(markers.size > 80 || modules.size > 80),
            "incompleteReasons" to strings(s.incomplete))
    }

    fun relevant(s: Snapshot, query: String): JsonObject {
        val words = query.lowercase().split(Regex("[^\\p{L}\\p{N}_]+" )).filter { it.length >= 2 }.distinct().take(20)
        val ranked = s.files.keys.mapNotNull { path ->
            val lower = path.lowercase()
            val name = lower.substringAfterLast('/')
            val score = words.sumOf { if (it == name.substringBeforeLast('.')) 8 else if (it in name) 4 else if (it in lower) 1 else 0 }
            if (score > 0) path to score else null
        }.sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
        return JsonObject.of("basis" to json("LEXICAL_PATH_RANKING"), "semantic" to json(false),
            "matches" to JsonArray(ranked.take(40).map { (path, score) -> JsonObject.of("path" to json(path), "score" to json(score)) }),
            "truncated" to json(ranked.size > 40), "metadataHash" to json(s.hash), "incompleteReasons" to strings(s.incomplete))
    }

    /** Только буквальные Gradle project(...): это нижняя граница объявленного графа, не интерпретатор DSL. */
    fun impact(s: Snapshot, changed: List<String>): Outcome<JsonObject> {
        val buildFiles = s.files.keys.filter { it.substringAfterLast('/') in BUILD_FILES }
        val modules = (buildFiles.map { FileNames.parent(it) } + "").distinct().sorted()
        fun module(path: String) = modules.filter { it.isEmpty() || path.startsWith("$it/") }.maxBy { it.length }
        val edges = linkedSetOf<Pair<String, String>>()
        val inputs = arrayListOf<JsonValue>()
        var bytes = 0
        var unread = 0
        for (file in buildFiles.take(200)) {
            if (cancelled()) return Outcome.failure(DiagCode.CANCELLED_BY_USER, "анализ влияния отменён")
            if (s.files.getValue(file).size > 256 * 1024 || bytes >= 2 * 1024 * 1024) { unread++; continue }
            val raw = tree.read(file, 256 * 1024).valueOrNull()
            val text = raw?.let { TextFile.decode(it).valueOrNull() }
            if (text == null) { unread++; continue }
            bytes += raw.size
            inputs += JsonObject.of("path" to json(file), "sha256" to json(Hashing.sha256(raw).hex))
            PROJECT.findAll(text).take(500).forEach { m ->
                val target = m.groupValues[1].trim(':').replace(':', '/')
                if (target in modules && edges.size < 1000) edges += module(file) to target
            }
        }
        val direct = changed.map(::module).toSet()
        val affected = (if ("" in direct) modules.toSet() else direct).toMutableSet()
        do { val before = affected.size; edges.filter { it.second in affected }.forEach { affected += it.first } } while (affected.size != before)
        val tests = s.files.keys.filter { isTest(it) && module(it) in affected }
        val adjacency = edges.groupBy({ it.first }, { it.second })
        val reach = hashMapOf<String, Set<String>>()
        val cycles = edges.filter { (a, b) -> a in reach.getOrPut(b) {
            val seen = hashSetOf<String>(); val queue = ArrayDeque(listOf(b))
            while (queue.isNotEmpty()) { val at = queue.removeFirst(); if (seen.add(at)) adjacency[at].orEmpty().forEach { queue += it } }
            seen
        } }
        return Outcome.success(JsonObject.of("basis" to json("TEXTUAL_GRADLE_REFERENCES"),
            "complete" to json(false), "semantic" to json(false), "changed" to strings(changed.take(80)),
            "directModules" to strings(direct.sorted().take(80)), "potentiallyAffectedModules" to strings(affected.sorted().take(80)),
            "testCandidates" to strings(tests.take(60)), "testSelectionProven" to json(false), "fullVerificationRequired" to json(true),
            "dependencies" to JsonArray(edges.take(80).map { (a,b) -> JsonObject.of("from" to json(a), "to" to json(b)) }),
            "potentialCycleEdges" to JsonArray(cycles.take(40).map { (a,b) -> JsonObject.of("from" to json(a), "to" to json(b)) }),
            "unreadBuildFiles" to json(unread + (buildFiles.size - 200).coerceAtLeast(0)),
            "inputs" to JsonArray(inputs.take(80)), "metadataHash" to json(s.hash), "incompleteReasons" to strings(s.incomplete),
            "truncated" to json(changed.size > 80 || affected.size > 80 || tests.size > 60 || edges.size > 80 || cycles.size > 40 || inputs.size > 80),
            "note" to json("Кандидаты проверок по размещению и буквальным зависимостям; динамические DSL и неявные связи требуют полного профиля.")))
    }

    fun duplicates(s: Snapshot): Outcome<JsonObject> {
        val byHash = linkedMapOf<String, MutableList<String>>()
        var readBytes = 0; var skipped = 0
        for ((path, meta) in s.files) {
            if (cancelled()) return Outcome.failure(DiagCode.CANCELLED_BY_USER, "поиск повторов отменён")
            if (meta.size < 32 || meta.size > 128 * 1024 || readBytes + meta.size > 4 * 1024 * 1024) { skipped++; continue }
            val raw = tree.read(path, 128 * 1024).valueOrNull()
            if (raw == null || TextFile.decode(raw).valueOrNull() == null) { skipped++; continue }
            readBytes += raw.size
            byHash.getOrPut(Hashing.sha256(raw).hex) { arrayListOf() } += path
        }
        val groups = byHash.filter { it.value.size > 1 }
        return Outcome.success(JsonObject.of("basis" to json("EXACT_FILE_BYTES"), "semantic" to json(false),
            "groups" to JsonArray(groups.entries.take(20).map { (h, paths) -> JsonObject.of("sha256" to json(h), "paths" to strings(paths.take(10))) }),
            "skippedFiles" to json(skipped), "truncated" to json(groups.size > 20 || groups.values.any { it.size > 10 }),
            "incompleteReasons" to strings(s.incomplete), "note" to json("Совпадение файлов не доказывает, что компонент не используется или может быть удалён.")))
    }

    companion object {
        private val BUILD_FILES = setOf("build.gradle", "build.gradle.kts", "pom.xml", "CMakeLists.txt", "Cargo.toml", "go.mod", "package.json", "pyproject.toml", "meson.build", "Makefile")
        private val PROJECT = Regex("project\\s*\\(\\s*(?:path\\s*[=:]\\s*)?[\"'](:[A-Za-z0-9_:.-]+)[\"']")
        fun strings(values: Iterable<String>) = JsonArray(values.map(::JsonString))
        private fun isTest(path: String) = path.split('/').any { it.lowercase() in setOf("test", "tests", "androidtest", "commontest") } ||
            path.substringAfterLast('/').let { it.startsWith("test_") || it.substringBeforeLast('.').endsWith("Test") }
    }
}
