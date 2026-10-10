"""Проверка `progress` — набор прогресса, границы и фигуры страницы (Д38, Д39).

Что проверяется для каждого набора data/progress/datasets/*.json:

    ряды        дни подряд от начала до конца периода; у каждого ряда — по значению
                на день; коммиты = Claude + автор = основная ветка + только в ветке;
                «только документы» не больше коммитов (ветки — только рабочие: коммит
                прямо в основную ветку ветки не добавляет)
    границы     репозитории набора есть в scope.yaml (или в архиве `excluded`) и наоборот; у закрытого проекта
                без имени нет name; факты ролей и стадий есть и публичны; связи —
                между известными ключами
    вехи        в периоде набора, ключи репозиториев и факты известны, источник есть
    величины    каждая из METRICS считается; ключи progress в claim_keywords.yaml — из METRICS
    фигуры      все 12 собираются на ru и en; в HTML фигур и в публичной выжимке
                (/api/v1/progress.json) нет ничего, что ловит фильтр чувствительных данных

Сборку клонов (collect.py) проверка не повторяет: она проверяет то, что уже
записано и попадёт на сайт.
"""
from __future__ import annotations

import datetime as dt
import json

from ..common import Report
from ..core.repo import Repo
from ..core.sensitive import Filter
from .dataset import METRICS, Progress
from .figures import FIGURES, STAGE_FACTS, render


def _series(p: Progress, r: Report) -> None:
    days = p.days
    start, end = dt.date.fromisoformat(p.data["period"]["start"]), dt.date.fromisoformat(p.data["period"]["end"])
    want = [(start + dt.timedelta(n)).isoformat() for n in range((end - start).days + 1)]
    if days != want:
        r.error(f"{p.id}: дни не подряд от {start} до {end}")
    for key, rec in p.data["repos"].items():
        d = rec["daily"]
        for field, vals in list(d.items()) + [("lines", rec["lines"])] + [(f"counters.{c}", v) for c, v in rec["counters"].items()]:
            if len(vals) != p.n:
                r.error(f"{p.id}/{key}: ряд {field} — {len(vals)} значений на {p.n} дней")
        for i in range(min(p.n, len(d["commits"]))):
            c = d["commits"][i]
            if d["ai"][i] + d["human"][i] != c:
                r.error(f"{p.id}/{key} {days[i]}: Claude + автор ≠ коммиты")
            if d["mainline"][i] + d["branch_only"][i] != c:
                r.error(f"{p.id}/{key} {days[i]}: основная ветка + только в ветке ≠ коммиты")
            if d["docs_only"][i] > c:
                r.error(f"{p.id}/{key} {days[i]}: «только документы» больше коммитов")


def _scope(p: Progress, repo: Repo, r: Report, latest: bool = True) -> None:
    facts = repo.facts()
    keys = set(p.specs)
    for k in p.data["repos"]:
        if k not in keys and k not in p.archived():  # архив — в excluded с ключом: в прежнем наборе есть, на странице нет
            r.error(f"{p.id}: репозиторий {k} есть в наборе, но не в scope.yaml — граница публичного не задана")
    for k, spec in p.specs.items():
        if k not in p.data["repos"] and latest:  # прежний набор собран до нового репозитория — не находка
            r.warn(f"{p.id}: {k} есть в scope.yaml, но не в наборе")
        if spec["visibility"] == "undisclosed" and spec.get("name"):
            r.error(f"scope.yaml: {k} — закрытый проект без публичного имени, а name задан")
        if spec["visibility"] != "undisclosed" and not spec.get("name"):
            r.error(f"scope.yaml: {k} — нет публичного имени")
        for fid in [spec.get("what"), STAGE_FACTS.get(k) if spec["group"] == "core" else None]:
            if not fid or spec["visibility"] == "undisclosed":
                continue
            f = facts.get(fid)
            if f is None:
                r.error(f"scope.yaml: {k} — факта {fid} нет в реестре")
            elif not f.get("public"):
                r.error(f"scope.yaml: {k} — факт {fid} не публичный, а выводится на странице")
    for rel in p.scope.get("relations", []):
        for end_ in ("from", "to"):
            if rel[end_] not in keys:
                r.error(f"scope.yaml: связь {rel['from']}→{rel['to']} — нет ключа {rel[end_]}")
    layers = {x["id"] for x in p.scope.get("layers", [])}
    for k, spec in p.specs.items():
        if spec["layer"] not in layers:
            r.error(f"scope.yaml: {k} — слой {spec['layer']} не описан в layers")


def _milestones(p: Progress, repo: Repo, r: Report) -> None:
    facts = repo.facts()
    for m in (repo.yaml("data/progress/milestones.yaml") or {}).get("milestones", []):
        for k in m.get("repos") or []:
            if k not in p.specs:
                r.error(f"вехи: {m['id']} — нет репозитория {k} в scope.yaml")
        if m.get("fact") and m["fact"] not in facts:
            r.error(f"вехи: {m['id']} — нет факта {m['fact']}")
        if not m.get("evidence"):
            r.error(f"вехи: {m['id']} — без источника")


def check(repo: Repo | None = None) -> Report:
    repo = repo or Repo()
    r = Report("progress — набор прогресса, границы и фигуры страницы (Д38, Д39)")
    d = repo.path("data", "progress", "datasets")
    ids = sorted(f.stem for f in d.glob("*.json")) if d.is_dir() else []
    if not ids:
        r.note("наборов прогресса нет")
        return r
    kw = (repo.yaml("data/facts/claim_keywords.yaml") or {}).get("rules", [])
    for rule in kw:
        for mid in rule.get("progress") or []:
            if mid not in METRICS:
                r.error(f"claim_keywords.yaml: {rule['id']} — нет величины {mid} в METRICS")
    flt = Filter(repo)
    for ds in ids:
        try:
            p = Progress(repo, ds)
        except (KeyError, ValueError, json.JSONDecodeError) as e:
            r.error(f"{ds}: набор не читается: {e}")
            continue
        _series(p, r)
        _scope(p, repo, r, latest=ds == ids[-1])
        for mid in METRICS:
            try:
                p.formatted(mid, "ru"), p.formatted(mid, "en")
            except Exception as e:  # noqa: BLE001 — любая ошибка величины — находка
                r.error(f"{ds}: величина {mid} не считается: {e!r}")
        for lang in ("ru", "en"):
            for fid in FIGURES:
                try:
                    html_, _ = render(p, fid, lang)
                except Exception as e:  # noqa: BLE001
                    r.error(f"{ds}: фигура {fid} [{lang}] не собирается: {e!r}")
                    continue
                for h in flt.scan_text(html_, f"{ds}: фигура {fid} [{lang}]"):
                    r.error(h.msg)
        for h in flt.scan_text(json.dumps(p.public(), ensure_ascii=False), f"{ds}: /api/v1/progress.json"):
            r.error(h.msg)
        r.note(f"{ds}: {p.n} дней, репозиториев {len(p.keys)} (по отдельности — {len(p.listed())}), "
               f"величин {len(METRICS)}, фигур {len(FIGURES)} × 2 языка")
    if ids:
        _milestones(Progress(repo, ids[-1]), repo, r)
    return r
