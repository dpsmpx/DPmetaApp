package com.dpsmpx.dppass.app

import com.dpsmpx.dppass.core.ProfileId
import com.dpsmpx.dppass.diagnostics.Diagnostic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Состояние туннеля для интерфейса (D-34). Меняет его только сервис
 * туннеля; экраны и уведомление его показывают. Без Android: переходы
 * проверяются на хосте.
 */
sealed interface TunnelStatus {
    /** Туннеля нет и не было отказа с прошлого запуска. */
    data object Idle : TunnelStatus

    /** Идёт подключение; [profileName] — кого проверяет сейчас (null — план ещё составляется). */
    data class Connecting(val profileName: String?) : TunnelStatus

    data class Connected(val profileId: ProfileId, val profileName: String, val sinceMillis: Long) : TunnelStatus

    data object Stopping : TunnelStatus

    /** Последняя попытка не удалась; причина — с кодом (D-22), туннеля нет. */
    data class Failed(val reason: Diagnostic) : TunnelStatus

    /** Туннель есть или поднимается: кнопка экрана — «Отключить», выбор сервера — переподключение. */
    val busy: Boolean get() = this is Connecting || this is Connected
}

/** Одно состояние на процесс: сервис и экраны живут в одном процессе (D-30). */
object TunnelState {
    private val flow = MutableStateFlow<TunnelStatus>(TunnelStatus.Idle)

    val status: StateFlow<TunnelStatus> = flow.asStateFlow()

    fun set(next: TunnelStatus) {
        flow.value = next
    }
}
