package com.dpsmpx.dppass.config

import com.dpsmpx.dppass.core.Json
import com.dpsmpx.dppass.core.JsonObject
import com.dpsmpx.dppass.diagnostics.Outcome

/** Explicit configuration migration for DPpass 0.5.0. */
object ConfigMigrations {
    val ONE_TO_TWO = ConfigMigration { document ->
        val routing = document["routing"] as? JsonObject ?: Json.obj(
            "default" to Json.str("proxy"),
            "domain_resolution" to Json.str("never"),
            "bypass_lan" to Json.bool(true),
            "apps" to Json.obj("mode" to Json.str("all")),
            "rules" to Json.arr(emptyList()),
            "automatic_domestic" to Json.bool(true),
        )
        val newRouting = if (routing["automatic_domestic"] == null) {
            JsonObject(routing.fields + ("automatic_domestic" to Json.bool(true)))
        } else {
            routing
        }
        Outcome.Success(JsonObject(document.fields + ("format_version" to Json.num(2)) + ("routing" to newRouting)))
    }
}
