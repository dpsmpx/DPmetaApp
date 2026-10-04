"""Подстановка фактов, ссылок и релизов в текст (Д28, PLATFORM_VISION §21–22).

В тексте канона и вариантов числа, версии и адреса не переписываются
руками, а ссылаются на источник:

    {{fact:GH-LOC}}          первая допустимая формулировка факта на языке текста
    {{fact:GH-CHECKS#1}}     формулировка с номером
    {{link:L-YT-CHANNEL}}    адрес из data/identity/links.yaml
    {{site:/projects/galdhold/}}   адрес на сайте (канонический для площадок)
    {{release:galdhold.latest.version}}   поле последнего релиза проекта

resolve() возвращает текст, зависимости с отпечатками (для устаревания)
и ошибки (неизвестный id, непубличный факт, гипотеза, битая ссылка).
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field

from .repo import Repo, fingerprint

PLACEHOLDER = re.compile(r"\{\{\s*(fact|link|site|release)\s*:\s*([^}#\s]+)(?:#(\d+))?\s*\}\}")
BAD_LINK = {"broken_for_public", "stale"}


def fact_fp(f: dict) -> str:
    return fingerprint({k: f.get(k) for k in ("statement", "allowed_wording", "class", "public")})


def link_fp(l: dict) -> str:
    return fingerprint({k: l.get(k) for k in ("url", "status")})


@dataclass
class Resolved:
    text: str
    deps: dict = field(default_factory=lambda: {"facts": {}, "links": {}, "releases": {}})
    errors: list = field(default_factory=list)
    facts_used: list = field(default_factory=list)


def latest_release(repo: Repo, project: str) -> dict | None:
    rels = [r for r in repo.releases().get(project, []) if r.get("status", "published") == "published"]
    return sorted(rels, key=lambda r: str(r.get("date")))[-1] if rels else None


def resolve(text: str, lang: str, repo: Repo, site_base: str = "") -> Resolved:
    res = Resolved(text)
    facts, links = repo.facts(), repo.links()

    def sub(m: re.Match) -> str:
        kind, key, idx = m.group(1), m.group(2), int(m.group(3) or 0)
        if kind == "fact":
            f = facts.get(key)
            if f is None:
                res.errors.append(f"{{{{fact:{key}}}}} — нет в реестре фактов")
                return m.group(0)
            if not f.get("public"):
                res.errors.append(f"{key} — не публичный факт")
            if f.get("class") == "HYPOTHESIS":
                res.errors.append(f"{key} — HYPOTHESIS не подставляется как утверждение (Д6)")
            words = ((f.get("allowed_wording") or {}).get(lang) or [])
            res.deps["facts"][key] = fact_fp(f)
            if key not in res.facts_used:
                res.facts_used.append(key)
            if idx >= len(words):
                res.errors.append(f"{key} — нет формулировки №{idx} на языке {lang}")
                return m.group(0)
            return words[idx]
        if kind == "link":
            l = links.get(key)
            if l is None or not l.get("url"):
                res.errors.append(f"{{{{link:{key}}}}} — нет адреса в data/identity/links.yaml")
                return m.group(0)
            if l.get("status") in BAD_LINK:
                res.errors.append(f"{key} — ссылка со статусом {l['status']}")
            res.deps["links"][key] = link_fp(l)
            return l["url"]
        if kind == "site":
            path = key if key.startswith("/") else "/" + key
            return (site_base.rstrip("/") + path) if site_base else path
        # release:<project>.latest.<field>
        parts = key.split(".")
        if len(parts) != 3 or parts[1] != "latest":
            res.errors.append(f"{{{{release:{key}}}}} — формат release:<проект>.latest.<поле>")
            return m.group(0)
        rel = latest_release(repo, parts[0])
        if rel is None:
            res.errors.append(f"{{{{release:{key}}}}} — у проекта {parts[0]} нет опубликованных релизов")
            return m.group(0)
        res.deps["releases"][f"{parts[0]}@{rel.get('version')}"] = fingerprint(rel)
        val = rel.get(parts[2])
        if val is None:
            res.errors.append(f"{{{{release:{key}}}}} — у релиза нет поля {parts[2]}")
            return m.group(0)
        return str(val)

    res.text = PLACEHOLDER.sub(sub, text)
    leftover = re.findall(r"\{\{[^}]*\}\}", res.text)
    for x in leftover:
        if not PLACEHOLDER.fullmatch(x):
            res.errors.append(f"{x} — неизвестная подстановка")
    return res


def current_deps(recorded: dict, repo: Repo) -> dict:
    """Текущие отпечатки тех же зависимостей, что записаны (для сравнения)."""
    facts, links = repo.facts(), repo.links()
    out = {"facts": {}, "links": {}, "releases": {}}
    for k in (recorded.get("facts") or {}):
        out["facts"][k] = fact_fp(facts[k]) if k in facts else None
    for k in (recorded.get("links") or {}):
        out["links"][k] = link_fp(links[k]) if k in links else None
    for k in (recorded.get("releases") or {}):
        proj, ver = k.split("@", 1)
        rel = next((r for r in repo.releases().get(proj, []) if str(r.get("version")) == ver), None)
        out["releases"][k] = fingerprint(rel) if rel else None
    return out


def changed(recorded: dict, repo: Repo) -> list[str]:
    """Что из записанных зависимостей изменилось: ['факт GH-LOC', …]."""
    cur = current_deps(recorded, repo)
    names = {"facts": "факт", "links": "ссылка", "releases": "релиз"}
    out = []
    for kind in ("facts", "links", "releases"):
        for k, fp in (recorded.get(kind) or {}).items():
            if cur[kind].get(k) != fp:
                out.append(f"{names[kind]} {k}")
    return out
