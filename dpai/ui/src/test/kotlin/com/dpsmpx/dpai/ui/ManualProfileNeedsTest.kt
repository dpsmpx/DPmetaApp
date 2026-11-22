package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Что профилю нужно на target (D-186; `DPmetaApp:D-162`): формат v4, проверка SDK в госте, ANDROID_HOME. */
class ManualProfileNeedsTest {
    private fun profile(needs: Set<ManualNeed>, env: String = "") =
        (ManualProfile.form(ManualProfileKind.BUILD, listOf(Triple("Сборка", "./gradlew assembleDebug", "1800")), emptyList(), "", env, needs) as Outcome.Success).value

    private fun JsonObject.with(k: String, v: com.dpsmpx.dpai.core.serialization.JsonValue) = JsonObject(LinkedHashMap(fields).apply { put(k, v) })

    @Test
    fun needsAreFormatV4OnlyWithNeeds() {
        val p = profile(setOf(ManualNeed.ANDROID_NDK, ManualNeed.ANDROID_SDK))
        val json = p.toJson()
        assertEquals(4L, (json.fields["version"] as JsonNumber).toLongExact())
        assertEquals(listOf("android-sdk", "android-ndk"), (json.fields["needs"] as JsonArray).items.map { (it as JsonString).value }, "порядок — как в перечне")
        assertEquals(p, ManualProfile.read(json))
        // Без needs — прежние версии: старое приложение читает профиль как раньше.
        assertEquals(2L, (profile(emptySet()).toJson().fields["version"] as JsonNumber).toLongExact())
        assertEquals(3L, (profile(emptySet(), "A=1").toJson().fields["version"] as JsonNumber).toLongExact())
        // needs в v3, пустой v4 и неизвестный код — отказ, а не «похожее».
        assertNull(ManualProfile.read(json.with("version", JsonNumber.of(3))))
        assertNull(ManualProfile.read(json.with("needs", JsonArray(emptyList()))))
        assertNull(ManualProfile.read(json.with("needs", JsonArray(listOf(JsonString("android-sdk"), JsonString("cuda"))))))
        // Раскрытие параметров needs не теряет.
        assertEquals(p.needs, (p.resolve(emptyMap()) as Outcome.Success).value.needs)
    }

    @Test
    fun missingInGuestAndAndroidHome() {
        val sdk = setOf(AndroidSdk.HOME + "/platforms/${AndroidSdk.PLATFORM.substringAfter(';')}", AndroidSdk.HOME + "/build-tools/36.1.0")
        assertEquals(emptyList(), AndroidSdk.missing(emptySet(), { false }, false))
        assertEquals(listOf(AndroidSdk.PLATFORM, AndroidSdk.BUILD_TOOLS), AndroidSdk.missing(setOf(ManualNeed.ANDROID_SDK), { false }, false))
        assertEquals(emptyList(), AndroidSdk.missing(setOf(ManualNeed.ANDROID_SDK), { it in sdk }, false))
        assertEquals(listOf("NDK"), AndroidSdk.missing(setOf(ManualNeed.ANDROID_NDK), { it in sdk }, false), "NDK — вместе с SDK")
        assertEquals(emptyList(), AndroidSdk.missing(setOf(ManualNeed.ANDROID_NDK), { it in sdk }, true))
        assertTrue("Android SDK…" in AndroidSdk.hint(listOf("NDK")))
        // ANDROID_HOME — сам, если профиль не задал свой.
        assertEquals(listOf("ANDROID_HOME" to AndroidSdk.HOME, "ANDROID_SDK_ROOT" to AndroidSdk.HOME), AndroidSdk.env(setOf(ManualNeed.ANDROID_SDK), emptyList()))
        assertEquals(listOf("ANDROID_HOME" to "/opt/sdk", "ANDROID_SDK_ROOT" to AndroidSdk.HOME), AndroidSdk.env(setOf(ManualNeed.ANDROID_SDK), listOf("ANDROID_HOME" to "/opt/sdk")))
        assertEquals(listOf("A" to "1"), AndroidSdk.env(emptySet(), listOf("A" to "1")))
    }
}
