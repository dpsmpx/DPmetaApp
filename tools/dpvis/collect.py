"""Срез `dpvis-state/1`: модель + клоны → наблюдения и состояние (ТЗ §6.3, §7.2).

Источник — только история git клонов: к сайту и сервисам сборщик не
обращается (D-19).

Срез — функция модели, клонов, времени среза и правил (D-04): часы не
читаются, если время задано; ключи и списки упорядочены. В срез попадают
только счётчики, время, хеши и имена веток (D-08).
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, datetime, time as dtime, timedelta, timezone
from pathlib import Path

from . import VERSION, gitlog, registry, rules

STATE_SCHEMA = "dpvis-state/1"

DIAG_CODES = (
    "MODEL_INVALID", "CLONE_NOT_FOUND", "CLONE_SHALLOW", "REF_NOT_FOUND",
    "GIT_FAILED", "FETCH_FAILED", "REGISTRY_NOT_FOUND",
    "REGISTRY_SCHEMA_UNSUPPORTED", "REGISTRY_DRIFT", "CONTRACT_UNCOVERED",
    "CONTRACT_NOT_FOUND", "HOST_NOT_ALLOWED", "PROBE_FAILED",
    "PROBE_STATUS_UNEXPECTED", "PROBE_HEADER_MISSING", "SIGNAL_UNSUPPORTED",
)

def iso(moment: datetime | None) -> str | None:
    if moment is None:
        return None
    return moment.astimezone(timezone.utc).replace(microsecond=0).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_now(text: str | None) -> datetime:
    """Время среза: ISO 8601 с поясом или без (тогда UTC); нет — текущее."""
    if not text:
        return datetime.now(timezone.utc).replace(microsecond=0)
    moment = datetime.fromisoformat(text.replace("Z", "+00:00"))
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=timezone.utc)
    return moment.astimezone(timezone.utc).replace(microsecond=0)


@dataclass
class Window:
    now: datetime
    days: int
    start: date = field(init=False)
    end: date = field(init=False)
    since: datetime = field(init=False)

    def __post_init__(self) -> None:
        self.end = self.now.astimezone(rules.TZ).date()
        self.start = self.end - timedelta(days=self.days - 1)
        self.since = datetime.combine(self.start, dtime(0), tzinfo=rules.TZ).astimezone(timezone.utc)

    def index(self, moment: datetime) -> int | None:
        """Номер дня окна для момента; вне окна или позже среза — None."""
        if moment > self.now or moment < self.since:
            return None
        return (moment.astimezone(rules.TZ).date() - self.start).days

    def histogram(self, moments: list[datetime]) -> list[int]:
        days = [0] * self.days
        for m in moments:
            i = self.index(m)
            if i is not None:
                days[i] += 1
        return days

    def hours_since(self, moment: datetime) -> float:
        return round((self.now - moment).total_seconds() / 3600, 2)


class Diagnostics:
    def __init__(self) -> None:
        self.items: list[dict] = []

    def add(self, code: str, subject: str, message: str) -> None:
        assert code in DIAG_CODES, code
        self.items.append({"code": code, "subject": subject, "message": message})

    def sorted(self) -> list[dict]:
        unique = {(d["code"], d["subject"], d["message"]): d for d in self.items}
        return [unique[k] for k in sorted(unique)]


# ----------------------------------------------------------------- клоны

def find_clone(node: dict, root: Path | None, overrides: dict[str, Path]) -> Path | None:
    """Клон узла: явный --clone, затем clone, имя из slug как есть, строчными, владелец/имя (F-10)."""
    if node["id"] in overrides:
        path = overrides[node["id"]]
        return path if gitlog.is_clone(path) else None
    if root is None:
        return None
    owner, name = node["repo"].split("/", 1)
    candidates = []
    if node.get("clone"):
        candidates.append(root / node["clone"])
    candidates += [root / name, root / name.lower(), root / owner / name, root / owner / name.lower()]
    seen = set()
    for path in candidates:
        if path in seen or not path.is_dir():
            continue
        seen.add(path)
        if not gitlog.is_clone(path) or not _origin_matches(path, name):
            continue
        return path
    return None


def _origin_matches(path: Path, name: str) -> bool:
    """Каталог — клон этого репозитория: адрес origin кончается его именем; без origin — да."""
    try:
        url = gitlog.git(path, "config", "--get", "remote.origin.url").strip()
    except gitlog.GitError:
        return True
    tail = url.rstrip("/").removesuffix(".git").rsplit("/", 1)[-1]
    return tail.lower() == name.lower()


@dataclass
class RepoData:
    path: Path
    ref: str
    main: list[gitlog.Commit]               # основная ветка, в окне, не позже среза
    all: list[gitlog.Commit]                # все ветки, в окне, не позже среза


def observe_repo(node: dict, path: Path, win: Window, diags: Diagnostics) -> tuple[dict, RepoData | None]:
    nid = node["id"]
    ref = gitlog.default_ref(path)
    if ref is None:
        diags.add("REF_NOT_FOUND", nid, f"в клоне {path.name} нет основной ветки (origin/HEAD, main, master)")
        return {"state": "unknown", "reasons": [{"rule": "no_ref"}]}, None
    try:
        main = [c for c in gitlog.commits(path, [ref], win.since) if c.time <= win.now]
        branches_all = gitlog.branch_refs(path)
        every = [c for c in gitlog.commits(path, sorted(set(branches_all) | {ref}), win.since)
                 if c.time <= win.now]
        head_out = gitlog.git(path, "log", "-1", "--format=%H%x1f%ct",
                              f"--before={int(win.now.timestamp())}", ref).strip()
        first = gitlog.first_commit_time(path, ref)
        shallow = gitlog.is_shallow(path)
        branches = gitlog.unmerged_branches(path, ref)
    except gitlog.GitError as exc:
        diags.add("GIT_FAILED", nid, str(exc))
        return {"state": "unknown", "reasons": [{"rule": "git_failed"}]}, None

    head_sha, head_time = None, None
    if head_out:
        sha, ct = head_out.split("\x1f")
        head_sha, head_time = sha, datetime.fromtimestamp(int(ct), tz=timezone.utc)
    partial = shallow and (first is None or first > win.since)
    if partial:
        diags.add("CLONE_SHALLOW", nid,
                  "неполный клон: граница истории внутри окна — числа за ранние дни занижены")

    def pick(commits: list[gitlog.Commit], kind: str) -> list[datetime]:
        if kind == "merge":
            return [c.time for c in commits if c.merge]
        if kind == "ai":
            return [c.time for c in commits if c.ai and not c.merge]
        if kind == "human":
            return [c.time for c in commits if not c.ai and not c.merge]
        return [c.time for c in commits]

    days = {k: win.histogram(pick(main, k)) for k in ("ai", "human", "merge")}
    days_all = {k: win.histogram(pick(every, k)) for k in ("ai", "human")}
    git_data = {
        "ref": ref,
        "head": head_sha,
        "head_time": iso(head_time),
        "first_time": iso(first) if not shallow else None,
        "history": "partial" if partial else ("shallow" if shallow else "complete"),
        "days": {"ai": days["ai"], "human": days["human"], "merges": days["merge"]},
        "days_all": days_all,
        "window": {"ai": sum(days["ai"]), "human": sum(days["human"]), "merges": sum(days["merge"])},
        "branches": [
            {"name": b.name, "ahead": b.ahead, "time": iso(b.time), "method": b.method}
            for b in branches
        ],
    }
    reasons = []
    if head_time is not None and win.hours_since(head_time) <= rules.MAIN_ACTIVE_HOURS:
        reasons.append({"rule": "main", "hours": win.hours_since(head_time)})
    fresh = [b for b in branches if win.hours_since(b.time) <= rules.BRANCH_ACTIVE_HOURS]
    if fresh:
        reasons.append({"rule": "branch", "hours": win.hours_since(fresh[0].time),
                        "branch": fresh[0].name})
    activity = {
        "state": "working" if reasons else "idle",
        "last": iso(max([t for t in [head_time] + [b.time for b in branches] if t is not None],
                        default=None)),
        "reasons": reasons,
    }
    return {"git": git_data, "activity": activity}, RepoData(path, ref, main, every)


# --------------------------------------------------------------- потоки

def flow_state(flow: dict, nodes_state: dict, repos: dict[str, RepoData],
               win: Window, diags: Diagnostics) -> dict:
    status = flow["status"]
    if status != "active":
        return {"state": status}
    signal = flow.get("signal")
    if signal is None:
        return {"state": "unobserved"}
    stype = signal["type"]
    data = repos.get(signal["repo"])
    if data is None:
        return {"state": "unknown", "signal": stype, "reason": "нет клона репозитория сигнала"}
    if stype == "git-commits":
        pool = data.all if signal.get("scope", "main") == "all" else data.main
        who = signal.get("who", "any")
        if who == "merge":
            times = [c.time for c in pool if c.merge]
        elif who == "ai":
            times = [c.time for c in pool if c.ai and not c.merge]
        elif who == "human":
            times = [c.time for c in pool if not c.ai and not c.merge]
        else:
            times = [c.time for c in pool]
    elif stype == "git-path":
        try:
            found = gitlog.commits(data.path, [data.ref], win.since, paths=signal["paths"])
        except gitlog.GitError as exc:
            diags.add("GIT_FAILED", flow["id"], str(exc))
            return {"state": "unknown", "signal": stype, "reason": "git не прочитал пути"}
        times = [c.time for c in found if c.time <= win.now]
    else:
        diags.add("SIGNAL_UNSUPPORTED", flow["id"], f"сигнал {stype!r} не поддержан")
        return {"state": "unknown", "signal": stype}
    return _events_state(stype, times, win)


def _events_state(stype: str, times: list[datetime], win: Window) -> dict:
    days = win.histogram(times)
    inside = [t for t in times if win.index(t) is not None]
    last = max(inside, default=None)
    week_from = win.now - timedelta(days=rules.RATE_DAYS)
    week = sum(1 for t in inside if t > week_from)
    recent = last is not None and win.hours_since(last) <= rules.FLOW_RECENT_HOURS
    return {"state": "flowing" if recent else "quiet", "signal": stype, "days": days,
            "total": sum(days), "week": week, "last": iso(last)}


def actor_state(name: str, times: list[datetime], win: Window) -> dict:
    inside = [t for t in times if win.index(t) is not None]
    last = max(inside, default=None)
    reasons = []
    if last is not None and win.hours_since(last) <= rules.ACTOR_ACTIVE_HOURS:
        reasons.append({"rule": name, "hours": win.hours_since(last)})
    return {
        "days": win.histogram(inside),
        "activity": {"state": "working" if reasons else "idle", "last": iso(last), "reasons": reasons},
    }


# --------------------------------------------------------------- сборка

def collect(model: dict, root: Path | None, now: datetime, days: int = rules.WINDOW_DAYS,
            fetch: bool = False, overrides: dict[str, Path] | None = None) -> dict:
    overrides = overrides or {}
    win = Window(now=now, days=days)
    diags = Diagnostics()
    nodes_state: dict[str, dict] = {}
    repos: dict[str, RepoData] = {}

    for node in model["nodes"]:
        nid = node["id"]
        if node["kind"] == "repo":
            path = find_clone(node, root, overrides)
            if path is None:
                diags.add("CLONE_NOT_FOUND", nid, f"нет клона {node['repo']} в каталоге клонов")
                nodes_state[nid] = {"activity": {"state": "unknown", "last": None,
                                                 "reasons": [{"rule": "no_clone"}]}}
                continue
            if fetch:
                try:
                    gitlog.fetch(path)
                except gitlog.GitError as exc:
                    diags.add("FETCH_FAILED", nid, str(exc))
            state, data = observe_repo(node, path, win, diags)
            if "git" not in state:
                state = {"activity": {"state": state["state"], "last": None, "reasons": state["reasons"]}}
            nodes_state[nid] = state
            if data is not None:
                repos[nid] = data
        elif node["kind"] != "actor":
            st = node["status"] if node["status"] != "active" else "unobserved"
            nodes_state[nid] = {"activity": {"state": st, "last": None, "reasons": []}}

    # Участники — по всем репозиториям с клоном (F-16).
    human_times: list[datetime] = []
    ai_times: list[datetime] = []
    for data in repos.values():
        human_times += [c.time for c in data.main if c.merge or not c.ai]
        ai_times += [c.time for c in data.all if c.ai and not c.merge]
    for node in model["nodes"]:
        if node["kind"] != "actor":
            continue
        if node["id"] == "author":
            nodes_state[node["id"]] = actor_state("author", human_times, win)
        elif node["id"] == "claude":
            nodes_state[node["id"]] = actor_state("claude", ai_times, win)
        else:
            nodes_state[node["id"]] = {"activity": {"state": "unobserved", "last": None, "reasons": []}}

    flows_state = {f["id"]: flow_state(f, nodes_state, repos, win, diags) for f in model["flows"]}
    reg = registry.check(model, repos, diags)

    return {
        "schema": STATE_SCHEMA,
        "as_of": iso(win.now),
        "generated_by": {"tool": "DPvisualisation", "version": VERSION, "rules_version": rules.RULES_VERSION},
        "model": {"sha256": model["source"]["sha256"], "as_of": model["as_of"]},
        "options": {"online": False, "fetch": fetch, "days": days},   # online — всегда false (D-19)
        "window": {"start": win.start.isoformat(), "end": win.end.isoformat(), "days": days,
                   "timezone": rules.TZ_NAME},
        "rules": {
            "main_active_hours": rules.MAIN_ACTIVE_HOURS,
            "branch_active_hours": rules.BRANCH_ACTIVE_HOURS,
            "actor_active_hours": rules.ACTOR_ACTIVE_HOURS,
            "flow_recent_hours": rules.FLOW_RECENT_HOURS,
            "rate_days": rules.RATE_DAYS,
        },
        "nodes": nodes_state,
        "flows": flows_state,
        "registry": reg,
        "diagnostics": diags.sorted(),
    }
