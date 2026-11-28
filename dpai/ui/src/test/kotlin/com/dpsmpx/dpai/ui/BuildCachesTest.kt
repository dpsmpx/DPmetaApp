package com.dpsmpx.dpai.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class BuildCachesTest {
    @Test fun independentNativeAndAlpineRunsSharePersistentDefaults() {
        val first = BuildCaches.environment(mapOf("JAVA_HOME" to AndroidNativeJdk.JAVA_HOME)) { false }
        val next = BuildCaches.environment(mapOf("JAVA_HOME" to "/usr/lib/jvm/default-jvm")) { false }
        assertEquals(first["GRADLE_USER_HOME"], next["GRADLE_USER_HOME"])
        assertEquals(BuildCaches.GRADLE, first["GRADLE_USER_HOME"])
        assertEquals("/root/.cache/pip", first["PIP_CACHE_DIR"])
    }

    @Test fun priorNativeWrapperIsUsedInPlaceAndExplicitSettingsWin() {
        val legacy = BuildCaches.environment(emptyMap()) { it == "${BuildCaches.LEGACY_GRADLE}/wrapper/dists" }
        assertEquals(BuildCaches.LEGACY_GRADLE, legacy["GRADLE_USER_HOME"])
        val own = mapOf("GRADLE_USER_HOME" to "/own/gradle", "PIP_CACHE_DIR" to "/own/pip", "npm_config_cache" to "/own/npm")
        val picked = BuildCaches.environment(own) { true }
        own.forEach { (k, v) -> assertEquals(v, picked[k]) }
        val upper = BuildCaches.environment(mapOf("NPM_CONFIG_CACHE" to "/upper/npm", "XDG_CACHE_HOME" to "/own/cache")) { false }
        assertEquals("/upper/npm", upper["npm_config_cache"])
        assertEquals("/own/cache/pip", upper["PIP_CACHE_DIR"])
    }
}
