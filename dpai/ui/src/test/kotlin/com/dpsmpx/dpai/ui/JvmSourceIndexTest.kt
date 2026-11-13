package com.dpsmpx.dpai.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Исходники для отладки APK (D-161): пакет из текста, файл класса — по пакету и имени. */
class JvmSourceIndexTest {
    @Test
    fun packageLineSkipsHeaderCommentsAndAnnotations() {
        assertEquals("com.example.app", JvmSourceIndex.packageLine("/*\n * Лицензия: class Foo\n */\n@file:JvmName(\"Util\")\n\npackage com.example.app\n\nimport a.b\n"))
        assertEquals("com.example.app", JvmSourceIndex.packageLine("// заголовок\npackage com.example.app;\npublic class A {}\n"))
        assertEquals("", JvmSourceIndex.packageLine("import x.y\nfun main() {}\npackage late\n"))
        assertEquals("", JvmSourceIndex.packageLine("class Main\n"))
    }

    @Test
    fun findPrefersFolderByPackageThenAnyFileWithThatPackage() {
        val texts = mapOf(
            "/root/p/app/src/main/java/com/ex/app/MainActivity.kt" to "package com.ex.app\nclass MainActivity",
            "/root/p/lib/src/main/java/com/ex/lib/MainActivity.kt" to "package com.ex.lib\nclass MainActivity",
            "/root/p/app/src/main/kotlin/Util.kt" to "package com.ex.app.util\nfun f() = 1",
            "/root/p/app/src/main/kotlin/Broken.kt" to null,
        )
        val index = JvmSourceIndex(texts.keys.toList()) { texts[it] }
        assertEquals("/root/p/app/src/main/java/com/ex/app/MainActivity.kt", index.find("com.ex.app", "MainActivity.kt"))
        assertEquals("/root/p/lib/src/main/java/com/ex/lib/MainActivity.kt", index.find("com.ex.lib", "MainActivity.kt"))
        // Папки не совпадают с пакетом — файл находится по строке `package`.
        assertEquals("/root/p/app/src/main/kotlin/Util.kt", index.find("com.ex.app.util", "Util.kt"))
        assertNull(index.find("com.other", "MainActivity.kt"))
        assertNull(index.packageOf("/root/p/app/src/main/kotlin/Broken.kt"))
        assertEquals("com.ex.app", index.packageOf("/root/p/app/src/main/java/com/ex/app/MainActivity.kt"))
    }
}
