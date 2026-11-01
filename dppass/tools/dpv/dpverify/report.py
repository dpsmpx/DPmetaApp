"""Экран, report.json, report.md, сравнение отчётов, команда `dpv report` (ТЗ, §6.8, §9.3–9.5).

Экран и `report.md` строятся из словаря `report.json`: `dpv report`
повторяет экран прошлого прогона, а `report --md` — его итог для PR.
"""
import json
import os

from . import VERSION
from .checks import REGISTRY
from .errors import UsageError
from .model import SYMBOL, Status

SHOWN = 5  # находок на проверку на экране (N-06)
SYMBOLS = {s.value: sym for s, sym in SYMBOL.items()}
ACCEPTED = {"waiver": "исключениями", "baseline": "базовой линией"}


def duration(seconds):
    if seconds < 60:
        return f"{seconds:.1f}".replace(".", ",") + " с"
    m, s = divmod(int(round(seconds)), 60)
    if m < 60:
        return f"{m} мин {s} с" if s else f"{m} мин"
    h, m = divmod(m, 60)
    return f"{h} ч {m} мин"


def _mandatory(r):
    cls = REGISTRY.get(r.id) if r.kind == "builtin" else None
    return bool(cls and cls.MANDATORY)


def to_json(run):
    """Всё, кроме meta, детерминировано (F-23)."""
    checks = []
    for r in run.results:
        item = r.to_json()
        checks.append({"id": item.pop("id"), "kind": item.pop("kind"), "mandatory": _mandatory(r), **item})
    return {
        "schema": 1,
        "dpv": VERSION,
        "project": run.profile.name,
        "level": run.level,
        "mode": run.mode,
        "commit": run.commit,
        "dirty": run.dirty,
        "base": run.base,
        "profile_sha256": run.profile.sha256,
        "result": run.result(),
        "partial": run.partial(),
        "selection": run.selection,
        "checks": checks,
        "meta": {
            "started": run.started,
            "duration_s": round(run.duration, 3),
            "durations": {r.id: round(r.duration, 3) for r in run.results},
            "summaries": {r.id: r.summary for r in run.results if r.summary},
            "excerpts": {r.id: r.excerpt for r in run.results if r.excerpt},
            "level_requested": run.level_requested,
            "invocation": run.invocation,
            "only": run.only,
            "budget_exceeded": run.budget_exceeded,
            "host": run.host,
            "python": run.python,
            "run_dir": run.run_dir_rel,
        },
    }


def write_json(rep, path):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(rep, f, ensure_ascii=False, indent=2)
        f.write("\n")


def read_json(path, what="отчёт"):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        raise UsageError(f"нет {what} {path} — сначала dpv run") from None
    except (OSError, ValueError) as e:
        raise UsageError(f"{what} {path} не разобран: {e}") from None


# ---- экран ----

def _open(c):
    return [f for f in c["findings"] if f["accepted"] is None]


def _errors(c):
    return [f for f in _open(c) if f["severity"] == "error"]


def _warnings(c):
    return [f for f in _open(c) if f["severity"] == "warning"]


def _accepted(rep):
    counts = {}
    for c in rep["checks"]:
        for f in c["findings"]:
            if f["accepted"]:
                counts[f["accepted"]] = counts.get(f["accepted"], 0) + 1
    return counts


def _fmt(f):
    where = f["path"] or ""
    if f["path"] and f["line"]:
        where += f":{f['line']}"
    mark = "предупреждение: " if f["severity"] == "warning" else ""
    return f"{where + '  ' if where else ''}{mark}{f['message']}"


def screen(rep, verbose=False, logdir="build/dpv/last"):
    """Строки экрана по report.json (§9.3)."""
    lines = []
    meta, checks = rep["meta"], rep["checks"]
    loud = verbose or rep["result"] != "pass" or rep["partial"]
    sel = rep.get("selection")
    if loud:
        sha = rep["commit"][:7] if rep["commit"] else "без коммита"
        head = f"dpv {rep['dpv']} · {rep['project']} · {rep['level']} · {rep['mode']} · {sha}"
        if rep["dirty"]:
            head += " · грязное дерево"
        if sel is not None:
            head += f" · база {(sel['base'] or '—')[:7]} ({sel['source'] or 'нет'}), изменено {sel['changed']}"
        lines.append(head)
    if sel is not None and sel["full"]:
        lines.append(f"  · полный прогон: {sel['reason']}")
    width = max((len(c["id"]) for c in checks), default=0) + 2
    for c in checks:
        sym = SYMBOLS[c["status"]]
        if c["status"] == "skip":
            lines.append(f"  {sym} {c['id']:<{width}} пропуск: {c['skip_reason']}")
        elif c["status"] != "pass" or _warnings(c):
            lines.append(f"  {sym} {c['id']:<{width}} {_headline(c)}")
            lines += _details(c, meta, logdir, verbose)
        elif verbose:
            lines.append(f"  {sym} {c['id']:<{width}} {meta['summaries'].get(c['id'], '')}")
        if verbose:
            lines += [f"      принято {ACCEPTED[f['accepted']]}: {_fmt(f)}" for f in c["findings"] if f["accepted"]]
    for b in meta["budget_exceeded"]:
        lines.append(f"  · бюджет {b['level']} превышен: {duration(b['actual_s'])} при {duration(b['budget_s'])}")
    lines.append(_total(rep, logdir))
    return lines


def _headline(c):
    if c["status"] == "error":
        return "внутренняя ошибка DP Verify — итог проверки неизвестен"
    found = _open(c)
    if c["kind"] == "command" or len(found) == 1:
        return _fmt(found[0]) if found else (c["skip_reason"] or "")
    head = f"находок: {len(_errors(c))}"
    if _warnings(c):
        head += f", предупреждений: {len(_warnings(c))}"
    return head


def _details(c, meta, logdir, verbose):
    out, rest = [], 0
    found = _open(c)
    if c["kind"] != "command" and len(found) > 1:
        shown = found if verbose else found[:SHOWN]
        out += [f"      {_fmt(f)}" for f in shown]
        rest = len(found) - len(shown)
    out += [f"      {ln}" for ln in meta.get("excerpts", {}).get(c["id"], [])]
    log = f"{logdir}/{c['id']}.log"
    if c["status"] == "fail" and c["kind"] == "builtin" and _errors(c):
        log += f" · dpv explain {c['id']}/{_errors(c)[0]['rule']}"
    if rest > 0:
        out.append(f"      … ещё {rest} — {log}")
    elif c["status"] in ("fail", "error"):
        out.append(f"      → {log}")
    return out


def _total(rep, logdir):
    checks, level, meta = rep["checks"], rep["level"], rep["meta"]
    total = len(checks)
    tail = f"{duration(meta['duration_s'])} · {logdir}/"
    acc = _accepted(rep)
    accepted = (" · принято " + ", ".join(f"{ACCEPTED[k]}: {acc[k]}" for k in ACCEPTED if acc.get(k))) if acc else ""
    if rep["result"] == "error":
        n = sum(c["status"] == "error" for c in checks)
        return f"! {level} не завершён: внутренняя ошибка DP Verify в проверках: {n} — итог неполон · {tail}"
    if rep["result"] == "fail":
        failed = sum(c["status"] == "fail" for c in checks)
        found = sum(len(_errors(c)) for c in checks)
        return f"✗ {level} не пройден: упало проверок {failed} из {total} · находок: {found}{accepted} · {tail}"
    skipped = sum(c["status"] == "skip" for c in checks)
    if rep["partial"]:
        how = "выборочно (--only)" if meta.get("only") else "частично"
        return (f"○ {level} пройден {how}: проверок {total - skipped} из {total}, пропущено: {skipped}"
                f"{accepted} · {tail}")
    return f"✓ {level} пройден: проверок {total}{accepted} · {tail}"


# ---- report.md (§9.5) ----

def _cell(text):
    return " ".join(str(text).split()).replace("|", "\\|")


def _row_result(c, meta):
    sym = SYMBOLS[c["status"]]
    if c["status"] == "skip":
        return f"{sym} пропуск: {c['skip_reason']}"
    if c["status"] == "error":
        return f"{sym} сбой DP Verify"
    if c["status"] == "fail":
        found = _errors(c)
        first = _fmt(found[0]) if found else ""
        return f"{sym} " + (f"находок {len(found)}: {first}" if c["kind"] == "builtin" else first)
    summary = meta["summaries"].get(c["id"], "") if c["kind"] == "command" else ""
    if summary.startswith(sym):  # итог команды сам начинается с ✓ — без «✓ ✓»
        summary = summary[len(sym):].lstrip()
    warn = f" (предупреждений: {len(_warnings(c))})" if _warnings(c) else ""
    return f"{sym} {summary}".rstrip() + warn


def _is_mandatory(c):
    """Поле mandatory — с 0.3.0; в отчёте постарше — по реестру."""
    if "mandatory" in c:
        return c["mandatory"]
    cls = REGISTRY.get(c["id"]) if c["kind"] == "builtin" else None
    return bool(cls and cls.MANDATORY)


def md(rep):
    """Итог прогона — для раздела «Проверки» описания PR."""
    meta, checks = rep["meta"], rep["checks"]
    sha = rep["commit"][:7] if rep["commit"] else "без коммита"
    inv = meta.get("invocation") or f"dpv run {rep['level']}"
    if rep["result"] == "error":
        verdict = "! итог неполон: сбой DP Verify"
    elif rep["result"] == "fail":
        verdict = "✗ не пройдено"
    elif rep["partial"]:
        verdict = "○ пройдено выборочно (--only)" if meta.get("only") else "○ пройдено частично"
    else:
        verdict = "✓ пройдено"
    dirty = " (грязное дерево)" if rep["dirty"] else ""
    lines = [f"**DP Verify {rep['dpv']}** · `{inv}` · коммит `{sha}`{dirty} · {verdict}", ""]
    sel = rep.get("selection")
    if sel is not None:
        base = f"база `{sel['base'][:7]}` ({sel['source']})" if sel["base"] else f"без базы ({sel['source'] or '—'})"
        what = f"полный прогон — {sel['reason']}" if sel["full"] else (
            "команды full: " + (", ".join(sel["commands"]) or "нет"))
        lines += [f"Выбор по изменениям: {base}, изменено файлов {sel['changed']}; {what}", ""]
    lines += ["| Проверка | Итог |", "|---|---|"]
    mandatory = [c for c in checks if _is_mandatory(c)]
    if mandatory:
        bad = [c for c in mandatory if c["status"] != "pass"]
        if not bad:
            lines.append(f"| обязательный набор ({len(mandatory)}) | ✓ |")
        else:
            sym = "!" if any(c["status"] == "error" for c in bad) else "✗"
            lines.append(f"| обязательный набор ({len(mandatory)}) | {sym} не пройдено {len(bad)} |")
            lines += [f"| {c['id']} | {_cell(_row_result(c, meta))} |" for c in bad]
    optional = [c for c in checks if c["kind"] == "builtin" and not _is_mandatory(c)]
    good = [c["id"] for c in optional if c["status"] == "pass" and not _warnings(c)]
    if good:
        lines.append(f"| {', '.join(good)} | ✓ |")
    lines += [f"| {c['id']} | {_cell(_row_result(c, meta))} |" for c in optional if c["id"] not in good]
    lines += [f"| {c['id']} | {_cell(_row_result(c, meta))} |" for c in checks if c["kind"] == "command"]
    acc = _accepted(rep)
    lines += ["", f"Базовая линия: {acc.get('baseline', 0)} · исключений: {acc.get('waiver', 0)} · "
                  f"{duration(meta['duration_s'])}"]
    return lines


def write_md(rep, path):
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(md(rep)) + "\n")


# ---- report --diff ----

def _key(a, b):
    """Ключ элементов списков для сравнения: id проверки, отпечаток находки или номер."""
    for key in ("id", "fingerprint"):
        if (a or b) and all(isinstance(x, dict) and key in x for x in a + b) and all(
                len({x[key] for x in items}) == len(items) for items in (a, b)):
            return key
    return None


def _label(value):
    text = str(value)
    return text[:19] + "…" if text.startswith("sha256:") else text


def diff(a, b, where=""):
    """Различия двух значений: [«путь: было → стало»]."""
    if isinstance(a, dict) and isinstance(b, dict):
        out = []
        for k in sorted(set(a) | set(b)):
            path = f"{where}.{k}" if where else k
            if k not in a:
                out.append(f"{path}: нет → {json.dumps(b[k], ensure_ascii=False)}")
            elif k not in b:
                out.append(f"{path}: {json.dumps(a[k], ensure_ascii=False)} → нет")
            else:
                out += diff(a[k], b[k], path)
        return out
    if isinstance(a, list) and isinstance(b, list):
        key = _key(a, b)
        if key is None:
            out = []
            for i in range(max(len(a), len(b))):
                if i >= len(a):
                    out.append(f"{where}[{i}]: нет → {json.dumps(b[i], ensure_ascii=False)}")
                elif i >= len(b):
                    out.append(f"{where}[{i}]: {json.dumps(a[i], ensure_ascii=False)} → нет")
                else:
                    out += diff(a[i], b[i], f"{where}[{i}]")
            return out
        ia, ib = {x[key]: x for x in a}, {x[key]: x for x in b}
        out = []
        order = [x[key] for x in a] + [x[key] for x in b if x[key] not in ia]
        for k in order:
            path = f"{where}[{_label(k)}]"
            if k not in ib:
                out.append(f"{path}: есть → нет")
            elif k not in ia:
                out.append(f"{path}: нет → есть")
            else:
                out += diff(ia[k], ib[k], path)
        if not out and [x[key] for x in a] != [x[key] for x in b]:
            out.append(f"{where}: другой порядок")
        return out
    if a != b or type(a) is not type(b):
        return [f"{where}: {json.dumps(a, ensure_ascii=False)} → {json.dumps(b, ensure_ascii=False)}"]
    return []


# ---- dpv report ----

def main(repo, profile, args, out):
    md_mode, run_dir, pair = False, None, None
    i = 0
    while i < len(args):
        a = args[i]
        if a == "--md":
            md_mode = True
        elif a == "--run" and i + 1 < len(args):
            run_dir = args[i + 1]
            i += 1
        elif a == "--diff" and i + 2 < len(args):
            pair = args[i + 1:i + 3]
            i += 2
        else:
            raise UsageError(f"report: неизвестный или неполный аргумент {a!r} — "
                             "dpv report [--md] [--run КАТАЛОГ] | dpv report --diff A.json B.json")
        i += 1
    if pair is not None:
        if md_mode or run_dir:
            raise UsageError("report --diff — без --md и --run")
        return _diff(repo, pair, out)
    target = repo.abspath(run_dir) if run_dir else repo.abspath(f"{profile.logs}/last")
    path = target if target.endswith(".json") else os.path.join(target, "report.json")
    rep = read_json(path)
    logdir = os.path.relpath(os.path.dirname(path), repo.root).replace(os.sep, "/")
    for line in (md(rep) if md_mode else screen(rep, logdir=logdir)):
        print(line, file=out)
    return 0


def _diff(repo, pair, out):
    reps = []
    for p in pair:
        rep = read_json(repo.abspath(p) if not os.path.isabs(p) else p)
        rep.pop("meta", None)
        reps.append(rep)
    found = diff(reps[0], reps[1])
    if not found:
        print(f"✓ отчёты совпадают без meta: {pair[0]} и {pair[1]}", file=out)
        return 0
    print(f"✗ отчёты различаются без meta: {len(found)}", file=out)
    for line in found:
        print(f"  {line}", file=out)
    return 1
