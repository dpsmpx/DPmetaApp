package com.dpsmpx.dpai.files

import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.result.Outcome

/** Файл после прошлой синхронизации: содержимое и сведения о нём с обеих сторон. */
data class BaseEntry(val sha256: String, val remote: FileMeta, val local: FileMeta)

/**
 * Что сделать с файлом (D-99). Папка автора — «remote», копия в Linux —
 * «local».
 */
enum class SyncAction {
    /** Из папки — в копию: новый или изменён в папке. */
    TO_LOCAL,

    /** Из копии — в папку: новый или изменён в копии. */
    TO_REMOTE,

    /** Удалён в папке — удалить в копии. */
    DELETE_LOCAL,

    /** Удалён в копии — удалить в папке: только с отметкой человека. */
    DELETE_REMOTE,

    /** Есть с обеих сторон и совпадает — запомнить как общий. */
    ADOPT,

    /** Нет ни с одной стороны — забыть. */
    FORGET,

    /** Менялся с обеих сторон по-разному или удалён с одной и изменён с другой — решает человек. */
    CONFLICT,

    /** Больше предела — не переносится. */
    TOO_LARGE,
}

data class SyncItem(
    val path: String,
    val action: SyncAction,
    val remote: FileMeta?,
    val local: FileMeta?,
    /** Хеш общего содержимого — у [SyncAction.ADOPT]. */
    val sha256: String? = null,
) {
    /** Действие, которое что-то меняет у автора или в копии (для экрана: «нечего делать»). */
    val visible: Boolean get() = action != SyncAction.ADOPT && action != SyncAction.FORGET
}

enum class Side { REMOTE, LOCAL }

/** Решение человека о конфликте: оставить версию папки или копии. */
enum class Resolution { KEEP_REMOTE, KEEP_LOCAL }

data class SyncOptions(val deleteInFolder: Boolean = false, val resolutions: Map<String, Resolution> = emptyMap())

data class SyncReport(
    val base: Map<String, BaseEntry>,
    val done: Map<SyncAction, Int>,
    /** Не сделано: конфликты без решения, удаления в папке без отметки, слишком большие. */
    val skipped: List<SyncItem>,
    val errors: List<Diagnostic>,
)

/**
 * Двусторонняя синхронизация папки автора и её копии в Linux (D-99).
 *
 * План строится по размеру и времени изменения относительно базы —
 * сведений после прошлой синхронизации; содержимое читается, только если
 * файл менялся с обеих сторон или появился с обеих сторон без базы. Ничего
 * не затирается молча (D-05): изменённое с обеих сторон по-разному —
 * конфликт, удаление в папке автора — только с отметкой.
 */
object FolderSync {
    const val MAX_FILE_BYTES: Long = 32L * 1024 * 1024
    const val MAX_FILES = 10_000

    /**
     * [hash] — SHA-256 файла стороны или `null`, если не прочитан
     * (тогда — конфликт: без содержимого совпадение не доказать).
     */
    fun plan(
        remote: Map<String, FileMeta>,
        local: Map<String, FileMeta>,
        base: Map<String, BaseEntry>,
        maxBytes: Long = MAX_FILE_BYTES,
        hash: (Side, String) -> String?,
    ): List<SyncItem> = (remote.keys + local.keys + base.keys).toSortedSet().map { p ->
        val r = remote[p]
        val l = local[p]
        val b = base[p]
        fun item(a: SyncAction, sha: String? = null) = SyncItem(p, a, r, l, sha)
        fun copy(a: SyncAction): SyncItem {
            val size = (if (a == SyncAction.TO_LOCAL) r else l)?.size ?: 0
            return if (size > maxBytes) item(SyncAction.TOO_LARGE) else item(a)
        }
        fun compare(): SyncItem {
            val hr = hash(Side.REMOTE, p)
            val hl = hash(Side.LOCAL, p)
            return if (hr != null && hr == hl) item(SyncAction.ADOPT, hr) else item(SyncAction.CONFLICT)
        }
        val rChanged = r != null && (b == null || r != b.remote)
        val lChanged = l != null && (b == null || l != b.local)
        when {
            r == null && l == null -> item(SyncAction.FORGET)
            b == null && l == null -> copy(SyncAction.TO_LOCAL)
            b == null && r == null -> copy(SyncAction.TO_REMOTE)
            b == null -> if ((r?.size ?: 0) > maxBytes || (l?.size ?: 0) > maxBytes) item(SyncAction.TOO_LARGE) else compare()
            !rChanged && !lChanged && r != null && l != null -> item(SyncAction.ADOPT, b.sha256)
            r == null -> if (lChanged) item(SyncAction.CONFLICT) else item(SyncAction.DELETE_LOCAL)
            l == null -> if (rChanged) item(SyncAction.CONFLICT) else item(SyncAction.DELETE_REMOTE)
            rChanged && lChanged -> if (r.size > maxBytes || l.size > maxBytes) item(SyncAction.TOO_LARGE) else compare()
            rChanged -> copy(SyncAction.TO_LOCAL)
            else -> copy(SyncAction.TO_REMOTE)
        }
    }

    /** Выполнить [plan]: что не вышло — в [SyncReport.errors], база для этого файла не меняется. */
    fun apply(plan: List<SyncItem>, remote: FileTree, local: FileTree, base: Map<String, BaseEntry>, options: SyncOptions = SyncOptions()): SyncReport {
        val next = base.toMutableMap()
        val done = mutableMapOf<SyncAction, Int>()
        val skipped = mutableListOf<SyncItem>()
        val errors = mutableListOf<Diagnostic>()
        fun ok(a: SyncAction) {
            done[a] = (done[a] ?: 0) + 1
        }
        fun <T> run(o: Outcome<T>, onSuccess: (T) -> Unit) {
            when (o) {
                is Outcome.Success -> onSuccess(o.value)
                is Outcome.Failure -> errors += o.errors.ifEmpty { o.diagnostics }
            }
        }
        fun toLocal(item: SyncItem, counted: SyncAction) = run(remote.read(item.path, MAX_FILE_BYTES)) { bytes ->
            run(local.write(item.path, bytes)) { lm ->
                next[item.path] = BaseEntry(Hashing.sha256(bytes).hex, item.remote ?: lm, lm)
                ok(counted)
            }
        }
        fun toRemote(item: SyncItem, counted: SyncAction) = run(local.read(item.path, MAX_FILE_BYTES)) { bytes ->
            run(remote.write(item.path, bytes)) { rm ->
                next[item.path] = BaseEntry(Hashing.sha256(bytes).hex, rm, item.local ?: rm)
                ok(counted)
            }
        }
        fun metadata(tree: FileTree, path: String): Outcome<FileMeta?> {
            var dir = ""
            for (part in path.split('/').dropLast(1)) {
                val entries = when (val r = tree.list(dir)) {
                    is Outcome.Failure -> return r
                    is Outcome.Success -> r.value
                }
                val child = entries.firstOrNull { it.name == part } ?: return Outcome.success(null)
                if (!child.dir) return Outcome.failure(DiagCode.STATE_CONFLICT, "на месте каталога появился файл", path)
                dir = FileNames.join(dir, part)
            }
            return when (val r = tree.list(dir)) {
                is Outcome.Failure -> r
                is Outcome.Success -> {
                    val current = r.value.firstOrNull { it.name == FileNames.name(path) }
                    if (current?.dir == true) Outcome.failure(DiagCode.STATE_CONFLICT, "на месте файла появился каталог", path)
                    else Outcome.success(current?.meta)
                }
            }
        }
        fun unchanged(tree: FileTree, path: String, expected: FileMeta?): Boolean = when (val r = metadata(tree, path)) {
            is Outcome.Failure -> {
                errors += r.diagnostics
                false
            }
            is Outcome.Success -> {
                if (r.value != expected) {
                    errors += Diagnostic.error(DiagCode.STATE_CONFLICT, "файл изменился после составления плана; нужна повторная сверка", path)
                    false
                } else {
                    true
                }
            }
        }
        for (item in plan) {
            // План мог ждать ответа человека (C-18). Проверяем обе версии непосредственно
            // перед действием, включая применение выбранного разрешения конфликта.
            if (item.action != SyncAction.TOO_LARGE &&
                (!unchanged(remote, item.path, item.remote) || !unchanged(local, item.path, item.local))) {
                skipped += item.copy(action = SyncAction.CONFLICT)
                continue
            }
            when (item.action) {
                SyncAction.TO_LOCAL -> toLocal(item, SyncAction.TO_LOCAL)
                SyncAction.TO_REMOTE -> toRemote(item, SyncAction.TO_REMOTE)
                SyncAction.DELETE_LOCAL -> run(local.delete(item.path)) {
                    next.remove(item.path)
                    ok(SyncAction.DELETE_LOCAL)
                }
                SyncAction.DELETE_REMOTE -> if (!options.deleteInFolder) {
                    skipped += item
                } else {
                    run(remote.delete(item.path)) {
                        next.remove(item.path)
                        ok(SyncAction.DELETE_REMOTE)
                    }
                }
                SyncAction.ADOPT -> {
                    next[item.path] = BaseEntry(item.sha256 ?: error("у ADOPT нет хеша"), item.remote!!, item.local!!)
                }
                SyncAction.FORGET -> next.remove(item.path)
                SyncAction.TOO_LARGE -> skipped += item
                SyncAction.CONFLICT -> when (options.resolutions[item.path]) {
                    null -> skipped += item
                    // Решение человека по этому файлу — и удаление тоже: он видел, что выбирает.
                    Resolution.KEEP_REMOTE -> if (item.remote != null) {
                        toLocal(item, SyncAction.CONFLICT)
                    } else {
                        run(local.delete(item.path)) {
                            next.remove(item.path)
                            ok(SyncAction.CONFLICT)
                        }
                    }
                    Resolution.KEEP_LOCAL -> if (item.local != null) {
                        toRemote(item, SyncAction.CONFLICT)
                    } else {
                        run(remote.delete(item.path)) {
                            next.remove(item.path)
                            ok(SyncAction.CONFLICT)
                        }
                    }
                }
            }
        }
        return SyncReport(next, done, skipped, errors)
    }

    /** Хеш файла дерева для [plan]: не прочитан или больше предела — `null`. */
    fun hashOf(tree: FileTree, path: String, maxBytes: Long = MAX_FILE_BYTES): String? =
        tree.read(path, maxBytes).valueOrNull()?.let { Hashing.sha256(it).hex }
}
