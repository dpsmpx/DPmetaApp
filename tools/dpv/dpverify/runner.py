"""Прогон: выбор проверок по уровню, порядок, пропуски, тайм-ауты, журналы, бюджет (ТЗ, §6.4–6.8)."""
import datetime
import os
import platform
import shutil
import time
import traceback
from dataclasses import dataclass

from . import report, scope, select, waivers
from .checks import REGISTRY
from .checks.base import Context, default_settings
from .commands import Tools, run_command
from .errors import UsageError
from .model import CheckResult, Finding, Status, assign_fingerprints

KEEP_RUNS = 10  # F-21


@dataclass
class Options:
    level: str = "static"
    ci: bool = False
    only: list = None
    fail_fast: bool = False
    verbose: bool = False
    json_path: str = None
    base: str = None  # --base REF (F-07)
    files: list = None  # --files Ф… вместо изменений git (F-08)
    no_baseline: bool = False  # --no-baseline (F-19)
    invocation: str = None  # строка вызова — для report.md


class Run:
    def __init__(self, repo, profile, options, package_root):
        self.repo, self.profile, self.options = repo, profile, options
        self.package_root = package_root
        self.level_requested = options.level
        self.level = options.level
        self.mode = "ci" if options.ci else "local"
        self.invocation = options.invocation
        self.base = None
        self.selection = None  # affected: что выбрано и почему (F-11)
        self._selected = None  # select.Selection
        self.only = options.only
        self.results = []
        self.budget_exceeded = []
        self.stopped = False
        self.commit = repo.head()
        self.dirty = repo.dirty()
        self.host = platform.node()
        self.python = platform.python_version()
        self.started = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        self.duration = 0.0
        self.last_dir_rel = f"{profile.logs}/last"
        self.run_dir_rel = None
        if options.ci:
            if options.level != "full":
                raise UsageError("--ci — только с full: CI-контур один (F-06)")
            if options.only:
                raise UsageError("--only в CI-режиме нельзя: CI-прогон — всегда весь профиль")
            if self.dirty:
                raise UsageError("--ci на грязном дереве: закоммитить или убрать изменения — "
                                 "итог CI-прогона относится к коммиту")
        if (options.base or options.files) and options.level != "affected":
            raise UsageError("--base и --files — только с affected: выбор по изменениям (F-07, F-08)")

    # ---- выбор ----

    def selected(self):
        """affected: изменения и выбор (§6.5); считается один раз за прогон."""
        if self._selected is None:
            explicit = self.options.base or self.options.files is not None
            record = None if explicit else scope.load(self.repo, self.profile)
            changes = select.changes(self.repo, self.profile, base_ref=self.options.base,
                                     files=self.options.files,
                                     scope_start=record.start if record else None)
            self._selected = select.select(self.repo, self.profile, changes)
            sel = self._selected
            self.base = changes.base
            self.selection = {
                "base": changes.base, "source": changes.source, "changed": len(changes.files),
                "full": sel.full, "reason": sel.reason,
                "commands": {cid: [path, rule] for cid, (path, rule) in sorted(sel.commands.items())},
            }
        return self._selected

    def _builtin_wanted(self, cls, settings):
        """Уровень встроенной проверки: static — везде, full — в полном, affected — только в affected.
        Встроенная full в affected — при полном прогоне или если выбрана команда из её after (smoke.cli)."""
        if self.level == "static":
            return cls.LEVEL == "static"
        if self.level == "full":
            return cls.LEVEL in ("static", "full")
        if cls.LEVEL in ("static", "affected"):
            return True
        sel = self.selected()
        return cls.LEVEL == "full" and (sel.full or any(dep in sel.commands for dep in settings.get("after") or []))

    def _command_wanted(self, cmd):
        if cmd.level == "static" or self.level == "full":
            return True
        if self.level == "static":
            return False
        sel = self.selected()
        return sel.full or cmd.id in sel.commands

    def plan(self):
        """[(id, kind, объект)] в порядке прогона: встроенные, затем команды с учётом after, затем
        встроенные с after (smoke.cli ждёт сборку)."""
        if self.level == "affected":
            self.selected()  # выбор и причина — в отчёт и plan, даже если команд full нет
        steps, deferred = [], []
        for cid, cls in REGISTRY.items():
            settings = self.profile.checks.get(cid) or default_settings(cls)
            if settings["enabled"] and self._builtin_wanted(cls, settings):
                (deferred if settings.get("after") else steps).append((cid, "builtin", cls(settings)))
        commands = {c.id: c for c in self.profile.commands if self._command_wanted(c)}
        placed = []

        def place(cmd):
            if cmd.id in placed:
                return
            for dep in cmd.after:
                if dep in commands:
                    place(commands[dep])
            placed.append(cmd.id)

        for cmd in self.profile.commands:
            if cmd.id in commands:
                place(cmd)
        steps += [(cid, "command", commands[cid]) for cid in placed] + deferred
        if self.only:
            known = {s[0] for s in steps}
            unknown = [i for i in self.only if i not in known]
            if unknown:
                raise UsageError(f"--only: проверки {unknown[0]!r} нет на уровне {self.level} "
                                 f"(есть: {', '.join(sorted(known))})")
            wanted = set(self.only)
            for cid, kind, obj in reversed(steps):
                if cid in wanted:
                    wanted |= set(obj.after if kind == "command" else obj.settings.get("after") or [])
            steps = [s for s in steps if s[0] in wanted]
        return steps

    # ---- прогон ----

    def execute(self, out):
        steps = self.plan()
        logs = self.repo.abspath(self.profile.logs)
        run_dir = self._new_run_dir(logs)
        self.run_dir_rel = os.path.relpath(run_dir, self.repo.root).replace(os.sep, "/")
        tools = Tools(self.profile.tools, self.repo.root)
        self.changed_file = ""
        if self.level == "affected" and not self.selected().full and self.selected().changes.files:
            # Полный прогон списка не передаёт: команды идут целиком, без делегирования (F-10).
            self.changed_file = os.path.join(run_dir, "changed.txt")
            select.write_changed(self.changed_file, self.selected().changes.files)
        start = time.monotonic()
        by_id = {}
        for cid, kind, obj in steps:
            if self.stopped:
                res = CheckResult(cid, kind, Status.SKIP, skip_reason="не запускалась: --fail-fast")
            elif kind == "builtin":
                res = self._builtin(obj, run_dir, by_id)
            else:
                res = self._command(obj, run_dir, tools, by_id)
            res.findings.sort(key=Finding.sort_key)
            by_id[cid] = res
            self.results.append(res)
            if self.options.fail_fast and res.status in (Status.FAIL, Status.ERROR):
                self.stopped = True
        self._accept()
        self.duration = time.monotonic() - start
        budget = self.profile.budget[self.level_requested]
        if self.duration > budget:
            self.budget_exceeded.append({"level": self.level_requested, "budget_s": budget,
                                         "actual_s": round(self.duration, 3)})
        rep = report.to_json(self)
        report.write_json(rep, os.path.join(run_dir, "report.json"))
        report.write_md(rep, os.path.join(run_dir, "report.md"))
        self._publish(run_dir, logs)
        if self.options.json_path:
            shutil.copyfile(os.path.join(run_dir, "report.json"), self.options.json_path)
        for line in report.screen(rep, self.options.verbose, self.last_dir_rel):
            print(line, file=out)
        return {"error": 3, "fail": 1, "pass": 0}[self.result()]

    def _after(self, after, by_id):
        """(причина не запускать, упал ли предшественник) — по проверкам из after (§7.3)."""
        for dep in after:
            prev = by_id.get(dep)
            if prev is None:
                continue
            if prev.status in (Status.FAIL, Status.ERROR):
                return f"не запускалась: упала {dep}", True
            if prev.status == Status.SKIP:
                return f"не запускалась: пропущена {dep}", False
        return None, False

    def _builtin(self, check, run_dir, by_id):
        reason, _ = self._after(check.settings.get("after") or [], by_id)
        if reason is not None:
            # Предшественник упал или пропущен: его итог уже в прогоне; в CI запрет пропуска — у него.
            return CheckResult(check.ID, "builtin", Status.SKIP, skip_reason=reason)
        res = CheckResult(check.ID, "builtin", Status.PASS)
        start = time.monotonic()
        with open(os.path.join(run_dir, f"{check.ID}.log"), "w", encoding="utf-8") as log:
            ctx = Context(self.repo, self.profile, self.mode, self.level, self.package_root, log, REGISTRY)
            try:
                outcome = check.run(ctx)
            except UsageError:
                raise  # неверное окружение или профиль — код 2, а не сбой проверки
            except Exception:  # noqa: BLE001 — сбой проверки отличим от сбоя кода (V-10)
                log.write("внутренняя ошибка DP Verify:\n" + traceback.format_exc())
                res.status = Status.ERROR
                res.duration = time.monotonic() - start
                return res
            res.scanned, res.findings, res.summary = outcome.scanned, outcome.findings, outcome.summary
            if outcome.skip:
                res.status, res.skip_reason = Status.SKIP, outcome.skip
                log.write(f"пропуск: {outcome.skip}\n")
                res.duration = time.monotonic() - start
                return res
            if res.scanned == 0:
                if check.settings.get("allow_empty"):
                    res.summary += f" (пусто — разрешено профилем: {check.settings['allow_empty']})"
                else:
                    res.findings.append(Finding(
                        "empty", "проверке нечего проверять — пустая проверка не защищает (V-07)",
                        hint=f'исправить профиль или allow_empty = "<причина>" в [checks."{check.ID}"]'))
            assign_fingerprints(check.ID, res.findings)
            if res.errors():
                res.status = Status.FAIL
            log.write(res.summary + "\n")
            for f in sorted(res.findings, key=Finding.sort_key):
                where = (f.path or "") + (f":{f.line}" if f.line else "")
                log.write(f"{where}: [{f.rule}] {f.message}" + (f" — {f.hint}" if f.hint else "") + "\n")
        res.duration = time.monotonic() - start
        return res

    def _command(self, cmd, run_dir, tools, by_id):
        reason, after_failed = self._after(cmd.after, by_id)
        if reason is None:
            for name in cmd.requires:
                reason = tools.missing(name)
                if reason:
                    break
        if reason is not None:
            res = CheckResult(cmd.id, "command", Status.SKIP, skip_reason=reason)
            if self.mode == "ci" and not after_failed:
                if cmd.allow_skip_in_ci:
                    res.skip_reason = f"{reason} — разрешено профилем: {cmd.allow_skip_in_ci}"
                else:
                    res.status = Status.FAIL
                    res.findings.append(Finding(
                        "forbidden-skip", f"пропуск в CI-контуре запрещён: {reason}",
                        hint="поставить инструмент хуком начала сессии или allow_skip_in_ci с причиной",
                        evidence=reason))
                    assign_fingerprints(cmd.id, res.findings)
            return res
        args = list(cmd.run)
        if self.level == "affected" and not self.selected().full and cmd.affected_args:
            args += cmd.affected_args  # делегирование: команда сама решает, что гонять (F-10)
        env = {
            "DPV_LEVEL": self.level, "DPV_MODE": self.mode, "DPV_BASE": self.base or "",
            "DPV_CHANGED": self.changed_file, "DPV_RUN_DIR": run_dir,
        }
        hint = f"журнал — {self.last_dir_rel}/{cmd.id}.log"
        res = run_command(cmd, args, self.repo.root, os.path.join(run_dir, f"{cmd.id}.log"), env, hint)
        assign_fingerprints(cmd.id, res.findings)
        return res

    # ---- исключения и базовая линия ----

    def _accept(self):
        """После всех проверок: [[waiver]] и базовая линия принимают находки (F-16, F-18);
        их правила — находки dpv.profile (F-17). Статусы пересчитываются."""
        rel = os.path.relpath(self.profile.path, self.repo.root).replace(os.sep, "/")
        todo = self.profile.process["todo"]
        todo_text = self.repo.text(todo) if todo and os.path.isfile(self.repo.abspath(todo)) else None
        extra = waivers.apply_waivers(self.profile, self.results, datetime.date.today(), todo_text,
                                      self.repo.all_paths(), rel)
        if not self.options.no_baseline:
            extra += waivers.apply_baseline(waivers.load_baseline(self.repo, self.profile), self.results,
                                            self.profile.baseline)
        own = next((r for r in self.results if r.id == "dpv.profile"), None)
        if own is not None and own.status != Status.ERROR and extra:
            own.findings += extra
            assign_fingerprints(own.id, own.findings)
            own.findings.sort(key=Finding.sort_key)
        for r in self.results:
            if r.status == Status.ERROR:
                continue
            if r.errors():
                r.status = Status.FAIL
            elif r.skip_reason:
                r.status = Status.SKIP  # принят запрет пропуска — проверка всё равно не шла
            else:
                r.status = Status.PASS

    # ---- итог ----

    def result(self):
        statuses = {r.status for r in self.results}
        if Status.ERROR in statuses:
            return "error"
        if Status.FAIL in statuses:
            return "fail"
        return "pass"

    def partial(self):
        return bool(self.only) or any(r.status == Status.SKIP for r in self.results)

    # ---- журналы ----

    def _new_run_dir(self, logs):
        stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
        sha = (self.commit or "nogit")[:7]
        base = f"{stamp}-{sha}{'-dirty' if self.dirty else ''}-{self.level_requested}"
        runs = os.path.join(logs, "runs")
        os.makedirs(runs, exist_ok=True)
        path, n = os.path.join(runs, base), 1
        while os.path.exists(path):
            n += 1
            path = os.path.join(runs, f"{base}-{n}")
        os.makedirs(path)
        return path

    def _publish(self, run_dir, logs):
        """Копия последнего прогона — build/dpv/last/; хранятся KEEP_RUNS последних (F-21)."""
        last = os.path.join(logs, "last")
        if os.path.isdir(last):
            shutil.rmtree(last)
        shutil.copytree(run_dir, last)
        runs = os.path.join(logs, "runs")
        old = sorted(os.listdir(runs))[:-KEEP_RUNS]
        for name in old:
            shutil.rmtree(os.path.join(runs, name), ignore_errors=True)
