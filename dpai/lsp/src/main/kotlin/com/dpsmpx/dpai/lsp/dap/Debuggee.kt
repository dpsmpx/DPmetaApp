package com.dpsmpx.dpai.lsp.dap

import com.dpsmpx.dpai.core.result.Outcome

/**
 * Идущий сеанс отладки для экрана (D-159, D-161): DAP ([DebugSession]) и JDWP
 * (`lsp.jdwp.JdwpSession`) дают один вид [view] и одни действия — панель и
 * контроллер отладки общие. Методы блокируют до ответа отладчика — звать не из
 * потока экрана.
 */
interface Debuggee {
    val view: DebugView

    val isOpen: Boolean

    /** Точки файла [path] целиком: прежние снимаются; итог — как их принял отладчик. */
    fun setBreakpoints(path: String, lines: List<Int>): Outcome<List<BreakpointState>>

    fun resume(): Outcome<Unit>

    fun next(): Outcome<Unit>

    fun stepIn(): Outcome<Unit>

    fun stepOut(): Outcome<Unit>

    fun pause(): Outcome<Unit>

    /** Выбрать кадр стека: его переменные. */
    fun selectFrame(frameId: Long): Outcome<List<DebugVariable>>

    /** Поля переменной со ссылкой [ref]. */
    fun children(ref: Long): Outcome<List<DebugVariable>>

    /** Значение выражения в выбранном кадре. */
    fun evaluate(expression: String): Outcome<String>

    /** Кончить отладку и закрыть связь. */
    fun stop()
}
