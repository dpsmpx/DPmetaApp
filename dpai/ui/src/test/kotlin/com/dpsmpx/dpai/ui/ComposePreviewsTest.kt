package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.result.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Предпросмотр Compose на телефоне (D-167): функции `@Preview`, класс-фасад, пакет debug, команда adb. */
class ComposePreviewsTest {
    @Test
    fun findsTopLevelPreviewsAndExplainsSkipped() {
        val text = """
            @file:Suppress("unused")
            package com.example.app.ui

            // Образец текста файла Compose; импорты разбору не нужны.

            @Preview(showBackground = true)
            @Composable
            fun GreetingPreview() {
                Greeting("Android")
            }

            @Preview @Composable private fun DarkPreview() = Greeting("x")

            // @Preview
            @Composable
            fun NotAPreview() {}

            @Preview
            @Composable
            fun Themed(@PreviewParameter(Names::class) name: String) {}

            object Screens {
                @Preview
                @Composable
                fun Inner() {}
            }
        """.trimIndent()
        val found = ComposePreviews.find("app/src/main/java/com/example/app/ui/main-screen.kt", text)
        assertEquals(listOf("GreetingPreview", "DarkPreview"), found.previews.map { it.name })
        assertEquals("com.example.app.ui.Main_screenKt.GreetingPreview", found.previews.first().composable)
        assertEquals(8, found.previews.first().line)
        assertEquals(listOf("Themed", "Inner"), found.skipped.map { it.first })
        // @file:JvmName меняет имя класса-фасада.
        val named = ComposePreviews.find("Ui.kt", "@file:JvmName(\"Screens\")\npackage a.b\n@Preview\n@Composable\nfun P() {}\n")
        assertEquals("a.b.Screens.P", named.previews.single().composable)
        assertEquals("MainKt", ComposePreviews.facadeName("Main.kt"))
    }

    @Test
    fun debugApplicationIdFromGradle() {
        val kts = """
            android {
                defaultConfig {
                    applicationId = "com.example.app"
                }
                buildTypes {
                    getByName("debug") {
                        applicationIdSuffix = ".debug"
                    }
                    release { applicationIdSuffix = ".rel" }
                }
            }
        """.trimIndent()
        assertEquals("com.example.app.debug", ComposePreviews.applicationId(kts))
        assertEquals("org.x.y", ComposePreviews.applicationId("android { defaultConfig { applicationId \"org.x.y\" } }"))
        assertEquals("org.x.y.dev", ComposePreviews.applicationId("applicationId \"org.x.y\"\nbuildTypes { debug { applicationIdSuffix \"dev\" } }"))
        assertNull(ComposePreviews.applicationId("plugins { id(\"com.android.library\") }"))
    }

    @Test
    fun adbPreviewCommandCarriesValuesAsArguments() {
        val argv = (Adb.preview(40001, "com.example.app.debug", "com.example.app.ui.MainKt.GreetingPreview") as Outcome.Success).value
        // Имя — в одинарных кавычках для оболочки телефона: `$` вложенного класса не раскрывается (проверка PR #188).
        assertEquals(listOf("40001", "com.example.app.debug", "'com.example.app.ui.MainKt.GreetingPreview'"), argv.takeLast(3))
        val nested = (Adb.preview(40001, "com.example.app.debug", "com.example.app.ui.Screens\$Inner.Preview") as Outcome.Success).value
        assertEquals("'com.example.app.ui.Screens\$Inner.Preview'", nested.last())
        assertTrue(Adb.PREVIEW_ACTIVITY in argv[2] && "am start -W" in argv[2])
        assertIs<Outcome.Failure>(Adb.preview(40001, "com.example.app", "x; reboot"))
        assertIs<Outcome.Failure>(Adb.preview(40001, "app; reboot", "a.B.c"))
        assertIs<Outcome.Success<Unit>>(Adb.previewResult(listOf("Starting: Intent { cmp=x/androidx.compose.ui.tooling.PreviewActivity }", "Status: ok"), 0))
        val missing = assertIs<Outcome.Failure>(Adb.previewResult(listOf("Error type 3", "Error: Activity class {x/androidx.compose.ui.tooling.PreviewActivity} does not exist."), 0))
        assertTrue("ui-tooling" in missing.diagnostics.first().message)
        assertEquals("40001", Adb.screencap(40001).last())
    }
}
