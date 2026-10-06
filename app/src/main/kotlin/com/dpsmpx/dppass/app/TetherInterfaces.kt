package com.dpsmpx.dppass.app

import android.content.Context
import android.net.ConnectivityManager
import com.dpsmpx.dppass.core.IpAddress
import com.dpsmpx.dppass.engine.ShareSpec
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException

/**
 * Интерфейсы раздачи (D-51): точка доступа, USB- и Bluetooth-модем.
 * Публичного списка нет (`docs/research/ANDROID_NETWORK_MODEL.md`, §9),
 * поэтому интерфейс узнаётся по признакам: поднят, широковещательный (не
 * точка-точка, как TUN и часто мобильная связь), частный IPv4 с местом для
 * клиентов — и это **не** сеть `ConnectivityManager`: Wi-Fi-клиент,
 * мобильная связь и VPN — сети «вверх по течению», раздача — «вниз». По
 * имени не узнаётся: у производителей имена разные.
 *
 * Ошибка в сторону «не раздавать»: не удалось узнать сети устройства —
 * интерфейсов раздачи нет, иначе вход мог бы встать на адрес Wi-Fi-клиента.
 */
object TetherInterfaces {
    /** Что известно об интерфейсе; без Android — для проверки выбора на хосте. */
    data class Iface(
        val name: String,
        val up: Boolean,
        val loopback: Boolean,
        val pointToPoint: Boolean,
        val addresses: List<Address>,
    )

    /** IPv4-адрес интерфейса: длина префикса и есть ли широковещательный адрес. */
    data class Address(val ip: IpAddress, val prefix: Int, val broadcast: Boolean)

    /** Интерфейсы раздачи сейчас; не удалось узнать — пусто. */
    fun find(context: Context): List<ShareSpec.Endpoint> {
        val upstream = upstream(context) ?: return emptyList()
        val all = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (e: SocketException) {
            return emptyList()
        }
        return select(all.mapNotNull(::describe), upstream)
    }

    /**
     * Выбор (D-51): интерфейсы не из [upstream], поднятые, не loopback и не
     * точка-точка; их частные IPv4-адреса с широковещанием и подсетью для
     * клиентов. Порядок — по адресу: одинаковый набор — одинаковый план.
     */
    fun select(interfaces: List<Iface>, upstream: Set<String>): List<ShareSpec.Endpoint> = interfaces
        .filter { it.up && !it.loopback && !it.pointToPoint && it.name !in upstream }
        .flatMap { i -> i.addresses.filter { it.broadcast }.mapNotNull { ShareSpec.Endpoint.ofOrNull(it.ip, it.prefix) } }
        .distinctBy { it.address }
        .sortedBy { it.address.toString() }

    /**
     * Переподключаться ли (D-51), когда опрос дал [wanted], а туннель
     * раздаёт на [current]. Пропал интерфейс — сразу. Новый — только если
     * виден два опроса подряд ([previous] — прошлый опрос): сеть
     * Wi-Fi-клиента в первые секунды может ещё не значиться в
     * `ConnectivityManager` и на миг выглядеть интерфейсом раздачи.
     */
    fun shouldReconnect(current: List<ShareSpec.Endpoint>, wanted: List<ShareSpec.Endpoint>, previous: List<ShareSpec.Endpoint>?): Boolean {
        if (wanted.toSet() == current.toSet()) return false
        return current.containsAll(wanted) || wanted.toSet() == previous?.toSet()
    }

    /** Имена интерфейсов сетей устройства; null — узнать не удалось. */
    private fun upstream(context: Context): Set<String>? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return try {
            // Замена — колбэки сетей; здесь нужен снимок раз в несколько секунд, а не
            // ещё одна регистрация колбэка (у процесса их не больше 100).
            @Suppress("DEPRECATION")
            val networks = cm.allNetworks
            networks.mapNotNull { cm.getLinkProperties(it)?.interfaceName }.toSet()
        } catch (e: RuntimeException) {
            null
        }
    }

    /** Интерфейс, который не удалось расспросить, пропускается. */
    private fun describe(n: NetworkInterface): Iface? = try {
        Iface(
            name = n.name,
            up = n.isUp,
            loopback = n.isLoopback,
            pointToPoint = n.isPointToPoint,
            addresses = n.interfaceAddresses.mapNotNull { a ->
                val ip = a.address as? Inet4Address ?: return@mapNotNull null
                Address(IpAddress.of(ip.address), a.networkPrefixLength.toInt(), a.broadcast != null)
            },
        )
    } catch (e: SocketException) {
        null
    }
}
