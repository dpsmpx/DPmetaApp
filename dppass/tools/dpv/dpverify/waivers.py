"""Исключения `[[waiver]]` и базовая линия (ТЗ, §6.7; V-08, V-09).

После всех проверок прогона: исключение принимает находки насовсем, с
причиной (F-16); базовая линия — известные находки на момент подключения,
долг, который только уменьшается (F-18). Принятая находка видна в отчёте
(`accepted`) и прогон не роняет. Свои нарушения — находки `dpv.profile`
(F-17, F-18): их не принимает ни исключение, ни линия.

Судить исключение или запись линии можно, только если её проверка прошла
целиком в этом прогоне (✓ или ✗): пропущенная, упавшая с `!` или не
выбранная проверка (`--only`, уровень) находок не дала — «не приняло
ничего» тогда не значит «лишнее».
"""
import datetime
import json
import os
import re

from .errors import UsageError
from .model import ERROR, Finding, Status
from .profile import OWN_RULES

SECRETS = "security.secrets"  # F-20: не в линию
ITEM = re.compile(r"^\s*(?:[-*+]|\d+[.)])\s+")
FIELDS = ("fingerprint", "check", "rule", "path", "message", "reason", "date")


def _judged(results):
    return {r.id: r for r in results if r.status in (Status.PASS, Status.FAIL)}


def _covers(waiver, finding):
    if waiver.rule is not None and finding.rule != waiver.rule:
        return False
    if not waiver.paths.match(finding.path or ""):  # находка без пути (команда) — маска «**»
        return False
    return waiver.match is None or waiver.match.search(finding.match_text()) is not None


def todo_items(text):
    """Пункты TODO.md с продолжениями строк, пробелы схлопнуты: подстрока ищется по пункту."""
    items, cur = [], None
    for line in text.splitlines():
        if ITEM.match(line):
            cur = [line]
            items.append(cur)
        elif cur is not None and line.strip() and not line.lstrip().startswith("#"):
            cur.append(line)
        else:
            cur = None
    return [" ".join(" ".join(item).split()) for item in items]


def apply_waivers(profile, results, today, todo_text, all_paths, profile_rel):
    """[[waiver]] принимают находки; → находки dpv.profile: waiver-expired, waiver-todo-missing,
    waiver-unused и dead-glob маски исключения (ТЗ, §8.1)."""
    judged = _judged(results)
    items = todo_items(todo_text) if todo_text is not None else None
    todo = profile.process["todo"]
    extra = []
    for w in profile.waivers:
        what = f"{w.where} ({w.check}{'/' + w.rule if w.rule else ''}, {w.path})"
        evidence = f"{w.check}\0{w.rule or ''}\0{w.path}"
        if w.until is not None and today > w.until:
            extra.append(Finding("waiver-expired", f"{what} просрочено: until = {w.until.isoformat()}",
                                 path=profile_rel, evidence=evidence,
                                 hint="исправить находку и убрать исключение или продлить until с причиной (V-08)"))
            continue
        if w.todo is not None and (items is None or not any(w.todo in item for item in items)):
            where = f"нет {todo}" if items is None else f"в {todo} нет пункта с «{w.todo}»"
            extra.append(Finding("waiver-todo-missing", f"{what}: {where}", path=profile_rel, evidence=evidence,
                                 hint="пункт выполнен — исправить находку и убрать исключение; иначе — "
                                      "вернуть пункт или поправить todo (V-08)"))
            continue
        result = judged.get(w.check)
        if result is None:
            continue
        used = 0
        for f in result.findings:
            if f.accepted is None and _covers(w, f):
                f.accepted = "waiver"
                used += 1
        if used:
            continue
        if not any(w.paths.match(p) for p in all_paths) and not any(
                w.paths.match(f.path or "") for f in result.findings):
            where = f"{w.where}.path"
            extra.append(Finding("dead-glob", f"маска «{w.path}» в {where} ни с чем не совпала", path=profile_rel,
                                 evidence=f"{where}\0{w.path}",
                                 hint="исправить маску или убрать исключение из профиля (V-07)"))
        else:
            extra.append(Finding("waiver-unused", f"{what} не приняло ни одной находки", path=profile_rel,
                                 evidence=evidence, hint="находку исправили — убрать исключение (V-08)"))
    return extra


# ---- базовая линия ----

def load_baseline(repo, profile):
    """Записи базовой линии или None, если файла нет. Испорченный файл — код 2."""
    path = repo.abspath(profile.baseline)
    if not os.path.isfile(path):
        return None
    try:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, ValueError) as e:
        raise UsageError(f"{profile.baseline}: базовая линия не разобрана: {e}") from None
    entries = data.get("entries") if isinstance(data, dict) and data.get("schema") == 1 else None
    if not isinstance(entries, list) or not all(
            isinstance(e, dict) and all(isinstance(e.get(k), str) for k in FIELDS if k != "path") for e in entries):
        raise UsageError(f"{profile.baseline}: нужна схема 1 — {{\"schema\": 1, \"entries\": [{{{', '.join(FIELDS)}}}]}}; "
                         "запись — только dpv baseline write")
    if any(e["check"] == SECRETS for e in entries):
        raise UsageError(f"{profile.baseline}: находки {SECRETS} в базовую линию не принимаются (F-20) — "
                         "убрать запись; секрет удалить из репозитория и сменить")
    return entries


def apply_baseline(entries, results, baseline_rel):
    """Линия принимает свои находки; запись без находки — baseline-stale (F-18)."""
    if not entries:
        return []
    book = {(e["check"], e["fingerprint"]) for e in entries}
    seen = set()
    for r in results:
        for f in r.findings:
            key = (r.id, f.fingerprint)
            if key in book:
                seen.add(key)
                if f.accepted is None:
                    f.accepted = "baseline"
    judged = _judged(results)
    extra = []
    for e in entries:
        if e["check"] in judged and (e["check"], e["fingerprint"]) not in seen:
            where = f"{e['path']}: " if e.get("path") else ""
            extra.append(Finding("baseline-stale", f"запись линии без находки — {e['check']} [{e['rule']}] "
                                                   f"{where}{e['message']}",
                                 path=baseline_rel, evidence=e["fingerprint"],
                                 hint="исправлено — dpv baseline prune и закоммитить линию (V-09)"))
    return extra


def _sort(entries):
    return sorted(entries, key=lambda e: (e["check"], (e.get("path") or "").encode(), e["rule"], e["fingerprint"]))


def _write(repo, profile, entries):
    path = repo.abspath(profile.baseline)
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump({"schema": 1, "entries": _sort(entries)}, f, ensure_ascii=False, indent=2)
        f.write("\n")


def last_report(repo, profile):
    """report.json последнего прогона — только если он о нынешнем дереве: тот же коммит, профиль
    и состояние «грязное/чистое». Иначе код 2: линия по устаревшему отчёту — неправда."""
    path = repo.abspath(f"{profile.logs}/last/report.json")
    if not os.path.isfile(path):
        raise UsageError("нет отчёта прогона — сначала dpv run")
    try:
        with open(path, encoding="utf-8") as f:
            rep = json.load(f)
    except (OSError, ValueError) as e:
        raise UsageError(f"{profile.logs}/last/report.json не разобран: {e}") from None
    stale = []
    head = repo.head()
    if rep.get("commit") != head:
        stale.append(f"прогон — на коммите {(rep.get('commit') or '—')[:7]}, а HEAD — {(head or '—')[:7]}")
    if rep.get("profile_sha256") != profile.sha256:
        stale.append("профиль изменён после прогона")
    if rep.get("dirty") != repo.dirty():
        stale.append("незакоммиченное изменилось после прогона")
    if stale:
        raise UsageError("отчёт последнего прогона устарел: " + "; ".join(stale) + " — прогнать dpv run снова")
    return rep


def main(repo, profile, args, out):
    if not args or args[0] not in ("write", "prune", "show"):
        raise UsageError('baseline: write --reason "…" [--check ID] | prune | show')
    cmd, rest = args[0], args[1:]
    if cmd == "write":
        return _write_cmd(repo, profile, rest, out)
    if rest:
        raise UsageError(f"baseline {cmd}: лишний аргумент {rest[0]!r}")
    entries = load_baseline(repo, profile)
    if not entries:
        print(f"базовая линия пуста — {profile.baseline} нет" if entries is None
              else f"базовая линия пуста — {profile.baseline}", file=out)
        return 0
    if cmd == "show":
        print(f"базовая линия {profile.baseline}: записей {len(entries)}", file=out)
        by_check = {}
        for e in _sort(entries):
            by_check.setdefault(e["check"], []).append(e)
        for cid, items in by_check.items():
            print(f"  {cid} ({len(items)})", file=out)
            for e in items:
                where = f"{e['path']}  " if e.get("path") else ""
                print(f"      {where}[{e['rule']}] {e['message']} — {e['reason']} · {e['date']}", file=out)
        return 0
    rep = last_report(repo, profile)
    judged = {c["id"]: {f["fingerprint"] for f in c["findings"]}
              for c in rep["checks"] if c["status"] in ("pass", "fail")}
    keep = [e for e in entries if e["check"] not in judged or e["fingerprint"] in judged[e["check"]]]
    removed = len(entries) - len(keep)
    if not removed:
        print(f"базовая линия: убирать нечего — записей {len(entries)}", file=out)
        return 0
    if keep:
        _write(repo, profile, keep)
        print(f"базовая линия: убрано {removed}, осталось {len(keep)} — {profile.baseline}", file=out)
    else:
        os.remove(repo.abspath(profile.baseline))
        print(f"базовая линия: убрано {removed}, линия пуста — {profile.baseline} удалён", file=out)
    return 0


def _write_cmd(repo, profile, args, out):
    reason = check = None
    i = 0
    while i < len(args):
        if args[i] in ("--reason", "--check") and i + 1 < len(args):
            if args[i] == "--reason":
                reason = args[i + 1]
            else:
                check = args[i + 1]
            i += 2
            continue
        raise UsageError(f"baseline write: неизвестный аргумент {args[i]!r} — "
                         'dpv baseline write --reason "…" [--check ID]')
    if not reason or not reason.strip():
        raise UsageError('baseline write: нужна причина — --reason "…" (F-18)')
    if check == SECRETS:
        raise UsageError(f"{SECRETS} в базовую линию не принимается (F-20): секрет удалить и сменить")
    rep = last_report(repo, profile)
    checks = [c for c in rep["checks"] if check is None or c["id"] == check]
    if check is not None and not checks:
        raise UsageError(f"baseline write: проверки {check!r} нет в последнем прогоне")
    found = [(c["id"], f) for c in checks if c["status"] in ("pass", "fail") for f in c["findings"]
             if f["accepted"] is None and f["severity"] == ERROR and f["rule"] not in OWN_RULES]
    secrets = [f for cid, f in found if cid == SECRETS]
    if secrets:
        raise UsageError(f"найдено секретов: {len(secrets)} — {SECRETS} в базовую линию не принимается (F-20): "
                         "секрет удалить и сменить; остальное — dpv baseline write --check ID")
    entries = load_baseline(repo, profile) or []
    have = {(e["check"], e["fingerprint"]) for e in entries}
    today = datetime.date.today().isoformat()
    new = [{"fingerprint": f["fingerprint"], "check": cid, "rule": f["rule"], "path": f["path"],
            "message": f["message"], "reason": reason, "date": today}
           for cid, f in found if (cid, f["fingerprint"]) not in have]
    if not new:
        print("базовая линия: новых находок нет — записывать нечего", file=out)
        return 0
    _write(repo, profile, entries + new)
    print(f"базовая линия: записано {len(new)}, всего {len(entries) + len(new)} — {profile.baseline}; "
          "закоммитить вместе с пунктом TODO.md (TR-6)", file=out)
    return 0
