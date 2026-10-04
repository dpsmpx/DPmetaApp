package com.dpsmpx.dppass.cli

import com.dpsmpx.dppass.config.ConfigLoader
import com.dpsmpx.dppass.config.ConfigWriter
import com.dpsmpx.dppass.core.ProfileId
import com.dpsmpx.dppass.engine.CompilePlan
import com.dpsmpx.dppass.engine.mock.MockBackendCompiler
import com.dpsmpx.dppass.routing.RouteEvaluator
import com.dpsmpx.dppass.routing.RouteQuery

/**
 * Замеры бюджета производительности (`docs/PERFORMANCE_BUDGET.md`) на
 * хосте: разбор и проверка документа, компиляция, решения
 * маршрутизации. Это ориентир, а не порог hostcheck: время на хосте и на
 * телефоне различается в разы; пороги на устройстве — этап 3.
 */
object Bench {
    fun run(rules: Int): Int {
        val text = syntheticConfig(rules)
        val loader = ConfigLoader()
        repeat(3) { loader.load(text) } // прогрев JIT
        val t0 = System.nanoTime()
        val loaded = loader.load(text).valueOrNull() ?: run {
            println("синтетическая конфигурация не прошла проверку")
            return 1
        }
        val t1 = System.nanoTime()
        val compiled = MockBackendCompiler().compile(loaded.ir, CompilePlan(ProfileId("p1"), true))
        val t2 = System.nanoTime()
        val evaluator = RouteEvaluator(loaded.ir.routing)
        val queries = 20_000
        var direct = 0
        for (i in 0 until queries) {
            if (evaluator.decide(RouteQuery(domain = "host$i.example.org")).action.name == "DIRECT") direct++
        }
        val t3 = System.nanoTime()
        println("document: ${text.length / 1024} KiB, rules: $rules")
        println("load+normalize+validate: ${(t1 - t0) / 1_000_000} ms")
        println("compile (mock): ${(t2 - t1) / 1_000_000} ms, ok=${compiled.isSuccess}")
        println("route decisions: $queries in ${(t3 - t2) / 1_000_000} ms (${(t3 - t2) / queries} ns/decision), direct=$direct")
        println("export (redacted): ${ConfigWriter.write(loaded.ir).length / 1024} KiB")
        return 0
    }

    fun syntheticConfig(rules: Int): String {
        val sb = StringBuilder()
        sb.append("""{"format":"dppass.config","format_version":1,"name":"bench","profiles":[""")
        sb.append(
            """{"id":"p1","name":"p1","server":"203.0.113.1","port":443,"protocol":{"type":"trojan","password":"bench-password"},""" +
                """"security":{"type":"tls","server_name":"bench.example.com"}}""",
        )
        sb.append("""],"routing":{"rules":[""")
        for (i in 0 until rules) {
            if (i > 0) sb.append(',')
            sb.append("""{"id":"r$i","action":"${if (i % 3 == 0) "direct" else "proxy"}","domain_suffix":["d$i.example.net","e$i.example.net"]}""")
        }
        sb.append("""]},"dns":{"servers":[{"id":"remote","type":"https","url":"https://dns.example.net/dns-query","route":"tunnel"}],"default":"remote"}}""")
        return sb.toString()
    }
}
