package com.dpsmpx.dpai.lsp.jdwp

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.lsp.dap.BreakpointState
import com.dpsmpx.dpai.lsp.dap.DebugStatus
import com.dpsmpx.dpai.lsp.dap.DebugVariable
import com.dpsmpx.dpai.lsp.dap.DebugView
import com.dpsmpx.dpai.lsp.dap.Debuggee
import com.dpsmpx.dpai.lsp.dap.StackFrame
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Исходники проекта для отладки по JDWP (D-161): класс ВМ знает только пакет и
 * имя файла (`SourceFile`), путь — дело проекта.
 */
interface JvmSources {
    /** Пакет файла [path] (строка `package`; без неё — `""`); файл не прочитан — `null`. */
    fun packageOf(path: String): String?

    /** Файл проекта с пакетом [pkg] и именем [file] или `null`. */
    fun find(pkg: String, file: String): String?
}

/**
 * Отладка Kotlin/Java по JDWP (`DPmetaApp:D-126`; D-161): своё приложение на
 * телефоне через `adb forward … jdwp:<pid>` или JVM с `-agentlib:jdwp`. Точка —
 * файл и строка: классы файла — загруженные классы его пакета с тем же
 * `SourceFile`, строка — по таблице строк методов; класс ещё не загружен — точка
 * ждёт `CLASS_PREPARE` по пакету. Стек, переменные кадра и `this`, шаги по строкам
 * (системные пакеты [SKIPPED] пропускаются), пауза, остановка на исключении,
 * которое код приложения не поймал. Выражение — имя переменной кадра: JDWP код
 * не вычисляет. Методы блокируют до ответа ВМ — звать не из потока экрана.
 */
class JdwpSession(
    input: InputStream,
    output: OutputStream,
    private val sources: JvmSources,
    private val onView: (DebugView) -> Unit,
    private val onClosed: (Diagnostic) -> Unit = {},
) : Debuggee {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "dpai-jdwp-session").apply { isDaemon = true } }

    @Volatile
    private var sizes = JdwpSizes()
    private val classes = ConcurrentHashMap<Long, ClassInfo>()

    /** Строки точек по файлам — как их задал человек. */
    private val wanted = ConcurrentHashMap<String, List<Int>>()

    /** Запросы `BREAKPOINT` ВМ по файлам и строкам. */
    private val requests = ConcurrentHashMap<String, MutableMap<Int, MutableList<Int>>>()

    /** Пакеты, для которых уже есть запрос `CLASS_PREPARE`. */
    private val prepared = ConcurrentHashMap.newKeySet<String>()
    private val frameInfo = ConcurrentHashMap<Long, JdwpLocation>()

    @Volatile
    private var stepRequest: Int? = null

    @Volatile
    private var detailField: Pair<Long, Long>? = null

    @Volatile
    override var view: DebugView = DebugView()
        private set

    private class MethodInfo(val id: Long, val name: String) {
        @Volatile
        var lines: List<Pair<Long, Int>>? = null
    }

    private class ClassInfo(val signature: String) {
        @Volatile
        var source: String? = null

        @Volatile
        var sourceRead: Boolean = false

        @Volatile
        var methods: List<MethodInfo>? = null
    }

    private class Slot(val start: Long, val name: String, val signature: String, val length: Int, val slot: Int)

    private val peer = JdwpPeer(input, output, object : JdwpPeer.Handler {
        override fun command(set: Int, cmd: Int, data: ByteArray) {
            if (set == EVENT_SET && cmd == EVENT_COMPOSITE) events(data)
        }

        override fun closed(reason: Diagnostic) {
            val s = view.status
            if (s !is DebugStatus.Exited && s !is DebugStatus.Failed) {
                publish { it.copy(status = if (s is DebugStatus.Starting) DebugStatus.Failed(reason.message) else DebugStatus.Exited(null), frames = emptyList(), frame = null, variables = emptyList()) }
            }
            worker.shutdown()
            onClosed(reason)
        }
    })

    override val isOpen: Boolean get() = peer.isOpen

    /**
     * Подключиться: рукопожатие, размеры идентификаторов, точки [breakpoints]
     * (путь → строки с единицы), остановка на непойманных исключениях, затем ВМ
     * продолжает (`VirtualMachine.Resume`; ВМ, ждущая отладчика, — тоже).
     */
    fun attach(breakpoints: Map<String, List<Int>>, timeout: Duration = START_TIMEOUT): Outcome<Unit> {
        peer.start().let { if (it is Outcome.Failure) return fail(it) }
        when (val r = call(VM, 7, ByteArray(0), timeout)) {
            is Outcome.Failure -> return fail(r)
            is Outcome.Success -> {
                val i = JdwpIn(r.value, sizes)
                sizes = JdwpSizes(fieldId = i.int(), methodId = i.int(), objectId = i.int(), typeId = i.int(), frameId = i.int())
            }
        }
        (call(VM, 1, ByteArray(0)) as? Outcome.Success)?.let { r ->
            val i = JdwpIn(r.value, sizes)
            runCatching {
                i.string(); i.int(); i.int()
                val version = i.string()
                val name = i.string()
                note("ВМ: $name $version")
            }
        }
        loadClasses().let { if (it is Outcome.Failure) return fail(it) }
        for ((path, lines) in breakpoints) setBreakpoints(path, lines)
        // Исключения, брошенные не системным кодом: остановка — если код приложения их не поймал.
        val o = out().byte(EXCEPTION).byte(SUSPEND_ALL).int(1 + SKIPPED.size).byte(MOD_EXCEPTION_ONLY).typeId(0).bool(true).bool(true)
        for (p in SKIPPED) o.byte(MOD_CLASS_EXCLUDE).string(p)
        call(EVENT_REQUEST, 1, o.bytes())
        call(VM, 9, ByteArray(0)).let { if (it is Outcome.Failure) return fail(it) }
        publish { if (it.status is DebugStatus.Starting) it.copy(status = DebugStatus.Running) else it }
        return Outcome.success(Unit)
    }

    private fun loadClasses(): Outcome<Unit> = when (val r = call(VM, 3, ByteArray(0))) {
        is Outcome.Failure -> r
        is Outcome.Success -> try {
            val i = JdwpIn(r.value, sizes)
            repeat(i.int()) {
                i.byte()
                val id = i.typeId()
                val sig = i.string()
                i.int()
                classes.putIfAbsent(id, ClassInfo(sig))
            }
            Outcome.success(Unit)
        } catch (e: IOException) {
            Outcome.failure(DiagCode.JDWP_REPLY_INVALID, "список классов ВМ не разобран: ${e.message}")
        }
    }

    /** Точки файла [path] целиком: прежние снимаются, новые ставятся во все загруженные классы файла, прочие ждут загрузки. */
    override fun setBreakpoints(path: String, lines: List<Int>): Outcome<List<BreakpointState>> {
        val file = path.substringAfterLast('/')
        if (!file.endsWith(".kt") && !file.endsWith(".java")) return Outcome.failure(DiagCode.UNSUPPORTED, "отладка APK — у файлов .kt и .java", path)
        requests.remove(path)?.values?.flatten()?.forEach { call(EVENT_REQUEST, 2, out().byte(BREAKPOINT).int(it).bytes()) }
        val clean = lines.filter { it >= 1 }.distinct().sorted()
        if (clean.isEmpty()) wanted.remove(path) else wanted[path] = clean
        val pkg = sources.packageOf(path)
        if (pkg == null) {
            val states = clean.map { BreakpointState(it, false, "файл не прочитан — пакет неизвестен") }
            publish { it.copy(breakpoints = it.breakpoints + (path to states)) }
            return Outcome.success(states)
        }
        if (clean.isNotEmpty() && prepared.add(pkg)) {
            val pattern = if (pkg.isEmpty()) "*" else "$pkg.*"
            call(EVENT_REQUEST, 1, out().byte(CLASS_PREPARE).byte(SUSPEND_THREAD).int(1).byte(MOD_CLASS_MATCH).string(pattern).bytes())
        }
        val set = ConcurrentHashMap<Int, MutableList<Int>>()
        requests[path] = set
        for ((id, c) in classes.entries.toList()) if (inPackage(c.signature, pkg) && source(id, c) == file) resolve(path, id, set)
        val states = states(path, set)
        publish { it.copy(breakpoints = it.breakpoints + (path to states)) }
        return Outcome.success(states)
    }

    private fun states(path: String, set: Map<Int, List<Int>>): List<BreakpointState> =
        wanted[path].orEmpty().map { line ->
            val ok = set[line].orEmpty().isNotEmpty()
            BreakpointState(line, ok, if (ok) null else "ждёт загрузки класса или в строке нет кода")
        }

    /** Поставить точки файла [path] в класс [id]: начало кода строки в каждом методе, где она есть. */
    private fun resolve(path: String, id: Long, set: MutableMap<Int, MutableList<Int>>) {
        val c = classes[id] ?: return
        val methods = methods(id, c)
        for (line in wanted[path].orEmpty()) {
            for (m in methods) {
                val index = lines(id, m).filter { it.second == line }.minOfOrNull { it.first } ?: continue
                val loc = JdwpLocation(TYPE_CLASS, id, m.id, index)
                val r = call(EVENT_REQUEST, 1, out().byte(BREAKPOINT).byte(SUSPEND_ALL).int(1).byte(MOD_LOCATION_ONLY).location(loc).bytes())
                if (r is Outcome.Success) set.getOrPut(line) { mutableListOf() } += JdwpIn(r.value, sizes).int()
            }
        }
    }

    override fun resume(): Outcome<Unit> {
        if (view.status !is DebugStatus.Stopped) return Outcome.failure(DiagCode.STATE_CONFLICT, "программа не остановлена")
        publish { it.copy(status = DebugStatus.Running, frames = emptyList(), frame = null, variables = emptyList()) }
        return call(VM, 9, ByteArray(0)).unit()
    }

    override fun next(): Outcome<Unit> = step(DEPTH_OVER)

    override fun stepIn(): Outcome<Unit> = step(DEPTH_INTO)

    override fun stepOut(): Outcome<Unit> = step(DEPTH_OUT)

    private fun step(depth: Int): Outcome<Unit> {
        val thread = (view.status as? DebugStatus.Stopped)?.threadId ?: return Outcome.failure(DiagCode.STATE_CONFLICT, "программа не остановлена")
        clearStep()
        val o = out().byte(SINGLE_STEP).byte(SUSPEND_ALL).int(2 + SKIPPED.size)
            .byte(MOD_STEP).objectId(thread).int(STEP_LINE).int(depth)
            .byte(MOD_COUNT).int(1)
        for (p in SKIPPED) o.byte(MOD_CLASS_EXCLUDE).string(p)
        when (val r = call(EVENT_REQUEST, 1, o.bytes())) {
            is Outcome.Failure -> return r
            is Outcome.Success -> stepRequest = JdwpIn(r.value, sizes).int()
        }
        publish { it.copy(status = DebugStatus.Running, frames = emptyList(), frame = null, variables = emptyList()) }
        return call(VM, 9, ByteArray(0)).unit()
    }

    private fun clearStep() {
        stepRequest?.let { call(EVENT_REQUEST, 2, out().byte(SINGLE_STEP).int(it).bytes()) }
        stepRequest = null
    }

    /** Остановить ВМ целиком; показать поток `main` (нет — первый). */
    override fun pause(): Outcome<Unit> {
        if (view.status is DebugStatus.Stopped) return Outcome.success(Unit)
        call(VM, 8, ByteArray(0)).let { if (it is Outcome.Failure) return it }
        val threads = when (val r = call(VM, 4, ByteArray(0))) {
            is Outcome.Failure -> return r
            is Outcome.Success -> JdwpIn(r.value, sizes).let { i -> List(i.int()) { i.objectId() } }
        }
        val main = threads.firstOrNull { t -> (call(THREAD, 1, out().objectId(t).bytes()) as? Outcome.Success)?.let { JdwpIn(it.value, sizes).string() } == "main" }
            ?: threads.firstOrNull()
            ?: return Outcome.failure(DiagCode.STATE_CONFLICT, "в ВМ нет потоков")
        stopped("pause", main, null)
        return Outcome.success(Unit)
    }

    override fun selectFrame(frameId: Long): Outcome<List<DebugVariable>> {
        val thread = (view.status as? DebugStatus.Stopped)?.threadId ?: return Outcome.failure(DiagCode.STATE_CONFLICT, "программа не остановлена")
        val loc = frameInfo[frameId] ?: return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "нет такого кадра")
        val vars = variables(thread, frameId, loc)
        publish { it.copy(frame = frameId, variables = vars) }
        return Outcome.success(vars)
    }

    /** Поля объектов не раскрываются: значение объекта — его класс. */
    override fun children(ref: Long): Outcome<List<DebugVariable>> = Outcome.success(emptyList())

    /** Выражение — имя переменной выбранного кадра или `this`: JDWP код не вычисляет. */
    override fun evaluate(expression: String): Outcome<String> {
        val name = expression.trim()
        view.variables.firstOrNull { it.name == name }?.let { return Outcome.success(it.value) }
        val known = view.variables.joinToString { it.name }.ifEmpty { "переменных нет" }
        return Outcome.failure(DiagCode.UNSUPPORTED, "в отладке Kotlin/Java выражение — имя переменной кадра: $known")
    }

    /** Отключиться (`Dispose`): точки снимаются, программа работает дальше. */
    override fun stop() {
        if (peer.isOpen) call(VM, 6, ByteArray(0), STOP_TIMEOUT)
        peer.close()
        worker.shutdownNow()
    }

    private fun events(data: ByteArray) {
        val i = JdwpIn(data, sizes)
        try {
            i.byte()
            repeat(i.int()) {
                when (val kind = i.byte()) {
                    SINGLE_STEP, BREAKPOINT -> {
                        i.int()
                        val thread = i.objectId()
                        i.location()
                        submit { stopped(if (kind == BREAKPOINT) "breakpoint" else "step", thread, null) }
                    }
                    EXCEPTION -> {
                        i.int()
                        val thread = i.objectId()
                        i.location()
                        i.byte()
                        val exception = i.objectId()
                        val caught = i.location()
                        submit { exception(thread, exception, caught) }
                    }
                    CLASS_PREPARE -> {
                        i.int()
                        val thread = i.objectId()
                        i.byte()
                        val id = i.typeId()
                        val sig = i.string()
                        i.int()
                        classes.putIfAbsent(id, ClassInfo(sig))
                        submit { prepared(thread, id, sig) }
                    }
                    VM_DEATH -> {
                        i.int()
                        publish { it.copy(status = DebugStatus.Exited(null), frames = emptyList(), frame = null, variables = emptyList()) }
                    }
                    VM_START, THREAD_START, THREAD_DEATH -> {
                        i.int()
                        i.objectId()
                    }
                    else -> return
                }
            }
        } catch (_: IOException) {
            // Событие короче ожидаемого — пропуск: связь жива, следующие события придут.
        }
    }

    /** Класс загружен: точки его файла — в него; поток, остановленный событием, продолжает. */
    private fun prepared(thread: Long, id: Long, sig: String) {
        val c = classes[id] ?: return
        for (path in wanted.keys.toList()) {
            val pkg = sources.packageOf(path) ?: continue
            if (!inPackage(sig, pkg) || source(id, c) != path.substringAfterLast('/')) continue
            val set = requests.getOrPut(path) { ConcurrentHashMap() }
            resolve(path, id, set)
            val states = states(path, set)
            publish { it.copy(breakpoints = it.breakpoints + (path to states)) }
        }
        call(THREAD, 3, out().objectId(thread).bytes())
    }

    /** Исключение не системного кода: поймал код приложения — дальше; иначе — остановка. */
    private fun exception(thread: Long, exception: Long, caught: JdwpLocation) {
        if (caught.classId != 0L) {
            val sig = classes[caught.classId]?.signature ?: signature(caught.classId)
            if (sig != null && !system(sig)) {
                call(VM, 9, ByteArray(0))
                return
            }
        }
        val type = objectType(exception)?.let(::className) ?: "исключение"
        val message = detailMessage(exception)
        stopped("exception", thread, "$type не поймано кодом приложения" + (message?.let { ": $it" } ?: ""))
    }

    private fun stopped(reason: String, thread: Long, description: String?) {
        clearStep()
        publish { it.copy(status = DebugStatus.Stopped(reason, thread, description), frames = emptyList(), frame = null, variables = emptyList()) }
        val count = (call(THREAD, 7, out().objectId(thread).bytes()) as? Outcome.Success)?.let { JdwpIn(it.value, sizes).int() } ?: 0
        if (count <= 0) return
        val frames = when (val r = call(THREAD, 6, out().objectId(thread).int(0).int(minOf(count, MAX_FRAMES)).bytes())) {
            is Outcome.Failure -> return
            is Outcome.Success -> try {
                JdwpIn(r.value, sizes).let { i -> List(i.int()) { i.frameId() to i.location() } }
            } catch (_: IOException) {
                return
            }
        }
        frameInfo.clear()
        val shown = frames.map { (fid, loc) ->
            frameInfo[fid] = loc
            val c = classes.getOrPut(loc.classId) { ClassInfo(signature(loc.classId) ?: "L?;") }
            val m = methods(loc.classId, c).firstOrNull { it.id == loc.methodId }
            val line = m?.let { lines(loc.classId, it) }?.filter { it.first <= loc.index }?.maxByOrNull { it.first }?.second ?: 0
            StackFrame(fid, className(c.signature) + "." + (m?.name ?: "?"), path(c, loc.classId), line)
        }
        val top = shown.firstOrNull { it.path != null } ?: shown.firstOrNull()
        val vars = top?.let { f -> frameInfo[f.id]?.let { variables(thread, f.id, it) } }.orEmpty()
        publish { v -> if (v.status is DebugStatus.Stopped) v.copy(frames = shown, frame = top?.id, variables = vars) else v }
    }

    /** Переменные кадра: видимые в его месте (`Method.VariableTable`) и `this`. Без отладочных сведений — только `this`. */
    private fun variables(thread: Long, frame: Long, loc: JdwpLocation): List<DebugVariable> {
        val out = mutableListOf<DebugVariable>()
        (call(FRAME, 3, out().objectId(thread).frameId(frame).bytes()) as? Outcome.Success)?.let { r ->
            runCatching {
                val i = JdwpIn(r.value, sizes)
                val tag = i.byte()
                val id = i.objectId()
                if (id != 0L) out += DebugVariable("this", value(tag, id), objectType(id)?.let(::className), 0)
            }
        }
        val table = (call(METHOD, 2, out().typeId(loc.classId).methodId(loc.methodId).bytes()) as? Outcome.Success)?.let { r ->
            runCatching {
                val i = JdwpIn(r.value, sizes)
                i.int()
                List(i.int()) { Slot(i.long(), i.string(), i.string(), i.int(), i.int()) }
            }.getOrNull()
        }.orEmpty().filter { loc.index >= it.start && loc.index < it.start + it.length && it.name != "this" && !it.name.startsWith("\$i\$") }
        if (table.isEmpty()) return out
        val o = out().objectId(thread).frameId(frame).int(table.size)
        for (s in table) o.int(s.slot).byte(s.signature.first().code)
        (call(FRAME, 1, o.bytes()) as? Outcome.Success)?.let { r ->
            runCatching {
                val i = JdwpIn(r.value, sizes)
                val n = i.int()
                for (k in 0 until minOf(n, table.size)) {
                    val tag = i.byte()
                    val v = primitive(tag, i) ?: value(tag, i.objectId())
                    out += DebugVariable(table[k].name, v, typeName(table[k].signature), 0)
                }
            }
        }
        return out.take(MAX_VARIABLES)
    }

    private fun primitive(tag: Int, i: JdwpIn): String? = when (tag.toChar()) {
        'Z' -> i.bool().toString()
        'B' -> i.byte().toByte().toString()
        'C' -> "'" + i.short().toChar() + "'"
        'S' -> i.short().toShort().toString()
        'I' -> i.int().toString()
        'J' -> i.long().toString()
        'F' -> Float.fromBits(i.int()).toString()
        'D' -> Double.fromBits(i.long()).toString()
        'V' -> "void"
        else -> null
    }

    /** Объект словами: строка — в кавычках, массив — длиной, прочее — классом и номером. */
    private fun value(tag: Int, id: Long): String {
        if (id == 0L) return "null"
        return when (tag.toChar()) {
            's' -> string(id)?.let { "\"" + it.take(MAX_VALUE) + "\"" } ?: "строка"
            '[' -> (call(ARRAY, 1, out().objectId(id).bytes()) as? Outcome.Success)?.let { "массив [${JdwpIn(it.value, sizes).int()}]" } ?: "массив"
            else -> (objectType(id)?.let(::className) ?: "объект") + "@" + java.lang.Long.toHexString(id)
        }
    }

    private fun string(id: Long): String? = (call(STRING, 1, out().objectId(id).bytes()) as? Outcome.Success)?.let { runCatching { JdwpIn(it.value, sizes).string() }.getOrNull() }

    /** Сигнатура класса объекта [id]. */
    private fun objectType(id: Long): String? {
        val type = (call(OBJECT, 1, out().objectId(id).bytes()) as? Outcome.Success)?.let { r -> runCatching { JdwpIn(r.value, sizes).let { i -> i.byte(); i.typeId() } }.getOrNull() } ?: return null
        return classes[type]?.signature ?: signature(type)
    }

    /** Текст исключения — поле `detailMessage` у `java.lang.Throwable`. */
    private fun detailMessage(exception: Long): String? {
        val field = detailField ?: run {
            val throwable = (call(VM, 2, out().string("Ljava/lang/Throwable;").bytes()) as? Outcome.Success)?.let { r ->
                runCatching { JdwpIn(r.value, sizes).let { i -> if (i.int() > 0) { i.byte(); i.typeId() } else null } }.getOrNull()
            } ?: return null
            val id = (call(REFERENCE_TYPE, 4, out().typeId(throwable).bytes()) as? Outcome.Success)?.let { r ->
                runCatching {
                    val i = JdwpIn(r.value, sizes)
                    var found: Long? = null
                    repeat(i.int()) {
                        val f = i.fieldId()
                        val name = i.string()
                        i.string()
                        i.int()
                        if (name == "detailMessage") found = f
                    }
                    found
                }.getOrNull()
            } ?: return null
            (throwable to id).also { detailField = it }
        }
        val r = call(OBJECT, 2, out().objectId(exception).int(1).fieldId(field.second).bytes()) as? Outcome.Success ?: return null
        val text = runCatching {
            val i = JdwpIn(r.value, sizes)
            if (i.int() < 1) return null
            i.byte()
            i.objectId()
        }.getOrNull()?.takeIf { it != 0L }?.let(::string)
        return text?.take(MAX_VALUE)
    }

    private fun signature(type: Long): String? = (call(REFERENCE_TYPE, 1, out().typeId(type).bytes()) as? Outcome.Success)?.let { runCatching { JdwpIn(it.value, sizes).string() }.getOrNull() }

    private fun source(id: Long, c: ClassInfo): String? {
        if (!c.sourceRead) {
            c.source = (call(REFERENCE_TYPE, 7, out().typeId(id).bytes()) as? Outcome.Success)?.let { runCatching { JdwpIn(it.value, sizes).string() }.getOrNull() }
            c.sourceRead = true
        }
        return c.source
    }

    private fun methods(id: Long, c: ClassInfo): List<MethodInfo> {
        c.methods?.let { return it }
        val list = (call(REFERENCE_TYPE, 5, out().typeId(id).bytes()) as? Outcome.Success)?.let { r ->
            runCatching {
                val i = JdwpIn(r.value, sizes)
                List(i.int()) {
                    val m = i.methodId()
                    val name = i.string()
                    i.string()
                    i.int()
                    MethodInfo(m, name)
                }
            }.getOrNull()
        }.orEmpty()
        c.methods = list
        return list
    }

    private fun lines(classId: Long, m: MethodInfo): List<Pair<Long, Int>> {
        m.lines?.let { return it }
        val list = (call(METHOD, 1, out().typeId(classId).methodId(m.id).bytes()) as? Outcome.Success)?.let { r ->
            runCatching {
                val i = JdwpIn(r.value, sizes)
                i.long()
                i.long()
                List(i.int()) { i.long() to i.int() }
            }.getOrNull()
        }.orEmpty()
        m.lines = list
        return list
    }

    /** Путь исходника класса в проекте — по пакету класса и `SourceFile`. */
    private fun path(c: ClassInfo, id: Long): String? {
        val file = source(id, c) ?: return null
        val pkg = c.signature.removePrefix("L").removeSuffix(";").substringBeforeLast('/', "").replace('/', '.')
        return sources.find(pkg, file)
    }

    private fun out() = JdwpOut(sizes)

    private fun call(set: Int, cmd: Int, data: ByteArray, timeout: Duration = REQUEST_TIMEOUT): Outcome<ByteArray> = peer.request(set, cmd, data, timeout)

    private fun Outcome<ByteArray>.unit(): Outcome<Unit> = when (this) {
        is Outcome.Failure -> this
        is Outcome.Success -> Outcome.success(Unit)
    }

    private fun note(line: String) = publish { v -> v.copy(output = (v.output + line).takeLast(MAX_OUTPUT_LINES)) }

    private fun submit(work: () -> Unit) {
        try {
            worker.execute(work)
        } catch (_: RejectedExecutionException) {
            // Сеанс уже закрыт — обновлять нечего.
        }
    }

    @Synchronized
    private fun publish(change: (DebugView) -> DebugView) {
        view = change(view)
        onView(view)
    }

    private fun fail(r: Outcome.Failure): Outcome.Failure {
        publish { it.copy(status = DebugStatus.Failed(r.diagnostics.first().message)) }
        return r
    }

    companion object {
        val START_TIMEOUT: Duration = 30.seconds
        val REQUEST_TIMEOUT: Duration = 15.seconds
        val STOP_TIMEOUT: Duration = 3.seconds
        const val MAX_FRAMES: Int = 64
        const val MAX_VARIABLES: Int = 200
        const val MAX_VALUE: Int = 500
        const val MAX_OUTPUT_LINES: Int = 200

        /** Системный код: шаги его пропускают, его исключения не останавливают. */
        val SKIPPED: List<String> = listOf(
            "java.*", "javax.*", "jdk.*", "sun.*", "kotlin.*", "kotlinx.*", "android.*", "androidx.*",
            "com.android.*", "com.google.android.*", "dalvik.*", "libcore.*", "org.apache.harmony.*",
        )

        /** Класс с сигнатурой [signature] — системный ([SKIPPED]). */
        fun system(signature: String): Boolean {
            val name = signature.removePrefix("L").removeSuffix(";").replace('/', '.')
            return SKIPPED.any { name.startsWith(it.removeSuffix("*")) }
        }

        /** Класс в пакете [pkg] ровно (не во вложенном): `Lcom/a/B$C;` — в `com.a`. */
        fun inPackage(signature: String, pkg: String): Boolean =
            signature.removePrefix("L").removeSuffix(";").substringBeforeLast('/', "") == pkg.replace('.', '/')

        /** Имя класса для экрана: без пакета, вложенные — через точку. */
        fun className(signature: String): String = signature.removePrefix("L").removeSuffix(";").substringAfterLast('/').replace('$', '.')

        /** Тип переменной по сигнатуре JVM: `I` — `int`, `[Ljava/lang/String;` — `String[]`. */
        fun typeName(signature: String): String = when (signature.firstOrNull()) {
            'Z' -> "boolean"
            'B' -> "byte"
            'C' -> "char"
            'S' -> "short"
            'I' -> "int"
            'J' -> "long"
            'F' -> "float"
            'D' -> "double"
            '[' -> typeName(signature.drop(1)) + "[]"
            'L' -> className(signature)
            else -> signature
        }

        private const val VM = 1
        private const val REFERENCE_TYPE = 2
        private const val METHOD = 6
        private const val OBJECT = 9
        private const val STRING = 10
        private const val THREAD = 11
        private const val ARRAY = 13
        private const val EVENT_REQUEST = 15
        private const val FRAME = 16
        private const val EVENT_SET = 64
        private const val EVENT_COMPOSITE = 100

        private const val SINGLE_STEP = 1
        private const val BREAKPOINT = 2
        private const val EXCEPTION = 4
        private const val THREAD_START = 6
        private const val THREAD_DEATH = 7
        private const val CLASS_PREPARE = 8
        private const val VM_START = 90
        private const val VM_DEATH = 99

        private const val MOD_COUNT = 1
        private const val MOD_CLASS_MATCH = 5
        private const val MOD_CLASS_EXCLUDE = 6
        private const val MOD_LOCATION_ONLY = 7
        private const val MOD_EXCEPTION_ONLY = 8
        private const val MOD_STEP = 10

        private const val SUSPEND_THREAD = 1
        private const val SUSPEND_ALL = 2
        private const val TYPE_CLASS = 1
        private const val STEP_LINE = 1
        private const val DEPTH_INTO = 0
        private const val DEPTH_OVER = 1
        private const val DEPTH_OUT = 2
    }
}
