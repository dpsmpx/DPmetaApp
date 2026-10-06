package com.dpsmpx.dppass.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

/**
 * Уведомления о сторонних компонентах для экрана «О программе» (D-43):
 * `tools/legal/notices.py` собирает `notices/NOTICES.txt` из матрицы
 * лицензий и файлов лицензий модулей Go тех версий, что в сборке. Результат
 * берёт `sources.assets` варианта; что каждый компонент APK из матрицы в
 * нём есть, проверяет `check_apk.py`.
 *
 * Без Go задача падает: без текстов лицензий модулей ядра уведомления
 * были бы неполными, а APK с ними — с нарушенными условиями лицензий.
 */
abstract class LegalNoticesTask : DefaultTask() {
    /** Матрица, скрипт и `go.mod`/`go.sum`: изменение любого — пересборка. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val script: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun build() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        exec.exec {
            commandLine("python3", script.get().asFile.path, "--out", out.path)
        }
    }
}
