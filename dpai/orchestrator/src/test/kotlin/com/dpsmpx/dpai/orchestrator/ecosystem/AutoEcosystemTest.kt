package com.dpsmpx.dpai.orchestrator.ecosystem

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.ecosystem.EcosystemProject
import com.dpsmpx.dpai.ecosystem.SuggestedTask
import com.dpsmpx.dpai.orchestrator.commands.Command
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.tasks.Priority
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * Задачи из экосистемы без подтверждения (D-106, T19): без политики и после
 * срока — ни одной; только разрешённые проект, вид работы и приоритет;
 * пределы за 24 часа и открытых; повтор предложения задачу не дублирует;
 * чтение политики строгое.
 */
class AutoEcosystemTest {
    @TempDir
    lateinit var dir: Path

    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(106))

    /** Контур-заглушка: ставит задачу по ключу один раз (как D-42), помнит, какие закончены. */
    private class Contour(private val ids: IdGenerator) {
        val byKey = LinkedHashMap<String, TaskId>()
        val finished = HashSet<TaskId>()
        var calls = 0

        fun place(c: Command.AddTask): Outcome<TaskId> {
            calls++
            return Outcome.success(byKey.getOrPut(c.key!!) { ids.next(IdKinds.TASK) })
        }

        fun open(t: TaskId): Boolean = t !in finished
    }

    private fun s(key: String, priority: String = "NORMAL", operation: String = "docs") = SuggestedTask(key, "задача $key", "цель $key", priority, operation)

    private val projects = listOf(
        EcosystemProject("app", "App", null, emptyMap(), emptyList(), listOf(s("a1"), s("a2", operation = "code"), s("a3", priority = "CRITICAL"), s("a4", priority = "LOW"))),
        EcosystemProject("other", "Other", null, emptyMap(), emptyList(), listOf(s("o1"))),
    )

    private fun auto() = AutoEcosystem(DocumentStore(NioVfs(dir), "docs"), clock)

    private fun policy(
        projects: Set<String> = setOf("app"),
        operations: Set<String> = setOf("docs"),
        priorities: Set<Priority> = setOf(Priority.NORMAL, Priority.LOW),
        perDay: Int = 10,
        open: Int = 5,
        until: com.dpsmpx.dpai.core.clock.Timestamp? = null,
    ) = AutoPolicy(projects, operations, priorities, perDay, open, until)

    private fun pass(a: AutoEcosystem, c: Contour, connected: Set<String> = setOf("app", "other")): AutoPass =
        (a.pass(projects, connected, c::open, c::place) as Outcome.Success).value

    @Test
    fun withoutPolicyNothingIsPlaced() {
        val c = Contour(ids)
        assertEquals(AutoPass.NONE, pass(auto(), c))
        assertEquals(0, c.calls)
    }

    @Test
    fun onlyAllowedProjectOperationAndPriority() {
        val a = auto()
        assertIs<Outcome.Success<Unit>>(a.enable(policy()))
        val c = Contour(ids)
        val r = pass(a, c)
        assertEquals(listOf("a1", "a4"), r.placed.map { it.suggestion }, "docs, NORMAL и LOW проекта app")
        val why = r.refused.toMap()
        assertEquals("вид работы code не разрешён прогону", why["ecosystem:app/a2"])
        assertEquals("приоритет CRITICAL не разрешён политикой", why["ecosystem:app/a3"])
        assertEquals("проект other не разрешён прогону", why["ecosystem:other/o1"])
        assertEquals(setOf("ecosystem:app/a1", "ecosystem:app/a4"), c.byKey.keys)
        assertEquals(policy().digest(), r.placed.first().policy, "в записи — отпечаток политики")
        assertEquals(r.placed, (a.entries() as Outcome.Success).value, "след сохранён")
        val queued = AutoEntry(clock.now(), "app", "a9", null, "0123456789abcdef")
        assertEquals(listOf(queued), (AutoEcosystem.readLedger(AutoEcosystem.ledgerJson(listOf(queued))) as Outcome.Success).value, "запись без id — команда в очереди")
    }

    @Test
    fun aSuggestionBecomesOneTaskEvenAcrossPasses() {
        val a = auto()
        a.enable(policy())
        val c = Contour(ids)
        pass(a, c)
        val second = pass(a, c)
        assertTrue(second.placed.isEmpty(), "повтор прохода задачу не дублирует")
        assertEquals(2, c.calls, "уже поставленное контуру второй раз не отдаётся")
        assertEquals(2, (a.entries() as Outcome.Success).value.size)
    }

    @Test
    fun dailyAndOpenLimits() {
        val a = auto()
        a.enable(policy(perDay = 1))
        val c = Contour(ids)
        val first = pass(a, c)
        assertEquals(listOf("a1"), first.placed.map { it.suggestion })
        assertEquals("предел 1 задач за 24 часа исчерпан", first.refused.toMap()["ecosystem:app/a4"])
        assertTrue(pass(a, c).placed.isEmpty(), "в те же сутки — больше ни одной")
        clock.advance(25.hours)
        assertEquals(listOf("a4"), pass(a, c).placed.map { it.suggestion }, "через сутки — следующая")

        val b = AutoEcosystem(DocumentStore(NioVfs(dir), "other"), clock)
        b.enable(policy(open = 1))
        val d = Contour(ids)
        assertEquals(listOf("a1"), pass(b, d).placed.map { it.suggestion })
        assertEquals("предел 1 открытых задач политики", pass(b, d).refused.toMap()["ecosystem:app/a4"])
        d.finished += d.byKey.getValue("ecosystem:app/a1")
        assertEquals(listOf("a4"), pass(b, d).placed.map { it.suggestion }, "задача кончилась — место свободно")
    }

    @Test
    fun expiredPolicyPlacesNothingAndDisableStops() {
        val a = auto()
        assertIs<Outcome.Failure>(a.enable(policy(until = clock.now())), "срок в прошлом — не включается")
        a.enable(policy(until = clock.now() + 1.days))
        clock.advance(2.days)
        val c = Contour(ids)
        val r = pass(a, c)
        assertTrue(r.placed.isEmpty())
        assertEquals("срок политики истёк", r.refused.single().second)
        a.enable(policy())
        a.disable()
        assertNull((a.policy() as Outcome.Success).value)
        assertEquals(AutoPass.NONE, pass(a, c))
        assertEquals(0, c.calls)
    }

    @Test
    fun placementFailureIsReportedAndNotRecorded() {
        val a = auto()
        a.enable(policy())
        val r = (a.pass(projects, setOf("app"), { true }) { Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "нет контроллера") } as Outcome.Success).value
        assertTrue(r.placed.isEmpty())
        assertEquals(2, r.failures.size)
        assertEquals(emptyList(), (a.entries() as Outcome.Success).value)
    }

    @Test
    fun queuedCommandGetsItsTaskLaterAndHumanPlacedIsNotThePolicys() {
        val a = auto()
        a.enable(policy(perDay = 1))
        val ops = HashMap<String, Outcome<TaskId>>()
        // a4 человек поставил сам из окна — до политики.
        ops["ecosystem:app/a4"] = Outcome.success(ids.next(IdKinds.TASK))
        val queuedPass = (a.pass(projects, setOf("app"), { true }, { Outcome.failure(DiagCode.COMMAND_QUEUED, "в очереди") }) { ops[it] } as Outcome.Success).value
        assertEquals(listOf("a1"), queuedPass.queued.map { it.suggestion }, "очередь контура — тоже задача политики, с записью")
        assertTrue(queuedPass.placed.isEmpty(), "без id — без сообщения о постановке")
        assertTrue(queuedPass.refused.none { it.first == "ecosystem:app/a4" }, "поставленное человеком — не политики и не отказ")
        val id = ids.next(IdKinds.TASK)
        ops["ecosystem:app/a1"] = Outcome.success(id)
        val c = Contour(ids)
        val later = (a.pass(projects, setOf("app"), c::open, c::place) { ops[it] } as Outcome.Success).value
        assertEquals(listOf<TaskId?>(id), later.placed.map { it.task }, "контур исполнил очередь — id в следе и сообщение")
        assertEquals(0, c.calls)
        assertEquals(listOf("a1"), (a.entries() as Outcome.Success).value.map { it.suggestion })
    }

    @Test
    fun queuedCommandRefusedByTheContourLeavesTheLedger() {
        val a = auto()
        a.enable(policy(perDay = 1))
        val ops = HashMap<String, Outcome<TaskId>>()
        a.pass(projects, setOf("app"), { true }, { Outcome.failure(DiagCode.COMMAND_QUEUED, "в очереди") }) { ops[it] }
        ops["ecosystem:app/a1"] = Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "отказ контура")
        val r = (a.pass(projects, setOf("app"), { true }, { Outcome.failure(DiagCode.COMMAND_QUEUED, "в очереди") }) { ops[it] } as Outcome.Success).value
        assertEquals(DiagCode.OPERATION_NOT_ALLOWED, r.failures.single().code)
        assertTrue(r.placed.isEmpty())
        assertEquals(listOf("a4"), r.queued.map { it.suggestion }, "место отказавшей команды свободно")
        assertEquals(listOf("a4"), (a.entries() as Outcome.Success).value.map { it.suggestion })
    }

    @Test
    fun policyReadingIsStrict() {
        val good = policy(until = clock.now() + 7.days)
        assertEquals(good, (AutoPolicy.read(good.toJson()) as Outcome.Success).value)
        fun with(key: String, v: com.dpsmpx.dpai.core.serialization.JsonValue) = JsonObject(LinkedHashMap(good.toJson().fields).also { it[key] = v })
        assertIs<Outcome.Failure>(AutoPolicy.read(with("operations", JsonArray(emptyList()))), "пустой список — не «всё»")
        assertIs<Outcome.Failure>(AutoPolicy.read(with("projects", JsonArray(emptyList()))))
        assertIs<Outcome.Failure>(AutoPolicy.read(with("priorities", JsonArray(listOf(JsonString("URGENT"))))))
        assertIs<Outcome.Failure>(AutoPolicy.read(with("extra", JsonString("x"))), "неизвестное поле")
        assertIs<Outcome.Failure>(AutoPolicy.read(with("version", com.dpsmpx.dpai.core.serialization.JsonNumber.of(2))), "чужая версия")
        assertIs<Outcome.Failure>(AutoPolicy.read(with("maxPerDay", com.dpsmpx.dpai.core.serialization.JsonNumber.of(AutoPolicy.MAX_PER_DAY + 1))))
        assertEquals("нет видов работ: политика без них ничего не разрешает", policy(operations = emptySet()).problem())
        assertIs<Outcome.Failure>(auto().enable(policy(open = 0)))
    }

    @Test
    fun unreadablePolicyPlacesNothing() {
        val store = DocumentStore(NioVfs(dir), "docs")
        store.write(AutoEcosystem.POLICY_KEY, JsonObject.of("format" to JsonString(AutoPolicy.FORMAT)))
        val c = Contour(ids)
        assertIs<Outcome.Failure>(AutoEcosystem(store, clock).pass(projects, setOf("app"), c::open, c::place))
        assertEquals(0, c.calls)
    }
}
