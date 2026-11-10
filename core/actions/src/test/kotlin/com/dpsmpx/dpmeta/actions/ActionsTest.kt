package com.dpsmpx.dpmeta.actions

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpmeta.model.ActionLevel
import com.dpsmpx.dpmeta.model.RepoRef
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import org.junit.jupiter.api.io.TempDir

/**
 * Политика действий и журнал (ТЗ, §6.5, A-06; D-07, D-36, D-37): уровень 2 без PIN не
 * исполняется, уровень 3 — без совпавшего имени; каждое действие уровня 1–3 — в журнале,
 * отказ — тоже; секрет из параметров в журнал не попадает.
 */
class ActionsTest {
    @TempDir
    lateinit var dir: Path

    private val clock = ManualClock()
    private val repo = (RepoRef.of("dpsmpx", "DPmetaApp") as Outcome.Success).value
    private val vfs by lazy { NioVfs(dir) }
    private val log by lazy { ActionLog(vfs, clock, RandomSource.seeded(1)) }
    private val actions by lazy { Actions(log, clock, RandomSource.seeded(2)) }
    private var calls = 0
    private val ok: () -> Outcome<ActionResult> = { calls++; Outcome.success(ActionResult("готово", "https://github.com/dpsmpx/DPmetaApp/pull/7")) }

    private fun cmd(kind: ActionKind, branch: String? = null, params: Map<String, String> = emptyMap()) = ActionCommand(kind, ActionTarget(repo, branch), params, "github:dpsmpx")

    private fun code(r: Outcome<*>) = assertIs<Outcome.Failure>(r).errors.single().code

    /** Уровни видов — по D-36 и D-37: ветка и PR — 1, `main` и слияние — 2, перезапись и удаления — 3. */
    @Test
    fun kindsCarryLevelsOfTheDecisions() {
        val want = mapOf(
            ActionKind.COMMENT to 1, ActionKind.FILES_COMMIT_PR to 1, ActionKind.PR_READY to 1, ActionKind.COMMAND_RUN to 1,
            ActionKind.PR_MERGE to 2, ActionKind.PR_CLOSE to 2, ActionKind.COMMIT_DEFAULT to 2, ActionKind.BRANCH_DELETE_MERGED to 2,
            ActionKind.HISTORY_REWRITE to 3, ActionKind.BRANCH_DELETE_UNMERGED to 3, ActionKind.REPO_DELETE to 3,
            ActionKind.REPO_SETTINGS to 3, ActionKind.BRANCH_PROTECTION to 3,
        )
        for ((k, level) in want) assertEquals(level, k.level.code, k.code)
        assertEquals(ActionKind.entries.size, ActionKind.entries.map { it.code }.toSet().size, "коды видов — разные")
        assertTrue(ActionKind.entries.none { it.level == ActionLevel.READ }, "чтение — не действие журнала")
        ActionKind.entries.forEach { assertEquals(it, ActionKind.of(it.code)) }
    }

    @Test
    fun levelOneRunsAfterShowingAndIsJournaled() {
        val c = cmd(ActionKind.COMMENT, params = mapOf("number" to "7", "body" to "Посмотрю"))
        val r = actions.run(c, actions.shown(c), ok)
        assertEquals("готово", r.valueOrNull()?.detail)
        val e = log.entries().valueOrNull()!!.single()
        assertEquals(listOf("comment", "dpsmpx/DPmetaApp", "shown", "github:dpsmpx"), listOf(e.kind, e.target, e.way, e.profile))
        assertEquals(ActionStatus.DONE to "https://github.com/dpsmpx/DPmetaApp/pull/7", e.status to e.link)
        assertEquals(mapOf("body" to "Посмотрю", "number" to "7"), e.params)
    }

    /** A-06: уровень 2 — только с PIN или биометрией устройства. */
    @Test
    fun levelTwoNeedsDeviceConfirmation() {
        val c = cmd(ActionKind.PR_MERGE, params = mapOf("number" to "7"))
        assertEquals(DiagCode.OPERATION_NOT_ALLOWED, code(actions.run(c, actions.shown(c), ok)))
        assertEquals(DiagCode.APPROVAL_REQUIRED, code(actions.run(c, null, ok)))
        assertEquals(0, calls, "исполнитель не вызывался")
        assertIs<Outcome.Success<ActionResult>>(actions.run(c, actions.deviceConfirmed(c), ok))
        assertEquals(1, calls)
        assertEquals(listOf(ActionStatus.REFUSED, ActionStatus.REFUSED, ActionStatus.DONE), log.entries().valueOrNull()!!.map { it.status })
    }

    /** D-37: уровень 3 — только ввод точного имени; PIN его не заменяет. */
    @Test
    fun levelThreeNeedsExactName() {
        val repoDelete = cmd(ActionKind.REPO_DELETE)
        assertEquals("dpsmpx/DPmetaApp", repoDelete.nameToType)
        assertEquals(DiagCode.OPERATION_NOT_ALLOWED, code(actions.run(repoDelete, actions.deviceConfirmed(repoDelete), ok)))
        assertEquals(DiagCode.OPERATION_NOT_ALLOWED, code(actions.run(repoDelete, actions.typedName(repoDelete, "dpsmpx/dpmetaapp"), ok)))
        assertEquals(0, calls)
        assertIs<Outcome.Success<ActionResult>>(actions.run(repoDelete, actions.typedName(repoDelete, " dpsmpx/DPmetaApp "), ok))
        val force = cmd(ActionKind.HISTORY_REWRITE, branch = "feature/x")
        assertEquals("feature/x", force.nameToType, "у ветки вводится имя ветки")
        assertEquals(DiagCode.OPERATION_NOT_ALLOWED, code(actions.run(force, actions.typedName(force, "dpsmpx/DPmetaApp"), ok)))
        assertIs<Outcome.Success<ActionResult>>(actions.run(force, actions.typedName(force, "feature/x"), ok))
        assertEquals(2, calls)
    }

    /** Одобрение — на это предложение, однократное и свежее (`dpai/security`). */
    @Test
    fun approvalIsBoundSingleUseAndExpires() {
        val a = cmd(ActionKind.COMMENT, params = mapOf("body" to "да"))
        val b = cmd(ActionKind.COMMENT, params = mapOf("body" to "нет"))
        assertEquals(DiagCode.APPROVAL_INVALID, code(actions.run(b, actions.shown(a), ok)), "одобрено другое")
        val once = actions.shown(a)
        assertIs<Outcome.Success<ActionResult>>(actions.run(a, once, ok))
        assertEquals(DiagCode.APPROVAL_INVALID, code(actions.run(a, once, ok)), "повтор того же нажатия")
        val old = actions.shown(a)
        clock.advance(Actions.APPROVAL_TTL + 1.minutes)
        assertEquals(DiagCode.APPROVAL_INVALID, code(actions.run(a, old, ok)), "истекло")
        assertEquals(1, calls)
    }

    @Test
    fun badCommandsAreRefusedBeforeConfirmation() {
        for (bad in listOf(cmd(ActionKind.FILES_COMMIT_PR), cmd(ActionKind.FILES_COMMIT_PR, branch = "-f"), cmd(ActionKind.FILES_COMMIT_PR, branch = "a/../b"),
            cmd(ActionKind.COMMENT, params = mapOf("Body" to "x")), cmd(ActionKind.COMMENT, params = mapOf("body" to "a\u0000b")))) {
            assertIs<Outcome.Failure>(actions.run(bad, actions.shown(bad), ok), bad.toString())
        }
        assertEquals(0, calls)
        assertTrue(log.entries().valueOrNull()!!.all { it.status == ActionStatus.REFUSED })
    }

    /** Секрет в тексте команды уходит исполнителю, но не в журнал (D-14); SHA коммита — не секрет. */
    @Test
    fun journalHasNoSecretsButKeepsCommitIds() {
        val token = "gh" + "p_" + "A1b2C3d4".repeat(5)
        val sha = "1".repeat(40)
        val c = cmd(ActionKind.COMMENT, params = mapOf("body" to "ключ $token", "head" to sha))
        var seen = ""
        actions.run(c, actions.shown(c)) { seen = c.params.getValue("body"); Outcome.success(ActionResult("готово")) }
        assertTrue(token in seen)
        val text = vfs.read(ActionLog.PATH)!!.toString(Charsets.UTF_8)
        assertTrue(token !in text, "токен в журнале")
        assertEquals(sha, log.entries().valueOrNull()!!.single().params["head"])
    }

    @Test
    fun failureAndCrashOfExecutorAreSettledAsFailed() {
        val c = cmd(ActionKind.COMMENT, params = mapOf("body" to "a"))
        actions.run(c, actions.shown(c)) { Outcome.failure(DiagCode.GITHUB_AUTH_EXPIRED, "вход истёк", "github") }
        val crash = cmd(ActionKind.COMMENT, params = mapOf("body" to "b"))
        val r = actions.run(crash, actions.shown(crash)) { error("сеть") }
        assertEquals(DiagCode.INTERNAL_ERROR, code(r))
        assertEquals(listOf(ActionStatus.FAILED, ActionStatus.FAILED), log.entries().valueOrNull()!!.map { it.status })
    }

    /** Процесс умер между «запрошено» и итогом — в журнале «итог неизвестен», а не «выполнено». */
    @Test
    fun requestedWithoutResultIsUnknownAndJournalSurvivesReopen() {
        val c = cmd(ActionKind.PR_CLOSE, params = mapOf("number" to "3"))
        log.requested(IdGenerator(clock, RandomSource.seeded(9)).next(IdKinds.OPERATION), c, Confirmation.DEVICE)
        val reopened = ActionLog(NioVfs(dir), clock, RandomSource.seeded(3))
        val e = reopened.entries().valueOrNull()!!.single()
        assertEquals(ActionStatus.UNKNOWN to 2, e.status to e.level)
        assertTrue(e.matches("pr.close") && e.matches("НЕИЗВЕСТЕН") && !e.matches("merge"))
    }

    /** Журнал только добавляется: правка записи в середине — отказ чтения, а не другой журнал. */
    @Test
    fun editedJournalIsRefused() {
        for (body in listOf("раз", "два")) {
            val c = cmd(ActionKind.COMMENT, params = mapOf("body" to body))
            actions.run(c, actions.shown(c), ok)
        }
        val text = vfs.read(ActionLog.PATH)!!.toString(Charsets.UTF_8)
        vfs.write(ActionLog.PATH, text.replaceFirst("раз", "три").toByteArray(Charsets.UTF_8))
        assertEquals(DiagCode.JOURNAL_CORRUPTED, code(ActionLog(NioVfs(dir), clock, RandomSource.seeded(4)).entries()))
    }
}
