"""Публичная выжимка для сайта автора (D-15): прогресс и система, без закрытых подробностей.

Цель выжимки — посетитель сайта в целом видит прогресс автора и систему, к
которой он идёт (слова автора 6 октября 2026), а не старые открытые проекты
и не устройство рабочей кухни. Белый список полей, а не чёрный: в выжимку
попадает только перечисленное ниже. Убираются:

- слой ранних и малых открытых проектов (`DROP_LAYERS`) — целиком, с потоками;
- узлы `undisclosed` (проект без публичного имени) и всё, что связано только с
  ним (внешний источник его сборки), — ни имени, ни деталей, ни потоков;
  остаётся число `hidden`, как «+ 1 проект без публичного имени» на сайте;
- устройства автора и личные каналы; цепочка выгрузки «репозиторий →
  устройство → сайт» схлопывается в «репозиторий → сайт»;
- подробности: ветки, вливания, пути передачи, триггеры, ссылки на источники,
  контракты, сигналы, пробы, адреса, реестр, диагностика, дневная активность
  самого автора; время — только датой.

Закрытые репозитории остаются — с ролью, стадией и прогрессом: коммиты по
дням (ИИ и человек), первый и последний коммит, итог за окно.

После сборки весь текст выжимки проверяется по FORBIDDEN: находка — отказ
сборки (PublicError), а не тихая правка. Второй слой — фильтр сайта
(SocialMediaImage:Д39).
"""
from __future__ import annotations

import json
import re

PUBLIC_SCHEMA = "dpvis-public/1"

DROP_LAYERS = {"background"}          # старые открытые проекты и профиль — не про текущую систему
DROP_VISIBILITY = {"undisclosed"}
DROP_KINDS = {"device"}
PERSONAL_NODES = {"ntfy"}             # личный канал уведомлений автора на его устройства

NODE_FIELDS = ("id", "kind", "layer", "name", "group", "role", "stage", "status", "visibility")
FLOW_FIELDS = ("id", "from", "to", "kind", "driver", "status", "what", "bidirectional")
MODEL_FIELDS = ("title", "as_of", "timezone")
CATALOG_KEYS = ("node_kinds", "flow_kinds", "drivers", "statuses", "visibility", "groups")

# Чего не должно быть нигде в выжимке (регистр не важен). Имя проекта без
# публичного имени и признаки его назначения; устройства и личные каналы;
# адреса, почта, хеши, ветки сессий, точное время событий.
FORBIDDEN = [
    (re.compile(r"dppass", re.I), "имя проекта без публичного имени"),
    (re.compile(r"xray|\bvpn\b|впн|прокси|proxy|туннел|tunnel|подписок", re.I), "детали проекта без публичного имени"),
    (re.compile(r"termux|телефон|ntfy|unifiedpush|устройств", re.I), "устройства и личные каналы автора"),
    (re.compile(r"beget|[\w.+-]+@[\w-]+\.[\w.]+|https?://|\b\d{1,3}(?:\.\d{1,3}){3}\b", re.I), "адреса, почта, хостинг"),
    (re.compile(r"\b[0-9a-f]{40}\b|\bccr-[0-9a-f]{8}|claude/[a-z]", re.I), "хеши коммитов и имена веток"),
]
TIME_IN_NODES = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}")

# Правки текста ролей и описаний для выжимки: смысл тот же, закрытого нет.
TEXT_RULES = [
    (re.compile(r",?\s*без имени DPpass"), ""),
    (re.compile(r"\s*на хостинге Beget"), " на хостинге"),
]
TEXT_FIELDS = ("role", "stage", "what", "name")


class PublicError(Exception):
    def __init__(self, problems: list[str]):
        super().__init__("; ".join(problems))
        self.problems = problems


def _date(iso: str | None) -> str | None:
    return iso[:10] if iso else None


def _clean(text: str) -> str:
    for pat, repl in TEXT_RULES:
        text = pat.sub(repl, text)
    return text


def _pick(src: dict, fields: tuple[str, ...]) -> dict:
    out = {k: src[k] for k in fields if k in src and src[k] not in (None, "", [])}
    for k in TEXT_FIELDS:
        if isinstance(out.get(k), str):
            out[k] = _clean(out[k])
    return out


def _base_drop(model: dict) -> set[str]:
    return {n["id"] for n in model["nodes"]
            if n.get("visibility") in DROP_VISIBILITY or n["kind"] in DROP_KINDS
            or n["id"] in PERSONAL_NODES or n.get("layer") in DROP_LAYERS}


def _collapse(model: dict, drop: set[str]) -> list[dict]:
    """«Репозиторий → устройство → B» выгрузки — поток «репозиторий → B»: путь виден, устройство — нет."""
    out = []
    for dev in (n["id"] for n in model["nodes"] if n["kind"] in DROP_KINDS):
        # вход — клон репозитория на устройстве (git), а не, скажем, APK на проверку
        ins = [f for f in model["flows"] if f["to"] == dev and f["kind"] == "deploy" and f["transport"] == "git"
               and f["from"] not in drop]
        outs = [f for f in model["flows"] if f["from"] == dev and f["kind"] == "deploy" and f["to"] not in drop]
        for a in ins:
            for b in outs:
                f = dict(b)
                f["id"] = f"deploy.{a['from']}.{b['to']}"
                f["from"] = a["from"]
                f["_state_of"] = b["id"]
                out.append(f)
    return out


def dropped_nodes(model: dict) -> set[str]:
    drop = _base_drop(model)
    collapsed = _collapse(model, drop)
    linked = {f["to"] for f in collapsed} | {f["from"] for f in collapsed}
    # узлы, все потоки которых ведут только к убранным, — тоже детали убранного
    for n in model["nodes"]:
        if n["id"] in drop or n["id"] in linked or n["kind"] in ("actor", "repo"):
            continue
        ends = [f["to"] if f["from"] == n["id"] else f["from"] for f in model["flows"] if n["id"] in (f["from"], f["to"])]
        if ends and all(e in drop for e in ends):
            drop.add(n["id"])
    return drop


def _node_state(st: dict | None, actor: bool) -> dict | None:
    if not st:
        return None
    act = st.get("activity") or {}
    res = {"activity": {"state": act.get("state", "unknown"), "last": None if actor else _date(act.get("last"))}}
    git = st.get("git")
    if git:
        res["git"] = {
            "days": {k: git.get("days", {}).get(k, []) for k in ("ai", "human")},
            "first_date": _date(git.get("first_time")),
            "head_date": _date(git.get("head_time")),
            "window": {k: git.get("window", {}).get(k, 0) for k in ("ai", "human")},
        }
    return res


def _flow_state(st: dict | None) -> dict | None:
    if not st:
        return None
    res = {"state": st.get("state", "unknown")}
    for k in ("days", "week", "total"):
        if k in st:
            res[k] = st[k]
    if st.get("last"):
        res["last"] = _date(st["last"])
    return res


def public_bundle(model: dict, state: dict | None) -> dict:
    drop = dropped_nodes(model)
    nodes = [_pick(n, NODE_FIELDS) for n in model["nodes"] if n["id"] not in drop]
    kept = {n["id"] for n in nodes}
    flows_src = [f for f in model["flows"] if f["from"] in kept and f["to"] in kept] + \
                [f for f in _collapse(model, drop) if f["from"] in kept and f["to"] in kept]
    flows = [_pick(f, FLOW_FIELDS) for f in flows_src]

    used = {"node_kinds": {n["kind"] for n in nodes}, "flow_kinds": {f["kind"] for f in flows},
            "visibility": {n["visibility"] for n in nodes}, "groups": {n["group"] for n in nodes if n.get("group")},
            "statuses": {x["status"] for x in nodes + flows}, "drivers": {f["driver"] for f in flows}}
    pm = _pick(model, MODEL_FIELDS)
    pm["layers"] = [l for l in model.get("layers", []) if any(n["layer"] == l["id"] for n in nodes)]
    pm["catalog"] = {k: [x for x in model["catalog"].get(k, []) if x["id"] in used[k]] for k in CATALOG_KEYS}
    pm["nodes"] = nodes
    pm["flows"] = flows
    hidden = sum(1 for n in model["nodes"] if n.get("visibility") in DROP_VISIBILITY)
    if hidden:
        pm["hidden"] = {"undisclosed": hidden}

    ps = None
    if state:
        ps = {
            "as_of": state["as_of"],
            "generated_by": state.get("generated_by"),
            "window": state.get("window"),
            "rules": {k: v for k, v in (state.get("rules") or {}).items() if k in ("rate_days", "flow_recent_hours")},
            "nodes": {},
            "flows": {},
        }
        actors = {n["id"] for n in nodes if n["kind"] == "actor"}
        for n in nodes:
            s = _node_state(state.get("nodes", {}).get(n["id"]), n["id"] in actors)
            if s:
                ps["nodes"][n["id"]] = s
        for f in flows_src:
            s = _flow_state(state.get("flows", {}).get(f.get("_state_of", f["id"])))
            if s:
                ps["flows"][f["id"]] = s
    doc = {"schema": PUBLIC_SCHEMA, "model": pm, "state": ps}
    check(doc)
    return doc


def check(doc: dict) -> None:
    """Отказ, если в выжимке осталось закрытое: имя, детали, устройства, адреса, хеши, точное время."""
    problems = []
    text = json.dumps(doc, ensure_ascii=False)
    for pat, what in FORBIDDEN:
        for m in pat.finditer(text):
            problems.append(f"{what}: «…{text[max(0, m.start() - 30):m.end() + 10]}…»")
    inner = json.dumps({"n": doc["model"]["nodes"], "f": doc["model"]["flows"],
                        "s": {k: v for k, v in (doc.get("state") or {}).items() if k in ("nodes", "flows")}},
                       ensure_ascii=False)
    if TIME_IN_NODES.search(inner):
        problems.append("точное время событий: в выжимке — только даты")
    if problems:
        raise PublicError(problems)
