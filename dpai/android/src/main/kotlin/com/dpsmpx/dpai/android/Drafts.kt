package com.dpsmpx.dpai.android

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.files.TextFile
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.ui.EditorDraftStore
import com.dpsmpx.dpai.ui.SavedFileDraft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Черновики чата — RAM; файлов — приватные восстановимые копии с D-135. */
object Drafts {
    data class Checkpoint(val current: Long = 0, val saved: Long = -1, val error: String? = null)
    data class Recovery(val ready: Boolean = true, val note: String = "", val unreadable: Int = 0, val checkpoints: Map<String, Checkpoint> = emptyMap())
    private val flow = MutableStateFlow(Recovery())
    val recovery: StateFlow<Recovery> = flow
    private val statusLock = Any()
    private fun status(change: (Recovery) -> Recovery) = synchronized(statusLock) { flow.value = change(flow.value) }
    private val io = ReentrantLock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scheduled = AtomicBoolean()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val blocked = ConcurrentHashMap.newKeySet<String>()
    private val map = ConcurrentHashMap<String, String>()
    private val files = ConcurrentHashMap<String, FileDraft>()
    @Volatile private var store: EditorDraftStore? = null
    private var installed = false

    class FileDraft(@Volatile var original: ByteArray, val editor: com.dpsmpx.dpai.ui.CodeEditorBuffer, val guestPath: String? = null, val restored: Boolean = false)

    /** До открытия экранов пометить загрузку; сам диск и Keystore — в IO. */
    @Synchronized
    fun install(context: android.content.Context) {
        if (installed) return
        installed = true
        status { it.copy(ready = false, note = "Восстановление черновиков…") }
        val app = context.applicationContext
        scope.launch {
            io.withLock {
                val vault = EditorDraftStore(NioVfs(File(app.noBackupFilesDir, "editor-drafts").toPath()), DraftEncryption)
                store = vault
                try {
                    when (val r = vault.restore()) {
                        is Outcome.Failure -> status { it.copy(ready = false, note = "Черновики не прочитаны; копии оставлены на месте") }
                        is Outcome.Success -> {
                            blocked += r.value.unreadable
                            var count = 0
                            for (saved in r.value.drafts) {
                                val base = TextFile.decode(saved.original).valueOrNull()
                                if (base == null) { blocked += EditorDraftStore.recordId(saved.key); continue }
                                val editor = com.dpsmpx.dpai.ui.CodeEditorBuffer(base)
                                if (!editor.edit(saved.text, saved.selection)) { blocked += EditorDraftStore.recordId(saved.key); continue }
                                files[saved.key] = FileDraft(saved.original, editor, saved.guestPath, restored = true)
                                status { it.copy(checkpoints = it.checkpoints + (saved.key to Checkpoint(0, 0))) }
                                count++
                            }
                            val warnings = buildList {
                                if (count > 0) add("Восстановлено черновиков: $count")
                                if (r.value.previous.isNotEmpty()) add("Часть копий восстановлена из прежней целой версии")
                                if (blocked.isNotEmpty()) add("Не прочитано копий: ${blocked.size}; они оставлены на месте")
                            }
                            status { it.copy(ready = true, note = warnings.joinToString(". "), unreadable = blocked.size) }
                        }
                    }
                } catch (_: Exception) {
                    status { it.copy(ready = false, note = "Восстановление черновиков не завершено; копии оставлены на месте") }
                }
            }
        }
    }

    fun file(key: String): FileDraft? = files[key]
    fun fileDrafts(): List<Pair<String, FileDraft>> = files.entries.filter { it.value.editor.dirty }.sortedBy { it.key }.map { it.key to it.value }

    @Synchronized
    fun startFile(key: String, original: ByteArray, text: String, guestPath: String? = null): FileDraft? {
        files[key]?.let { return it }
        if (!flow.value.ready || EditorDraftStore.recordId(key) in blocked || files.size >= EditorDraftStore.MAX_OPEN) return null
        return FileDraft(original, com.dpsmpx.dpai.ui.CodeEditorBuffer(text), guestPath).also { files[key] = it }
    }

    fun startFailure(key: String): String = when {
        !flow.value.ready -> "Сначала дождитесь восстановления черновиков; при ошибке перезапустите приложение"
        EditorDraftStore.recordId(key) in blocked -> "Копия этого черновика не прочитана и не будет перезаписана"
        else -> "Открыто восемь буферов: закройте одну вкладку (правки можно сохранить)"
    }

    fun changed(key: String) {
        if (store == null || files[key] == null) return
        status { state ->
            val before = state.checkpoints[key] ?: Checkpoint()
            state.copy(checkpoints = state.checkpoints + (key to before.copy(current = before.current + 1, error = null)))
        }
        pending += key
        schedule()
    }

    private fun schedule() {
        if (!scheduled.compareAndSet(false, true)) return
        scope.launch {
            try {
                do { delay(500); flush() } while (pending.isNotEmpty())
            } finally {
                scheduled.set(false)
                if (pending.isNotEmpty()) schedule()
            }
        }
    }

    /** Уход окна не ждёт диска в UI, но просит немедленно зафиксировать текущие снимки. */
    fun flushNow() {
        if (store == null || !flow.value.ready) return
        pending += files.keys
        scope.launch { flush() }
    }

    private fun flush() = io.withLock {
        val vault = store ?: return@withLock
        for (key in pending.toList()) {
            pending.remove(key)
            val draft = files[key] ?: continue
            val generation = flow.value.checkpoints[key]?.current ?: 0
            val snapshot = draft.editor.state
            val result = try {
                if (snapshot.text == draft.editor.original) vault.remove(key)
                else vault.save(SavedFileDraft(key, draft.original, snapshot.text, snapshot.selection, draft.guestPath))
            } catch (_: Exception) { Outcome.failure(DiagCode.STORAGE_IO_FAILED, "копия черновика не записана") }
            status { state ->
                val current = state.checkpoints[key] ?: Checkpoint()
                val next = if (result is Outcome.Success) current.copy(saved = generation, error = null)
                    else current.copy(error = "Копия не записана; последние правки пока только в памяти")
                state.copy(checkpoints = state.checkpoints + (key to next))
            }
        }
    }

    /** Исходник уже записан: обновить базу, сохранив поле и undo; копия удаляется под общим IO-замком. */
    fun savedFile(key: String, expected: FileDraft, bytes: ByteArray, text: String): Outcome<Unit> = io.withLock {
        if (files[key] !== expected) return@withLock Outcome.failure(DiagCode.STATE_CONFLICT, "файл записан, но черновик сменился")
        expected.original = bytes
        expected.editor.markSaved(text)
        val result = store?.remove(key) ?: Outcome.success(Unit)
        if (expected.editor.dirty) { pending += key; schedule() }
        status { state ->
            val current = state.checkpoints[key] ?: Checkpoint()
            state.copy(checkpoints = state.checkpoints + (key to current.copy(saved = current.current,
                error = if (result is Outcome.Failure) "Файл сохранён, но копия не удалена" else null)))
        }
        result
    }

    /** Вызывать вне UI. Удаление подтверждается только после удаления всех версий копии. */
    fun dropFile(key: String, expected: FileDraft? = files[key]): Outcome<Unit> {
        if (!flow.value.ready) return Outcome.failure(DiagCode.STATE_CONFLICT, "сначала дождитесь восстановления черновиков")
        return io.withLock {
        if (files[key] !== expected) return@withLock Outcome.failure(DiagCode.STATE_CONFLICT, "черновик изменился; откройте его заново")
        val generation = flow.value.checkpoints[key]?.current
        val result = store?.remove(key) ?: Outcome.success(Unit)
        if (result is Outcome.Failure) {
            status { state -> state.copy(checkpoints = state.checkpoints + (key to (state.checkpoints[key] ?: Checkpoint()).copy(error = "Копия не удалена; черновик оставлен"))) }
            return@withLock result
        }
        if (generation != flow.value.checkpoints[key]?.current) {
            pending += key; schedule()
            return@withLock Outcome.failure(DiagCode.STATE_CONFLICT, "появились новые правки; черновик оставлен")
        }
        pending.remove(key)
        files.remove(key)
        status { it.copy(checkpoints = it.checkpoints - key) }
        Outcome.success(Unit)
        }
    }

    /** Явная кнопка после подтверждения: читаемые черновики и исходники не затрагиваются. */
    fun discardUnavailable(): Outcome<Unit> = io.withLock {
        val vault = store ?: return@withLock Outcome.success(Unit)
        for (record in blocked.toList()) {
            val result = vault.removeUnavailable(record)
            if (result is Outcome.Failure) {
                status { it.copy(unreadable = blocked.size, note = "Не все недоступные копии удалены") }
                return@withLock result
            }
            blocked.remove(record)
        }
        status { it.copy(unreadable = 0, note = "Недоступные копии удалены; сохранённые файлы не изменены") }
        Outcome.success(Unit)
    }

    fun checkpoint(key: String): String {
        if (!flow.value.ready) return flow.value.note
        if (files[key]?.editor?.dirty != true) return "Нет несохранённых изменений"
        if (store == null) return "Черновик в памяти"
        val check = flow.value.checkpoints[key] ?: return "Исходный файл меняется только по «Сохранить»"
        return check.error ?: if (check.saved < check.current) "Запись восстановимой копии…" else "Восстановимая копия записана"
    }

    fun dirtyIn(location: com.dpsmpx.dpai.ui.WorkspaceLocation, inApp: Boolean): Boolean = files.any { (key, draft) ->
        if (!draft.editor.dirty) false else {
            val path = draft.guestPath
            key.startsWith("file:workspace:${location.key}\u0001") || (inApp && path != null && path.startsWith(location.path.trimEnd('/') + "/"))
        }
    }

    /** Окно подтверждения блокирует ввод; чистые вкладки получают записанные файлы и новую базу. */
    fun reloadWorkspace(location: com.dpsmpx.dpai.ui.WorkspaceLocation, tree: com.dpsmpx.dpai.files.FileTree, inApp: Boolean): Outcome<Unit> {
        val prefix = "file:workspace:${location.key}\u0001"
        val absolute = location.path.trimEnd('/') + "/"
        for ((key, draft) in files.toMap()) {
            val path = when {
                key.startsWith(prefix) -> key.removePrefix(prefix)
                inApp && draft.guestPath?.startsWith(absolute) == true -> draft.guestPath.removePrefix(absolute)
                else -> continue
            }
            if (draft.editor.dirty) continue
            val bytes = when (val r = tree.read(path, TextFile.MAX_EDIT_BYTES)) {
                is Outcome.Failure -> return r
                is Outcome.Success -> r.value
            }
            val text = when (val r = TextFile.decode(bytes)) {
                is Outcome.Failure -> return r
                is Outcome.Success -> r.value
            }
            if (files[key] !== draft || draft.editor.dirty) continue
            draft.editor.edit(text, draft.editor.state.selection)
            val saved = savedFile(key, draft, bytes, text)
            if (saved is Outcome.Failure) return saved
        }
        return Outcome.success(Unit)
    }

    fun get(key: String): String? = map[key]
    fun set(key: String, text: String?) { if (text.isNullOrEmpty()) map.remove(key) else map[key] = text }
    fun chat(session: String): String = "chat:$session"
}
