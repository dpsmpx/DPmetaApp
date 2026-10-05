package com.dpsmpx.dppass.config

import com.dpsmpx.dppass.core.Json
import com.dpsmpx.dppass.core.JsonObject
import com.dpsmpx.dppass.diagnostics.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigV2Test {
    @Test
    fun v1MigrationEnablesAutomaticDomesticSplitAndAdvancesVersion() {
        val source = Json.obj(
            "format" to Json.str(ConfigFormat.MARKER),
            "format_version" to Json.num(1),
            "routing" to Json.obj(
                "default" to Json.str("proxy"),
                "domain_resolution" to Json.str("never"),
                "bypass_lan" to Json.bool(true),
                "apps" to Json.obj("mode" to Json.str("all")),
                "rules" to Json.arr(emptyList()),
            ),
        )
        val migrated = ConfigMigrations.ONE_TO_TWO.migrate(source)
        assertTrue(migrated is Outcome.Success)
        val doc = (migrated as Outcome.Success<JsonObject>).value
        assertEquals(2, (doc["format_version"] as com.dpsmpx.dppass.core.JsonNumber).toIntOrNull())
        assertEquals(true, (doc["routing"] as JsonObject)["automatic_domestic"]?.let {
            (it as com.dpsmpx.dppass.core.JsonBool).value
        })
    }

    @Test
    fun loaderAcceptsOldDocumentThroughExplicitMigration() {
        val source = """
            {
              "format": "dppass.config",
              "format_version": 1,
              "profiles": [{
                "id": "p1",
                "name": "p1",
                "server": "example.com",
                "port": 443,
                "protocol": {"type": "vless", "id": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"},
                "transport": {"type": "tcp"},
                "security": {"type": "tls", "server_name": "example.com"}
              }],
              "routing": {
                "default": "proxy",
                "domain_resolution": "never",
                "bypass_lan": true,
                "apps": {"mode": "all"},
                "rules": []
              },
              "dns": {
                "servers": [{"id": "d1", "type": "https", "url": "https://example.com/dns", "route": "tunnel"}],
                "default": "d1",
                "rules": [],
                "strategy": "prefer_ipv4",
                "leak_protection": true
              }
            }
        """.trimIndent()
        val result = ConfigLoader().load(source)
        assertTrue(result is Outcome.Success)
        assertTrue(result.value.ir.routing.automaticDomestic)
    }
}
