"""Сбор набора данных прогресса из клонов репозиториев (Д38).

Запускается в сессии, где рядом лежат клоны репозиториев экосистемы со всеми
ветками и историей с начала периода:

    python3 -m tools.cs progress collect --root .. --start 2026-09-11 --end 2026-10-05 [--id 2026-10-05]

Что измеряется — по каждому репозиторию из `data/progress/scope.yaml`, по дням
периода (часовой пояс — `timezone` в scope):

- коммиты автора и Claude во всех ветках; копия изменения после rebase или
  cherry-pick считается один раз (patch-id); коммиты чужих авторов (исходные
  проекты форков) не считаются;
- где коммит сейчас: в основной ветке или только в ветке; коммиты, которые
  меняют только документы;
- слияния PR в основную ветку и время от первого коммита порции до слияния;
- сколько разных веток получили коммиты в этот день;
- на конец дня, по основной ветке: строки текстовых файлов по видам (код,
  тесты, документы, прочее), записи журнала решений и счётчики документов;
- значения из файлов проекта на даты, когда они менялись (`reported`):
  то, что проект сам о себе записал, — не перемеренное здесь;
- на момент среза: невлитые ветки — на ревью (открытый PR, передаётся
  аргументом), активные (последний коммит не старше ACTIVE_HOURS),
  только документы и отложенные.

В набор не попадают имена веток, сообщения коммитов, адреса почты, пути
файлов и имена закрытых репозиториев, которые не названы публично
(`visibility: undisclosed` — только ключ). Набор — вход сборки сайта;
клоны для сборки не нужны.
"""
from __future__ import annotations

import datetime as dt
import re
import subprocess
from collections import Counter
from pathlib import Path

METHOD = 1  # версия методики: изменилась — новый набор данных, прежние не пересчитываются молча
ACTIVE_HOURS = 72  # невлитая ветка «активна», если последний коммит не старше этого к срезу
BRANCH_STATES = ("in_review", "active", "docs", "deferred")  # открытый PR; свежая; только документы; отложенная

CODE_EXT = set("py c h cc cpp hpp cxx hxx cm dpm kt kts java go js mjs ts css html htm sh bash vert frag comp "
               "glsl geom gd pas lua rs gradle cmake mk bat ps1 swift m mm sql".split())
DOC_EXT = {"md", "txt", "rst", "adoc"}
TEST_DIRS = {"test", "tests", "testing", "testdata", "hostcheck", "fuzz", "androidtest", "selftest"}
DATA_EXT_IN_TESTS = {"out", "args", "toml", "json", "jsonl", "yaml", "yml", "expected", ""}
DECISIONS_DEFAULT = r"^#{2,4}\s+\[?(?:Р|Д|D-|V-|M-|ADR-)\d+"
PR_MERGE = re.compile(r"Merge pull request #(\d+) from [^/\s]+/(\S+)")


def category(path: str) -> str:
    """Вид файла для счёта строк: code | tests | docs | other."""
    p = path.lower()
    name = p.rsplit("/", 1)[-1]
    ext = name.rsplit(".", 1)[-1] if "." in name else ""
    segs = p.split("/")
    if "bench/results" in p or ("/archive/" in f"/{p}" and ext not in DOC_EXT):
        return "other"
    if ext in DOC_EXT or name in ("license", "license_ru", "readme", "todo"):
        return "docs"
    is_test = (any(s in TEST_DIRS for s in segs[:-1]) or name.startswith(("test_", "selftest"))
               or re.search(r"(_test|test)\.[a-z0-9]+$", name) is not None)
    if is_test and (ext in CODE_EXT or ext in DATA_EXT_IN_TESTS):
        return "tests"
    if ext in CODE_EXT or name in ("dpv", "gradlew", "makefile", "dockerfile", "cmakelists.txt"):
        return "code"
    return "other"


def git(path: Path, *args: str, check: bool = True) -> str:
    r = subprocess.run(["git", "-C", str(path), "-c", "core.quotepath=off", *args], capture_output=True, text=True)
    if check and r.returncode != 0:
        raise RuntimeError(f"git {' '.join(args[:3])}… в {path.name}: {r.stderr.strip()[:300]}")
    return r.stdout


def patch_ids(path: Path, since: str) -> dict[str, str]:
    """sha → patch-id (--stable) для коммитов без слияний во всех удалённых ветках."""
    log = subprocess.Popen(["git", "-C", str(path), "log", "--remotes=origin", f"--since={since}", "--no-merges", "-p",
                            "--format=commit %H"], stdout=subprocess.PIPE)
    pid = subprocess.run(["git", "-C", str(path), "patch-id", "--stable"], stdin=log.stdout, capture_output=True, text=True)
    log.wait()
    out = {}
    for line in pid.stdout.splitlines():
        p, c = line.split()
        out[c] = p
    return out


def find_clone(root: Path, slug: str, explicit: dict[str, Path]) -> Path | None:
    """Клон репозитория: явный путь, иначе каталог с именем репозитория в root или root/<владелец> (регистр не важен)."""
    if slug in explicit:
        return explicit[slug]
    owner, name = slug.split("/", 1)
    for base in (root, root / owner, root / owner.lower()):
        if not base.is_dir():
            continue
        for d in base.iterdir():
            if d.is_dir() and d.name.lower() == name.lower() and (d / ".git").exists():
                url = git(d, "remote", "get-url", "origin", check=False).strip().lower().removesuffix(".git")
                if url.endswith("/" + slug.lower()):
                    return d
    return None


def main_ref(path: Path) -> str:
    for ref in ("origin/main", "origin/master"):
        if git(path, "rev-parse", "--verify", "-q", ref, check=False).strip():
            return ref
    raise RuntimeError(f"{path.name}: нет origin/main и origin/master")


class Period:
    def __init__(self, start: dt.date, end: dt.date, tz: dt.timezone, cutoff: dt.datetime):
        self.tz = tz
        self.start = dt.datetime.combine(start, dt.time(), tz)
        self.cutoff = cutoff.astimezone(tz)
        last = min(end, self.cutoff.date())
        self.days = [(start + dt.timedelta(days=i)).isoformat() for i in range((last - start).days + 1)]
        self.index = {d: i for i, d in enumerate(self.days)}

    def day(self, ts: int) -> str:
        return dt.datetime.fromtimestamp(ts, self.tz).date().isoformat()

    def day_end(self, i: int) -> int:
        end = dt.datetime.fromisoformat(self.days[i]).replace(tzinfo=self.tz) + dt.timedelta(days=1)
        return int(min(end, self.cutoff).timestamp())

    def inside(self, ts: int) -> bool:
        return self.start.timestamp() <= ts <= self.cutoff.timestamp()


def _commits(path: Path, since: str) -> dict[str, dict]:
    fmt = "%x1e%H%x1f%P%x1f%an%x1f%at%x1f%ct%x1f%s"
    raw = git(path, "log", "--remotes=origin", f"--since={since}", f"--format={fmt}", "--numstat")
    out = {}
    for chunk in raw.split("\x1e")[1:]:
        head, _, rest = chunk.partition("\n")
        sha, parents, an, at, ct, subj = head.split("\x1f")
        files = []
        for line in rest.strip().splitlines():
            parts = line.split("\t")
            if len(parts) == 3:
                f = parts[2]
                if "=>" in f:
                    f = re.sub(r"\{([^}]*) => ([^}]*)\}", r"\2", f).split(" => ")[-1]
                files.append(f)
        out[sha] = {"sha": sha, "parents": parents.split(), "an": an, "at": int(at), "ct": int(ct), "subj": subj, "files": files}
    return out


def _snapshot(path: Path, sha: str, counters: list[dict], decisions_re: re.Pattern) -> dict:
    sizes = Counter()
    for line in git(path, "grep", "-I", "-c", "", sha, check=False).splitlines():
        body = line[len(sha) + 1:]
        f, _, n = body.rpartition(":")
        if n.isdigit():
            sizes[category(f)] += int(n)
    files = git(path, "ls-tree", "-r", "--name-only", sha).splitlines()
    dec = 0
    for f in files:
        if f.endswith("DECISIONS.md") and "archive" not in f.lower():
            dec = max(dec, len(decisions_re.findall(git(path, "show", f"{sha}:{f}", check=False))))
    cnt = {"decisions": dec}
    for c in counters:
        if c.get("glob"):
            pat = re.compile("^" + re.escape(c["glob"]).replace(r"\*", "[^/]*") + "$")
            cnt[c["id"]] = sum(1 for f in files if pat.match(f))
        else:
            text = git(path, "show", f"{sha}:{c['file']}", check=False) if c["file"] in files else ""
            cnt[c["id"]] = len(re.findall(c["pattern"], text, re.M)) if text else None
    lines = {k: sizes.get(k, 0) for k in ("code", "tests", "docs", "other")}
    return {"lines": lines, "counters": cnt}


def _reported(path: Path, mref: str, spec: dict, period: Period) -> list[dict]:
    """Значения, которые проект записал о себе в файл (`reported`): точки на даты изменения, без продолжения между ними."""
    pat = re.compile(spec["pattern"], re.M)
    revs = git(path, "log", mref, "--first-parent", "--format=%H %ct",
               f"--until={int(period.cutoff.timestamp())}", "--", spec["file"]).split("\n")
    points: dict[str, int] = {}
    before = None
    for row in reversed([r for r in revs if r.strip()]):
        sha, ct = row.split()
        text = git(path, "show", f"{sha}:{spec['file']}", check=False)
        m = pat.search(text)
        if not m:
            continue
        val = int(re.sub(r"\D", "", m.group(1)))
        if int(ct) < period.start.timestamp():
            before = val
            continue
        points[period.day(int(ct))] = val  # последнее значение дня
    out, prev = [], before
    if before is not None:
        out.append({"date": period.days[0], "value": before, "carried": True})
    for day in sorted(points):
        if points[day] != prev:
            out.append({"date": day, "value": points[day]})
            prev = points[day]
    return out


def collect_repo(spec: dict, path: Path, period: Period, authors: dict, open_prs: set[str],
                 decisions_re: re.Pattern) -> dict:
    n = len(period.days)
    mref = main_ref(path)
    since = (period.start - dt.timedelta(days=1)).isoformat()
    main_set = set(git(path, "rev-list", mref).split())
    commits = _commits(path, since)
    pids = patch_ids(path, since)
    main_pids = {pids[c] for c in commits if c in main_set and c in pids}
    own = set(authors["ai"]) | set(authors["human"])

    branch_of: dict[str, str] = {}
    pr_minutes: list[int] = []
    daily = {k: [0] * n for k in ("commits", "ai", "human", "mainline", "branch_only", "docs_only", "merges")}
    for c in commits.values():
        m = PR_MERGE.match(c["subj"])
        if not (m and len(c["parents"]) == 2 and c["sha"] in main_set):
            continue
        inner = git(path, "rev-list", f"{c['parents'][0]}..{c['parents'][1]}", check=False).split()
        firsts = [commits[s]["at"] for s in inner if s in commits and commits[s]["an"] in own and len(commits[s]["parents"]) == 1]
        for s in inner:
            branch_of.setdefault(s, m.group(2))
        if period.inside(c["ct"]):
            daily["merges"][period.index[period.day(c["ct"])]] += 1
            if firsts:
                pr_minutes.append(max(0, round((c["ct"] - min(firsts)) / 60)))

    now = {f"{s}{x}": 0 for s in BRANCH_STATES for x in ("", "_commits")}
    refs = [r for r in git(path, "for-each-ref", "--format=%(refname:short)", "refs/remotes/origin").split()
            if r not in ("origin/HEAD", "origin", mref)]
    for ref in refs:
        br = ref[len("origin/"):]
        for s in git(path, "rev-list", f"{mref}..{ref}", check=False).split():
            branch_of.setdefault(s, br)
        plus = [l[2:] for l in git(path, "cherry", mref, ref, check=False).splitlines() if l.startswith("+ ")]
        plus = [s for s in plus if s in commits and commits[s]["an"] in own and commits[s]["at"] <= period.cutoff.timestamp()]
        if not plus:
            continue
        last = max(commits[s]["at"] for s in plus)
        if last < period.start.timestamp():
            continue
        docs = all(commits[s]["files"] and all(category(f) == "docs" for f in commits[s]["files"]) for s in plus)
        state = ("in_review" if br in open_prs else "docs" if docs else
                 "active" if period.cutoff.timestamp() - last <= ACTIVE_HOURS * 3600 else "deferred")
        now[state] += 1
        now[state + "_commits"] += len(plus)

    branches_day = [set() for _ in range(n)]
    seen = set()
    first_own = None
    for c in sorted(commits.values(), key=lambda x: x["at"]):
        if c["an"] not in own or len(c["parents"]) > 1 or not period.inside(c["at"]):
            continue
        in_main = c["sha"] in main_set
        p = pids.get(c["sha"])
        if not in_main and p is not None and (p in main_pids or p in seen):
            continue  # копия уже учтённого изменения
        if p is not None:
            seen.add(p)
        i = period.index[period.day(c["at"])]
        first_own = first_own or period.days[i]
        daily["commits"][i] += 1
        daily["ai" if c["an"] in authors["ai"] else "human"][i] += 1
        daily["mainline" if in_main else "branch_only"][i] += 1
        if c["files"] and all(category(f) == "docs" for f in c["files"]):
            daily["docs_only"][i] += 1
        br = branch_of.get(c["sha"])
        if br:
            branches_day[i].add(br)
    daily["branches"] = [len(s) for s in branches_day]

    root_commit = git(path, "rev-list", "--max-parents=0", mref).split()
    created = None
    shallow = git(path, "rev-parse", "--is-shallow-repository").strip() == "true"
    if root_commit and not shallow:
        created = period.day(int(git(path, "log", "-1", "--format=%at", root_commit[-1]).strip()))
    lines, counters = [], {"decisions": []}
    extra = spec.get("counters") or []
    for c in extra:
        counters[c["id"]] = []
    for i in range(n):
        sha = git(path, "rev-list", "-1", "--first-parent", f"--before={period.day_end(i)}", mref).strip()
        if not sha:
            lines.append(None)
            for k in counters:
                counters[k].append(None)
            continue
        snap = _snapshot(path, sha, extra, decisions_re)
        lines.append(snap["lines"])
        for k in counters:
            counters[k].append(snap["counters"].get(k))
    reported = {r["id"]: _reported(path, mref, r, period) for r in spec.get("reported") or []}
    head = git(path, "rev-list", "-1", f"--before={int(period.cutoff.timestamp())}", mref).strip()
    boundary = None
    if shallow:
        oldest = git(path, "log", mref, "--reverse", "--format=%at").split()
        boundary = period.day(int(oldest[0])) if oldest else None
    return {
        "head": head, "main": mref.split("/", 1)[1],
        "history": "complete" if not shallow or (boundary and boundary < period.days[0]) else "partial",
        "created": created, "first_commit_in_period": first_own,
        "daily": daily, "lines": lines, "counters": counters, "reported": reported,
        "pr_minutes": sorted(pr_minutes), "branches_now": now,
    }


def collect(scope: dict, root: Path, start: dt.date, end: dt.date, cutoff: dt.datetime | None = None,
            clones: dict[str, Path] | None = None, open_prs: list[str] = (), dataset_id: str | None = None) -> dict:
    """Собрать набор данных (progress/v1). open_prs — «ключ:ветка» открытых PR на момент среза (из GitHub)."""
    sign, hh, mm = re.match(r"([+-])(\d\d):(\d\d)", scope["timezone"]).groups()
    off = dt.timedelta(hours=int(hh), minutes=int(mm)) * (1 if sign == "+" else -1)
    tz = dt.timezone(off)
    cutoff = (cutoff or dt.datetime.now(tz)).replace(second=0, microsecond=0)
    period = Period(start, end, tz, cutoff)
    decisions_re = re.compile(scope.get("decision_heading") or DECISIONS_DEFAULT, re.M)
    review: dict[str, set] = {}
    for x in open_prs:
        k, _, b = x.partition(":")
        review.setdefault(k, set()).add(b)
    repos, missing = {}, []
    for spec in scope["repos"]:
        path = find_clone(root, spec["slug"], clones or {})
        if path is None:
            missing.append(spec["key"])
            continue
        repos[spec["key"]] = collect_repo(spec, path, period, scope["authors"], review.get(spec["key"], set()), decisions_re)
    if missing:
        raise RuntimeError("нет клонов: " + ", ".join(missing) + " — клонировать рядом (--root) или указать --clone ключ=путь")
    return {
        "schema": "progress/v1",
        "id": dataset_id or period.days[-1],
        "period": {"start": period.days[0], "end": period.days[-1], "timezone": scope["timezone"],
                   "cutoff": cutoff.isoformat(timespec="minutes")},
        "method": {"version": METHOD, "active_hours": ACTIVE_HOURS, "tool": "tools/cs/progress/collect.py",
                   "open_prs_source": "GitHub API, открытые PR на момент среза" if open_prs else None},
        "days": period.days,
        "repos": repos,
    }
