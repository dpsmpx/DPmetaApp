"""Набор данных прогресса и производные величины (Д38).

Progress(repo, id) читает:

    data/progress/datasets/<id>.json   измерения (collect.py): ряды по дням, снимки на конец дня
    data/progress/scope.yaml           публичные имена, группы, слои, связи; граница публичного
    data/progress/milestones.yaml      вехи с доказательствами

и считает производные величины. У каждой — формула (METRICS), она же выводится
в методике на странице. Подстановка `{{progress:<id>.<величина>}}` (inject.py)
берёт значение отсюда; отпечаток трёх файлов — зависимость варианта (Д28):
изменился набор — одобренный вариант устарел.
"""
from __future__ import annotations

import datetime as dt
import hashlib
import json
import statistics
from dataclasses import dataclass
from typing import Callable

from ..core.repo import Repo
from .collect import BRANCH_STATES

# Группы рядов: Galdhold, остальное ядро; управление, синхронизация и поддерживающий слой (DPmeta M-10, M-12).
# Архива (ранние проекты до репозитория игры и убранные автором) в счёте нет — слово автора 09.10.2026 (Д38).
GROUPS = ("galdhold", "core_other", "support")
LINE_KINDS = ("tests", "code", "docs")  # «прочее» (данные, схемы, журналы прогонов) в объём не входит

MONTHS = {
    "ru": ["января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря"],
    "en": ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"],
}
MONTHS_SHORT = {
    "ru": ["янв", "фев", "мар", "апр", "май", "июн", "июл", "авг", "сен", "окт", "ноя", "дек"],
    "en": ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"],
}
UNDISCLOSED = {"ru": "закрытый проект", "en": "undisclosed private project"}


def fmt_int(v: int | float, lang: str) -> str:
    s = f"{int(round(v)):,}"
    return s.replace(",", " ") if lang == "ru" else s


def fmt_date(day: str, lang: str, year: bool = True) -> str:
    d = dt.date.fromisoformat(day[:10])
    if lang == "ru":
        return f"{d.day} {MONTHS['ru'][d.month - 1]}" + (f" {d.year}" if year else "")
    return f"{d.day} {MONTHS['en'][d.month - 1]}" + (f" {d.year}" if year else "")


def fmt_short(day: str, lang: str) -> str:
    d = dt.date.fromisoformat(day)
    return f"{d.day:02d}.{d.month:02d}" if lang == "ru" else f"{MONTHS_SHORT['en'][d.month - 1]} {d.day}"


def fmt_value(v, unit: str, lang: str) -> str:
    if v is None:
        return "—"
    if unit == "count":
        return fmt_int(v, lang)
    if unit == "percent":
        return f"{int(v)} %" if lang == "ru" else f"{int(v)}%"
    if unit == "minutes":
        return f"{fmt_int(v, lang)} мин" if lang == "ru" else f"{fmt_int(v, lang)} min"
    if unit == "kilolines":  # вниз до тысяч, меньше 10 тысяч — до десятых (CLAIM_POLICY, §3)
        if v < 10000:
            s = f"{int(v // 100) / 10:.1f}"
            return f"{s.replace('.', ',')} тыс." if lang == "ru" else f"{s}k"
        k = int(v // 1000)
        return f"{fmt_int(k, lang)} тыс." if lang == "ru" else f"{fmt_int(k, lang)}k"
    if unit == "date":
        return fmt_date(v, lang)
    if unit == "datetime":
        d = dt.datetime.fromisoformat(v)
        return f"{fmt_date(v, lang)}, {d:%H:%M}" + (" МСК" if lang == "ru" else " Moscow time")
    return str(v)


@dataclass(frozen=True)
class Metric:
    unit: str
    ru: str
    en: str
    fn: Callable


class Progress:
    def __init__(self, repo: Repo, ds_id: str):
        self.repo, self.id = repo, ds_id
        self.path = repo.path("data", "progress", "datasets", f"{ds_id}.json")
        if not self.path.is_file():
            raise FileNotFoundError(f"набора прогресса {ds_id} нет: {self.path}")
        self.data = json.loads(self.path.read_text(encoding="utf-8"))
        self.scope = repo.yaml("data/progress/scope.yaml") or {}
        ms = repo.yaml("data/progress/milestones.yaml") or {}
        self.days: list[str] = self.data["days"]
        self.n = len(self.days)
        self.specs = {r["key"]: r for r in self.scope.get("repos", [])}
        self.keys = [k for k in self.specs if k in self.data["repos"]]
        self.milestones = sorted((m for m in ms.get("milestones", []) if self.days[0] <= str(m["date"]) <= self.days[-1]),
                                 key=lambda m: (str(m["date"]), m.get("order", 0)))

    # ------------------------------------------------------------ основа
    @property
    def fingerprint(self) -> str:
        h = hashlib.sha256()
        for rel in (f"data/progress/datasets/{self.id}.json", "data/progress/scope.yaml", "data/progress/milestones.yaml"):
            p = self.repo.path(rel)
            h.update(p.read_bytes() if p.is_file() else b"")
        return h.hexdigest()[:16]

    def name(self, key: str, lang: str) -> str:
        return self.specs[key].get("name") or UNDISCLOSED[lang]

    def group(self, key: str) -> str:
        if key == "galdhold":
            return "galdhold"
        return "core_other" if self.specs[key]["group"] == "core" else "support"

    def core(self) -> list[str]:
        return [k for k in self.keys if self.specs[k]["group"] == "core"]

    def supporting(self) -> list[str]:
        """Управление, синхронизация и поддерживающий слой (DPmeta M-10, M-12): в счёте, но не в ядре."""
        return [k for k in self.keys if self.specs[k]["group"] != "core"]

    def archived(self) -> set[str]:
        """Ключи архива из `excluded`: прежние наборы их содержат, страница их не показывает."""
        return {x["key"] for x in self.scope.get("excluded", []) if x.get("kind") == "archive" and x.get("key")}

    def listed(self, keys: list[str] | None = None) -> list[str]:
        """Кого можно показывать по отдельности (строка, узел карты, дата): все, кроме закрытых
        проектов без публичного имени — они только в общих суммах (Д38, фильтр Д39)."""
        keys = self.keys if keys is None else keys
        return [k for k in keys if self.specs[k]["visibility"] != "undisclosed"]

    def hidden_count(self, keys: list[str] | None = None) -> int:
        """Сколько репозиториев с работой в периоде входит только в суммы."""
        keys = self.keys if keys is None else keys
        return sum(1 for k in keys if k not in self.listed(keys) and any(self.daily(k, "commits")))

    def daily(self, key: str, field: str) -> list[int]:
        return self.data["repos"][key]["daily"][field]

    def total(self, field: str, keys: list[str] | None = None) -> list[int]:
        keys = self.keys if keys is None else keys
        return [sum(self.daily(k, field)[i] for k in keys) for i in range(self.n)]

    def by_group(self, field: str = "commits") -> dict[str, list[int]]:
        return {g: self.total(field, [k for k in self.keys if self.group(k) == g]) for g in GROUPS}

    def lines(self, key: str, i: int) -> dict | None:
        return self.data["repos"][key]["lines"][i]

    def core_lines(self) -> dict[str, list[int]]:
        out = {k: [0] * self.n for k in LINE_KINDS + ("other",)}
        for key in self.core():
            for i in range(self.n):
                snap = self.lines(key, i)
                if snap:
                    for k in out:
                        out[k][i] += snap[k]
        return out

    def counter(self, key: str, cid: str) -> list:
        return self.data["repos"][key]["counters"].get(cid) or [None] * self.n

    def decisions(self) -> list[int]:
        return [sum(self.counter(k, "decisions")[i] or 0 for k in self.core()) for i in range(self.n)]

    def first_day(self, key: str) -> str | None:
        """День появления репозитория в периоде: создан в периоде — дата первого коммита, иначе начало периода."""
        r = self.data["repos"][key]
        created = r.get("created")
        if created and created >= self.days[0]:
            return created
        return self.days[0] if any(r["daily"]["commits"]) or created else r.get("first_commit_in_period")

    def core_established(self) -> list[int]:
        firsts = [self.first_day(k) for k in self.core()]
        return [sum(1 for f in firsts if f and f <= d) for d in self.days]

    def active_repos(self) -> list[int]:
        return [sum(1 for k in self.keys if self.daily(k, "commits")[i]) for i in range(self.n)]

    def pr_minutes(self) -> list[int]:
        return sorted(m for k in self.keys for m in self.data["repos"][k]["pr_minutes"])

    def branches_now(self) -> dict[str, int]:
        out: dict[str, int] = {}
        for k in self.keys:
            for f, v in self.data["repos"][k]["branches_now"].items():
                out[f] = out.get(f, 0) + v
        return out

    def reported(self, key: str, rid: str) -> list[dict]:
        return self.data["repos"][key]["reported"].get(rid, [])

    @staticmethod
    def cumulative(xs: list[int]) -> list[int]:
        out, acc = [], 0
        for x in xs:
            acc += x
            out.append(acc)
        return out

    # ------------------------------------------------------------ величины
    def value(self, metric: str):
        m = METRICS.get(metric)
        if m is None:
            raise KeyError(metric)
        return m.fn(self)

    def formatted(self, metric: str, lang: str) -> str:
        return fmt_value(self.value(metric), METRICS[metric].unit, lang)

    def public(self) -> dict:
        """Публичная выжимка для /api/v1/progress.json — белый список полей (фильтр Д39).

        Только то, что видно на странице: ряды по дням, суммы, величины с формулами.
        Нет: head-коммитов, имён веток, состояния истории, счётчиков документов по
        файлам. Закрытый проект без имени — только в общих суммах: ни строки, ни ключа,
        ни даты появления (Д38).
        """
        per = self.data["period"]
        return {
            "version": 1, "dataset": self.id,
            "period": {k: per[k] for k in ("start", "end", "timezone", "cutoff")},
            "method_version": self.data["method"]["version"],
            "days": self.days,
            "repositories": [{"name": self.specs[k]["name"], "group": self.specs[k]["group"],
                              "visibility": self.specs[k]["visibility"], "first_day": self.first_day(k),
                              "commits": self.daily(k, "commits"), "merges": self.daily(k, "merges")} for k in self.listed()],
            "only_in_totals": self.hidden_count(),
            "commits": {"total": self.total("commits"), "ai": self.total("ai"), "author": self.total("human"),
                        "mainline": self.total("mainline"), "branch_only": self.total("branch_only"),
                        "docs_only": self.total("docs_only")},
            "merges": self.total("merges"), "branches_with_commits": self.total("branches"),
            "core_lines": {k: v for k, v in self.core_lines().items() if k in LINE_KINDS},
            "decisions": self.decisions(),
            "metrics": {k: self.value(k) for k in METRICS},
        }


def _pct(a: float, b: float) -> int | None:
    return int(a * 100 // b) if b else None


def _peak(p: Progress, series: list[int]) -> int:
    return max(range(p.n), key=lambda i: (series[i], -i))


def _core_size(p: Progress, i: int) -> int:
    cl = p.core_lines()
    return sum(cl[k][i] for k in LINE_KINDS)


METRICS: dict[str, Metric] = {
    "period_start": Metric("date", "первый день периода", "first day of the period", lambda p: p.days[0]),
    "period_end": Metric("date", "последний день периода", "last day of the period", lambda p: p.days[-1]),
    "cutoff": Metric("datetime", "момент среза: позже коммиты не учтены", "snapshot time: later commits are not counted",
                     lambda p: p.data["period"]["cutoff"]),
    "days": Metric("count", "календарных дат в периоде (последняя — до среза)", "calendar dates in the period (the last one until the snapshot)",
                   lambda p: p.n),
    "span_days": Metric("count", "дней от первого дня периода до последнего", "days from the first day of the period to the last",
                        lambda p: (dt.date.fromisoformat(p.days[-1]) - dt.date.fromisoformat(p.days[0])).days),
    "active_days": Metric("count", "дней хотя бы с одним коммитом", "days with at least one commit",
                          lambda p: sum(1 for x in p.total("commits") if x)),
    "commits": Metric("count", "Σ коммитов автора и Claude во всех ветках всех репозиториев; копия изменения — один раз",
                      "Σ commits by the author and Claude across all branches and repositories; a copied change counts once",
                      lambda p: sum(p.total("commits"))),
    "commits_ai": Metric("count", "Σ коммитов с автором Claude", "Σ commits authored by Claude", lambda p: sum(p.total("ai"))),
    "commits_author": Metric("count", "Σ коммитов от имени автора", "Σ commits under the author's name",
                             lambda p: sum(p.total("human"))),
    "ai_share": Metric("percent", "коммиты Claude ÷ все коммиты × 100, вниз до целого",
                       "Claude commits ÷ all commits × 100, rounded down", lambda p: _pct(sum(p.total("ai")), sum(p.total("commits")))),
    "galdhold_share": Metric("percent", "коммиты Galdhold ÷ все коммиты × 100", "Galdhold commits ÷ all commits × 100",
                             lambda p: _pct(sum(p.daily("galdhold", "commits")), sum(p.total("commits")))),
    "docs_only_share": Metric("percent", "коммиты, которые меняют только документы, ÷ все коммиты × 100",
                              "commits that change only documents ÷ all commits × 100",
                              lambda p: _pct(sum(p.total("docs_only")), sum(p.total("commits")))),
    "merges": Metric("count", "Σ слияний PR в основные ветки", "Σ pull requests merged into main branches",
                     lambda p: sum(p.total("merges"))),
    "peak_day": Metric("date", "день с наибольшим числом коммитов", "the day with the most commits",
                       lambda p: p.days[_peak(p, p.total("commits"))]),
    "peak_commits": Metric("count", "коммитов в этот день", "commits on that day",
                           lambda p: max(p.total("commits"))),
    "peak_merges": Metric("count", "слияний PR в день пика коммитов", "merges on the peak day",
                          lambda p: p.total("merges")[_peak(p, p.total("commits"))]),
    "peak_branches": Metric("count", "наибольшее число рабочих веток (PR и сохранившиеся ветки, без основных) с коммитами за день",
                            "the most working branches (pull request and surviving branches, not main) with commits in one day",
                            lambda p: max(p.total("branches"))),
    "branches_first_weeks": Metric("count", "то же за первые три недели периода (21 день)",
                                   "the same over the first three weeks of the period (21 days)",
                                   lambda p: max(p.total("branches")[:21])),
    "repos_active": Metric("count", "репозиториев в счёте хотя бы с одним коммитом", "repositories in scope with at least one commit",
                           lambda p: sum(1 for k in p.keys if any(p.daily(k, "commits")))),
    "repos_core": Metric("count", "репозиториев ядра (Д5) с работой в периоде", "core repositories with work in the period",
                         lambda p: sum(1 for k in p.core() if any(p.daily(k, "commits")))),
    "repos_core_new": Metric("count", "репозиториев ядра, созданных после первого дня периода",
                             "core repositories created after the first day of the period",
                             lambda p: sum(1 for k in p.core() if (p.first_day(k) or "") > p.days[0])),
    "pr_median_minutes": Metric("minutes", "медиана времени от первого коммита порции до слияния PR",
                                "median time from the first commit of a merged batch to the merge",
                                lambda p: int(statistics.median(p.pr_minutes())) if p.pr_minutes() else None),
    "pr_within_hour": Metric("percent", "PR, влитые не позже часа от первого коммита порции, ÷ все PR × 100",
                             "pull requests merged within an hour of the batch's first commit ÷ all × 100",
                             lambda p: _pct(sum(1 for m in p.pr_minutes() if m <= 60), len(p.pr_minutes()))),
    "unmerged_branches": Metric("count", "веток с коммитами, которых нет в основной ветке, на момент среза",
                                "branches with commits not in the main branch at snapshot time",
                                lambda p: sum(p.branches_now().get(s, 0) for s in BRANCH_STATES)),
    "unmerged_commits": Metric("count", "коммитов в этих ветках", "commits on those branches",
                               lambda p: sum(p.branches_now().get(s + "_commits", 0) for s in BRANCH_STATES)),
    "lines_start": Metric("kilolines", "строк кода, тестов и документов в основных ветках ядра на конец первого дня",
                          "lines of code, tests and documents in core main branches at the end of the first day",
                          lambda p: _core_size(p, 0)),
    "lines_end": Metric("kilolines", "то же на момент среза", "the same at snapshot time", lambda p: _core_size(p, p.n - 1)),
    "tests_start": Metric("kilolines", "строк в файлах тестов ядра на конец первого дня", "lines in core test files at the end of the first day",
                          lambda p: p.core_lines()["tests"][0]),
    "tests_end": Metric("kilolines", "то же на момент среза", "the same at snapshot time", lambda p: p.core_lines()["tests"][-1]),
    "decisions_end": Metric("count", "записей в журналах решений ядра на момент среза",
                            "entries in core decision logs at snapshot time", lambda p: p.decisions()[-1]),
    "decisions_first": Metric("date", "первый день, когда в ядре появился журнал решений",
                              "the first day a core decision log existed",
                              lambda p: next((p.days[i] for i, v in enumerate(p.decisions()) if v), None)),
    "hostcheck_start": Metric("count", "автоматических проверок Galdhold в начале периода — по README игры",
                              "Galdhold automated checks at the start — as reported in the game's README",
                              lambda p: (p.reported("galdhold", "hostcheck") or [{}])[0].get("value")),
    "hostcheck_end": Metric("count", "то же — последнее значение в периоде", "the same — last value in the period",
                            lambda p: (p.reported("galdhold", "hostcheck") or [{}])[-1].get("value")),
}
