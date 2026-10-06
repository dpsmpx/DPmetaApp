package com.dpsmpx.dppass.engine.xray

import com.dpsmpx.dppass.core.IpAddress
import com.dpsmpx.dppass.diagnostics.DiagCode
import com.dpsmpx.dppass.diagnostics.Diagnostic
import com.dpsmpx.dppass.routing.GeoResolver

/**
 * [GeoResolver] на наборе геоданных ядра (D-27): geosite и geoip
 * сопоставляет мост тем же кодом Xray, что и маршрутизатор ядра, и по тем
 * же файлам (`bridge.GeoMatch`). Поэтому объяснение решения
 * ([com.dpsmpx.dppass.routing.RouteEvaluator], D-09) не расходится с тем,
 * что ядро делает с трафиком: в dlc есть правила regexp с синтаксисом Go,
 * сочетания атрибутов и списки, которые свой разбор на JVM понял бы иначе.
 *
 * Отказ моста (кода нет в наборе, набор не задан) запоминается в
 * [failure], а условие считается невыполненным: [GeoResolver] отвечает
 * только «да» или «нет». Решение, при котором был отказ, вызывающий не
 * выдаёт за объяснение (D-05). Один объект — на одно решение, в одном
 * потоке.
 */
internal class XrayGeoResolver(private val runtime: XrayRuntime) : GeoResolver {
    /** Первый отказ моста за время жизни объекта; null — отказов не было. */
    var failure: Diagnostic? = null
        private set

    override fun siteMatches(code: String, domain: String): Boolean = ask(GeoKind.SITE, code, domain.encodeToByteArray(), "geosite:$code")

    override fun ipMatches(code: String, ip: IpAddress): Boolean = ask(GeoKind.IP, code, ip.toBytes(), "geoip:$code")

    private fun ask(kind: GeoKind, code: String, value: ByteArray, condition: String): Boolean =
        when (val r = runtime.geoMatch(kind, code, value)) {
            is GeoMatchResult.Answer -> r.matches
            is XrayResult.Failed -> {
                if (failure == null) {
                    val diag = if (r.kind == XrayFailureKind.CONFIG) DiagCode.GEO_CODE_UNKNOWN else DiagCode.GEODATA_UNAVAILABLE
                    failure = Diagnostic.error(diag, "$condition: набор геоданных ядра не ответил — решение не объясняется").withCause(r.cause(diag))
                }
                false
            }
        }
}
