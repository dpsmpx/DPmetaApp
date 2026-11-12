package com.dpsmpx.dpai.android

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Дзен принадлежит текущему полю; уход обязательно возвращает системные панели (D-138). */
@Composable internal fun IdeWindow(zen: Boolean) {
    val activity = LocalActivity.current ?: return
    if (!zen) return
    DisposableEffect(activity) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        val previous = controller.systemBarsBehavior
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()); controller.systemBarsBehavior = previous }
    }
}

/** В редакторе Back сначала предлагает сохранить, включая Back перед скрытием IME. */
@Composable internal fun IdeBackHandler(onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    val activity = LocalActivity.current ?: return
    val latest by androidx.compose.runtime.rememberUpdatedState(onBack)
    if (android.os.Build.VERSION.SDK_INT >= 33) {
        DisposableEffect(activity) {
            val callback = android.window.OnBackInvokedCallback { latest() }
            activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
            onDispose { activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback) }
        }
    }
}
