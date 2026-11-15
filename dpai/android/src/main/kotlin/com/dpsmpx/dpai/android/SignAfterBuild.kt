package com.dpsmpx.dpai.android

import com.dpsmpx.dpai.files.TreeArtifacts

/**
 * Какие APK подписать после сборки (D-178): APK дерева не старше начала сборки
 * [startedAt] (мс; неизвестно — `null`, тогда ничего: старые файлы без спроса не
 * подписываются), кроме самих `*-signed.apk` и тех, рядом с которыми уже лежит
 * `<имя>-signed.apk` не старше исходного. Повторный показ экрана поэтому не просит
 * подписать то же снова. Без Android: проверяется на хосте.
 */
internal object SignAfterBuild {
    /** Не больше — APK за одну сборку (одно подтверждение входа). */
    const val MAX_APKS = 10

    fun candidates(items: List<TreeArtifacts.Artifact>, startedAt: Long?): List<TreeArtifacts.Artifact> {
        if (startedAt == null) return emptyList()
        val byPath = items.associateBy { it.path }
        return items.filter { a ->
            a.path.endsWith(".apk", ignoreCase = true) && !a.path.endsWith("-signed.apk", ignoreCase = true) && a.modified >= startedAt &&
                byPath[signedPath(a.path)].let { s -> s == null || s.modified < a.modified }
        }.sortedBy { it.path }.take(MAX_APKS)
    }

    /** Путь подписанной копии рядом с исходным: `out/app-release.apk` → `out/app-release-signed.apk`. */
    fun signedPath(path: String): String {
        val dir = path.substringBeforeLast('/', "")
        val name = ApkSign.signedName(path.substringAfterLast('/'))
        return if (dir.isEmpty()) name else "$dir/$name"
    }

    /** Строка предупреждения, когда ключа нет ни у проекта, ни глобального (D-146). */
    const val NO_KEY = "Ключ подписи не задан ни для проекта, ни глобальный: APK остаётся таким, как собран (debug-подпись или без подписи — без подписи Android его не установит)."
}
