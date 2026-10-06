package com.dpsmpx.dppass.subscriptions

import com.dpsmpx.dppass.core.Ids
import com.dpsmpx.dppass.core.Json
import com.dpsmpx.dppass.core.JsonArray
import com.dpsmpx.dppass.core.JsonNumber
import com.dpsmpx.dppass.core.JsonObject
import com.dpsmpx.dppass.core.JsonParseException
import com.dpsmpx.dppass.core.JsonString
import com.dpsmpx.dppass.core.JsonValue
import com.dpsmpx.dppass.core.SubscriptionId
import com.dpsmpx.dppass.diagnostics.DiagCode
import com.dpsmpx.dppass.diagnostics.Diagnostic
import com.dpsmpx.dppass.diagnostics.Outcome
import com.dpsmpx.dppass.diagnostics.Secret

/** Сохранённые подписки и какая из них сейчас применена (D-36). */
data class SubscriptionsState(val items: List<Subscription>, val activeId: SubscriptionId?) {
    fun find(id: SubscriptionId): Subscription? = items.firstOrNull { it.id == id }

    companion object {
        val EMPTY: SubscriptionsState = SubscriptionsState(emptyList(), null)
    }
}

/**
 * Документ хранения подписок `dppass.subscriptions` версии 1 (D-36).
 *
 * В нём секреты — адрес подписки и идентификатор устройства (D-33):
 * документ пишется только в зашифрованное хранилище (D-19) и наружу не
 * отдаётся. Поэтому здесь — одно из немногих мест, где разрешён
 * `reveal()` (`code.forbidden`, правило `secret-reveal`, `tools/dpverify.toml`).
 *
 * Чтение строгое: неизвестная версия, неверный id или поле не того типа —
 * отказ целиком; повреждённое хранилище уходит в карантин вызывающим, а
 * не читается наполовину.
 */
object SubscriptionCodec {
    const val FORMAT: String = "dppass.subscriptions"
    const val VERSION: Int = 1

    fun write(state: SubscriptionsState): String = Json.write(
        Json.obj(
            "format" to Json.str(FORMAT),
            "format_version" to Json.num(VERSION),
            "active" to state.activeId?.let { Json.str(it.value) },
            "items" to Json.arr(state.items.map(::item)),
        ),
        pretty = false,
    )

    private fun item(s: Subscription): JsonObject {
        val url = (s.source as SubscriptionSource.Url).url.reveal()
        val m = s.metadata
        return Json.obj(
            "id" to Json.str(s.id.value),
            "name" to Json.str(s.name),
            "url" to Json.str(url),
            "device_id" to s.deviceId?.let { Json.str(it.reveal()) },
            "binding" to Json.str(if (s.binding == RoutingBinding.PROVIDER_ROUTING) "provider" else "user"),
            "revision_counter" to Json.num(s.revisionCounter),
            "metadata" to Json.obj(
                "title" to Json.str(m.title),
                "update_interval_hours" to m.updateIntervalHours?.let(Json::num),
                "traffic" to m.traffic?.let { t ->
                    Json.obj(
                        "upload" to t.uploadBytes?.let(Json::num),
                        "download" to t.downloadBytes?.let(Json::num),
                        "total" to t.totalBytes?.let(Json::num),
                        "expire" to t.expireEpochSeconds?.let(Json::num),
                    )
                },
                "support_url" to Json.str(m.supportUrl),
                "web_page_url" to Json.str(m.webPageUrl),
                "announce" to Json.str(m.announce),
            ),
            "last_successful" to s.lastSuccessful?.let { r ->
                Json.obj(
                    "number" to Json.num(r.number),
                    "checksum" to Json.str(r.checksum),
                    "fetched_at" to Json.num(r.fetchedAtMillis),
                    "profiles" to Json.num(r.profileCount),
                )
            },
            "last_failed" to s.lastFailed?.let { f ->
                Json.obj(
                    "checksum" to Json.str(f.checksum),
                    "failed_at" to Json.num(f.failedAtMillis),
                    "code" to Json.str(f.reason.code.name),
                    "message" to Json.str(f.reason.message),
                )
            },
        )
    }

    fun read(text: String): Outcome<SubscriptionsState> {
        val root = try {
            Json.parse(text) as? JsonObject
        } catch (e: JsonParseException) {
            null
        } ?: return Outcome.failure(DiagCode.STORAGE_CORRUPTED, "подписки: не JSON-объект")
        if ((root["format"] as? JsonString)?.value != FORMAT || (root["format_version"] as? JsonNumber)?.toIntOrNull() != VERSION) {
            return Outcome.failure(DiagCode.FORMAT_VERSION_UNSUPPORTED, "подписки: неизвестный формат хранения")
        }
        return try {
            val items = (root["items"] as? JsonArray ?: bad("items")).items.map { readItem(it) }
            require(items.map { it.id }.toSet().size == items.size) { "повторный id подписки" }
            val active = (root["active"] as? JsonString)?.value?.let(::id)
            require(active == null || items.any { it.id == active }) { "применённой подписки нет в списке" }
            Outcome.Success(SubscriptionsState(items, active))
        } catch (e: IllegalArgumentException) {
            Outcome.failure(DiagCode.STORAGE_CORRUPTED, "подписки: ${e.message}")
        }
    }

    private fun readItem(v: JsonValue): Subscription {
        val o = v as? JsonObject ?: bad("items[]")
        val m = o["metadata"] as? JsonObject ?: bad("metadata")
        val traffic = (m["traffic"] as? JsonObject)?.let { t ->
            TrafficInfo(t.long("upload"), t.long("download"), t.long("total"), t.long("expire"))
        }
        val ok = (o["last_successful"] as? JsonObject)?.let { r ->
            SubscriptionRevision(r.long("number") ?: bad("number"), r.str("checksum") ?: bad("checksum"), r.long("fetched_at") ?: bad("fetched_at"), (r.long("profiles") ?: bad("profiles")).toInt())
        }
        val failed = (o["last_failed"] as? JsonObject)?.let { f ->
            val code = DiagCode.entries.firstOrNull { it.name == f.str("code") } ?: DiagCode.SUBSCRIPTION_FETCH_FAILED
            FailedRevision(f.str("checksum"), f.long("failed_at") ?: bad("failed_at"), Diagnostic.error(code, f.str("message") ?: ""))
        }
        return Subscription(
            id = id(o.str("id") ?: bad("id")),
            name = o.str("name") ?: bad("name"),
            source = SubscriptionSource.Url(Secret(o.str("url") ?: bad("url"))),
            binding = if (o.str("binding") == "provider") RoutingBinding.PROVIDER_ROUTING else RoutingBinding.USER_ROUTING,
            metadata = SubscriptionMetadata(
                title = m.str("title"),
                updateIntervalHours = m.long("update_interval_hours")?.toInt(),
                traffic = traffic,
                supportUrl = m.str("support_url"),
                webPageUrl = m.str("web_page_url"),
                announce = m.str("announce"),
            ),
            lastSuccessful = ok,
            lastFailed = failed,
            revisionCounter = o.long("revision_counter") ?: 0,
            deviceId = o.str("device_id")?.let { d ->
                require(DeviceId.FORMAT.matches(d)) { "идентификатор устройства не по формату" }
                Secret(d)
            },
        )
    }

    private fun id(v: String): SubscriptionId {
        require(Ids.isValid(v)) { "id подписки не по формату" }
        return SubscriptionId(v)
    }

    private fun bad(what: String): Nothing = throw IllegalArgumentException("нет или не того типа: $what")

    private fun JsonObject.str(key: String): String? = when (val v = this[key]) {
        null -> null
        is JsonString -> v.value
        else -> bad(key)
    }

    private fun JsonObject.long(key: String): Long? = when (val v = this[key]) {
        null -> null
        is JsonNumber -> v.toLongOrNull() ?: bad(key)
        else -> bad(key)
    }
}
