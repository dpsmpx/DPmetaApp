package com.dpsmpx.dpai.android

import android.content.Context
import androidx.core.content.edit
import com.dpsmpx.dpai.android.linux.LinuxSetup
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.orchestrator.linux.LinuxEnvironment
import com.dpsmpx.dpai.ui.BrokenPackage
import com.dpsmpx.dpai.ui.Health
import com.dpsmpx.dpai.ui.HealthFacts
import com.dpsmpx.dpai.ui.LinuxFacts
import com.dpsmpx.dpai.ui.TickFacts
import java.io.File
import java.nio.file.Files

/**
 * Факты для проверки приложения (DPmetaApp:D-123, D-158): с хоста, без процессов
 * гостя — файлы корневой системы, секреты, Termux, замер такта, место. Вызывать
 * не из потока экрана: Termux проверяется подключением к порту.
 */
object HealthProbe {
    /** Больше — не `~/.gitconfig` человека; читать не нужно. */
    private const val MAX_CONFIG = 1 shl 20

    fun collect(context: Context, model: DemoModel? = null): HealthFacts {
        val app = context.applicationContext
        val linux = if (!LinuxSetup.available(app)) null else linuxFacts(app)
        val github = runCatching { SecretGrants.rows(app).firstOrNull { it.variable == GITHUB_VARIABLE } }.getOrNull()
        val o = model?.liveOrchestrator()
        val termux = runCatching { TermuxLauncher.state(app, Controller.get(app.filesDir).targets()) }.getOrNull()
        val stats = ControllerService.lastStats?.takeIf { ControllerService.running }
        return HealthFacts(
            linux = linux,
            slots = o?.accounts?.slots?.size ?: 0,
            loggedIn = o?.accounts?.slots?.values?.any { it.login == com.dpsmpx.dpai.accounts.LoginStatus.OK } == true,
            githubToken = github?.present,
            githubForAi = github?.granted,
            termux = termux?.first,
            termuxPort = termux?.second ?: 8022,
            tick = stats?.let { TickFacts(it.maxMs, it.avgMs, it.slowest?.first, it.slowest?.second) },
            freeBytes = runCatching { android.os.StatFs(app.filesDir.path).availableBytes }.getOrNull(),
        )
    }

    private fun linuxFacts(context: Context): LinuxFacts {
        val env = LinuxSetup.environment(context)
        if (env.installed() == null) return LinuxFacts(installed = false)
        val rootfs = File(context.filesDir, env.rootfs)
        val broken = env.brokenPackages().valueOrNull().orEmpty().map { BrokenPackage(it.name, it.why) }
        val gitconfig = File(rootfs, "etc/gitconfig")
        val foreign = Files.isSymbolicLink(gitconfig.toPath()) ||
            (gitconfig.isFile && !small(gitconfig).orEmpty().startsWith(LinuxEnvironment.GIT_MARK))
        val identity = listOf(File(rootfs, "root/.gitconfig"), File(rootfs, "root/.config/git/config"))
            .any { f -> small(f)?.let(Health::gitIdentity) == true }
        return LinuxFacts(
            installed = true,
            broken = broken,
            repairFailure = HealthAuto.lastFailure.takeIf { broken.isNotEmpty() },
            git = File(rootfs, "usr/bin/git").exists(),
            gitHelper = env.gitConfigured(),
            gitConfigForeign = foreign,
            gitIdentity = identity,
            claude = File(rootfs, "usr/local/bin/claude").exists() || File(rootfs, "usr/bin/claude").exists(),
            dns = small(File(rootfs, "etc/resolv.conf"))?.lineSequence()?.any { it.trim().startsWith("nameserver ") } == true,
        )
    }

    /** Небольшой файл гостя как текст; ссылка гостя, нет файла или больше [MAX_CONFIG] — `null`. */
    private fun small(f: File): String? {
        if (Files.isSymbolicLink(f.toPath()) || !f.isFile || f.length() > MAX_CONFIG) return null
        return runCatching { f.readText() }.getOrNull()
    }

    /** Переменная токена GitHub для ИИ (`DPmetaApp:D-114`). */
    private const val GITHUB_VARIABLE = "GH_TOKEN"
}

/**
 * Исправления без нажатия (DPmetaApp:D-123, D-158) и их история: помощник входа Git
 * в `/etc/gitconfig` гостя, починка повреждённых пакетов Alpine (`apk fix`, долгой
 * операцией, не чаще раза в 10 минут на один набор), DNS гостя при смене сети. Всё —
 * строкой «[проверка] …» в журнале приложения и в списке «исправлено само».
 */
object HealthAuto {
    private const val PREFS = "dpai.health"
    private const val FIXED = "fixed"
    private const val COUNT = "count"
    private const val MAX_FIXED = 20

    /** Пауза между починками без нажатия одного набора пакетов. */
    private const val AUTO_EVERY_MS = 30 * 60_000L

    /** Последняя починка без нажатия: набор и время (монотонные мс). */
    @Volatile
    private var lastAuto: Pair<Set<String>, Long>? = null

    /** Причина последней неудачной починки пакетов; удачная — `null`. */
    @Volatile
    var lastFailure: String? = null
        private set

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Исправлено само: строка в журнале и в истории (последние [MAX_FIXED]). */
    fun fixed(context: Context, text: String) {
        AppLogs.info(context, "проверка", "исправлено само: $text")
        val now = com.dpsmpx.dpai.core.clock.Clock.SYSTEM.now().epochMillis
        val lines = (listOf("$now\t${text.replace('\n', ' ')}") + history(context).map { "${it.first}\t${it.second}" }).take(MAX_FIXED)
        prefs(context).edit { putString(FIXED, lines.joinToString("\n")) }
    }

    /** История исправлений без нажатия: время (мс эпохи) и что сделано, новые — первыми. */
    fun history(context: Context): List<Pair<Long, String>> =
        prefs(context).getString(FIXED, null).orEmpty().lineSequence().mapNotNull { line ->
            val at = line.substringBefore('\t').toLongOrNull() ?: return@mapNotNull null
            at to line.substringAfter('\t')
        }.toList()

    /** Сколько пунктов проверки требуют внимания — по последней проверке; для строки пульта. */
    fun count(context: Context): Int = lastCount(context) ?: 0

    /** Нет результата проверки — неизвестно, а не успешная проверка с нулём проблем (D-13). */
    fun lastCount(context: Context): Int? = prefs(context).let { if (it.contains(COUNT)) it.getInt(COUNT, 0) else null }

    /** Запомнить итог проверки [items] — для полосы и строки пульта без нового сбора. */
    fun remember(context: Context, items: Int) {
        if (lastCount(context) != items) {
            prefs(context).edit { putInt(COUNT, items) }
            (context.applicationContext as? DpaiApplication)?.studioChanged()
        }
    }

    /**
     * Раз в минуту из службы контроллера и при открытии окна «AI»: безопасные исправления
     * и подсчёт пунктов. Процессы гостя не запускаются здесь — починка пакетов идёт долгой
     * операцией, если гость свободен.
     */
    fun tick(context: Context) {
        val app = context.applicationContext
        if (LinuxSetup.available(app) && LinuxSetup.environment(app).installed() != null) {
            val env = LinuxSetup.environment(app)
            val had = env.gitConfigured()
            if (!had && env.writeGitConfig().valueOrNull() == true && env.gitConfigured()) {
                fixed(app, "в Git Linux записан помощник входа GitHub (берёт токен из GH_TOKEN, если он выдан)")
            }
            val broken = env.brokenPackages().valueOrNull().orEmpty().map { it.name }.toSet()
            // Без нажатия — только при сети и не чаще [AUTO_EVERY_MS] на один набор: без сети `apk fix` не пройдёт.
            val now = com.dpsmpx.dpai.core.clock.Clock.SYSTEM.monotonicNanos() / 1_000_000
            val last = lastAuto
            val online = app.getSystemService(android.net.ConnectivityManager::class.java)?.activeNetwork != null
            if (broken.isNotEmpty() && online && (last == null || last.first != broken || now - last.second >= AUTO_EVERY_MS)) {
                if (repair(app, force = false)) lastAuto = broken to now
            }
        }
        remember(app, Health.items(HealthProbe.collect(app)).size)
    }

    /**
     * Починка повреждённых пакетов долгой операцией (гость — один `apk` за раз);
     * [force] — по нажатию, без паузы. `false` — уже идёт другая работа гостя.
     */
    fun repair(context: Context, force: Boolean): Boolean {
        val app = context.applicationContext
        return LongJobs.get(app).start(LongJobs.LINUX_APK, "Починка пакетов Linux", excludes = LongJobs.GUEST) {
            when (val r = LinuxSetup.repairBroken(app, force)) {
                is Outcome.Success -> {
                    lastFailure = null
                    if (r.value.isEmpty()) LongJobs.Done(if (force) "Повреждённых пакетов нет" else "Починка отложена: недавно не удалась")
                    else LongJobs.Done("Пакеты восстановлены: ${r.value.joinToString(", ")}")
                }
                is Outcome.Failure -> {
                    val why = r.errors.first().message
                    if (why != lastFailure) AppLogs.info(app, "проверка", "починка пакетов не удалась: $why")
                    lastFailure = why
                    LongJobs.Done(why, failed = true)
                }
            }
        }
    }
}
