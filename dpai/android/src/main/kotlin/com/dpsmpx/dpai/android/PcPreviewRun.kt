package com.dpsmpx.dpai.android

import android.graphics.BitmapFactory
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.run
import com.dpsmpx.dpai.files.FileNames
import com.dpsmpx.dpai.files.FileTree
import com.dpsmpx.dpai.jsch.SftpTree
import com.dpsmpx.dpai.ui.PcPreview
import com.dpsmpx.dpai.ui.TargetEntry
import kotlin.time.Duration.Companion.minutes

/** Дерево файлов открыто на ПК: [target] и корень дерева [root] — путь на ПК (D-166). */
internal data class PcPreviewPlace(val model: DemoModel, val target: TargetEntry, val root: String)

/** Задаётся экраном файлов ПК: панель предпросмотра предлагает «Нарисовать на ПК». */
internal val LocalPcPreview = staticCompositionLocalOf<PcPreviewPlace?> { null }

/**
 * Предпросмотр на ПК (`DPmetaApp:D-132`; D-169): init-скрипт и тест — в домашний
 * каталог ПК по SFTP, Gradle wrapper проекта — по SSH, PNG — обратно по SFTP.
 * Файлы проекта не меняются. Звать не из главного потока.
 */
internal object PcPreviewRun {
    /** Нарисованное: [image] — `null`, если не нарисовано (причина — в [PcPreview.Rendered.error]). */
    data class Shot(val rendered: PcPreview.Rendered, val image: ImageBitmap?)

    /** Итог: картинки и хвост вывода Gradle (для отказа и для «почему не нарисовано»). */
    data class Result(val shots: List<Shot>, val log: String)

    fun run(place: PcPreviewPlace, tree: FileTree, path: String, compose: List<String>, layouts: List<String>, step: (String) -> Unit): Outcome<Result> {
        // Есть ли файл — по списку его каталога (список — один раз на каталог).
        val listed = HashMap<String, Set<String>>()
        val project = PcPreview.locate(path) { file ->
            val names = listed.getOrPut(FileNames.parent(file)) {
                ((tree.list(FileNames.parent(file)) as? Outcome.Success)?.value ?: emptyList()).filter { !it.dir }.map { it.name }.toSet()
            }
            FileNames.name(file) in names
        }
            ?: return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "не найден проект Gradle: нет build.gradle(.kts) модуля или settings.gradle(.kts) выше файла", path)
        val platform = place.target.platform
        step("Кладу init-скрипт и тест на ПК…")
        val home = when (val h = withSftp(place) { sftp ->
            val raw = when (val r = sftp.home()) {
                is Outcome.Failure -> return@withSftp r
                is Outcome.Success -> r.value
            }
            for ((rel, text) in listOf(PcPreview.INIT to PcPreview.initScript(), PcPreview.TEST to PcPreview.testSource(), "${PcPreview.OUT}/${PcPreview.INDEX}" to "")) {
                val w = sftp.write("${PcPreview.DIR}/$rel", text.toByteArray(Charsets.UTF_8))
                if (w is Outcome.Failure) return@withSftp w
            }
            Outcome.success(PcPreview.localPath(platform, raw).trimEnd('/'))
        }) {
            is Outcome.Failure -> return h
            is Outcome.Success -> h.value
        }
        val dir = "$home/${PcPreview.DIR}"
        val argv = when (val a = PcPreview.command(
            platform, PcPreview.pcDir(platform, place.root, home, project.project), project.module,
            "$dir/${PcPreview.INIT}", "$dir/${PcPreview.SRC}", "$dir/${PcPreview.OUT}", compose, layouts,
        )) {
            is Outcome.Failure -> return a
            is Outcome.Success -> a.value
        }
        val host = when (val h = place.model.pcHost(place.target)) {
            is Outcome.Failure -> return h
            is Outcome.Success -> h.value
        }
        step("Gradle рисует на ПК (${project.module}); первый раз — дольше: Robolectric скачивает образ Android…")
        val done = when (val r = host.run(ProcessSpec(argv), TIMEOUT, maxStdoutChars = 32_768)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        val log = (done.stdout + done.stderr).joinToString("\n").takeLast(LOG_TAIL)
        step("Забираю картинки…")
        return withSftp(place) { sftp ->
            val index = (sftp.read("${PcPreview.DIR}/${PcPreview.OUT}/${PcPreview.INDEX}", 1L shl 20) as? Outcome.Success)?.value?.toString(Charsets.UTF_8).orEmpty()
            val rows = PcPreview.parseIndex(index)
            if (rows.isEmpty()) {
                return@withSftp Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "Gradle не нарисовал (код ${done.exitCode}). ${log.ifBlank { "Вывода нет." }}", project.module)
            }
            Outcome.success(Result(rows.map { row -> Shot(row, row.file?.let { image(sftp, it) }) }, log))
        }
    }

    private fun image(sftp: SftpTree, file: String): ImageBitmap? {
        val bytes = (sftp.read("${PcPreview.DIR}/${PcPreview.OUT}/$file", MAX_PNG) as? Outcome.Success)?.value ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }

    /** Отдельное подключение SFTP на шаг: Gradle идёт минутами, держать канал всё это время незачем. */
    private fun <T> withSftp(place: PcPreviewPlace, block: (SftpTree) -> Outcome<T>): Outcome<T> =
        when (val s = place.model.pcFiles(place.target, "", writable = true)) {
            is Outcome.Failure -> s
            is Outcome.Success -> s.value.use(block)
        }

    private val TIMEOUT = 20.minutes
    private const val LOG_TAIL = 2_000
    private const val MAX_PNG = 16L shl 20
}
