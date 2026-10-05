"""Утверждения в контенте: ссылки на реестр, запрещённые формулировки, числа (гейт Factual).

Проверяется публикуемый текст объектов: строки `text` спецификаций
вариантов и файлы `copy/*.md`. Документация не проверяется — в ней
запрещённые формулировки встречаются как примеры (forbidden.yaml).

Слова-утверждения: слово из data/facts/claim_keywords.yaml («движок»,
«Windows»…) требует подкрепляющего факта в claims.

Числа: каждое число от 10 в публикуемом тексте должно встречаться в
утверждении или допустимой формулировке заявленного факта (CLAIM_POLICY
§3). Даты и годы не считаются; «150 тысяч» = «150 000» = «150,000».
"""
from __future__ import annotations

import re
from pathlib import Path

from .common import Finding, Item, Report, load_items, load_yaml, rel
from .facts import expired, registry, rules

# Даты: 04.10.2026, 10.2026, 2026-10-04, «4 октября», «October 2026», «Oct 2026».
_MONTHS_RU = r"(?:январ|феврал|март|апрел|ма[йя]|июн|июл|август|сентябр|октябр|ноябр|декабр)\w*"
_MONTHS_EN = r"(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\.?"
DATE_RE = re.compile(
    rf"\b\d{{1,2}}\.\d{{1,2}}\.\d{{4}}\b|\b\d{{4}}-\d{{2}}-\d{{2}}\b|\b\d{{1,2}}\.\d{{4}}\b"
    rf"|\b\d{{1,2}}\s+{_MONTHS_RU}|\b\d{{1,2}}\s+{_MONTHS_EN}|{_MONTHS_EN}\s+\d{{1,2}}\b"
    rf"|\b(?:19|20)\d{{2}}\b",
    re.IGNORECASE,
)
FIGURE_REF = re.compile(r"\{\{\s*figure\s*:[^}]*\}\}")
NUM_RE = re.compile(
    r"(?<![\w+#.,])(\d{1,3}(?:[   ,]\d{3})+|\d+(?:[.,]\d+)?)(?![\w])"
    r"(?:\s*(тыс\w*|thousand|k\b))?",
    re.IGNORECASE,
)


def numbers(text: str) -> set[float]:
    """Числа текста без дат: значения с учётом разрядов и «тысяч»."""
    text = DATE_RE.sub(" ", text)
    out = set()
    for m in NUM_RE.finditer(text):
        raw, mult = m.group(1), m.group(2)
        if re.fullmatch(r"\d{1,3}(?:[   ,]\d{3})+", raw):
            val = float(re.sub(r"\D", "", raw))
        else:
            val = float(raw.replace(",", "."))
        if mult:
            val *= 1000
        out.add(round(val, 3))
    return out


def fact_numbers(fids: list[str], reg: dict) -> set[float]:
    out: set[float] = set()
    for fid in fids:
        f = reg.get(fid)
        if not f:
            continue
        texts = [f["statement"]] + [w for ws in (f.get("allowed_wording") or {}).values() for w in ws]
        for t in texts:
            out |= numbers(t)
    return out


def scan(text: str, rule_list: list[dict]) -> list[tuple[dict, str]]:
    """Срабатывания запретов: (правило, фрагмент). unless — в окне ±60 символов."""
    hits = []
    for rule in rule_list:
        for m in re.finditer(rule["pattern"], text):
            if rule.get("unless"):
                window = text[max(0, m.start() - 60):m.end() + 60]
                if re.search(rule["unless"], window):
                    continue
            hits.append((rule, m.group(0)))
    return hits


def _strings(node) -> list[str]:
    if isinstance(node, str):
        return [node]
    if isinstance(node, dict):
        return [s for v in node.values() for s in _strings(v)]
    if isinstance(node, list):
        return [s for v in node for s in _strings(v)]
    return []


def _accepted(item: Item, rule_id: str) -> bool:
    issues = (item.data.get("review") or {}).get("issues") or {}
    for sev in ("fatal", "major", "minor"):
        for i in issues.get(sev, []):
            if i.get("status") == "accepted" and rule_id in (i.get("text", "") + i.get("resolution", "")):
                return True
    return False


def _fact_refs(fids: list[str], reg: dict, where: str) -> list[Finding]:
    out = []
    for fid in fids:
        f = reg.get(fid)
        if f is None:
            out.append(Finding("error", f"{where}: факт {fid} не найден в реестре"))
            continue
        if not f["public"]:
            out.append(Finding("error", f"{where}: факт {fid} не публичный (public: false)"))
        if f["class"] == "HYPOTHESIS":
            out.append(Finding("error", f"{where}: {fid} — HYPOTHESIS не подаётся как утверждение (Д6)"))
        elif f["class"] == "INTERPRETATION":
            out.append(Finding("warn", f"{where}: {fid} — INTERPRETATION, подавать как мнение"))
        if f["class"] != "FACT" and f["risk"] == "high":
            out.append(Finding("error", f"{where}: {fid} — утверждение высокого риска не FACT (Д6)"))
        if expired(f):
            out.append(Finding("warn", f"{where}: {fid} — recheck_by {f['recheck_by']} прошёл"))
    return out


def keywords() -> list[dict]:
    return load_yaml("data/facts/claim_keywords.yaml")["rules"]


def text_findings(item: Item, text: str, fids: list[str], where: str, soft: bool, reg: dict,
                  rule_list: list[dict], progress=None) -> list[Finding]:
    """Запреты и числа в одном публикуемом тексте. soft — отклонённый вариант: только предупреждения.

    progress — результат подстановки (Resolved) с величинами наборов прогресса (Д38): их
    значения — источник чисел, а сами величины подкрепляют слова-утверждения (progress в
    claim_keywords.yaml), как факты из claims.
    """
    used = set(getattr(progress, "progress_used", None) or [])
    text = FIGURE_REF.sub(" ", text)  # {{figure:…}} — место графика, не слова текста; данные фигур проверяет progress
    out = []
    hard = "warn" if soft else "error"
    for rule, frag in scan(text, rule_list):
        msg = f"{where}: «{frag}» — {rule['id']}: {rule['reason']}"
        if rule["severity"] == "fatal":
            out.append(Finding(hard, msg))
        elif rule["severity"] == "major":
            out.append(Finding("note" if _accepted(item, rule["id"]) else hard, msg))
        else:
            out.append(Finding("warn", msg))
    for kw in keywords():
        m = re.search(kw["pattern"], text)
        if m and not set(kw["any_of"]) & set(fids) and not set(kw.get("progress") or []) & used:
            out.append(Finding(hard, f"{where}: «{m.group(0)}» — нужен факт из {', '.join(kw['any_of'])} в claims ({kw['id']})"))
    allowed = fact_numbers(fids, reg)
    for v in getattr(progress, "progress_values", None) or []:
        allowed |= numbers(v)
    for n in sorted(numbers(text)):
        if n >= 10 and n not in allowed:
            shown = int(n) if n == int(n) else n
            out.append(Finding(hard, f"{where}: число {shown} не найдено в заявленных фактах ({', '.join(fids) or 'нет'})"))
    return out


def item_findings(item: Item, variant: str | None = None) -> list[Finding]:
    """Находки гейта Factual по объекту; variant — только этот вариант (и copy/)."""
    reg, rule_list = registry(), rules()
    d = item.data
    out = []
    item_claims = [c["fact"] for c in d.get("claims", [])]
    out += _fact_refs(item_claims, reg, item.id)
    for v in d.get("variants", []):
        if variant and v["id"] != variant:
            continue
        spec_path = item.path(v["spec"])
        if not spec_path.is_file():
            continue  # отсутствие спецификации — ошибка validate.py
        spec = load_yaml(spec_path)
        where = f"{item.id}/{v['id']}"
        spec_claims = spec.get("claims", [])
        # Сами факты проверены на уровне объекта; варианту достаточно не выходить за его claims.
        for fid in spec_claims:
            if fid not in item_claims:
                out.append(Finding("error", f"{where}: {fid} есть в спецификации, но не заявлен в claims объекта"))
        soft = v.get("status") == "rejected"
        for lang, t in (spec.get("text") or {}).items():
            out += text_findings(item, "\n".join(_strings(t)), spec_claims, f"{where} [{lang}]", soft, reg, rule_list)
    vdir = item.dir / "variants"
    if vdir.is_dir():  # content_item/v2: тексты вариантов площадок после подстановки фактов (Д22, Д28)
        from .core import textdoc
        from .core.inject import resolve
        from .core.repo import Repo
        repo = Repo()
        for p in sorted(vdir.glob("*.md")):
            doc = textdoc.load(p)
            res = resolve(doc.body, doc.meta.get("lang", "ru"), repo, repo.canonical_base())
            fids = sorted(set(item_claims + res.facts_used))
            text = "\n".join(x for x in (doc.meta.get("title"), doc.meta.get("summary"), res.text, doc.meta.get("cta")) if x)
            out += text_findings(item, text, fids, rel(p), doc.meta.get("status") == "rejected", reg, rule_list, res)
    copy_dir = item.dir / "copy"
    if copy_dir.is_dir():
        for p in sorted(copy_dir.glob("*.md")):
            out += text_findings(item, _publishable(p), item_claims, rel(p), False, reg, rule_list)
    return out


def _publishable(p: Path) -> str:
    """Текст публикации из copy/*.md: без служебных комментариев <!-- … -->."""
    return re.sub(r"<!--.*?-->", "", p.read_text(encoding="utf-8"), flags=re.S)


def check() -> Report:
    r = Report("claims — утверждения и anti-hype в контенте")
    n = 0
    for item in load_items():
        n += 1
        r.add(item_findings(item))
    r.note(f"объектов {n}")
    return r
