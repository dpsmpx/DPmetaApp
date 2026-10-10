"""Фигуры страницы прогресса: SVG, подписи, таблицы данных, методика (Д38).

Фигура вставляется в текст страницы подстановкой `{{figure:<набор>.<фигура>}}`
(inject.py) — порядок и пояснения задаёт одобренный автором текст варианта,
а данные и подписи осей — эта функция. Каждая фигура:

- помечена видом данных: измерено | записано проектом | производное | веха;
- называет источник по виду («история git репозиториев»), а не по закрытому адресу;
- дублирует значения таблицей (`<details>`) — для экранного диктора и телефона.
"""
from __future__ import annotations

import datetime as dt
import html
import re
import statistics

from . import charts
from .dataset import (LINE_KINDS, METRICS, MONTHS_SHORT, Progress, fmt_date, fmt_int, fmt_short,
                      fmt_value)

E = html.escape

FIGURES = ("snapshot", "activity", "cumulative", "repos", "authorship", "parallel", "codebase", "verification",
           "knowledge", "ecosystem", "timeline", "method")

WEEKDAYS = {"ru": ["пн", "вт", "ср", "чт", "пт", "сб", "вс"], "en": ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]}

L = {
    "ru": {
        "kind": {"measured": "измерено", "reported": "записано проектом", "derived": "производное", "milestone": "вехи",
                 "facts": "факты реестра"},
        "src_git": "история git репозиториев в счёте, все ветки",
        "src_git_main": "история git, основные ветки на конец дня",
        "src_readme": "README игры: число на даты, когда оно менялось",
        "src_facts": "реестр фактов сайта: документы проектов с датой",
        "src_ms": "коммиты, PR, журналы решений и релизы; у каждой вехи — свой источник",
        "src_scope": "границы счёта и контракты DPmeta",
        "source": "Источник", "table": "Таблица значений", "date": "День", "total": "Всего",
        "groups": {"galdhold": "Galdhold", "core_other": "другие репозитории ядра", "support": "управление и поддерживающий слой"},
        "commits_n": "коммитов", "merges_n": "PR влито", "ai": "Claude", "human": "автор",
        "cum_commits": "Коммитов с начала периода", "cum_merges": "PR влито с начала периода",
        "core_est": "Репозиториев ядра с работой",
        "grp_core": "Ядро", "grp_sup": "Управление и поддерживающий слой", "commits_prs": "коммиты · PR", "sum": "Σ",
        "branches_day": "Рабочих веток с коммитами (без основных)", "cycle": "От первого коммита порции до слияния PR",
        "cycle_buckets": ["до 15 мин", "15–30 мин", "30–60 мин", "1–3 ч", "3–12 ч", "дольше 12 ч"],
        "now": "Невлитая работа на момент среза",
        "states": {"in_review": "на ревью (открытый PR)", "active": "активная ветка", "docs": "только документы",
                   "deferred": "отложена (без движения дольше 72 ч)"},
        "branches_n": "веток", "commits_in": "коммитов",
        "lines": {"tests": "тесты и проверки", "code": "код", "docs": "документы и решения"},
        "lines_title": "Строк в основных ветках ядра", "per_project": "По репозиториям на момент среза, тыс. строк",
        "tests_title": "Строк в файлах тестов на момент среза, тыс.", "hostcheck": "Автоматические проверки Galdhold (hostcheck)",
        "decisions_title": "Записей в журналах решений ядра", "decisions_per": "Записей в журнале решений, по репозиториям",
        "doc_counters": "Документы знания на момент среза",
        "counters": {"facts": "фактов (DPprofile)", "insights": "выводов (DPprofile)", "sources": "источников (DPprofile)",
                     "timeline": "строк хронологии (DPprofile)", "contracts": "контрактов между репозиториями (DPmeta)",
                     "risks": "рисков на суд автора (DPmeta)", "sync_queue": "пунктов очереди согласований (DPmeta)",
                     "principles": "общих инженерных принципов (DPmeta)", "reports": "срезов состояния экосистемы (DPmeta)"},
        "first_seen": "с", "planned": "план", "relations": "Связи между репозиториями",
        "since_start": "с начала периода", "snapshot": "Срез", "stage": "Стадия", "role": "Что это",
        "project": "Репозиторий", "fact_date": "на",
        "tiles": {"repos_core": "репозиториев ядра", "repos_active": "репозиториев с работой в периоде",
                  "lines_end": "строк кода, тестов и документов в ядре", "tests_end": "из них — в файлах тестов",
                  "decisions_end": "записей в журналах решений", "hostcheck_end": "автопроверок Galdhold (README)",
                  "unmerged_branches": "веток с невлитой работой"},
        "core_note": "из них один закрытый проект без имени",
        "only_totals": "Закрытый проект без публичного имени здесь не показан отдельно — он входит только в общие суммы.",
        "personal_n": "личные репозитории — {n}: заметки и планы; имена не публикуются",
        "archive_n": "архив — {n}: ранние проекты до репозитория игры и убранные автором; вне текущей системы, на странице не показываются",
        "ms_kind": {"decision": "решение автора", "verification": "проверки", "device": "замер на устройстве",
                    "release": "релиз", "start": "начало", "build": "сборка", "world": "мир игры", "graphics": "графика",
                    "process": "процесс", "platform": "платформы", "repository": "новый репозиторий",
                    "publishing": "сайт", "structure": "структура экосистемы"},
        "ev_kind": {"code": "история git", "doc": "документ проекта", "decision": "журнал решений проекта",
                    "measurement": "замер", "public_post": "публикация", "platform_page": "страница площадки"},
        "ev_ver": {"re-measured": "перемерено", "reported": "записано проектом", "observed": "видно на площадке",
                   "decision": "записанное решение", "self-reported": "со слов автора"},
        "m_metric": "Величина", "m_formula": "Формула", "m_value": "Значение",
        "m_period": "Период", "m_cutoff": "Срез", "m_method": "Методика", "m_repos": "Репозиториев в счёте",
        "m_excluded": "Не входят в счёт", "m_hidden": "На странице не публикуются",
        "hidden": ["имена веток, сообщения коммитов, адреса почты и пути файлов",
                   "имя и описание закрытого проекта, который ещё не назван публично, — только его числа в общих суммах",
                   "время суток коммитов (личный распорядок)", "код и выдержки из закрытых репозиториев (Д17)",
                   "оценки готовности в процентах — у проектов нет их определения"],
        "markers": "ромб под осью — веха дня",
        "partial": "5 октября учтено до {t}",
    },
    "en": {
        "kind": {"measured": "measured", "reported": "reported by the project", "derived": "derived", "milestone": "milestones",
                 "facts": "fact registry"},
        "src_git": "git history of the repositories in scope, all branches",
        "src_git_main": "git history, main branches at the end of each day",
        "src_readme": "the game's README: the number on the dates it changed",
        "src_facts": "the site's fact registry: dated project documents",
        "src_ms": "commits, pull requests, decision logs and releases; each milestone cites its own source",
        "src_scope": "scope boundaries and DPmeta contracts",
        "source": "Source", "table": "Values table", "date": "Day", "total": "Total",
        "groups": {"galdhold": "Galdhold", "core_other": "other core repositories", "support": "control and support layer"},
        "commits_n": "commits", "merges_n": "PRs merged", "ai": "Claude", "human": "author",
        "cum_commits": "Commits since the start", "cum_merges": "PRs merged since the start",
        "core_est": "Core repositories with work",
        "grp_core": "Core", "grp_sup": "Control and support layer", "commits_prs": "commits · PRs", "sum": "Σ",
        "branches_day": "Working branches with commits (main not counted)", "cycle": "From a batch's first commit to the PR merge",
        "cycle_buckets": ["under 15 min", "15–30 min", "30–60 min", "1–3 h", "3–12 h", "over 12 h"],
        "now": "Unmerged work at snapshot time",
        "states": {"in_review": "in review (open PR)", "active": "active branch", "docs": "documents only",
                   "deferred": "deferred (idle for over 72 h)"},
        "branches_n": "branches", "commits_in": "commits",
        "lines": {"tests": "tests and checks", "code": "code", "docs": "documents and decisions"},
        "lines_title": "Lines in core main branches", "per_project": "By repository at snapshot time, thousand lines",
        "tests_title": "Lines in test files at snapshot time, thousands", "hostcheck": "Galdhold automated checks (hostcheck)",
        "decisions_title": "Entries in core decision logs", "decisions_per": "Decision log entries by repository",
        "doc_counters": "Knowledge documents at snapshot time",
        "counters": {"facts": "facts (DPprofile)", "insights": "insights (DPprofile)", "sources": "sources (DPprofile)",
                     "timeline": "timeline rows (DPprofile)", "contracts": "contracts between repositories (DPmeta)",
                     "risks": "risks for the author to decide (DPmeta)", "sync_queue": "sync queue items (DPmeta)",
                     "principles": "shared engineering principles (DPmeta)", "reports": "ecosystem state reports (DPmeta)"},
        "first_seen": "since", "planned": "planned", "relations": "Links between repositories",
        "since_start": "since the start", "snapshot": "Snapshot", "stage": "Stage", "role": "What it is",
        "project": "Repository", "fact_date": "as of",
        "tiles": {"repos_core": "core repositories", "repos_active": "repositories with work in the period",
                  "lines_end": "lines of code, tests and documents in the core", "tests_end": "of them in test files",
                  "decisions_end": "decision log entries", "hostcheck_end": "Galdhold automated checks (README)",
                  "unmerged_branches": "branches with unmerged work"},
        "core_note": "including one undisclosed private project",
        "only_totals": "A private project without a public name is not shown separately — it is counted only in totals.",
        "personal_n": "personal repositories — {n}: notes and plans; names are not published",
        "archive_n": "archive — {n}: early projects that predate the game repository and ones the author removed; outside the current system, not shown here",
        "ms_kind": {"decision": "author's decision", "verification": "checks", "device": "device measurement",
                    "release": "release", "start": "start", "build": "build", "world": "game world", "graphics": "graphics",
                    "process": "process", "platform": "platforms", "repository": "new repository",
                    "publishing": "website", "structure": "ecosystem structure"},
        "ev_kind": {"code": "git history", "doc": "project document", "decision": "project decision log",
                    "measurement": "measurement", "public_post": "publication", "platform_page": "platform page"},
        "ev_ver": {"re-measured": "re-measured", "reported": "reported by the project", "observed": "observed on the platform",
                   "decision": "recorded decision", "self-reported": "stated by the author"},
        "m_metric": "Quantity", "m_formula": "Formula", "m_value": "Value",
        "m_period": "Period", "m_cutoff": "Snapshot", "m_method": "Method", "m_repos": "Repositories in scope",
        "m_excluded": "Not counted", "m_hidden": "Not published on this page",
        "hidden": ["branch names, commit messages, email addresses and file paths",
                   "the name and description of a private project not yet named publicly — only its numbers within totals",
                   "time of day of commits (a personal routine)", "code or excerpts from private repositories",
                   "readiness percentages — the projects do not define them"],
        "markers": "a diamond under the axis marks a milestone",
        "partial": "5 October counted until {t}",
    },
}

MS_CLASS = {"decision": "c-human", "process": "c-human", "verification": "c-ver", "device": "c-ver", "release": "c-ver"}
STAGE_FACTS = {"galdhold": "GH-STAGES", "dplang": "DP-STAGE", "dpverify": "VRF-STAGE", "smi": "SMI-SITE",
               "dpprofile": "PRF-STAGE", "dplearn": "LRN-STAGE", "market": "MKT-STAGE", "dpmeta": "META-STAGE",
               "orchestrator": "ORC-STAGE"}
BINS = [2, 5, 10, 20, 40]
CYCLE_EDGES = [15, 30, 60, 180, 720]


class Fig:
    """Сборщик фигур одного набора на одном языке."""

    def __init__(self, p: Progress, lang: str):
        self.p, self.lang, self.t = p, lang, L[lang]
        self.days = p.days
        self.facts_used: list[str] = []

    # ------------------------------------------------------------ общее
    def labels(self) -> list[str]:
        return [str(int(d[8:])) for d in self.days]

    def months(self) -> dict[int, str]:
        return {i: MONTHS_SHORT[self.lang][int(d[5:7]) - 1] for i, d in enumerate(self.days) if i == 0 or d.endswith("-01")}

    def day_long(self, i: int) -> str:
        d = dt.date.fromisoformat(self.days[i])
        out = f"{fmt_date(self.days[i], self.lang, year=False)}, {WEEKDAYS[self.lang][d.weekday()]}"
        if i == self.p.n - 1:
            out += " — " + self.t["partial"].format(t=self.p.data["period"]["cutoff"][11:16])
        return out

    def weekend(self) -> list[bool]:
        return [dt.date.fromisoformat(d).weekday() >= 5 for d in self.days]

    def n(self, v) -> str:
        return fmt_int(v, self.lang)

    def k(self, v) -> str:
        """Тысячи с одним знаком: 344,3 тыс. / 344.3k — для подписей у полос."""
        x = v / 1000
        s = f"{x:.1f}" if x < 100 else f"{x:.0f}"
        return (s.replace(".", ",") + " тыс.") if self.lang == "ru" else (s + "k")

    def fact(self, fid: str, idx: int = 0) -> str | None:
        f = self.p.repo.facts().get(fid) or {}
        words = (f.get("allowed_wording") or {}).get(self.lang) or []
        if fid not in self.facts_used:
            self.facts_used.append(fid)
        return words[idx] if f.get("public") and len(words) > idx else None

    def what(self, spec: dict) -> str | None:
        """Роль репозитория: формулировка факта `what` с номером `what_idx` (по умолчанию первая)."""
        return self.fact(spec["what"], spec.get("what_idx", 0)) if spec.get("what") else None

    def stage_row(self, key: str) -> str:
        """Строка таблицы среза: имя, роль, стадия с датой факта."""
        spec, t = self.p.specs[key], self.t
        sf = STAGE_FACTS.get(key)
        stage = self.fact(sf) if sf else None
        fd = self.fact_date(sf) if sf else None
        when = f' <span class="meta">({E(t["fact_date"])} {E(fmt_short(fd, self.lang))})</span>' if fd else ""
        return (f'<tr><th scope="row">{E(spec["name"])}</th><td>{E(self.what(spec) or "")}</td>'
                f'<td>{E(stage or "—")}{when}</td></tr>')

    def fact_date(self, fid: str) -> str | None:
        ev = (self.p.repo.facts().get(fid) or {}).get("evidence") or []
        return max((str(e["date"]) for e in ev if e.get("date")), default=None)

    def both(self, wide: str, narrow: str) -> str:
        return f'<div class="fig-wide">{wide}</div><div class="fig-narrow">{narrow}</div>'

    def caption(self, kinds: list[str], source: str, legend: list[tuple[str, str]] = (), note: str = "") -> str:
        keys = "".join(f'<span class="key"><span class="sw {cls}"></span>{E(name)}</span>' for cls, name in legend)
        chips = "".join(f'<span class="dkind dk-{k}">{E(self.t["kind"][k])}</span>' for k in kinds)
        out = '<figcaption>'
        if keys:
            out += f'<span class="legend">{keys}</span>'
        out += f'<span class="fsrc">{chips} {E(self.t["source"])}: {E(source)}.'
        if note:
            out += f" {E(note)}"
        return out + "</span></figcaption>"

    def table(self, head: list[str], rows: list[list], numeric_from: int = 1) -> str:
        num = ' class="num"'
        th = "".join(f'<th{num if i >= numeric_from else ""} scope="col">{E(h)}</th>' for i, h in enumerate(head))
        trs = []
        for r in rows:
            cells = []
            for i, c in enumerate(r):
                if i == 0:
                    cells.append(f'<th scope="row">{E(str(c))}</th>')
                else:
                    cells.append(f'<td{num if i >= numeric_from else ""}>{E(str(c))}</td>')
            trs.append("<tr>" + "".join(cells) + "</tr>")
        return (f'<details class="fdata"><summary>{E(self.t["table"])}</summary><div class="tscroll">'
                f'<table class="facts num-table"><thead><tr>{th}</tr></thead><tbody>{"".join(trs)}</tbody></table></div></details>')

    def totals_note(self) -> str:
        """Пояснение у фигур по репозиториям: закрытый проект без имени — только в суммах."""
        return self.t["only_totals"] if self.p.hidden_count() else ""

    def figure(self, fid: str, body: str, wide: bool = True) -> str:
        cls = "pfig" + (" pfig-full" if wide else "")
        return f'<figure class="{cls}" id="fig-{fid}">{body}</figure>'

    # ------------------------------------------------------------ фигуры
    def snapshot(self) -> str:
        p, t = self.p, self.t
        tiles = []
        for mid in ("repos_core", "repos_active", "lines_end", "tests_end", "decisions_end", "hostcheck_end", "unmerged_branches"):
            v = p.formatted(mid, self.lang)
            kind = "reported" if mid.startswith("hostcheck") else "measured"
            note = ""
            if mid == "repos_core" and any(p.specs[k]["visibility"] == "undisclosed" for k in p.core()):
                note = f'<span class="tnote">{E(t["core_note"])}</span>'
            if mid == "unmerged_branches":
                bn = p.branches_now()
                note = '<span class="tnote">' + E(", ".join(f'{t["states"][s].split(" (")[0]} — {bn.get(s, 0)}'
                                                             for s in ("in_review", "active", "docs", "deferred"))) + "</span>"
            tiles.append(f'<div class="tile"><div class="tv">{E(v)}</div><div class="tl">{E(t["tiles"][mid])}</div>{note}'
                         f'<span class="dkind dk-{kind}">{E(t["kind"][kind])}</span></div>')
        rows = []
        for gname, keys in (("", p.core()), (t["grp_sup"], p.supporting())):
            keys = p.listed(keys)
            if gname and keys:  # управление и поддерживающий слой — отдельной частью таблицы, под ядром
                rows.append(f'<tr><th scope="colgroup" colspan="3">{E(gname)}</th></tr>')
            for key in keys:
                rows.append(self.stage_row(key))
        cut = fmt_value(p.data["period"]["cutoff"], "datetime", self.lang)

        stages = (f'<div class="tscroll"><table class="facts stages"><thead><tr><th scope="col">{E(t["project"])}</th>'
                  f'<th scope="col">{E(t["role"])}</th><th scope="col">{E(t["stage"])}</th></tr></thead>'
                  f'<tbody>{"".join(rows)}</tbody></table></div>')
        body = (f'<p class="snap-when"><span class="dkind dk-measured">{E(t["snapshot"])}</span> {E(cut)}</p>'
                f'<div class="tiles">{"".join(tiles)}</div>{stages}'
                + self.caption(["measured", "reported", "facts"], f'{t["src_git"]}; {t["src_readme"]}; {t["src_facts"]}'))
        return self.figure("snapshot", body)

    def activity(self) -> str:
        p, t = self.p, self.t
        g = p.by_group("commits")
        merges = p.total("merges")
        series = [("c-gald", g["galdhold"], t["groups"]["galdhold"]), ("c-core", g["core_other"], t["groups"]["core_other"]),
                  ("c-bg", g["support"], t["groups"]["support"])]
        tips = []
        for i in range(p.n):
            parts = ", ".join(f"{name} {vals[i]}" for _, vals, name in series if vals[i])
            tips.append(f"{self.day_long(i)}: {self.n(sum(v[i] for _, v, _ in series))} {t['commits_n']}"
                        + (f" ({parts})" if parts else "") + f"; {t['merges_n']} {merges[i]}")
        tot = [sum(v[i] for _, v, _ in series) for i in range(p.n)]
        peak = max(range(p.n), key=lambda i: tot[i])
        peak_early = max(range(p.n - 5), key=lambda i: tot[i])
        markers = {}
        for ms in p.milestones:
            i = p.days.index(str(ms["date"]))
            if i not in markers or MS_CLASS.get(ms["kind"]) == "c-human":
                markers[i] = (MS_CLASS.get(ms["kind"], "c-paper"), f"{fmt_short(str(ms['date']), self.lang)}: {ms[self.lang]}")
        desc = (f"{t['commits_n'].capitalize()}: {self.n(sum(tot))}; " +
                "; ".join(f"{name} — {self.n(sum(vals))}" for _, vals, name in series) +
                f". {fmt_date(p.days[peak], self.lang)} — {self.n(tot[peak])}.")
        title = f"{t['commits_n'].capitalize()} — {fmt_date(p.days[0], self.lang)} … {fmt_date(p.days[-1], self.lang)}"
        svg = self.both(
            charts.columns("pf-act-w", charts.WIDE, title, desc, self.labels(), self.months(), series, self.lang, 300, tips,
                           (peak, peak_early), markers, self.weekend()),
            charts.columns("pf-act-n", charts.NARROW, title, desc, self.labels(), self.months(), series, self.lang, 250, tips,
                           (peak,), markers, self.weekend()))
        rows = [[fmt_short(d, self.lang), tot[i], g["galdhold"][i], g["core_other"][i], g["support"][i], merges[i]]
                for i, d in enumerate(p.days)]
        rows = [[r[0]] + [self.n(x) for x in r[1:]] for r in rows]
        table = self.table([t["date"], t["total"], t["groups"]["galdhold"], t["groups"]["core_other"], t["groups"]["support"],
                            t["merges_n"]], rows)
        cap = self.caption(["measured"], t["src_git"], [(cls, name) for cls, _, name in series], t["markers"] + ".")
        return self.figure("activity", svg + cap + table)

    def cumulative(self) -> str:
        p, t = self.p, self.t
        cc = p.cumulative(p.total("commits"))
        cm = p.cumulative(p.total("merges"))
        ce = p.core_established()
        parts = []
        for uid, vals, title, cls in (("pf-cc", cc, t["cum_commits"], "c-paper"), ("pf-cm", cm, t["cum_merges"], "c-ver"),
                                      ("pf-ce", ce, t["core_est"], "c-core")):
            tips = [f"{self.day_long(i)}: {self.n(vals[i])}" for i in range(p.n)]
            desc = f"{title}: {self.n(vals[0])} → {self.n(vals[-1])}."
            svg = charts.stacked_area(uid, {"w": 320}, title, desc, self.labels(), self.months(), [(cls, vals, title)],
                                      self.lang, 190, tips)
            parts.append(f'<div class="mult"><p class="mult-t">{E(title)} <b>{E(self.n(vals[-1]))}</b></p>{svg}</div>')
        rows = [[fmt_short(d, self.lang), self.n(cc[i]), self.n(cm[i]), self.n(ce[i])] for i, d in enumerate(p.days)]
        body = (f'<div class="mults">{"".join(parts)}</div>'
                + self.caption(["derived"], f'{t["src_git"]}; Σ {t["since_start"]}')
                + self.table([t["date"], t["cum_commits"], t["cum_merges"], t["core_est"]], rows))
        return self.figure("cumulative", body)

    def repos(self) -> str:
        p, t = self.p, self.t
        groups = []
        for gname, keys in ((t["grp_core"], p.listed([k for k in p.keys if p.specs[k]["group"] == "core"])),
                            (t["grp_sup"], p.listed([k for k in p.keys if p.specs[k]["group"] != "core"]))):
            rows = []
            for k in keys:
                vals = p.daily(k, "commits")
                if not any(vals):
                    continue
                name = p.name(k, self.lang)
                tips = [f"{name} — {self.day_long(i)}: {self.n(v)} {t['commits_n']}" for i, v in enumerate(vals)]
                rows.append({"label": name, "values": vals, "tips": tips, "total": self.n(sum(vals)),
                             "extra": self.n(sum(p.daily(k, "merges"))), "first": p.first_day(k)})
            groups.append((f"{gname} · {len(rows)}", rows))
        desc = "; ".join(f"{r['label']} — {r['total']}" for _, rows in groups for r in rows)
        title = t["grp_core"] + " / " + t["grp_sup"]
        svg = self.both(
            charts.heatmap("pf-heat-w", charts.WIDE, title, desc, self.labels(), self.months(), groups, BINS, self.lang,
                           t["commits_prs"]),
            charts.heatmap("pf-heat-n", charts.NARROW, title, desc, self.labels(), self.months(), groups, BINS, self.lang,
                           t["sum"]))
        bins = ["1–2", "3–5", "6–10", "11–20", "21–40", "41+"]
        legend = [(f"c-h{i + 1}", b) for i, b in enumerate(bins)]
        rows = [[r["label"], r["total"], r["extra"], fmt_short(r["first"], self.lang) if r["first"] else "—"]
                for _, rs in groups for r in rs]
        head = [t["project"], t["commits_n"], t["merges_n"], t["first_seen"]]
        return self.figure("repos", svg + self.caption(["measured"], t["src_git"], legend, self.totals_note())
                           + self.table(head, rows))

    def authorship(self) -> str:
        p, t = self.p, self.t
        ai, hu = p.total("ai"), p.total("human")
        series = [("c-ai", ai, t["ai"]), ("c-human", hu, t["human"])]
        tips = [f"{self.day_long(i)}: {t['ai']} {ai[i]}, {t['human']} {hu[i]}" for i in range(p.n)]
        desc = f"{t['ai']} — {self.n(sum(ai))}, {t['human']} — {self.n(sum(hu))}."
        title = f"{t['ai']} / {t['human']}"
        svg = self.both(
            charts.columns("pf-auth-w", charts.WIDE, title, desc, self.labels(), self.months(), series, self.lang, 240, tips,
                           weekend=self.weekend()),
            charts.columns("pf-auth-n", charts.NARROW, title, desc, self.labels(), self.months(), series, self.lang, 210, tips,
                           weekend=self.weekend()))
        rows = [[fmt_short(d, self.lang), self.n(ai[i] + hu[i]), self.n(ai[i]), self.n(hu[i])] for i, d in enumerate(p.days)]
        return self.figure("authorship", svg + self.caption(["measured"], t["src_git"], [(c, nm) for c, _, nm in series])
                           + self.table([t["date"], t["total"], t["ai"], t["human"]], rows))

    def parallel(self) -> str:
        p, t = self.p, self.t
        br = p.total("branches")
        bo = p.total("branch_only")
        tips = [f"{self.day_long(i)}: {br[i]} {t['branches_n']}" for i in range(p.n)]
        peak = max(range(p.n), key=lambda i: br[i])
        title = t["branches_day"]
        desc = f"{title}: {min(br)}–{max(br)}; {fmt_date(p.days[peak], self.lang)} — {br[peak]}."
        cols = self.both(
            charts.columns("pf-br-w", charts.WIDE, title, desc, self.labels(), self.months(), [("c-paper", br, title)],
                           self.lang, 210, tips, (peak,), weekend=self.weekend()),
            charts.columns("pf-br-n", charts.NARROW, title, desc, self.labels(), self.months(), [("c-paper", br, title)],
                           self.lang, 190, tips, (peak,), weekend=self.weekend()))
        mins = p.pr_minutes()
        edges = [0] + CYCLE_EDGES + [10 ** 9]
        counts = [sum(1 for x in mins if edges[j] <= x < edges[j + 1]) for j in range(len(edges) - 1)]
        n = len(mins) or 1
        crow = [{"label": lab, "segs": [("c-ver", c, lab)], "value": f"{self.n(c)} · {c * 100 // n} %" if self.lang == "ru"
                 else f"{c} · {c * 100 // n}%", "tip": f"{lab}: {c}"} for lab, c in zip(t["cycle_buckets"], counts)]
        med = statistics.median(mins) if mins else 0
        cdesc = f"{t['cycle']}: n={len(mins)}, median {med:.0f} min."
        cyc = self.both(charts.hbars("pf-cyc-w", {"w": 440}, t["cycle"], cdesc, crow, self.lang, label_w=104),
                        charts.hbars("pf-cyc-n", charts.NARROW, t["cycle"], cdesc, crow, self.lang, label_w=96))
        bn = p.branches_now()
        srow = [{"label": t["states"][s].split(" (")[0], "segs": [(f"c-st-{s}", bn.get(s, 0), s)],
                 "value": f"{t['branches_n']} {bn.get(s, 0)} · {t['commits_in']} {bn.get(s + '_commits', 0)}",
                 "tip": f"{t['states'][s]}: {bn.get(s, 0)} {t['branches_n']}, {bn.get(s + '_commits', 0)} {t['commits_in']}"}
                for s in ("in_review", "active", "docs", "deferred")]
        sdesc = "; ".join(r["tip"] for r in srow)
        now = self.both(charts.hbars("pf-now-w", {"w": 440}, t["now"], sdesc, srow, self.lang, label_w=134),
                        charts.hbars("pf-now-n", charts.NARROW, t["now"], sdesc, srow, self.lang, label_w=118))
        rows = [[fmt_short(d, self.lang), self.n(br[i]), self.n(p.total("mainline")[i]), self.n(bo[i]), self.n(p.total("merges")[i])]
                for i, d in enumerate(p.days)]
        head = [t["date"], t["branches_day"], "main" if self.lang == "en" else "в main",
                "branch only" if self.lang == "en" else "только в ветке", t["merges_n"]]
        legend_states = [(f"c-st-{s}", t["states"][s]) for s in ("in_review", "active", "docs", "deferred")]
        body = (f'<div class="pblock">{cols}</div>'
                f'<div class="pgrid"><div><p class="mult-t">{E(t["cycle"])}</p>{cyc}</div>'
                f'<div><p class="mult-t">{E(t["now"])}</p>{now}</div></div>'
                + self.caption(["measured", "derived"], t["src_git"], legend_states)
                + self.table(head, rows))
        return self.figure("parallel", body)

    def codebase(self) -> str:
        p, t = self.p, self.t
        cl = p.core_lines()
        order = ("tests", "docs", "code")  # зелёный и бирюзовый не соседи: обычным зрением различимы плохо (dataviz)
        layers = [("c-ver" if k == "tests" else "c-ai" if k == "code" else "c-human", cl[k], t["lines"][k]) for k in order]
        tot = [sum(cl[k][i] for k in LINE_KINDS) for i in range(p.n)]
        tips = [f"{self.day_long(i)}: {self.k(tot[i])} — " + ", ".join(f"{t['lines'][k]} {self.k(cl[k][i])}" for k in order)
                for i in range(p.n)]
        title = t["lines_title"]
        desc = f"{title}: {self.k(tot[0])} → {self.k(tot[-1])}; " + ", ".join(f"{t['lines'][k]} {self.k(cl[k][-1])}" for k in LINE_KINDS)
        ends = [self.k(cl[k][-1]) for k in order]
        area = self.both(
            charts.stacked_area("pf-loc-w", charts.WIDE, title, desc, self.labels(), self.months(), layers, self.lang, 300, tips, ends),
            charts.stacked_area("pf-loc-n", charts.NARROW, title, desc, self.labels(), self.months(), layers, self.lang, 240, tips))
        rows = []
        for k in p.listed(p.core()):
            snap = p.lines(k, p.n - 1)
            if not snap:
                continue
            total = sum(snap[x] for x in LINE_KINDS)
            name = p.name(k, self.lang)
            rows.append({"label": name, "segs": [(c, snap[x], t["lines"][x]) for (c, _, _), x in zip(layers, order)],
                         "value": self.k(total), "tip": f"{name}: " + ", ".join(f"{t['lines'][x]} {self.k(snap[x])}" for x in LINE_KINDS),
                         "_t": total})
        rows.sort(key=lambda r: -r["_t"])
        bdesc = "; ".join(r["tip"] for r in rows)
        bars = self.both(charts.hbars("pf-locp-w", charts.WIDE, t["per_project"], bdesc, rows, self.lang),
                         charts.hbars("pf-locp-n", charts.NARROW, t["per_project"], bdesc, rows, self.lang))
        trows = [[fmt_short(d, self.lang)] + [self.n(cl[k][i]) for k in LINE_KINDS] + [self.n(tot[i])] for i, d in enumerate(p.days)]
        body = (area + f'<p class="mult-t">{E(t["per_project"])}</p>' + bars
                + self.caption(["measured"], t["src_git_main"], [(c, nm) for c, _, nm in layers], self.totals_note())
                + self.table([t["date"]] + [t["lines"][k] for k in LINE_KINDS] + [t["total"]], trows))
        return self.figure("codebase", body)

    def verification(self) -> str:
        p, t = self.p, self.t
        pts = p.reported("galdhold", "hostcheck")
        idx = [(p.days.index(x["date"]), x["value"], f"{fmt_date(x['date'], self.lang)}: {self.n(x['value'])}") for x in pts]
        desc = "; ".join(f"{fmt_short(x['date'], self.lang)} — {self.n(x['value'])}" for x in pts)
        chk = self.both(
            charts.points("pf-chk-w", {"w": 560}, t["hostcheck"], desc, p.days, self.labels(), self.months(), idx, "c-ver", self.lang, 220),
            charts.points("pf-chk-n", charts.NARROW, t["hostcheck"], desc, p.days, self.labels(), self.months(), idx, "c-ver", self.lang, 200))
        rows = []
        for k in p.listed(p.core()):
            snap = p.lines(k, p.n - 1)
            if not snap:
                continue
            name = p.name(k, self.lang)
            rows.append({"label": name, "segs": [("c-ver", snap["tests"], t["lines"]["tests"])], "value": self.k(snap["tests"]),
                         "tip": f"{name}: {self.k(snap['tests'])}", "_t": snap["tests"]})
        rows.sort(key=lambda r: -r["_t"])
        tdesc = "; ".join(r["tip"] for r in rows)
        tests = self.both(charts.hbars("pf-tst-w", {"w": 380}, t["tests_title"], tdesc, rows, self.lang, label_w=120),
                          charts.hbars("pf-tst-n", charts.NARROW, t["tests_title"], tdesc, rows, self.lang))
        body = (f'<div class="pgrid pgrid-wide"><div><p class="mult-t">{E(t["hostcheck"])}</p>{chk}'
                + self.caption(["reported"], t["src_readme"]) + "</div>"
                f'<div><p class="mult-t">{E(t["tests_title"])}</p>{tests}' + self.caption(["measured"], t["src_git_main"]) + "</div></div>"
                + self.table([t["date"], t["hostcheck"]], [[fmt_short(x["date"], self.lang), self.n(x["value"])] for x in pts]))
        return self.figure("verification", body)

    def knowledge(self) -> str:
        p, t = self.p, self.t
        dec = p.decisions()
        tips = [f"{self.day_long(i)}: {self.n(dec[i])}" for i in range(p.n)]
        desc = f"{t['decisions_title']}: {self.n(dec[0])} → {self.n(dec[-1])}."
        area = self.both(
            charts.stacked_area("pf-dec-w", {"w": 560}, t["decisions_title"], desc, self.labels(), self.months(),
                                [("c-human", dec, t["decisions_title"])], self.lang, 220, tips),
            charts.stacked_area("pf-dec-n", charts.NARROW, t["decisions_title"], desc, self.labels(), self.months(),
                                [("c-human", dec, t["decisions_title"])], self.lang, 200, tips))
        rows = []
        for k in p.listed(p.core()):
            v = p.counter(k, "decisions")[-1] or 0
            name = p.name(k, self.lang)
            rows.append({"label": name, "segs": [("c-human", v, "")], "value": self.n(v), "tip": f"{name}: {v}", "_t": v})
        rows.sort(key=lambda r: -r["_t"])
        bdesc = "; ".join(r["tip"] for r in rows)
        bars = self.both(charts.hbars("pf-decp-w", {"w": 380}, t["decisions_per"], bdesc, rows, self.lang, label_w=120, row_h=26),
                         charts.hbars("pf-decp-n", charts.NARROW, t["decisions_per"], bdesc, rows, self.lang, row_h=26))
        items = []
        for key in p.listed(p.core()):
            for cid in t["counters"]:
                vals = p.counter(key, cid)
                if cid in p.data["repos"][key]["counters"] and vals[-1] is not None:
                    items.append(f'<div class="tile tile-sm"><div class="tv">{E(self.n(vals[-1]))}</div>'
                                 f'<div class="tl">{E(t["counters"][cid])}</div></div>')
        body = (f'<div class="pgrid pgrid-wide"><div><p class="mult-t">{E(t["decisions_title"])}</p>{area}</div>'
                f'<div><p class="mult-t">{E(t["decisions_per"])}</p>{bars}</div></div>'
                f'<p class="mult-t">{E(t["doc_counters"])}</p><div class="tiles">{"".join(items)}</div>'
                + self.caption(["measured"], t["src_git_main"], [("c-human", t["decisions_title"])], self.totals_note())
                + self.table([t["date"], t["decisions_title"]], [[fmt_short(d, self.lang), self.n(dec[i])] for i, d in enumerate(p.days)]))
        return self.figure("knowledge", body)

    def ecosystem(self) -> str:
        p, t = self.p, self.t
        layers = []
        for layer in p.scope.get("layers", []):
            chips = []
            for k in p.keys:
                spec = p.specs[k]
                if spec["layer"] != layer["id"]:
                    continue
                if spec["visibility"] == "undisclosed":  # только в суммах (Д38, Д39): ни узла, ни слоя
                    continue
                what = self.what(spec)
                first = p.first_day(k)
                since = (f'<span class="meta">{E(t["first_seen"])} {E(fmt_short(first, self.lang))}</span>'
                         if first and first > p.days[0] else "")
                role = f"<span>{E(what)}</span>" if what else ""
                chips.append(f'<li class="node"><b>{E(spec["name"])}</b>{role}{since}</li>')
            if chips:
                layers.append(f'<div class="layer"><p class="layer-t">{E(layer[self.lang])}</p><ul class="nodes">{"".join(chips)}</ul></div>')
        rels = []
        for r in p.scope.get("relations", []):
            if r["from"] not in p.listed() or r["to"] not in p.listed():
                continue
            arrow = "⇄" if r.get("both") else "→"
            plan = f' <span class="meta">({E(t["planned"])})</span>' if r.get("planned") else ""
            rels.append(f'<li><b>{E(p.name(r["from"], self.lang))} {arrow} {E(p.name(r["to"], self.lang))}</b> — '
                        f'{E(r[self.lang])}{plan} <span class="meta">{E(r["source"])}</span></li>')
        body = (f'<div class="layers">{"".join(layers)}</div><p class="mult-t">{E(t["relations"])}</p>'
                f'<ul class="rels">{"".join(rels)}</ul>'
                + self.caption(["facts"], f'{t["src_scope"]}; {t["src_facts"]}', note=self.totals_note()))
        return self.figure("ecosystem", body)

    def timeline(self) -> str:
        p, t = self.p, self.t
        items = []
        for ms in p.milestones:
            d = str(ms["date"])
            cls = MS_CLASS.get(ms["kind"], "c-paper")
            ev = ms["evidence"][0]
            src = f'{t["ev_kind"][ev["kind"]]} · {t["ev_ver"][ev["verification"]]}'
            names = ", ".join(p.name(k, self.lang) for k in p.listed([k for k in ms["repos"] if k in p.specs]))
            items.append(f'<li><time datetime="{d}">{E(fmt_short(d, self.lang))}</time><span class="mk {cls}" aria-hidden="true"></span>'
                         f'<div class="ev"><span class="evk">{E(t["ms_kind"][ms["kind"]])}</span> {E(ms[self.lang])}'
                         f'<span class="meta">{E(names)} · {E(src)}</span></div></li>')
        legend = [("c-human", t["ms_kind"]["decision"]), ("c-ver", " / ".join(t["ms_kind"][k] for k in ("verification", "device", "release"))),
                  ("c-paper", t["ms_kind"]["repository"])]
        return self.figure("timeline", f'<ol class="ptl">{"".join(items)}</ol>' + self.caption(["milestone"], t["src_ms"], legend))

    def method(self) -> str:
        p, t = self.p, self.t
        per = p.data["period"]
        rows = []
        for mid, m in METRICS.items():
            rows.append(f'<tr><th scope="row"><code>{E(mid)}</code></th><td>{E(getattr(m, self.lang))}</td>'
                        f'<td class="num">{E(p.formatted(mid, self.lang))}</td></tr>')
        excluded = p.scope.get("excluded", [])
        excl = "".join(f'<li><code>{E(x["slug"].split("/")[-1])}</code> — {E(x["reason"])}</li>'
                       for x in excluded if x.get("kind") not in ("personal", "archive"))
        personal = sum(1 for x in excluded if x.get("kind") == "personal")
        if personal:  # личные репозитории — без имён (Д2, ОР-8; фильтр Д39)
            excl += f'<li>{E(t["personal_n"].format(n=personal))}</li>'
        archive = sum(1 for x in excluded if x.get("kind") == "archive")
        if archive:  # архив — только числом: на сайте лишь текущая система (Д38, слово автора 09.10.2026)
            excl += f'<li>{E(t["archive_n"].format(n=archive))}</li>'
        hidden = "".join(f"<li>{E(h)}</li>" for h in t["hidden"])
        head = (f'<dl class="facts-dl"><dt>{E(t["m_period"])}</dt><dd>{E(fmt_date(per["start"], self.lang))} — '
                f'{E(fmt_date(per["end"], self.lang))} (UTC{E(per["timezone"])})</dd>'
                f'<dt>{E(t["m_cutoff"])}</dt><dd>{E(fmt_value(per["cutoff"], "datetime", self.lang))}</dd>'
                f'<dt>{E(t["m_method"])}</dt><dd>v{p.data["method"]["version"]} · {E(p.id)}</dd>'
                f'<dt>{E(t["m_repos"])}</dt><dd>{len(p.keys)}</dd></dl>')
        body = (head + f'<div class="tscroll"><table class="facts formulas"><thead><tr><th scope="col">{E(t["m_metric"])}</th>'
                f'<th scope="col">{E(t["m_formula"])}</th><th scope="col" class="num">{E(t["m_value"])}</th></tr></thead>'
                f'<tbody>{"".join(rows)}</tbody></table></div>'
                f'<div class="pgrid"><div><p class="mult-t">{E(t["m_excluded"])}</p><ul class="plain">{excl}</ul></div>'
                f'<div><p class="mult-t">{E(t["m_hidden"])}</p><ul class="plain">{hidden}</ul></div></div>')
        return self.figure("method", body)


def render(p: Progress, fid: str, lang: str) -> tuple[str, list[str]]:
    """HTML фигуры и id фактов, которые она вывела (зависимости варианта, Д28)."""
    if fid not in FIGURES:
        raise KeyError(fid)
    f = Fig(p, lang)
    out = getattr(f, fid)()
    return out, f.facts_used


def facts_of(p: Progress, fid: str) -> list[str]:
    """Какие факты реестра выводит фигура — без сборки HTML (для зависимостей при фиксации варианта)."""
    if fid == "snapshot":
        return [p.specs[k]["what"] for k in p.core() + p.supporting()
                if p.specs[k].get("what") and p.specs[k]["visibility"] != "undisclosed"] + \
               [STAGE_FACTS[k] for k in p.core() if k in STAGE_FACTS]
    if fid == "ecosystem":
        return [p.specs[k]["what"] for k in p.keys if p.specs[k].get("what") and p.specs[k]["visibility"] != "undisclosed"]
    return []


FIGURE_MARK = re.compile(r"<p>\{\{figure:([^}#\s]+)\}\}</p>")
