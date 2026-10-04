"""Реестр утверждений: целостность data/facts/facts.yaml и ссылки на id в документах.

Схему проверяет validate.py; здесь — то, чего схема не выражает (Д6,
docs/content/CLAIM_POLICY.md): уникальность id, доказательства у FACT,
просроченные recheck_by, id фактов и правил в документах существуют.
"""
from __future__ import annotations

import re

from .common import ROOT, Report, as_date, load_yaml, rel, repo_files, today

FACTS_PATH = "data/facts/facts.yaml"
FORBIDDEN_PATH = "data/facts/forbidden.yaml"

# Где id фактов — ссылки на реестр: документы в корне и в этих каталогах.
DOC_DIRS = {"docs", "ROADMAP", "templates", "prompts", "content"}


def registry() -> dict[str, dict]:
    return {f["id"]: f for f in load_yaml(FACTS_PATH)["facts"]}


def rules() -> list[dict]:
    return load_yaml(FORBIDDEN_PATH)["rules"]


def expired(fact: dict) -> bool:
    rb = as_date(fact.get("recheck_by"))
    return rb is not None and rb < today()


def _id_pattern(prefixes: set[str]) -> re.Pattern:
    alt = "|".join(sorted(map(re.escape, prefixes), key=len, reverse=True))
    return re.compile(rf"(?<![\w-])(?:{alt})-[A-Z0-9]+(?:-[A-Z0-9]+)*(?![\w-])")


def check() -> Report:
    r = Report("facts — реестр утверждений")
    data = load_yaml(FACTS_PATH)
    seen: set[str] = set()
    for f in data["facts"]:
        fid = f["id"]
        if fid in seen:
            r.error(f"{fid}: id повторяется")
        seen.add(fid)
        if f["class"] == "FACT" and not f.get("evidence"):
            r.error(f"{fid}: FACT без доказательства")
        if f["class"] == "HYPOTHESIS" and f.get("public") and f["risk"] == "high":
            r.error(f"{fid}: HYPOTHESIS высокого риска не может быть public (Д6)")
        if expired(f):
            r.warn(f"{fid}: recheck_by {f['recheck_by']} прошёл — перепроверить перед использованием")
        for ev in f.get("evidence", []):
            d = as_date(ev.get("date"))
            if d and d > today():
                r.error(f"{fid}: дата доказательства {d} в будущем")
    as_of = as_date(data.get("as_of"))
    if as_of and as_of > today():
        r.error(f"as_of {as_of} в будущем")

    rule_ids = [x["id"] for x in rules()]
    if len(rule_ids) != len(set(rule_ids)):
        r.error(f"{FORBIDDEN_PATH}: id правил повторяются")
    for x in rules():
        for key in ("pattern", "unless"):
            if x.get(key):
                try:
                    re.compile(x[key])
                except re.error as e:
                    r.error(f"{FORBIDDEN_PATH} {x['id']}: {key} — неверное выражение: {e}")

    # id в документах: префиксы — из реестра фактов и правил запретов.
    known = seen | set(rule_ids)
    prefixes = {i.split("-")[0] for i in known}
    pat = _id_pattern(prefixes)
    docs = [p for p in repo_files() if p.suffix == ".md"
            and (len(p.relative_to(ROOT).parts) == 1 or p.relative_to(ROOT).parts[0] in DOC_DIRS)]
    refs = 0
    for p in docs:
        for n, line in enumerate(p.read_text(encoding="utf-8").splitlines(), 1):
            for m in pat.finditer(line):
                refs += 1
                if m.group(0) not in known:
                    r.error(f"{rel(p)}:{n}: {m.group(0)} — нет в реестре фактов и в правилах запретов")
    r.note(f"фактов {len(seen)}, правил {len(rule_ids)}, ссылок в документах {refs}")
    return r
