package com.dpsmpx.dpai.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Видно ли окно (активность между ON_START и ON_STOP). Цикл Compose сам не
 * останавливается, когда приложение свёрнуто или экран гаснет (Redmi Note 13:
 * пробы `/usage` шли при выключенном экране), — циклы экрана привязаны к этому признаку.
 */
@Composable
internal fun rememberVisible(): Boolean {
    val lifecycle = (androidx.activity.compose.LocalActivity.current as? androidx.activity.ComponentActivity)?.lifecycle
    var visible by remember { mutableStateOf(lifecycle?.currentState?.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) ?: true) }
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_START) visible = true
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) visible = false
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    return visible
}

/**
 * Опрос экрана (AI:A-15): [block] сразу и затем раз в [periodMs], пока окно
 * видно; свёрнутое «AI» и погасший экран не опрашивают ничего, вернувшееся
 * окно — опрашивает сразу. [keys] перезапускают опрос, как у `LaunchedEffect`.
 */
@Composable
internal fun Poll(vararg keys: Any?, periodMs: Long, block: suspend () -> Unit) {
    val visible = rememberVisible()
    val current by rememberUpdatedState(block)
    LaunchedEffect(*arrayOf(visible, *keys)) {
        if (!visible) return@LaunchedEffect
        while (true) {
            current()
            delay(periodMs)
        }
    }
}

/**
 * Значение с диска для экрана (AI:A-16): начальное — [initial], чтение — не
 * в главном потоке; [keys] перечитывают. Мелкие файлы читаются быстро, но на
 * флеше под нагрузкой — десятки миллисекунд, это пропущенные кадры.
 */
@Composable
internal fun <T> rememberRead(vararg keys: Any?, initial: T, read: () -> T): T {
    var value by remember { mutableStateOf(initial) }
    val current by rememberUpdatedState(read)
    LaunchedEffect(*keys) { value = withContext(Dispatchers.IO) { current() } }
    return value
}

/**
 * Ключи списка без своих id (AI:A-25): содержимое и номер повтора. Элемент
 * держит своё состояние, когда начало списка обрезано или сдвинуто, — без
 * ключа сдвиг перерисовывал бы все строки.
 */
internal fun <T> contentKeys(list: List<T>, of: (T) -> Any): List<String> {
    val seen = HashMap<Any, Int>()
    return list.map { item ->
        val k = of(item)
        val n = (seen[k] ?: 0) + 1
        seen[k] = n
        "$k#$n"
    }
}
