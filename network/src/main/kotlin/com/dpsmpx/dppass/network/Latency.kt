package com.dpsmpx.dppass.network

import com.dpsmpx.dppass.core.Clock
import com.dpsmpx.dppass.core.Host
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Итог замера задержки до сервера (D-41). */
sealed interface LatencyResult {
    /** Время TCP-рукопожатия, мс (без резолва имени). */
    data class Measured(val millis: Long) : LatencyResult

    /** Не ответил за тайм-аут, отказал или имя не резолвится. */
    data class Failed(val reason: LatencyFailure) : LatencyResult

    /** Не мерится так: UDP (Hysteria2). */
    data object NotTcp : LatencyResult
}

enum class LatencyFailure { TIMEOUT, REFUSED, UNREACHABLE, NAME_NOT_RESOLVED }

/** Замер одного адреса. В тестах — свой, без сети. */
fun interface LatencyProbe {
    suspend fun measure(host: Host, port: Int, timeoutMillis: Int): LatencyResult
}

/**
 * TCP-рукопожатие до сервера из процесса приложения, то есть мимо
 * туннеля (D-17, D-41). Не «работает ли сервер»: TCP проходит и там, где
 * режется TLS (D-13).
 *
 * Имя сервера резолвит система — как и для ядра (D-29). Это единственное
 * место вне ядра, где DPpass резолвит имя (`check_forbidden_apis.py`,
 * правило `dns-resolve`); адрес IP не резолвится. Время резолва в замер не
 * входит. Блокирует поток: вызывать в фоне.
 */
class TcpLatencyProbe(private val clock: Clock) : LatencyProbe {
    override suspend fun measure(host: Host, port: Int, timeoutMillis: Int): LatencyResult {
        val address = when (host) {
            is Host.Ip -> InetAddress.getByAddress(host.address.toBytes())
            is Host.Domain -> try {
                InetAddress.getAllByName(host.name).firstOrNull() ?: return LatencyResult.Failed(LatencyFailure.NAME_NOT_RESOLVED)
            } catch (e: UnknownHostException) {
                return LatencyResult.Failed(LatencyFailure.NAME_NOT_RESOLVED)
            } catch (e: SecurityException) {
                return LatencyResult.Failed(LatencyFailure.UNREACHABLE)
            }
        }
        val socket = Socket()
        return try {
            val start = clock.nowMillis()
            socket.connect(InetSocketAddress(address, port), timeoutMillis)
            LatencyResult.Measured((clock.nowMillis() - start).coerceAtLeast(1))
        } catch (e: SocketTimeoutException) {
            LatencyResult.Failed(LatencyFailure.TIMEOUT)
        } catch (e: java.net.ConnectException) {
            LatencyResult.Failed(LatencyFailure.REFUSED)
        } catch (e: IOException) {
            LatencyResult.Failed(LatencyFailure.UNREACHABLE)
        } finally {
            try {
                socket.close()
            } catch (e: IOException) {
                // закрытие неудачного сокета — не событие
            }
        }
    }
}
