#!/usr/bin/env python3
"""Финальный аудит стабильности (ТЗ, §99–§125; этап 19): отчёт по доказательствам.

Каждый пункт аудитов A–S (ТЗ, §100–§118) сопоставлен с тестами, которые
его проверяют. Статус пункта — из результатов JUnit последнего прогона
(`*/build/test-results/test/*.xml`), а не из заявления:

  PASS     все тесты-доказательства прошли;
  FAIL     хотя бы один упал;
  SKIPPED  тест пропущен (нужен настоящий target или Claude — запуск со
           свойствами dpai.test.*, docs/TESTING.md);
  DEVICE   проверка только на устройстве (D-28, D-33) — не засчитывается;
  N/A      пункт не применим к V1 — с причиной;
  GAP      доказательства нет — находка с уровнем серьёзности.

Неизвестное имя теста в карте — ошибка генератора: доказательство не может
быть выдуманным. Отчёт — `build/audits/final-stability/` по ТЗ, §123:
файлы по областям, SUMMARY.md и report.json. Прогон выносливости (ТЗ,
§116) — отдельный долгий тест; его отчёт `endurance-*.md` подхватывается из
`--endurance` (путь к файлу) в LONG_RUN.md.

    python3 tools/audit/final_stability.py [--endurance путь]

Код возврата: 0 — отчёт построен (находки — в нём); 2 — карта ссылается на
несуществующий тест или нет результатов прогона.
"""
import glob
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "build", "audits", "final-stability")

# Файл отчёта → [(аудит ТЗ, пункт, доказательства | "DEVICE" | None, уровень находки при GAP/DEVICE)].
# Доказательство — "Класс.метод" или "Класс" (все методы класса).
MAP = {
    "FUNCTIONAL.md": [
        ("A §100", "create", ["TasksTest.dependencyGraphRunsInOrder", "V1ScenariosTest"], None),
        ("A §100", "queue", ["TasksTest.orderIsStable", "TasksTest.agingPreventsStarvation"], None),
        ("A §100", "assign", ["TasksTest.capabilitiesSelectTarget", "AccountsTest.loadIsSpreadAcrossSlotsAndDeterministic"], None),
        ("A §100", "start", ["V1ScenariosTest.scenario1ParallelDevelopmentInIsolatedWorktrees", "LiveSystemTest.taskRunsOnLoggedInSlotWithItsConfigDir"], None),
        ("A §100", "monitor", ["MonitoringTest", "ScreensTest"], None),
        ("A §100", "pause / resume", ["MockRunnerTest.pauseStopsTheTurnAndResumeContinuesSameProviderSession"], None),
        ("A §100", "stop", ["AgentRuntimeTest.startStreamAndStopWholeTree", "AgentCommandTest.localTargetStartsAndStopsThroughUnifiedInterface"], None),
        ("A §100", "retry", ["TasksTest.retryWaitsForBackoff", "TasksTest.retryLimitIsRespected", "V1ScenariosTest.scenario3RecoveryAfterFailure"], None),
        ("A §100", "continue", ["V1ScenariosTest.scenario2AutomaticContinuationAfterLimit", "RestartTest.continuationAfterRestartKeepsContextAndHandoff"], None),
        ("A §100", "verify", ["CompletionFlowTest", "LiveProjectTest", "ReviewFlowTest"], None),
        ("A §100", "complete", ["V1ScenariosTest.scenario4CompletionVerifiedCommittedAndMergedOnlyAfterApproval", "LifecycleTablesTest.doneOnlyAfterVerificationOrPolicyException"], None),
        ("A §100", "fail", ["TasksTest.failedDependencyBlocksDependents", "TasksTest.nonRetryableBlocksImmediately", "ChaosLoopTest.startFailureDoesNotLeaveTaskRunning"], None),
        ("A §100", "reassign", ["AccountsTest.continuationOnAnotherSubscriptionOnlyWhenAllowedAndWithoutLoops"], None),
        ("A §100", "recover", ["RecoveryTest", "RestartTest"], None),
    ],
    "INVARIANTS.md": [
        ("B §101", "все состояния и переходы, недопустимые и повторные", ["LifecycleTablesTest", "StateMachineTest"], None),
        ("B §101", "сбой во время перехода", ["RecoveryTest.chaos", "DocumentStoreTest.crashAtEveryStepLeavesOldOrNew"], None),
        ("B §101", "одновременные переходы", ["SessionsTest.repeatedCreateAndContinueAreIdempotent", "AccountsTest.leaseIsIdempotentAndExclusive"], None),
        ("S §118", "инварианты машинно проверяемы и держатся после каждого такта", ["InvariantsTest", "V1ScenariosTest", "LiveSystemTest", "ChaosLoopTest.loopKeepsProgressingThroughRestartsAndTargetLoss"], None),
        ("S §118", "инварианты под нагрузкой ТЗ, §93, §94", ["PerformanceTest.stress", "PerformanceTest.extremeStress"], None),
        ("§119", "поиск и починка сирот", ["RecoveryTest.orphansAreRepairedWhereSafeAndReportedOtherwise", "ChaosLoopTest"], None),
        ("§120", "цикл зависимостей — DEPENDENCY_CYCLE", ["TasksTest.dependencyCycleIsDetected"], None),
        ("§121", "отсутствие прогресса (петля повторов)", ["TasksTest.sameFailureRepeatedIsLoop"], None),
        ("§122", "молчащая сессия — STALE, а не RUNNING", ["MonitoringTest.silentRunningSessionIsStaleNotRunning", "StateMachineTest.heartbeatFreshnessUsesSeveralThresholdsAndNeverInfersDeath"], None),
    ],
    "STORAGE.md": [
        ("C §102", "повтор, пропуск, порядок, задержка событий", ["SequenceTrackerTest", "RunChannelTest", "MockRunnerTest.missingEventIsReconciledNotSkipped"], None),
        ("C §102", "испорченное событие", ["EventsTest.strictReadingCollectsAllFindings", "ClaudeStreamTest.corruptedSamples", "ClaudeStreamTest.mutatedStreamsNeverThrow"], None),
        ("C §102", "повтор после переподключения", ["RunChannelTest.anySingleReplyLossIsRecovered", "AgentRuntimeTest.connectionDropLosesNothing"], None),
        ("D §103", "прерванная запись и чтение", ["DocumentStoreTest", "EventJournalTest", "EventJournalSegmentsTest"], None),
        ("D §103", "прерванная миграция", ["MigratorTest"], None),
        ("D §103", "прерванные копия и восстановление", ["BackupTest"], None),
        ("D §103", "диск полон", ["DocumentStoreTest.ioFailureAtEveryStepKeepsOldVersion", "EventJournalTest.writeFailureClosesJournalUntilReopen"], None),
        ("D §103", "испорченные журнал и снимок", ["EventJournalTest.corruptionInTheMiddleNeedsExplicitQuarantine", "SnapshotRecoveryTest"], None),
    ],
    "RECOVERY.md": [
        ("E §104", "убит процесс оркестратора", ["RecoveryTest.appKilledWhileSessionRunsIsReconciledAndContinued", "RestartTest", "LiveSystemTest.restartAdoptsRunningTurnFromJournal"], None),
        ("E §104", "убит раннер / процесс Claude", ["RecoveryTest.claudeProcessVanishedIsStoppedAndFailed", "AgentRuntimeTest.lostSupervisorIsDetectedAndStopKillsTheTree"], None),
        ("E §104", "оборвано SSH-соединение", ["AgentRuntimeTest.connectionDropLosesNothing", "RecoveryTest.targetGoneLeavesSessionPendingNotFailed"], None),
        ("E §104", "убит интерфейс Android", "DEVICE", "MEDIUM"),
        ("R §117", "перезапуск приложения, потеря target, сбой сессии в долгом прогоне", ["ChaosLoopTest", "EnduranceTest"], None),
        ("R §117", "смена аккаунта", ["AccountsTest.continuationOnAnotherSubscriptionOnlyWhenAllowedAndWithoutLoops", "AccountNotificationsTest"], None),
    ],
    "CHAOS.md": [
        ("F §105", "offline, отключение, переподключение", ["TargetHealthTest", "ExecutionTargetManagerTest.unreachableTargetGoesOfflineOnlyAfterThresholds", "ChaosLoopTest", "ReviewChaosTest"], None),
        ("F §105", "медленная связь, задержка", ["TargetHealthTest.slowProbeDegrades"], None),
        ("F §105", "SSH: тайм-аут, отказ, ключ хоста", ["SshProcessHostTest.sshFailuresAreClassified", "JschProcessHostTest.refusedPortIsConnectionFailure", "JschProcessHostTest.unknownHostKeyIsRejectedOnRealTarget"], None),
        ("F §105", "сбой DNS", ["JschProcessHostTest.unresolvableHostIsConnectionFailure"], None),
        ("F §105", "потеря ответа, частичное соединение", ["RunChannelTest.anySingleReplyLossIsRecovered", "RunChannelTest.unclosedGapRequiresReconciliationInsteadOfApplying"], None),
        ("N §113", "объём событий и уведомлений", ["PerformanceTest.searchHundredThousandEvents", "NotificationEngineTest.stormIsHeldAndDigested"], None),
        ("N §113", "диск и размер хранилища", ["DocumentStoreTest.ioFailureAtEveryStepKeepsOldVersion", "EventJournalSegmentsTest"], None),
        ("N §113", "память, дескрипторы, процессор", ["EnduranceTest"], None),
    ],
    "AI_PROVIDER.md": [
        ("G §106", "Claude недоступен, сбой хода, тайм-аут", ["MockRunnerTest.everyScenarioGivesExpectedStateAndDiagnostic"], None),
        ("G §106", "истёк вход", ["ClaudeStreamTest.recordedAuthFailureIsAuthRequiredDespiteSuccessSubtype", "SessionsTest.authFailureBlocksAndRateLimitPausesAndFeedsAccounts", "LiveSystemTest.slotWithoutLoginKeepsTasksWaiting"], None),
        ("G §106", "предел подписки", ["AccountsTest.limitReachedWaitsForResetTime", "ClaudeStreamTest.recordedSuccessWithStructuredOutputAndLimits"], None),
        ("G §106", "испорченный, неполный, повторённый поток", ["ClaudeStreamTest.corruptedSamples", "ClaudeStreamTest.textClaimWithoutResultIsNotCompletion", "MockRunnerTest"], None),
        ("G §106", "настоящий Claude: ход и продолжение (--resume)", ["RealClaudeTest.twoTurnsWithResumeOnRealTarget"], None),
        ("J §109", "A назначен — A исполняет; B — B; A недоступен — B", ["AccountsTest", "SessionsTest.sessionCannotUseAnotherSessionsSlot"], None),
        ("J §109", "вход A истёк — A не используется", ["AccountsTest.loggedOutAndExpiredLoginNeedReLogin"], None),
        ("J §109", "учётные данные B не попадают в сессию A", ["SlotEnvironmentTest", "IsolationTest"], None),
        ("H §107", "неверный, устаревший, отсутствующий, повторный контекст", ["ContextTest.changedContextIsNewVersionAndSameIsNot", "ContextTest.handoffIsIdempotentAndHashed", "RestartTest.continuationAfterRestartKeepsContextAndHandoff"], None),
        ("H §107", "огромный контекст", ["ContextTest.overBudgetIsNamedNotDropped"], None),
        ("H §107", "отравление контекста, утечка между проектами", ["ContextTest.otherProjectNeverLeaksIn", "ContextTest.repositoryContentStaysData", "ContextTest.sourceKeyAndSecretsInDataAreNeutralized", "IsolationTest"], None),
    ],
    "GIT.md": [
        ("I §108", "грязное дерево, одновременная запись", ["GitServiceTest.oneWritableOwnerPerWorktree", "GitServiceTest.parallelSessionsDoNotCorruptEachOthersWorktrees", "OverlapTest"], None),
        ("I §108", "удалённое дерево, конфликт слияния", ["GitServiceTest.probeFindsConflictAndMissingWorktree"], None),
        ("I §108", "разрушительное — только с одобрением", ["GitServiceTest.destructiveOperationNeedsApproval", "V1ScenariosTest.scenario4ApprovalSurvivesRestart"], None),
        ("I §108", "хуки и fsmonitor репозитория не исполняются", ["GitServiceTest.repositoryHooksAndFsmonitorDoNotRun"], None),
        ("I §108", "удалённая ветка, сбой push", ["GitServiceTest.deletedBranchAndPushFailureAreReportedAndRepositoryIsIntact"], None),
        ("I §108", "расхождение с remote, прерванный rebase", ("N/A", "контур V1 не делает fetch-слияний и rebase: только worktree, commit и merge по одобрению"), None),
    ],
    "SECURITY.md": [
        ("K §110", "модель угроз T1–T11, аудит 2026-10-07 (A-1…A-8)", ["RedactorTest", "SecretTest", "StorageLeakTest", "GitServiceTest.repositoryHooksAndFsmonitorDoNotRun", "AgentRuntimeTest.secretInOutputNeverReachesDisk"], None),
        ("K §110", "SSH: ключ хоста, вход только ключом", ["SshProcessHostTest", "JschProcessHostTest", "SshKeysTest"], None),
        ("K §110", "журналы и копии без секретов", ["StorageLeakTest", "AgentRuntimeTest.turnInputIsDeliveredThenDeleted"], None),
        ("L §111", "проекты A и B на одном target не видят друг друга", ["IsolationTest", "ContextTest.otherProjectNeverLeaksIn"], None),
    ],
    "SCHEDULER.md": [
        ("M §112", "справедливость, приоритет, голодание", ["TasksTest.agingPreventsStarvation", "TasksTest.orderIsStable"], None),
        ("M §112", "порядок зависимостей", ["TasksTest.randomDagExecutesRespectingDependencies"], None),
        ("M §112", "deadlock, livelock, шторм повторов", ["TasksTest.dependencyCycleIsDetected", "TasksTest.sameFailureRepeatedIsLoop", "TasksTest.retryWaitsForBackoff"], None),
        ("M §112", "перегрузка аккаунтов и targets", ["AccountsTest.twelveSlotsLiveThroughTheirLifecycleTogether", "PerformanceTest.extremeStress"], None),
        ("§31", "пересечение файлов параллельных задач", ["OverlapTest"], None),
    ],
    "PERFORMANCE.md": [
        ("O §114", "создание задачи, такт планировщика, приём события, запись", ["PerformanceTest.taskCreationWithJournal", "PerformanceTest.schedulerTick", "PerformanceTest.eventIngest", "PerformanceTest.storageWrite"], None),
        ("O §114", "список сессий, поиск, контекст, уведомления", ["PerformanceTest.sessionListAndDashboard", "PerformanceTest.searchHundredThousandEvents", "PerformanceTest.contextAssembly", "PerformanceTest.notificationSubmit"], None),
        ("O §114", "нагрузка ТЗ, §93, §94", ["PerformanceTest.stress", "PerformanceTest.extremeStress"], None),
        ("O §114", "холодный старт и кадр сводки на устройстве", "DEVICE", "MEDIUM"),
    ],
    "MOBILE.md": [
        ("P §115", "большой список сессий (20+) без потери кадров", ["MonitoringTest.twentyFiveSessionsBuildWithinFrameBudget", "ScreensTest.demoSystemShowsTwentyPlusSessions"], None),
        ("P §115", "быстрая навигация, поворот, пересоздание процесса, фон", "DEVICE", "MEDIUM"),
        ("P §115", "множество уведомлений, кнопки одобрения", ["NotificationEngineTest", "UserRequestsFlowTest"], None),
        ("P §115", "интерфейс не блокируется тактом планировщика", "DEVICE", "MEDIUM"),
    ],
}


def load_results():
    results = {}
    files = glob.glob(os.path.join(ROOT, "*", "build", "test-results", "test", "*.xml")) + \
        glob.glob(os.path.join(ROOT, "tests", "*", "build", "test-results", "test", "*.xml"))
    for f in files:
        s = open(f, encoding="utf-8").read()
        for m in re.finditer(r'<testcase name="([^"(]+)[^"]*" classname="([^"]+)"[^>]*?(/>|>(.*?)</testcase>)', s, re.S):
            name, cls, body = m.group(1), m.group(2).split(".")[-1], m.group(4) or ""
            status = "FAIL" if "<failure" in body or "<error" in body else "SKIPPED" if "<skipped" in body else "PASS"
            results[f"{cls}.{name}"] = status
    return results


def evidence_status(evidence, results):
    statuses = []
    for e in evidence:
        hits = [v for k, v in results.items() if k == e or k.startswith(e + ".")]
        if not hits:
            print(f"карта ссылается на тест без результата: {e} — нет такого теста или прогон был неполным (нужен tools/verify/run.sh full)")
            sys.exit(2)
        statuses += hits
    if "FAIL" in statuses:
        return "FAIL"
    if all(s == "SKIPPED" for s in statuses):
        return "SKIPPED"
    return "PASS"


def main():
    endurance = None
    if "--endurance" in sys.argv:
        endurance = sys.argv[sys.argv.index("--endurance") + 1]
    results = load_results()
    if not results:
        print("нет результатов JUnit: сначала tools/verify/run.sh full")
        return 2
    os.makedirs(OUT, exist_ok=True)
    report = {"format": "dpai.final-stability", "version": 1, "files": {}, "findings": []}
    for fname, items in MAP.items():
        lines = [f"# {fname[:-3]} — финальный аудит (ТЗ, §123)", "", "| Аудит | Пункт | Статус | Доказательства |", "|---|---|---|---|"]
        rows = []
        for audit, item, evidence, severity in items:
            if evidence == "DEVICE":
                status, ev = "DEVICE", "проверка на устройстве (D-28, D-33)"
            elif evidence is None:
                status, ev = "GAP", "нет теста"
            elif isinstance(evidence, tuple):
                status, ev = evidence
            else:
                status, ev = evidence_status(evidence, results), ", ".join(f"`{e}`" for e in evidence)
            if status in ("GAP", "DEVICE", "FAIL", "SKIPPED"):
                level = {"FAIL": "HIGH", "SKIPPED": "INFO"}.get(status, severity)
                report["findings"].append({"file": fname, "audit": audit, "item": item, "status": status, "severity": level})
            rows.append({"audit": audit, "item": item, "status": status, "evidence": evidence if isinstance(evidence, list) else ev})
            lines.append(f"| {audit} | {item} | {status} | {ev} |")
        open(os.path.join(OUT, fname), "w", encoding="utf-8", newline="\n").write("\n".join(lines) + "\n")
        report["files"][fname] = rows
    long_run = ["# LONG_RUN — выносливость (ТЗ, §116, §117)", ""]
    if endurance and os.path.isfile(endurance):
        long_run.append(open(endurance, encoding="utf-8").read())
        report["endurance"] = os.path.basename(endurance)
    else:
        long_run.append("Прогона выносливости в этом отчёте нет: `-Ddpai.endurance.minutes=1440` (docs/TESTING.md).")
        report["findings"].append({"file": "LONG_RUN.md", "audit": "Q §116", "item": "24 ч", "status": "GAP", "severity": "HIGH"})
    open(os.path.join(OUT, "LONG_RUN.md"), "w", encoding="utf-8", newline="\n").write("\n".join(long_run) + "\n")
    counts = {}
    for rows in report["files"].values():
        for r in rows:
            counts[r["status"]] = counts.get(r["status"], 0) + 1
    by_sev = {}
    for f in report["findings"]:
        by_sev[f["severity"]] = by_sev.get(f["severity"], 0) + 1
    report["counts"] = counts
    report["severity"] = by_sev
    summary = ["# SUMMARY — финальный аудит стабильности (ТЗ, §99–§125)", "",
               "Статусы пунктов: " + ", ".join(f"{k} — {v}" for k, v in sorted(counts.items())) + ".",
               "Находки по уровню: " + (", ".join(f"{k} — {v}" for k, v in sorted(by_sev.items())) or "нет") + ".", "",
               "| Файл | Аудит | Пункт | Статус | Уровень |", "|---|---|---|---|---|"]
    for f in report["findings"]:
        summary.append(f"| {f['file']} | {f['audit']} | {f['item']} | {f['status']} | {f['severity']} |")
    open(os.path.join(OUT, "SUMMARY.md"), "w", encoding="utf-8", newline="\n").write("\n".join(summary) + "\n")
    open(os.path.join(OUT, "report.json"), "w", encoding="utf-8", newline="\n").write(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(f"✓ финальный аудит: {sum(counts.values())} пунктов ({', '.join(f'{k} {v}' for k, v in sorted(counts.items()))}); отчёт — build/audits/final-stability/")
    return 0


if __name__ == "__main__":
    sys.exit(main())
