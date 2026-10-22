package com.dpsmpx.dpai.execution.process

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import java.io.File
import java.util.Base64
import kotlin.time.Duration

/**
 * Объект задания Windows для процессов хода (находка A-9; слово автора
 * 22 октября 2026 — помощник PowerShell, без новых зависимостей).
 *
 * У Windows нет сеанса процессов, как `setsid` на Linux: потомок, которого
 * инструмент Claude запустил в фоне (`start /b`, сервер разработки),
 * теряет родителя, и остановка дерева его не видит. Помощник —
 * `powershell.exe` с типом из `Add-Type` (P/Invoke `kernel32`): создаёт
 * объект задания с `KILL_ON_JOB_CLOSE`, кладёт в него процесс хода — его
 * будущие потомки попадают туда же — и держит объект, пока жив
 * супервизор. Ввод и вывод хода помощник не трогает.
 *
 * - Остановка хода — [kill]: помощник завершается, его описатель объекта
 *   закрывается, и с объектом уходят все процессы хода, в том числе
 *   потерявшие родителя.
 * - Ход кончился сам: супервизор кладёт отметку (путь — в команде
 *   помощника, запись — через `Vfs` агента) и выходит; помощник, дождавшись
 *   его выхода, снимает `KILL_ON_JOB_CLOSE` — фоновые потомки живут
 *   дальше, как на Linux.
 * - Супервизор умер без отметки (ход `LOST`) — объект закрывается:
 *   процессы хода останавливаются сразу, а не при `stop`.
 * - Не ждать выхода супервизора помощник не может — снимает
 *   `KILL_ON_JOB_CLOSE` и выходит: без охраны, как раньше, а не убить ход.
 * - Помощник не смог (нет PowerShell, `Add-Type` запрещён политикой) —
 *   ход идёт без объекта, как раньше; при остановке — предупреждение
 *   [DiagCode.PROCESS_JOB_UNAVAILABLE] с причиной.
 *
 * Окно: потомков, которых процесс хода запустил раньше, чем помощник
 * положил его в объект (запуск PowerShell и компиляция — секунды), объект
 * не держит. Claude Code запускает инструменты после ответа модели — позже.
 */
class TurnJob private constructor(private val helper: RunningProcess) {
    private val lock = Any()
    private var ready = false
    private var failure: String? = null

    private fun line(l: OutputLine) = synchronized(lock) {
        when {
            l.text == READY -> ready = true
            l.text.startsWith(PREFIX) -> failure = l.text.removePrefix(PREFIX).take(300)
        }
    }

    /**
     * Ход останавливают: помощник завершается, и объект уносит процессы хода.
     * Объект не был готов — предупреждение: фоновые потомки могли остаться.
     */
    fun kill(grace: Duration, subject: String): Diagnostic? {
        val (wasReady, why) = synchronized(lock) { ready to failure }
        val alive = helper.isAlive
        helper.stop(grace)
        if (wasReady) return null
        val reason = why ?: if (alive) "помощник не успел" else "помощник вышел, не создав объект"
        return Diagnostic.warning(DiagCode.PROCESS_JOB_UNAVAILABLE, "объект задания Windows не готов ($reason): фоновые потомки хода могли остаться (A-9)", subject)
    }

    companion object {
        const val READY = "dpai-job: ready"
        const val PREFIX = "dpai-job: "

        /** Запустить помощника [command] для процесса хода. Ожидания нет: ход идёт, пока помощник готовится. */
        fun start(host: ProcessHost, command: List<String>): Outcome<TurnJob> {
            var job: TurnJob? = null
            val early = mutableListOf<OutputLine>()
            // Строки до того, как объект создан, копятся и отдаются ему: помощник может ответить сразу.
            val started = host.start(ProcessSpec(command)) { l ->
                synchronized(early) {
                    val j = job
                    if (j != null) j.line(l) else early += l
                }
            }
            return when (started) {
                is Outcome.Failure -> started
                is Outcome.Success -> {
                    val j = TurnJob(started.value)
                    synchronized(early) {
                        early.forEach(j::line)
                        job = j
                    }
                    Outcome.success(j)
                }
            }
        }

        /** Помощник PowerShell (Windows PowerShell 5.1 есть в Windows 10 и 11): сценарий — в `-EncodedCommand`. */
        fun powershell(child: Long, supervisor: Long, releaseFile: File): List<String> =
            listOf(
                "powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-EncodedCommand", encode(script(child, supervisor, releaseFile.path)),
            )

        /** `-EncodedCommand` — base64 от UTF-16LE: так сценарий не разбирается командной строкой. */
        fun encode(script: String): String = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))

        private fun psQuote(s: String): String = "'" + s.replace("'", "''") + "'"

        /**
         * Сценарий помощника. Структуры `JOBOBJECT_EXTENDED_LIMIT_INFORMATION`
         * — по раскладке Windows SDK (`LayoutKind.Sequential` выравнивает поля
         * сам, x86 и x64); класс сведений 9 — расширенные пределы,
         * `0x2000` — `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`; `AssignProcessToJobObject`
         * требует `PROCESS_SET_QUOTA | PROCESS_TERMINATE`. Вывод — UTF-8: причина
         * отказа .NET бывает по-русски, а агент читает UTF-8 (без консоли
         * кодировку не сменить — тогда как есть). `ArgumentException` при
         * ожидании — супервизора уже нет: дальше как после его выхода; иной
         * отказ ожидания — охрана снимается, а не ход убивается.
         */
        fun script(child: Long, supervisor: Long, releaseFile: String): String = """
            |try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }
            |${'$'}ErrorActionPreference = 'Stop'
            |${'$'}child = $child
            |${'$'}supervisor = $supervisor
            |${'$'}release = ${psQuote(releaseFile)}
            |try {
            |    Add-Type -TypeDefinition @'
            |using System;
            |using System.ComponentModel;
            |using System.Runtime.InteropServices;
            |public static class DpaiJob {
            |    [StructLayout(LayoutKind.Sequential)]
            |    struct Basic {
            |        public long PerProcessUserTimeLimit; public long PerJobUserTimeLimit; public uint LimitFlags;
            |        public UIntPtr MinimumWorkingSetSize; public UIntPtr MaximumWorkingSetSize; public uint ActiveProcessLimit;
            |        public UIntPtr Affinity; public uint PriorityClass; public uint SchedulingClass;
            |    }
            |    [StructLayout(LayoutKind.Sequential)]
            |    struct Io { public ulong Reads, Writes, Others, ReadBytes, WriteBytes, OtherBytes; }
            |    [StructLayout(LayoutKind.Sequential)]
            |    struct Extended {
            |        public Basic Basic; public Io Io; public UIntPtr ProcessMemoryLimit; public UIntPtr JobMemoryLimit;
            |        public UIntPtr PeakProcessMemoryUsed; public UIntPtr PeakJobMemoryUsed;
            |    }
            |    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
            |    static extern IntPtr CreateJobObject(IntPtr attributes, string name);
            |    [DllImport("kernel32.dll", SetLastError = true)]
            |    static extern bool SetInformationJobObject(IntPtr job, int infoClass, ref Extended info, uint length);
            |    [DllImport("kernel32.dll", SetLastError = true)]
            |    static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);
            |    [DllImport("kernel32.dll", SetLastError = true)]
            |    static extern IntPtr OpenProcess(uint access, bool inherit, uint pid);
            |    [DllImport("kernel32.dll", SetLastError = true)]
            |    static extern bool CloseHandle(IntPtr handle);
            |    static IntPtr job;
            |    static void Limit(uint flags) {
            |        Extended info = new Extended();
            |        info.Basic.LimitFlags = flags;
            |        if (!SetInformationJobObject(job, 9, ref info, (uint)Marshal.SizeOf(typeof(Extended)))) throw new Win32Exception();
            |    }
            |    public static void Enter(uint pid) {
            |        job = CreateJobObject(IntPtr.Zero, null);
            |        if (job == IntPtr.Zero) throw new Win32Exception();
            |        Limit(0x2000);
            |        IntPtr process = OpenProcess(0x0100 | 0x0001, false, pid);
            |        if (process == IntPtr.Zero) throw new Win32Exception();
            |        try { if (!AssignProcessToJobObject(job, process)) throw new Win32Exception(); }
            |        finally { CloseHandle(process); }
            |    }
            |    public static void Release() { Limit(0); }
            |}
            |'@
            |    [DpaiJob]::Enter([uint32]${'$'}child)
            |} catch {
            |    [Console]::Out.WriteLine('$PREFIX' + ${'$'}_.Exception.Message)
            |    exit 3
            |}
            |[Console]::Out.WriteLine('$READY')
            |try {
            |    [System.Diagnostics.Process]::GetProcessById(${'$'}supervisor).WaitForExit()
            |} catch [System.ArgumentException] {
            |} catch {
            |    [DpaiJob]::Release()
            |    [Console]::Out.WriteLine('$PREFIX' + ${'$'}_.Exception.Message)
            |    exit 4
            |}
            |if (Test-Path -LiteralPath ${'$'}release) {
            |    [DpaiJob]::Release()
            |    Remove-Item -LiteralPath ${'$'}release -ErrorAction SilentlyContinue
            |}
            |exit 0
        """.trimMargin()
    }
}
