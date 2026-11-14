package com.dpsmpx.dpai.lsp

/**
 * Владение сеансами открытыми вкладками (D-177). Короткие операции только
 * с памятью: закрытие процессов выполняет вызывающий вне этого монитора.
 * Токен отличает новый интерфейс того же дерева от отложенного ухода старого.
 */
class EditorSessions<T> {
    private class Scope<T> {
        var owner: Any? = null
        var root: String? = null
        var paths: Set<String> = emptySet()
        val sessions = LinkedHashMap<String, T>()
    }

    data class Acquired<T>(val session: T, val retired: List<T>)
    private val scopes = HashMap<String, Scope<T>>()

    @Synchronized
    fun enter(key: String, owner: Any) { scopes.getOrPut(key) { Scope() }.owner = owner }

    @Synchronized
    fun acquire(key: String, root: String, path: String, create: () -> T): Acquired<T> {
        val scope = scopes.getOrPut(key) { Scope() }
        val canonicalRoot = root.trimEnd('/').ifEmpty { "/" }
        val retired = if (scope.root != null && scope.root != canonicalRoot) scope.sessions.values.toList() else emptyList()
        if (scope.root != canonicalRoot) scope.sessions.clear()
        scope.root = canonicalRoot
        // До SideEffect родителя состав вкладок ещё может быть не опубликован.
        scope.paths = scope.paths + path
        return Acquired(scope.sessions.getOrPut(path, create), retired)
    }

    @Synchronized
    fun retain(key: String, owner: Any, paths: Set<String>): List<T> {
        val scope = scopes[key]?.takeIf { it.owner === owner } ?: return emptyList()
        scope.paths = paths.toSet()
        val retired = scope.sessions.filterKeys { it !in paths }.values.toList()
        scope.sessions.keys.retainAll(paths)
        return retired
    }

    @Synchronized
    fun close(key: String, owner: Any): List<T> {
        val scope = scopes[key]?.takeIf { it.owner === owner } ?: return emptyList()
        scopes.remove(key)
        return scope.sessions.values.toList()
    }

    /** Даже ещё не показанная вкладка удерживает уже включённую службу своего языка. */
    @Synchronized
    fun keeps(key: String, projectRoot: String, language: CodeLanguage): Boolean {
        val scope = scopes[key] ?: return false
        val root = scope.root ?: return false
        val project = projectRoot.trimEnd('/').ifEmpty { "/" }
        return scope.paths.any { path ->
            val file = if (root == "/") "/$path" else "$root/$path"
            (project == "/" || file.startsWith("$project/")) && LanguageServers.language(path) == language
        }
    }
}
