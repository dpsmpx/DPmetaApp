package com.dpsmpx.dpai.android

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpai.android.linux.LinuxSetup
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.orchestrator.linux.AlpinePackages
import com.dpsmpx.dpai.orchestrator.linux.ApkIndex
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.ui.AndroidSdk
import com.dpsmpx.dpai.ui.ManualProfileKind
import com.dpsmpx.dpai.ui.ProjectNeeds
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Проекты в Linux приложения для экрана «Окружение» (D-195; `DPmetaApp:D-171`, шаг 5): что
 * нужно каждому проекту на target «в приложении» — по файлам корня и шагам его ручных
 * профилей, SDK — по «Нужно» (как перед запуском профиля, D-186). Файлы корня — с хоста,
 * без перехода по ссылкам.
 */
internal object EnvironmentProjects {
    fun checks(context: Context, controller: Controller?, model: DemoModel?, inv: AlpinePackages.Inventory?): List<ProjectNeeds.Check> {
        if (controller == null || inv == null) return emptyList()
        val app = context.applicationContext
        val inApp = controller.targets().filter { it.inApp }.map { it.id }.toSet()
        val rootfs = File(app.filesDir, LinuxSetup.environment(app).rootfs)
        val vfs = NioVfs(app.filesDir.toPath())
        val envRoot = LinuxSetup.environment(app).rootfs
        val index = ApkIndex.arch(vfs, envRoot)?.let { ApkIndex.read(vfs, envRoot, inv.repositories, it).first }
        return controller.projects().projects.filter { it.target in inApp }.map { p ->
            val dir = rootfs.toPath().resolve(p.root.removePrefix("/"))
            val files = if (Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) runCatching { Files.list(dir).use { s -> s.iterator().asSequence().map { it.fileName.toString() }.toSet() } }.getOrDefault(emptySet()) else emptySet()
            val profiles = ManualProfileKind.entries.mapNotNull { k -> (model?.manualProfile(p, k) as? Outcome.Success)?.value?.let { k to it } }
            val wanted = ProjectNeeds.fromFiles(files) + profiles.flatMap { (k, pr) -> ProjectNeeds.fromProfile(k, pr) }
            val needs = profiles.flatMap { it.second.needs }.toSet()
            val guest = { path: String -> File(rootfs, path.removePrefix("/")) }
            val sdk = if (needs.isEmpty()) emptyList() else AndroidSdk.missing(needs, { guest(it).exists() }, guest(AndroidSdk.HOME + "/ndk").list()?.isNotEmpty() == true)
            ProjectNeeds.check(p.root.removePrefix("/root/projects/").ifEmpty { p.project.value }, wanted, inv, index, sdk)
        }
    }
}

/** Раздел «Проекты» экрана «Окружение». */
@Composable
internal fun EnvironmentProjectsSection(checks: List<ProjectNeeds.Check>, running: Boolean, onInstall: (List<String>, String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Проекты в Linux приложения", style = MaterialTheme.typography.titleSmall)
        Text("Что нужно проекту — по файлам его корня (CMake, Gradle, npm, Python, Cargo, Go) и шагам ручных профилей; Android SDK — по «Нужно» профиля.", style = MaterialTheme.typography.bodySmall)
        if (checks.isEmpty()) Text("Проектов на target «в приложении» нет.", style = MaterialTheme.typography.bodySmall)
        for (c in checks) {
            Text(c.project, style = MaterialTheme.typography.bodyMedium)
            DiagnosticText(ProjectNeeds.text(c), style = MaterialTheme.typography.bodySmall, color = if (c.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
            for (n in c.needs.filter { it.found == null }) Text("• ${n.command} — ${n.why}", style = MaterialTheme.typography.labelSmall)
            val packages = ProjectNeeds.installs(c)
            if (packages.isNotEmpty()) TextButton(enabled = !running, onClick = { onInstall(packages, "Для ${c.project}: поставить ${packages.joinToString(" ")}") }) { Text("Поставить: ${packages.joinToString(" ")}") }
            if (c.sdk.isNotEmpty()) Text("SDK ставится в «Linux в приложении» → «Android SDK…».", style = MaterialTheme.typography.labelSmall)
            HorizontalDivider()
        }
    }
}
