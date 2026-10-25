"""Матрица возможностей площадок: таблица из data/platforms/capabilities.yaml (Д33).

`python3 -m tools.cs capabilities --write` переписывает таблицу между
метками в docs/platforms/PUBLISHING_CAPABILITY_MATRIX.md; проверка
`capabilities` сверяет таблицу с данными и данные — с адаптерами.
"""
from __future__ import annotations

from .common import ROOT, Report, load_yaml

DOC = ROOT / "docs" / "platforms" / "PUBLISHING_CAPABILITY_MATRIX.md"
BEGIN, END = "<!-- matrix:begin -->", "<!-- matrix:end -->"
MARK = {"api": "✓ API", "restricted": "◐ по допуску", "own": "● своя система", "manual": "✋ вручную", "no": "✗"}
OPS = ["read", "create", "edit", "delete", "media", "schedule", "analytics"]
HEAD = ["Площадка", "Read", "Create", "Edit", "Delete", "Media", "Schedule", "Analytics", "OAuth/API", "Automation level", "Адаптер"]


def table() -> str:
    data = load_yaml("data/platforms/capabilities.yaml")
    rows = ["| " + " | ".join(HEAD) + " |", "|" + "---|" * len(HEAD)]
    for p in data["platforms"]:
        ops = [MARK[p["operations"][o]["status"]] for o in OPS]
        adapter = {"implemented": "есть", "stub": "ручной путь", "none": "нет"}[p.get("adapter_status", "none")]
        auth = p.get("auth", "").replace("|", "/")
        rows.append("| " + " | ".join([p["name"], *ops, auth, f"**{p['automation']}**", adapter]) + " |")
    rows.append("")
    rows.append(f"Данные — `data/platforms/capabilities.yaml` (проверено {data['checked']}); "
                f"примечания к ячейкам и лимиты — там же; источники — `{data['research']}`.")
    return "\n".join(rows)


def write() -> None:
    text = DOC.read_text(encoding="utf-8")
    a, b = text.index(BEGIN) + len(BEGIN), text.index(END)
    DOC.write_text(text[:a] + "\n" + table() + "\n" + text[b:], encoding="utf-8")


def check() -> Report:
    from .adapters import REGISTRY
    r = Report("capabilities — матрица площадок и адаптеры")
    data = load_yaml("data/platforms/capabilities.yaml")
    if not DOC.is_file():
        r.error(f"нет {DOC.relative_to(ROOT)}")
        return r
    text = DOC.read_text(encoding="utf-8")
    if BEGIN not in text or END not in text:
        r.error("в матрице нет меток matrix:begin/end")
    elif text[text.index(BEGIN) + len(BEGIN):text.index(END)].strip() != table().strip():
        r.error("таблица матрицы расходится с capabilities.yaml — python3 -m tools.cs capabilities --write")
    dests = {d["platform"] for d in (load_yaml("data/platforms/destinations.yaml") or {}).get("destinations", [])}
    for p in data["platforms"]:
        ad = p.get("adapter")
        if ad and ad not in REGISTRY:
            r.error(f"{p['id']}: адаптер {ad} не зарегистрирован")
        if ad and p.get("adapter_status") == "implemented" and not REGISTRY[ad].automated:
            r.error(f"{p['id']}: implemented, а адаптер — ручной путь")
        if ad and p.get("adapter_status") == "stub" and REGISTRY[ad].automated:
            r.error(f"{p['id']}: stub, а адаптер публикует — обновить capabilities.yaml")
    for platform in dests:
        if platform not in {p["id"] for p in data["platforms"]}:
            r.error(f"адресаты площадки {platform}, а её нет в capabilities.yaml")
    r.note(f"площадок {len(data['platforms'])}, проверено {data['checked']}")
    return r
