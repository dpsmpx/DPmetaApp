package com.dpsmpx.dpai.android

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.dpsmpx.dpai.android.linux.LinuxSetup
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.DisplayZone
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.orchestrator.linux.AlpinePackages
import com.dpsmpx.dpai.orchestrator.linux.LinuxEnvironment
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.ui.AlpineView
import com.dpsmpx.dpai.ui.AppComponents
import com.dpsmpx.dpai.ui.EnvSnapshots
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Снимки окружения и откат (D-194; `DPmetaApp:D-171`, шаг 4): каталог `env-snapshots` в
 * данных приложения, последние [EnvSnapshots.KEEP]; снимок — перед каждой командой apk
 * ([LinuxSetup.apk], [LinuxSetup.runSteps]) и сменой настроек; тот же, что последний, — не
 * пишется. Кеш файлов пакетов — по настройке (по умолчанию включён), лишнее в нём убирается.
 */
internal object EnvSnapshotStore {
    private const val DIR = "env-snapshots"
    private const val PREFS = "dpai.environment"
    private const val CACHE_KEY = "apkCache"
    private val lock = Any()

    fun cacheOn(context: Context): Boolean = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(CACHE_KEY, true)

    fun setCache(context: Context, on: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(CACHE_KEY, on) }
        val env = LinuxSetup.environment(context)
        if (on) env.enableCache() else { env.disableCache(); env.dropCached(env.cachedPackages()) }
    }

    private fun vfs(context: Context) = NioVfs(context.applicationContext.filesDir.toPath())

    private fun inventory(context: Context): AlpinePackages.Inventory? =
        (AlpinePackages.read(vfs(context), LinuxSetup.environment(context).rootfs) as? Outcome.Success)?.value

    /**
     * Снять снимок перед правкой [label]; среды нет или состояние то же — ничего. [prune] —
     * убрать лишнее из кеша: только из долгой работы гостя, когда apk не идёт параллельно
     * (иначе ушёл бы файл, который apk только что скачал).
     */
    fun take(context: Context, label: String, prune: Boolean = true) {
        synchronized(lock) {
            val env = LinuxSetup.environment(context)
            if (env.installed() == null) return
            if (cacheOn(context)) env.enableCache()
            val inv = inventory(context) ?: return
            val at = Clock.SYSTEM.now().epochMillis
            val snap = EnvSnapshots.take("env-$at", at, label, inv, OwnVariablesStore.load(context))
            val all = list(context)
            if (all.firstOrNull()?.sameState(snap) == true) return
            val v = vfs(context)
            try {
                v.mkdirs(DIR)
                v.write("$DIR/${snap.id}.json.draft", EnvSnapshots.encode(snap).toByteArray())
                v.rename("$DIR/${snap.id}.json.draft", "$DIR/${snap.id}.json")
                val (keep, drop) = EnvSnapshots.prune(all + snap)
                for (d in drop) v.delete("$DIR/${d.id}.json")
                if (prune) {
                    val cache = env.cachedPackages().mapNotNull(EnvSnapshots::cacheFile)
                    env.dropCached(EnvSnapshots.prunableCache(keep, inv, cache).map { it.file })
                }
            } catch (e: IOException) {
                AppLogs.info(context.applicationContext, "окружение", "снимок не записан: ${e.message}")
            }
        }
    }

    fun list(context: Context): List<EnvSnapshots.Snapshot> {
        val v = vfs(context)
        return v.list(DIR).filter { it.endsWith(".json") }.mapNotNull { f ->
            v.read("$DIR/$f")?.toString(Charsets.UTF_8)?.let { (EnvSnapshots.decode(it) as? Outcome.Success)?.value }
        }.sortedByDescending { it.at }
    }

    fun cacheBytes(context: Context): Long? =
        AppComponents.size(File(File(context.filesDir, LinuxSetup.environment(context).rootfs), LinuxEnvironment.CACHE_DIR.removePrefix("/")).toPath()).first

    fun plan(context: Context, s: EnvSnapshots.Snapshot): EnvSnapshots.Plan? {
        val inv = inventory(context) ?: return null
        val cache = LinuxSetup.environment(context).cachedPackages().mapNotNull(EnvSnapshots::cacheFile)
        return EnvSnapshots.plan(s, inv, OwnVariablesStore.load(context), cache)
    }

    /**
     * Откат к [s] одной долгой работой (порядок сверен с apk-tools 3 в госте): снимок нынешнего;
     * репозитории и свои переменные снимка; `world` снимка и `apk fix`; точные версии — файлами
     * кеша, остальные — `имя=версия` из зеркала по одной, чего нет нигде — к версии ветки снимка;
     * снова `world` (снять закрепления) и
     * `apk fix`; итог сверяется со снимком.
     */
    fun rollback(context: Context, jobs: LongJobs, s: EnvSnapshots.Snapshot) {
        val label = "Откат к снимку ${time(s.at)}"
        jobs.start(LongJobs.LINUX_APK, label, excludes = LongJobs.GUEST) {
            take(context, "Перед откатом к снимку ${time(s.at)}")
            val env = LinuxSetup.environment(context)
            val out = mutableListOf<String>()
            val notes = mutableListOf<String>()
            fun apk(r: Outcome<com.dpsmpx.dpai.execution.process.Completed>, what: String) {
                when (r) {
                    is Outcome.Success -> {
                        out += (r.value.stdout + r.value.stderr).takeLast(10)
                        if (!r.value.ok) notes += "$what: код ${r.value.exitCode}"
                    }
                    is Outcome.Failure -> notes += "$what: ${r.errors.first().message}"
                }
            }
            val plan = plan(context, s) ?: return@start LongJobs.Done("$label: пакеты гостя не прочитаны", failed = true)
            if (plan.repositories) (env.writeRepositories(s.repositories.joinToString("") { "$it\n" }) as? Outcome.Failure)?.let { notes += "репозитории: ${it.errors.first().message}" }
            if (plan.vars) (OwnVariablesStore.save(context, s.vars) as? Outcome.Failure)?.let { notes += "переменные: ${it.errors.first().message}" }
            (env.writeWorld(s.world) as? Outcome.Failure)?.let { return@start LongJobs.Done("$label: ${it.errors.first().message}", failed = true) }
            apk(LinuxSetup.apk(context, LinuxEnvironment.Apk.APPLY_WORLD, emptyList(), snapshot = false), "список пакетов")
            val cached = plan.versions.mapNotNull { it.cached }
            if (cached.isNotEmpty()) {
                when (val spec = LinuxEnvironment.apkAddCached(cached.take(LinuxEnvironment.MAX_PACKAGES))) {
                    is Outcome.Success -> apk(LinuxSetup.runSteps(context, listOf(spec.value), snapshot = false), "версии из кеша")
                    is Outcome.Failure -> notes += "версии из кеша: ${spec.errors.first().message}"
                }
            }
            for (v in plan.versions.filter { it.cached == null }.take(LinuxEnvironment.MAX_PACKAGES)) {
                when (val spec = LinuxEnvironment.apkPin(v.name, v.want)) {
                    is Outcome.Success -> apk(LinuxSetup.runSteps(context, listOf(spec.value), snapshot = false), "${v.name} ${v.want}")
                    is Outcome.Failure -> notes += "${v.name}: ${spec.errors.first().message}"
                }
            }
            // Точной версии нет ни в кеше, ни в зеркале — к ближайшей версии ветки снимка (итог скажет, какой).
            val still = inventory(context)?.packages?.filter { p -> s.packages[p.name]?.let { it != p.version } == true }?.map { it.name }.orEmpty()
            if (still.isNotEmpty()) apk(LinuxSetup.apk(context, LinuxEnvironment.Apk.UPGRADE_AVAILABLE_SOME, still.take(LinuxEnvironment.MAX_PACKAGES), snapshot = false), "к ветке снимка")
            env.writeWorld(s.world)
            apk(LinuxSetup.apk(context, LinuxEnvironment.Apk.APPLY_WORLD, emptyList(), snapshot = false), "список пакетов")
            val left = inventory(context)?.let { EnvSnapshots.leftover(s, it, OwnVariablesStore.load(context)) } ?: listOf("пакеты гостя не прочитаны")
            val text = if (left.isEmpty()) "$label: вернулось всё" else "$label: не вернулось — ${left.take(12).joinToString("; ")}" + if (left.size > 12) " и ещё ${left.size - 12}" else ""
            LongJobs.Done(text, (out + notes).takeLast(40), failed = left.isNotEmpty())
        }
    }

    fun time(at: Long): String = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(DisplayZone.current).format(Instant.ofEpochMilli(at))
}

/** Раздел «Снимки» экрана «Окружение». */
@Composable
internal fun EnvironmentSnapshotsSection(running: Boolean, onRollback: (EnvSnapshots.Snapshot) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var snaps by remember { mutableStateOf<List<EnvSnapshots.Snapshot>>(emptyList()) }
    var cacheOn by remember { mutableStateOf(true) }
    var cacheBytes by remember { mutableStateOf<Long?>(null) }
    var asking by remember { mutableStateOf<Pair<EnvSnapshots.Snapshot, EnvSnapshots.Plan>?>(null) }
    LaunchedEffect(version, running) {
        if (running) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            snaps = EnvSnapshotStore.list(context)
            cacheOn = EnvSnapshotStore.cacheOn(context)
            cacheBytes = EnvSnapshotStore.cacheBytes(context)
        }
    }
    asking?.let { (s, p) ->
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text("Откатить к снимку ${EnvSnapshotStore.time(s.at)}?") },
            text = { Text(EnvSnapshots.planText(p)) },
            confirmButton = { if (!p.empty) TextButton(onClick = { asking = null; onRollback(s) }) { Text("Откатить") } },
            dismissButton = { TextButton(onClick = { asking = null }) { Text(if (p.empty) "Закрыть" else "Отмена") } },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Снимки окружения", style = MaterialTheme.typography.titleSmall)
        Text(
            "Снимок — перед каждой командой apk и сменой настроек: ваши пакеты, версии, репозитории, свои переменные. Хранятся последние ${EnvSnapshots.KEEP}.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Хранить файлы пакетов для отката", style = MaterialTheme.typography.bodyMedium)
                Text("Кеш apk: ${AlpineView.size(cacheBytes)}. Без него старая версия вернётся, только если зеркало её ещё даёт.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(cacheOn, enabled = !running, onCheckedChange = { on ->
                scope.launch {
                    withContext(Dispatchers.IO) { EnvSnapshotStore.setCache(context, on) }
                    version++
                }
            })
        }
        HorizontalDivider()
        if (snaps.isEmpty()) Text("Снимков пока нет: первый появится перед первой командой apk.", style = MaterialTheme.typography.bodySmall)
        for (s in snaps) {
            Column(Modifier.fillMaxWidth().clickable(enabled = !running) {
                scope.launch {
                    val p = withContext(Dispatchers.IO) { EnvSnapshotStore.plan(context, s) }
                    if (p != null) asking = s to p
                }
            }.padding(vertical = 4.dp)) {
                Text("${EnvSnapshotStore.time(s.at)} — перед «${s.label}»", style = MaterialTheme.typography.bodyMedium)
                Text("пакетов ${s.packages.size}, ваших ${s.world.size}, переменных ${s.vars.size}", style = MaterialTheme.typography.labelSmall)
            }
            HorizontalDivider()
        }
    }
}
