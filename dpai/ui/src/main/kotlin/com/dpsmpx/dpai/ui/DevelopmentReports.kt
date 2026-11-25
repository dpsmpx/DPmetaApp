package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Redactor
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNull
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpai.core.serialization.json
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory
import javax.xml.parsers.ParserConfigurationException
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXNotRecognizedException
import org.xml.sax.SAXNotSupportedException
import org.xml.sax.ext.DefaultHandler2

/** Читает доказательства штатных анализаторов, не подменяя их своим анализом кода (D-223). */
object DevelopmentReports {
    data class TestCase(val suite: String, val name: String, val status: String, val seconds: Double?, val seed: String?) {
        val key: String get() = Hashing.sha256(JsonArray(listOf(json(suite), json(name)))).hex
        fun jsonValue() = JsonObject.of("suite" to json(clean(suite)), "name" to json(clean(name)), "status" to json(status),
            "seconds" to (seconds?.let { JsonNumber(it.toString()) } ?: JsonNull), "seed" to (seed?.let { json(clean(it)) } ?: JsonNull))
    }
    data class Tests(val cases: List<TestCase>, val declared: Long?, val sha256: String, val countersMatch: Boolean = true) {
        val complete: Boolean get() = countersMatch && declared == cases.size.toLong()
    }

    fun junit(text: String, cancelled: () -> Boolean = { false }): Outcome<Tests> {
        if (text.length > MAX_TEXT) return invalid("XML слишком велик")
        val cases = arrayListOf<TestCase>()
        var depth = 0
        var suite = ""
        var name: String? = null
        var status = "PASSED"
        var seconds: Double? = null
        var declared: Long? = null
        var declaredFailed: Long? = null
        var declaredSkipped: Long? = null
        var seed: String? = null
        val parents = ArrayDeque<Pair<String, String?>>()
        val keys = hashSetOf<String>()
        return try {
            val handler = object : DefaultHandler2() {
                override fun resolveEntity(publicId: String?, systemId: String?): InputSource = throw SAXException("external entity")
                override fun resolveEntity(name: String?, publicId: String?, baseURI: String?, systemId: String?): InputSource = throw SAXException("external entity")
                override fun startDTD(name: String?, publicId: String?, systemId: String?) { throw SAXException("DTD forbidden") }
                override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes) {
                    if (cancelled()) throw SAXException("cancelled")
                    if (++depth > 64 || qName == "testcase" && cases.size >= 5000) throw SAXException("report limit")
                    if (depth == 1 && qName !in setOf("testsuite", "testsuites")) throw SAXException("unknown root")
                    fun attr(key: String): String? = attrs.getValue(key)?.also { if (it.length > 2048) throw SAXException("attribute limit") }
                    when (qName) {
                        "testsuites", "testsuite" -> {
                            if (depth == 1) {
                                declared = attr("tests")?.toLongOrNull()?.takeIf { it >= 0 }
                                declaredFailed = attr("failures")?.toLongOrNull()?.let { it + (attr("errors")?.toLongOrNull() ?: 0) }
                                declaredSkipped = attr("skipped")?.toLongOrNull()
                            }
                            parents.addLast(suite to seed)
                            suite = attr("name") ?: suite
                        }
                        "property" -> if (attr("name")?.lowercase() in setOf("seed", "random.seed", "jqwik.seed", "kotest.proptest.seed")) seed = attr("value")
                        "testcase" -> {
                            if (name != null) throw SAXException("nested testcase")
                            name = attr("name") ?: throw SAXException("test name missing")
                            val owner = attr("classname") ?: suite
                            parents.addLast(suite to seed); suite = owner
                            seconds = attr("time")?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
                            status = when (attr("status")) { null, "run" -> "PASSED"; "notrun", "disabled" -> "SKIPPED"; else -> throw SAXException("unknown status") }
                        }
                        "failure", "error" -> if (name != null) status = "FAILED"
                        "flakyFailure", "flakyError" -> if (name != null && status != "FAILED") status = "FLAKY"
                        "rerunFailure", "rerunError" -> if (name != null) status = "FAILED"
                        "skipped" -> if (name != null && status != "FAILED") status = "SKIPPED"
                    }
                }
                override fun endElement(uri: String?, localName: String?, qName: String) {
                    when (qName) {
                        "testcase" -> {
                            val item = TestCase(suite, name ?: throw SAXException("test missing"), status, seconds, seed)
                            if (!keys.add(item.key)) throw SAXException("duplicate test identity")
                            cases += item; name = null
                            val old = parents.removeLast(); suite = old.first; seed = old.second
                        }
                        "testsuite", "testsuites" -> { val old = parents.removeLast(); suite = old.first; seed = old.second }
                    }
                    depth--
                }
            }
            val factory = SAXParserFactory.newInstance().apply {
                isNamespaceAware = false; isValidating = false
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            val reader = factory.newSAXParser().xmlReader
            // Стандартный lexical handler отличает DTD от примера XML в CDATA/комментарии.
            // Если backend его не поддерживает, разбор не начинается.
            reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
            reader.contentHandler = handler; reader.errorHandler = handler
            reader.entityResolver = handler; reader.dtdHandler = handler
            reader.parse(InputSource(StringReader(text)))
            if (cases.isEmpty() && declared != 0L) invalid("нет проверяемых testcases")
            else Outcome.success(Tests(cases, declared, Hashing.sha256(text).hex,
                (declaredFailed == null || declaredFailed == cases.count { it.status == "FAILED" }.toLong()) &&
                (declaredSkipped == null || declaredSkipped == cases.count { it.status == "SKIPPED" }.toLong())))
        } catch (e: Exception) {
            if (cancelled()) Outcome.failure(DiagCode.CANCELLED_BY_USER, "разбор отчёта отменён")
            else if (e is ParserConfigurationException || e is SAXNotRecognizedException || e is SAXNotSupportedException)
                Outcome.failure(DiagCode.UNSUPPORTED, "XML-парсер не поддерживает безопасный контракт без DTD/внешних сущностей")
            else invalid("XML отчёта повреждён, неоднозначен или превышает предел")
        }
    }

    fun tests(current: Tests, baseline: Tests? = null): JsonObject {
        val previous = baseline?.cases?.associateBy { it.key }.orEmpty()
        val changed = current.cases.filter { previous[it.key]?.status?.let { s -> s != it.status } == true }
        return JsonObject.of("basis" to json("JUNIT_TESTCASES"), "complete" to json(current.complete),
            "total" to json(current.cases.size), "declared" to (current.declared?.let { json(it) } ?: JsonNull),
            "passed" to json(current.cases.count { it.status == "PASSED" }), "failed" to json(current.cases.count { it.status == "FAILED" }),
            "skipped" to json(current.cases.count { it.status == "SKIPPED" }), "sha256" to json(current.sha256),
            "flakyReported" to json(current.cases.count { it.status == "FLAKY" }), "countersMatch" to json(current.countersMatch),
            "failures" to JsonArray(current.cases.filter { it.status == "FAILED" }.take(25).map { it.jsonValue() }),
            "changedResults" to JsonArray(changed.take(25).map { it.jsonValue() }), "changedCount" to json(changed.size),
            "baselineSha256" to (baseline?.sha256?.let { json(it) } ?: JsonNull),
            "truncated" to json(current.cases.count { it.status == "FAILED" } > 25 || changed.size > 25),
            "freshnessVerified" to json(false), "environmentVerified" to json(false), "flakinessProven" to json(false),
            "note" to json("Сохранённый отчёт не доказывает свежий запуск; смена результата не доказывает нестабильность без одинаковых входов и среды."))
    }

    fun sarif(text: String, cancelled: () -> Boolean = { false }): Outcome<JsonObject> {
        if (cancelled()) return Outcome.failure(DiagCode.CANCELLED_BY_USER, "разбор SARIF отменён")
        val o = when (val r = JsonParser(JsonParser.Limits(MAX_TEXT, 48)).parse(text)) {
            is Outcome.Failure -> return r; is Outcome.Success -> r.value as? JsonObject ?: return invalid("нужен объект SARIF")
        }
        if ((o["version"] as? JsonString)?.value != "2.1.0") return invalid("нужен SARIF 2.1.0")
        val runs = (o["runs"] as? JsonArray)?.items ?: return invalid("нет runs SARIF")
        if (runs.size > 50) return invalid("слишком много запусков SARIF")
        val results = arrayListOf<JsonValue>(); var total = 0; var complete = true
        for (value in runs) {
            val run = value as? JsonObject ?: return invalid("неверный run SARIF")
            val tool = (((run["tool"] as? JsonObject)?.get("driver") as? JsonObject)?.get("name") as? JsonString)?.value ?: "unknown"
            val toolVersion = (((run["tool"] as? JsonObject)?.get("driver") as? JsonObject)?.get("version") as? JsonString)?.value
            val findings = (run["results"] as? JsonArray)?.items ?: return invalid("нет results SARIF")
            complete = complete && (run["invocations"] as? JsonArray)?.items?.isNotEmpty() == true &&
                (run["invocations"] as? JsonArray)?.items.orEmpty().all { (it as? JsonObject)?.get("executionSuccessful") == JsonBool(true) }
            for (found in findings) {
                if (cancelled()) return Outcome.failure(DiagCode.CANCELLED_BY_USER, "разбор SARIF отменён")
                val f = found as? JsonObject ?: return invalid("неверная находка SARIF")
                total++
                if (results.size >= 30) continue
                val message = ((f["message"] as? JsonObject)?.get("text") as? JsonString)?.value ?: ""
                val location = (((f["locations"] as? JsonArray)?.items?.firstOrNull() as? JsonObject)?.get("physicalLocation") as? JsonObject)
                val rawPath = ((location?.get("artifactLocation") as? JsonObject)?.get("uri") as? JsonString)?.value
                val path = rawPath?.takeIf { DevelopmentTools.safePath(it) }
                results += JsonObject.of("tool" to json(clean(tool)), "toolVersion" to (toolVersion?.let { json(clean(it)) } ?: JsonNull), "rule" to json(clean((f["ruleId"] as? JsonString)?.value.orEmpty())),
                    "severity" to json(clean((f["level"] as? JsonString)?.value ?: "warning")), "message" to json(clean(message)),
                    "path" to (path?.let { json(it) } ?: JsonNull), "line" to (((location?.get("region") as? JsonObject)?.get("startLine") as? JsonNumber)?.takeIf { (it.toLongExact() ?: 0) in 1..Int.MAX_VALUE } ?: JsonNull))
            }
        }
        return Outcome.success(JsonObject.of("basis" to json("SARIF_2_1_0"), "complete" to json(complete), "findings" to json(total),
            "items" to JsonArray(results), "truncated" to json(total > results.size), "sha256" to json(Hashing.sha256(text).hex),
            "freshnessVerified" to json(false), "note" to json("Доказательства анализатора; отсутствие находок не заменяет полный профиль качества.")))
    }

    fun measurements(current: String, baseline: String): Outcome<JsonObject> {
        fun parse(text: String) = JsonParser(JsonParser.Limits(MAX_TEXT, 32)).parse(text).valueOrNull() as? JsonObject
        val a = parse(current) ?: return invalid("неверный текущий замер")
        val b = parse(baseline) ?: return invalid("неверная база замеров")
        fun valid(o: JsonObject): Boolean = o["format"] == json("dpai.measurements") && o["version"] == json(1) &&
            listOf("commit", "environment", "method").all { (o[it] as? JsonString)?.value?.isNotBlank() == true } &&
            ((o["commit"] as? JsonString)?.value?.matches(Regex("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}")) == true)
        if (!valid(a) || !valid(b) || a["environment"] != b["environment"] || a["method"] != b["method"])
            return invalid("замеры требуют commit, одинаковых environment и method")
        val am = a["metrics"] as? JsonObject ?: return invalid("нет metrics")
        val bm = b["metrics"] as? JsonObject ?: return invalid("нет metrics базы")
        if (am.fields.isEmpty() || am.fields.keys != bm.fields.keys || am.fields.size > 40) return invalid("наборы метрик различаются или слишком велики")
        val rows = arrayListOf<JsonValue>()
        for ((key, value) in am.fields) {
            val av = value as? JsonObject ?: return invalid("неверная метрика")
            val bv = bm[key] as? JsonObject ?: return invalid("неверная базовая метрика")
            val unit = av["unit"] as? JsonString ?: return invalid("нет единицы метрики")
            val x = (av["value"] as? JsonNumber)?.toBigDecimal()
            val y = (bv["value"] as? JsonNumber)?.toBigDecimal()
            if (x == null || y == null || x.precision() > 100 || y.precision() > 100 || x.scale() !in -100..100 || y.scale() !in -100..100 || unit != bv["unit"])
                return invalid("метрики превышают числовой предел или единицы различаются")
            rows += JsonObject.of("name" to json(clean(key)), "unit" to json(clean(unit.value)), "current" to JsonNumber(x.toString()), "baseline" to JsonNumber(y.toString()), "delta" to JsonNumber((x-y).toString()))
        }
        return Outcome.success(JsonObject.of("basis" to json("MEASUREMENTS"), "currentCommit" to a["commit"]!!, "baselineCommit" to b["commit"]!!,
            "environment" to json(clean((a["environment"] as JsonString).value)), "method" to json(clean((a["method"] as JsonString).value)),
            "metrics" to JsonArray(rows), "currentHash" to json(Hashing.sha256(current).hex), "baselineHash" to json(Hashing.sha256(baseline).hex),
            "measurementAuthenticityVerified" to json(false), "note" to json("Сравнение предоставленных чисел; причинность и качество методики автоматически не доказаны.")))
    }

    private fun clean(value: String) = Redactor.STANDARD.redact(value).take(400)
    private fun <T> invalid(message: String): Outcome<T> = Outcome.failure(DiagCode.VALUE_INVALID, message)
    private const val MAX_TEXT = 2 * 1024 * 1024
}
