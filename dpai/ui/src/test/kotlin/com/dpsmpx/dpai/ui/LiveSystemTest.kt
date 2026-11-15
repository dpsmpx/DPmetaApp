package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.accounts.SlotEnvironment
import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.state.AccountState
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.execution.agent.ExitReason
import com.dpsmpx.dpai.execution.agent.MockAgentHost
import com.dpsmpx.dpai.execution.claude.MockRunner
import com.dpsmpx.dpai.execution.claude.MockScenario
import com.dpsmpx.dpai.orchestrator.Orchestrator
import com.dpsmpx.dpai.storage.vfs.NioVfs
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.io.TempDir

/** Контроллер приложения на mock target: вход слотов → задача → ход на своём target (D-35). */
class LiveSystemTest {
    @TempDir
    lateinit var dir: Path

    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(77))
    private val host = MockAgentHost(clock, ids)
    private var loggedIn = true

    /** Аккаунт, вошедший на target (по каталогу Claude); `null` — без отпечатка. */
    private val account = HashMap<String, String>()

    /** Ход идёт, пока тест не продвинет его шаги (`host.advance`). */
    private var slow = false

    /** Ход отвечает `authentication_failed` (сбой сети: токен не обновился, D-58). */
    private var authError = false

    private fun entry(label: String, configDir: String?) =
        TargetForm.validate(ids.next(IdKinds.TARGET), label, "build.lan", "22", "dev", false, "/opt/dpai/bin/dpai", configDir).valueOrNull()!!

    init {
        host.onStart = { run, spec, _ ->
            if (spec.command.take(3) == listOf("claude", "auth", "status")) {
                val email = account[spec.setEnv["CLAUDE_CONFIG_DIR"]]?.let { ",\"email\":\"$it\",\"orgId\":\"org-$it\"" }.orEmpty()
                host.output(run, if (loggedIn) """{"loggedIn":true,"authMethod":"claude.ai","apiProvider":"firstParty","subscriptionType":"pro"$email}""" else """{"loggedIn":false,"authMethod":"none"}""")
                host.exit(run, if (loggedIn) 0 else 1)
            } else {
                val c = spec.command
                val sid = c[c.indexOfFirst { it == "--session-id" || it == "--resume" } + 1]
                val steps = MockRunner.script(if (authError) MockScenario.AUTH_ERROR else MockScenario.SUCCESS, sid).map { l -> { host.output(run, l); Unit } } +
                    listOf({ host.exit(run, 0, ExitReason.EXITED) })
                if (slow) host.schedule(run, steps) else steps.forEach { it() }
            }
        }
    }

    private fun system(vararg entries: TargetEntry) =
        LiveSystem(clock, NioVfs(dir), entries.toList(), { _, _ -> host }, "/known_hosts", RandomSource.seeded(78))

    private fun steps(s: LiveSystem, n: Int) = repeat(n) {
        s.step()
        assertEquals(emptyList(), s.orchestrator.invariants(), "инварианты ТЗ, §118")
        clock.advance(1.seconds)
    }

    @Test
    fun taskRunsOnLoggedInSlotWithItsConfigDir() {
        val t = entry("сервер", "/home/dev/.claude-dpai")
        val s = system(t, entry("старый", null))
        steps(s, 1)
        assertEquals(AccountState.AVAILABLE, s.orchestrator.accounts.slots.values.single().state)
        assertTrue(s.dashboard().attention.any { "старый" in it }, s.dashboard().attention.toString())
        val task = s.addTask("Проверка", "ответить одним словом").valueOrNull()!!
        steps(s, 4)
        val session = s.orchestrator.sessions.sessions.values.single { it.task == task }
        assertEquals(t.id, session.target)
        val turn = host.spec(session.id)!!
        assertEquals("/home/dev/.claude-dpai", turn.setEnv[SlotEnvironment.CONFIG_DIR])
        val state = s.orchestrator.tasks.tasks.getValue(task).state
        // Профиля проверки нет: ход завершён, но завершение не проверено (D-23).
        assertEquals(TaskState.COMPLETION_UNVERIFIED, state, s.problems.toString())
        assertTrue(s.session(session.id).timeline.isNotEmpty())
        // Уведомление о завершении без проверки — оболочке один раз (ТЗ, §36; D-23).
        assertTrue(s.drainNotifications().any { it.title.startsWith("Задача без проверки") && it.request != null })
        assertEquals(emptyList(), s.drainNotifications())
    }

    /**
     * Два рабочих проекта (D-179): контуры над одним target с одним местом. Ход идёт у
     * одного — второй ждёт, хотя своих сессий у него нет; ход кончился — место берёт второй.
     */
    @Test
    fun twoProjectContoursShareOneTargetPlace() {
        val t = entry("сервер", "/home/dev/.claude-dpai")
        slow = true
        val all = arrayOfNulls<LiveSystem>(2)
        fun live(s: LiveSystem?) = s?.orchestrator?.sessions?.sessions?.values?.filter { !it.terminal }.orEmpty()
        fun others(i: Int) = object : com.dpsmpx.dpai.orchestrator.NeighbourLoad {
            override fun sessions(target: com.dpsmpx.dpai.core.ids.ExecutionTargetId) = all.filterIndexed { k, _ -> k != i }.sumOf { s -> live(s).count { it.target == target } }
            override fun total() = all.filterIndexed { k, _ -> k != i }.sumOf { live(it).size }
        }
        for (i in 0..1) {
            all[i] = LiveSystem(clock, NioVfs(dir.resolve("p$i").also { it.toFile().mkdirs() }), listOf(t), { _, _ -> host }, "/known_hosts", RandomSource.seeded(80L + i), neighbours = others(i))
        }
        val (a, b) = all.map { it!! }
        a.step(); b.step(); clock.advance(1.seconds)
        val ta = a.addTask("A", "цель A").valueOrNull()!!
        val tb = b.addTask("B", "цель B").valueOrNull()!!
        repeat(3) { a.step(); b.step(); clock.advance(1.seconds) }
        assertEquals(1, live(a).size + live(b).size, "одно место на target — одно на двоих")
        assertEquals(1, live(a).size, "первым начал контур, чей такт раньше")
        // Ход A доходит до конца — место переходит ко второму проекту.
        repeat(20) {
            for (r in host.runIds()) while (host.advance(r)) continue
            a.step(); b.step(); clock.advance(1.seconds)
        }
        assertTrue(a.orchestrator.sessions.sessions.values.filter { it.task == ta }.all { it.terminal }, "ход A кончился: ${a.orchestrator.tasks.tasks.getValue(ta).state}")
        assertTrue(b.orchestrator.sessions.sessions.values.any { it.task == tb }, "второй проект получил место")
        assertTrue(live(a).size + live(b).size <= 1)
    }

    @Test
    fun problemsKeepOnlyTheLatest() {
        // Отказ каждый такт (target недоступен) не растит список без предела (ревью 8 октября 2026, A-33, A-83).
        val s = system(entry("сервер", "/home/dev/.claude-dpai"))
        val kept = Orchestrator.KEPT_PROBLEMS
        repeat(kept + 50) { s.orchestrator.problems += Diagnostic.warning(DiagCode.INTERNAL_ERROR, "отказ $it") }
        s.orchestrator.problems.addAll(listOf(Diagnostic.warning(DiagCode.INTERNAL_ERROR, "последний")))
        assertEquals(kept, s.orchestrator.problems.size)
        assertEquals("последний", s.orchestrator.problems.last().message)
        assertEquals("отказ 51", s.orchestrator.problems.first().message)
    }

    /**
     * D-87: хранилище «в порядке» — по факту, а не константой. Отказ хранилища в
     * проблемах (диск полон, `STORAGE_*`) — сигнал `storageOk = false`: разрушительные
     * записи останавливаются, как обещает `docs/RECOVERY.md`, §6.
     */
    @Test
    fun storageTroubleIsVisibleInSelfDiagnostics() {
        val s = system(entry("сервер", "/home/dev/.claude-dpai"))
        assertEquals(true, s.storageOk(), "чистый контур — хранилище в порядке")
        s.orchestrator.problems += Diagnostic.error(DiagCode.STORAGE_UNAVAILABLE, "журнал закрыт после отказа записи")
        assertEquals(false, s.storageOk(), "отказ хранилища виден сигналом")
        assertEquals(false, s.signals().storageOk, "и в самодиагностике экрана")
    }

    /**
     * D-87: отрезанный хвост журнала не теряется молча. Испорченная запись после
     * целых — `JOURNAL_TAIL_TRUNCATED` с числом байт и именем карантина; система
     * открывается, а предупреждение стоит в её проблемах (раньше уходило в `.valueOrNull()`).
     */
    @Test
    fun truncatedJournalTailIsReportedNotSwallowed() {
        val target = entry("сервер", "/home/dev/.claude-dpai")
        // Первый запуск пишет журнал, затем портим его хвост: обрываем последнюю строку.
        system(target).also { s -> steps(s, 2) }.close()
        val journal = dir.resolve("live.journal")
        if (java.nio.file.Files.exists(journal)) {
            val bytes = java.nio.file.Files.readAllBytes(journal)
            // Обрезаем последние 10 байт: последняя запись не подтверждена — её отрежет open().
            java.nio.file.Files.write(journal, bytes.copyOf((bytes.size - 10).coerceAtLeast(0)))
        }
        val s = system(target)
        try {
            assertTrue(
                s.problems.any { it.code == DiagCode.JOURNAL_TAIL_TRUNCATED },
                "потеря хвоста журнала — в проблемах контура: ${s.problems.map { it.code }}",
            )
        } finally {
            s.close()
        }
    }

    /**
     * D-24 (аудит надёжности 2 ноября 2026, A-1): порча в середине журнала —
     * не молча отрезанные подтверждённые записи, а отказ открытия до решения
     * человека; файл не тронут. После подтверждения — карантин хвоста, и
     * потеря уходит в журнал приложения (D-43), а не только в короткий список.
     */
    @Test
    fun corruptionInTheMiddleWaitsForTheHuman() {
        val target = entry("сервер", "/home/dev/.claude-dpai")
        system(target).also { s -> steps(s, 2) }.close()
        val journal = dir.resolve("live.journal")
        val bytes = java.nio.file.Files.readAllBytes(journal)
        val firstEnd = bytes.indexOf('\n'.code.toByte())
        assertTrue(firstEnd > 0 && firstEnd < bytes.size - 2, "в журнале больше одной записи")
        val spoiled = bytes.copyOf().also { b -> b[firstEnd / 2] = if (b[firstEnd / 2] == 'X'.code.toByte()) 'Y'.code.toByte() else 'X'.code.toByte() }
        java.nio.file.Files.write(journal, spoiled)

        kotlin.test.assertFailsWith<JournalNeedsConfirmation> { system(target) }
        assertTrue(spoiled.contentEquals(java.nio.file.Files.readAllBytes(journal)), "без решения человека журнал не тронут")

        val told = mutableListOf<Diagnostic>()
        val s = LiveSystem(clock, NioVfs(dir), listOf(target), { _, _ -> host }, "/known_hosts", RandomSource.seeded(79), onProblem = { told += it }, quarantineConfirmed = true)
        try {
            assertTrue(s.problems.any { it.code == DiagCode.JOURNAL_TAIL_TRUNCATED }, s.problems.toString())
            assertTrue(told.any { it.code == DiagCode.JOURNAL_TAIL_TRUNCATED }, "потеря — в журнал приложения: $told")
        } finally {
            s.close()
        }
    }

    /**
     * D-75, шаг 5: target «в приложении» — без SSH, через агента процесса;
     * ход идёт с каталогом слота в госте. Linux не установлен (агента нет) —
     * контроллер работает с остальными targets, а этот назван в заметках.
     */
    @Test
    fun inAppTargetRunsThroughTheAppAgent() {
        val inApp = TargetForm.inApp(ids.next(IdKinds.TARGET), "Linux в приложении · слот 1", "/root/.claude-slot-1").valueOrNull()!!
        val without = LiveSystem(clock, NioVfs(dir.resolve("without")), listOf(inApp), { _, _ -> error("SSH «в приложении» не нужен") }, "/known_hosts", RandomSource.seeded(80))
        try {
            assertTrue(without.notes.any { "не установлен" in it }, without.notes.toString())
            steps(without, 1)
            assertTrue(without.orchestrator.accounts.slots.isEmpty(), "без агента приложения — слота нет")
        } finally {
            without.close()
        }

        val s = LiveSystem(clock, NioVfs(dir.resolve("with")), listOf(inApp), { _, _ -> error("SSH «в приложении» не нужен") }, "/known_hosts", RandomSource.seeded(81), inAppHost = host)
        try {
            steps(s, 1)
            assertEquals(AccountState.AVAILABLE, s.orchestrator.accounts.slots.values.single().state, s.problems.toString())
            val task = s.addTask("В Alpine", "ответить одним словом").valueOrNull()!!
            steps(s, 4)
            val session = s.orchestrator.sessions.sessions.values.single { it.task == task }
            assertEquals(inApp.id, session.target)
            assertEquals("/root/.claude-slot-1", host.spec(session.id)!!.setEnv[SlotEnvironment.CONFIG_DIR], "каталог слота — путь гостя")
        } finally {
            s.close()
        }
    }

    @Test
    fun slotWithoutLoginKeepsTasksWaiting() {
        loggedIn = false
        val target = entry("сервер", "/home/dev/.claude-dpai")
        val s = system(target)
        steps(s, 1)
        assertTrue(s.orchestrator.accounts.slots.isEmpty(), "без входа слота нет")
        assertTrue(s.dashboard().attention.any { "войдите в Claude" in it }, s.dashboard().attention.toString())
        val task = s.addTask("Ждёт", "ждать входа").valueOrNull()!!
        steps(s, 3)
        assertTrue(s.orchestrator.sessions.sessions.values.none { it.task == task })
        loggedIn = true
        s.probeSlot(target.id)
        steps(s, 4)
        assertTrue(s.orchestrator.sessions.sessions.values.any { it.task == task })
    }

    /**
     * Ревью A-79: первая проба входа не удалась (нет связи — SSH до
     * добавления ключа, сон ПК). Target без слота пробуется снова раз в
     * минуту и получает слот без «Вход Claude»; задача идёт.
     */
    @Test
    fun failedSlotProbeIsRetried() {
        host.reachable = false
        val target = entry("сервер", "/home/dev/.claude-dpai")
        val s = system(target)
        steps(s, 1)
        assertTrue(s.orchestrator.accounts.slots.isEmpty(), "без связи слота нет")
        assertTrue(s.dashboard().attention.any { "повтор раз в минуту" in it }, s.dashboard().attention.toString())
        // Ревью A-84: самодиагностика не выдумывает «все targets в сети».
        assertEquals(0, s.signals().targetsOnline)
        host.reachable = true
        val task = s.addTask("Ждёт", "ждать слота").valueOrNull()!!
        clock.advance(LiveSystem.PROBE_RETRY_MS.milliseconds)
        steps(s, 4)
        assertEquals(1, s.orchestrator.accounts.slots.size, "слот после повтора пробы")
        assertTrue(s.orchestrator.sessions.sessions.values.any { it.task == task })
        assertTrue(s.dashboard().attention.none { "повтор раз в минуту" in it })
        assertEquals(1, s.signals().targetsOnline)
    }

    /**
     * D-58: без сети ход ответил «нужен вход», хотя вход цел. Слот не ждёт
     * «Вход Claude»: через 2 минуты приложение само проверяет вход, слот
     * снова доступен, задача продолжается; на «Внимание» — когда проверка.
     */
    @Test
    fun slotMarkedByFailedTurnRechecksLoginItself() {
        val target = entry("сервер", "/home/dev/.claude-dpai")
        val s = system(target)
        steps(s, 1)
        authError = true
        val task = s.addTask("Сбой сети", "ответить").valueOrNull()!!
        steps(s, 4)
        val slot = s.orchestrator.accounts.slots.values.single()
        assertEquals(AccountState.AUTH_REQUIRED, slot.state, "ход сказал «нужен вход»")
        assertEquals(TaskState.BLOCKED, s.orchestrator.tasks.tasks.getValue(task).state)
        steps(s, 1)
        assertTrue(s.dashboard().attention.any { "проверю вход сам через" in it }, s.dashboard().attention.toString())
        authError = false
        clock.advance(LiveSystem.AUTH_RECHECK_FIRST_MS.milliseconds)
        steps(s, 6)
        assertEquals(AccountState.AVAILABLE, s.orchestrator.accounts.slots.values.single().state, "вход цел — слот снова доступен")
        assertTrue(s.orchestrator.sessions.sessions.values.count { it.task == task } >= 2, "задача продолжилась сама")
        assertTrue(s.dashboard().attention.none { "проверю вход сам" in it })
    }

    /**
     * Перезапуск с сообщением в очереди (D-60): контроллер строится и
     * доставляет его после хода. На устройстве 11 октября 2026 очередь,
     * прочитанная при построении, роняла контроллер NullPointerException:
     * её хранилище было объявлено ниже `init` оркестратора.
     */
    @Test
    fun restartWithQueuedMessageBuildsAndDeliversIt() {
        slow = true
        val t = entry("сервер", "/home/dev/.claude-dpai")
        val first = system(t)
        steps(first, 1)
        val task = first.addTask("Долгая", "ход идёт через перезапуск").valueOrNull()!!
        steps(first, 2)
        val session = first.orchestrator.sessions.sessions.values.single { it.task == task }.id
        assertTrue(first.orchestrator.sendToSession(session, "ещё вопрос", null, null, false) is com.dpsmpx.dpai.core.result.Outcome.Success)
        assertEquals(1, first.orchestrator.queued(task).size)

        val second = system(t)
        assertEquals(1, second.orchestrator.queued(task).size, "очередь прочитана при построении")
        do { val more = host.advance(session) } while (more)
        steps(second, 6)
        assertTrue(second.orchestrator.queued(task).isEmpty(), "очередь доставлена после хода")
        assertTrue(second.orchestrator.chat(task).any { it.text == "ещё вопрос" })
    }

    /**
     * Перезапуск приложения посреди хода (ТЗ, §54): новый контроллер над тем
     * же журналом знает задачу, сессию и слот, сверяет сессию с агентом и
     * подхватывает идущий ход; второго слота не появляется.
     */
    @Test
    fun restartAdoptsRunningTurnFromJournal() {
        slow = true
        val t = entry("сервер", "/home/dev/.claude-dpai")
        val first = system(t)
        steps(first, 1)
        val task = first.addTask("Долгая", "ход идёт через перезапуск").valueOrNull()!!
        steps(first, 2)
        val session = first.orchestrator.sessions.sessions.values.single { it.task == task }.id
        assertTrue(host.hasPending(session), "ход должен ещё идти")

        val second = system(t)
        assertEquals(1, second.orchestrator.accounts.slots.size)
        assertEquals(TaskState.RUNNING, second.orchestrator.tasks.tasks.getValue(task).state)
        do { val more = host.advance(session) } while (more)
        steps(second, 4)
        assertEquals(TaskState.COMPLETION_UNVERIFIED, second.orchestrator.tasks.tasks.getValue(task).state, second.problems.toString())
    }

    /**
     * Ход идёт потоком агента (D-76): события хода приходят без опроса
     * `events` на каждый такт; поток кончается вместе с ходом.
     */
    @Test
    fun runningTurnIsFollowedNotPolled() {
        slow = true
        val s = system(entry("сервер", "/home/dev/.claude-dpai"))
        steps(s, 1)
        val task = s.addTask("Поток", "ход идёт потоком").valueOrNull()!!
        steps(s, 2)
        val session = s.orchestrator.sessions.sessions.values.single { it.task == task }.id
        assertEquals(1, host.followerCount(), "у идущего хода — поток")
        val polls = host.eventCalls
        var ticks = 0
        while (host.advance(session)) {
            steps(s, 1)
            ticks++
        }
        assertTrue(ticks >= 3, "ход из нескольких шагов: $ticks")
        assertEquals(polls, host.eventCalls, "за $ticks тактов хода — ни одного опроса events")
        assertEquals(0, host.followerCount(), "поток кончился вместе с ходом")
        steps(s, 3)
        assertEquals(TaskState.COMPLETION_UNVERIFIED, s.orchestrator.tasks.tasks.getValue(task).state, s.problems.toString())
        s.close()
    }

    /**
     * Подписка слота — по аккаунту (D-29, D-30): два targets, вошедшие одним
     * аккаунтом, — одна подписка; другим аккаунтом — своя.
     */
    @Test
    fun slotsJoinSubscriptionsByAccount() {
        account["/home/dev/.claude-a"] = "a@example.org"
        account["/home/dev/.claude-a2"] = "a@example.org"
        account["/home/dev/.claude-b"] = "b@example.org"
        val s = system(entry("один", "/home/dev/.claude-a"), entry("два", "/home/dev/.claude-a2"), entry("три", "/home/dev/.claude-b"))
        steps(s, 1)
        val slots = s.orchestrator.accounts.slots.values
        assertEquals(3, slots.size)
        assertTrue(slots.all { it.state == AccountState.AVAILABLE }, slots.map { it.state }.toString())
        val bySub = slots.groupBy { it.subscriptionId }.values.map { g -> g.map { it.configDir }.toSet() }.toSet()
        assertEquals(setOf(setOf("/home/dev/.claude-a", "/home/dev/.claude-a2"), setOf("/home/dev/.claude-b")), bySub)
    }
}
