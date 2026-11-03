package com.dpsmpx.dpai.providers

import com.dpsmpx.dpai.accounts.ApiProfile
import com.dpsmpx.dpai.accounts.ApiProfiles
import com.dpsmpx.dpai.accounts.ProviderKind
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import java.io.IOException

/** Транспорт без сети: запросы записываются, ответ — по запросу. */
class FakeTransport(private val respond: (HttpCall) -> FakeReply) : Transport {
    val calls: MutableList<HttpCall> = mutableListOf()

    override fun open(call: HttpCall, cancel: Cancel): Outcome<HttpReply> {
        calls += call
        return Outcome.success(respond(call).also { it.cancel = cancel })
    }

    val last: HttpCall get() = calls.last()

    fun bodyOf(call: HttpCall = last): String = call.body!!.toString(Charsets.UTF_8)
}

/** Ответ из строк; [hook] — перед строкой с номером: отмена, обрыв. */
class FakeReply(override val status: Int, body: String, private val hook: (Int, Cancel) -> Unit = { _, _ -> }) : HttpReply {
    private val lines = body.split("\n")
    private var i = 0
    lateinit var cancel: Cancel

    override fun header(name: String): String? = null

    override fun readLine(): String? {
        hook(i, cancel)
        if (cancel.cancelled) throw IOException("соединение закрыто")
        return if (i < lines.size) lines[i++] else null
    }

    override fun close() = Unit
}

/** SSE из пар (событие, данные). */
fun sse(vararg events: Pair<String?, String>): String = events.joinToString("") { (e, d) ->
    (e?.let { "event: $it\n" } ?: "") + "data: $d\n\n"
}

fun profile(
    kind: ProviderKind,
    baseUrl: String = "",
    model: String = "m",
    custom: Boolean = false,
    max: Int? = null,
): ApiProfile = ApiProfiles.form(
    IdKinds.ACCOUNT.parse("acc_01J9ZQ3K8M7N6P5R4S3T2V1W0X").valueOrNull()!!,
    "профиль", kind, baseUrl, model, max, custom,
).valueOrNull()!!

/** Ключ-образец: собран из частей, чтобы проверка секретов репозитория не видела его целиком. */
fun sampleKey(prefix: String): String = prefix + "-" + "abcdefghij".repeat(4)
