"""Раздел «Путь» (Д44): путь автора по датам и конечная цель экосистемы.

Данные — `data/website/path.yaml` (схема `path/v1`): станции маршрута и схема конечной цели
(факт GOAL-ARCH). Тексты страницы — только формулировки фактов реестра и вехи страницы
прогресса: здесь — раскладка и рисунки, а не утверждения. Сборка сверяет данные (`problems`):
факты публичные и с формулировкой на языке страницы, вехи есть и попадают в дни станции.

Рисунки — SVG без скриптов и встроенных стилей (CSP сайта, П2-12): цвета и шрифты — классы
`style.css`. Маршрут: широкий рисунок для компьютера (на телефоне скрыт) и список-маршрут для
всех ширин — он же текст рисунка для экранного диктора. Цель: широкий и узкий рисунки и список
направлений. Ширина рисунка в единицах viewBox равна ширине колонки — текст не мельче 12 px.
"""
from __future__ import annotations

import datetime as dt
import html
import math

from ..progress.dataset import MONTHS_SHORT

E = html.escape

W_WIDE = 780      # колонка 860 px без полей и рамки фигуры
W_NARROW = 340    # телефон 390 px без полей страницы и фигуры
CH = 0.56         # средняя ширина знака IBM Plex Sans, доля кегля (с запасом на латиницу)

L = {
    "ru": {
        "title": "Путь",
        "description": "Путь dpsmpx по датам — от первых проектов до экосистемы — и конечная цель: "
                       "законченные системы и одно приложение над ними.",
        "lead": "Откуда я иду и к какой системе: маршрут по датам и цель, как я её записал.",
        "route": "Маршрут", "goal": "Конечная цель", "now": "сейчас", "plan": "план",
        "route_aria": "Маршрут: станции пути от первых проектов до записанной цели и две фазы впереди",
        "goal_aria": "Схема конечной цели: одно приложение, два ядра, пять направлений и визуализация под ними",
        "route_note": "Квадрат — репозиторий с работой к этой станции; станции — не в масштабе времени: "
                      "между первыми проектами и игрой — годы, дальше — дни.",
        "groups": {"game": "игра", "core": "ядро на срезе 5 октября", "new": "новые после среза",
                   "hidden": "без публичного имени"},
        "finished": "впереди — те же системы, доведённые до законченных",
        "exists": "есть сейчас", "planned": "план",
        "goal_note": "Сплошная рамка — есть сейчас, пунктир — план. Сегодня проекты не зависят друг от друга; "
                     "связи схемы — то, что появится.",
        "directions": "Направления цели списком: роли и стадии проектов",
        "rule_by": "dpsmpx, план экосистемы",
        "sources": "Тексты страницы — из реестра фактов сайта и вех страницы прогресса: у каждого утверждения "
                   "есть документ проекта и дата.",
        "see_eco": "Как система выглядит сейчас — интерактивная схема", "eco": "Экосистема",
        "see_progress": "Числа пути — страница", "progress": "Прогресс",
        "home_h2": "Путь и цель", "home_link": "Путь и конечная цель",
        "frame_now": "сегодня",
    },
    "en": {
        "title": "Path",
        "description": "dpsmpx's path by date — from the first projects to an ecosystem — and the end goal: "
                       "finished systems and one app over them.",
        "lead": "Where I come from and what system I'm heading for: the route by date and the goal as I wrote it down.",
        "route": "Route", "goal": "The end goal", "now": "now", "plan": "planned",
        "route_aria": "Route: stations of the path from the first projects to the written goal, and two phases ahead",
        "goal_aria": "The end goal: one app, two cores, five directions and a visualisation under them",
        "route_note": "A square is a repository with work by that station; stations are not to scale: "
                      "years pass between the first projects and the game, days after that.",
        "groups": {"game": "the game", "core": "core as of the 5 October snapshot", "new": "new after the snapshot",
                   "hidden": "no public name"},
        "finished": "ahead — the same systems, brought to finished",
        "exists": "exists now", "planned": "planned",
        "goal_note": "Solid frame — exists now, dashed — planned. Today the projects do not depend on each other; "
                     "the links in the diagram are what comes next.",
        "directions": "The goal's directions as a list: roles and stages of the projects",
        "rule_by": "dpsmpx, the ecosystem plan",
        "sources": "The page's texts come from the site's fact registry and the progress page milestones: every "
                   "claim has a project document and a date.",
        "see_eco": "What the system looks like today — the interactive diagram", "eco": "Ecosystem (RU)",
        "see_progress": "The numbers behind the path — the", "progress": "Progress page",
        "home_h2": "Path and goal", "home_link": "The path and the end goal",
        "frame_now": "today",
    },
}


# ------------------------------------------------------------------ данные
def name_of(node: dict, lang: str) -> str:
    n = node["name"]
    return n if isinstance(n, str) else (n.get(lang) or n["ru"])


def label(d: dict, lang: str) -> str:
    return d.get(lang) or d["ru"]


def _date(v) -> dt.date | None:
    if v is None:
        return None
    return v if isinstance(v, dt.date) else dt.date.fromisoformat(str(v)[:10])


def when(st: dict, lang: str) -> str:
    """Подпись дня станции: 11.09.2026, 25–29.09.2026 (RU); 11 Sep 2026, 25–29 Sep 2026 (EN)."""
    if st.get("when"):
        return label(st["when"], lang)
    d, d2 = _date(st.get("date")), _date(st.get("date_to"))
    if d is None:
        return ""
    if lang == "ru":
        if d2 and (d2.month, d2.year) == (d.month, d.year):
            return f"{d.day:02d}–{d2.day:02d}.{d.month:02d}.{d.year}"
        return f"{d.day:02d}.{d.month:02d}.{d.year}" + (f"–{d2.day:02d}.{d2.month:02d}.{d2.year}" if d2 else "")
    mon = MONTHS_SHORT["en"][d.month - 1]
    if d2 and (d2.month, d2.year) == (d.month, d.year):
        return f"{d.day}–{d2.day} {mon} {d.year}"
    return f"{d.day} {mon} {d.year}" + (f" – {d2.day} {MONTHS_SHORT['en'][d2.month - 1]} {d2.year}" if d2 else "")


def day_text(day, lang: str) -> str:
    return when({"date": day}, lang)


def when_short(st: dict, lang: str) -> str:
    """Подпись дня на рисунке маршрута — без года (год — скобкой под станциями): 25–29.09, Sep 25–29."""
    if st.get("when"):
        return label(st["when"], lang)
    d, d2 = _date(st.get("date")), _date(st.get("date_to"))
    if d is None:
        return ""
    if d.year < 2026 and not d2:
        return str(d.year)
    if lang == "ru":
        if d2:
            return f"{d.day:02d}–{d2.day:02d}.{d.month:02d}" if d2.month == d.month else \
                f"{d.day:02d}.{d.month:02d}–{d2.day:02d}.{d2.month:02d}"
        return f"{d.day:02d}.{d.month:02d}"
    mon = MONTHS_SHORT["en"][d.month - 1]
    if d2:
        return f"{mon} {d.day}–{d2.day}" if d2.month == d.month else f"{mon} {d.day}–{MONTHS_SHORT['en'][d2.month - 1]} {d2.day}"
    return f"{mon} {d.day}"


def sentence(t: str) -> str:
    """Формулировка факта как предложение списка: с заглавной и точкой в конце."""
    t = t[:1].upper() + t[1:]
    return t if t[-1:] in ".!?…»" else t + "."


def problems(data: dict, fact_ok, milestones: dict) -> list[str]:
    """Что не так с данными раздела: ссылки на узлы, факты, вехи и их дни.

    fact_ok(fid, idx, lang) → текст ошибки или None; milestones — {id: веха}."""
    out = []
    nodes = data.get("nodes") or {}

    def text_ok(src: dict, where: str) -> None:
        if "milestone" in src:
            ms = milestones.get(src["milestone"])
            if not ms:
                out.append(f"путь: {where}: вехи {src['milestone']} нет в data/progress/milestones.yaml")
            else:
                for lang in ("ru", "en"):
                    if not ms.get(lang):
                        out.append(f"путь: {where}: у вехи {src['milestone']} нет текста {lang}")
            return
        for lang in ("ru", "en"):
            err = fact_ok(src["fact"], src.get("idx", 0), lang)
            if err:
                out.append(f"путь: {where}: {err}")

    for nid, n in nodes.items():
        for key in ("role", "stage"):
            if n.get(key):
                text_ok(n[key], f"узел {nid}, {key}")
    seen_plan = False
    for st in data.get("stations") or []:
        sid = st["id"]
        if st.get("plan"):
            seen_plan = True
        elif seen_plan:
            out.append(f"путь: станция {sid} пройдена, а стоит после станций плана")
        if not st.get("plan") and not st.get("date"):
            out.append(f"путь: станция {sid} без даты")
        for r in st.get("repos") or []:
            if r not in nodes:
                out.append(f"путь: станция {sid}: узла {r} нет в nodes")
        lo, hi = _date(st.get("date")), _date(st.get("date_to") or st.get("date"))
        for src in st.get("texts") or []:
            text_ok(src, f"станция {sid}")
            ms = milestones.get(src.get("milestone") or "")
            if ms and lo and not (lo <= _date(ms["date"]) <= hi):
                out.append(f"путь: станция {sid}: веха {src['milestone']} от {ms['date']} не попадает в дни станции")
    goal = data.get("goal") or {}
    for key in ("app", "base"):
        if goal.get(key):
            if goal[key]["node"] not in nodes:
                out.append(f"путь: цель, {key}: узла {goal[key]['node']} нет в nodes")
            text_ok(goal[key]["text"], f"цель, {key}")
    for i, c in enumerate(goal.get("cores") or []):
        text_ok(c["text"], f"цель, ядро {i + 1}")
    for d in goal.get("domains") or []:
        for nid in d["nodes"]:
            if nid not in nodes:
                out.append(f"путь: цель, направление {d['id']}: узла {nid} нет в nodes")
    if goal.get("rule"):
        text_ok(goal["rule"], "цель, принцип")
    if goal.get("source"):
        err = fact_ok(goal["source"], 0, "ru")
        if err:
            out.append(f"путь: цель, источник: {err}")
    return out


def labels(data: dict) -> list[str]:
    """Подписи из данных (не факты): станции, направления, ядра, имена — для проверки anti-hype."""
    out = []
    for st in data.get("stations") or []:
        for key in ("title", "when"):
            if st.get(key):
                out += [st[key]["ru"], st[key]["en"]]
    goal = data.get("goal") or {}
    for d in (goal.get("domains") or []) + (goal.get("cores") or []):
        out += [d["title"]["ru"], d["title"]["en"]]
    for n in (data.get("nodes") or {}).values():
        out += [n["name"]] if isinstance(n["name"], str) else [n["name"]["ru"], n["name"]["en"]]
    return out


# ------------------------------------------------------------------ раскладка текста
def tw(s: str, size: float) -> float:
    return len(s) * size * CH


def wrap(s: str, width: float, size: float) -> list[str]:
    """Перенос по словам в ширину width (оценка по средней ширине знака)."""
    lines, cur = [], ""
    for word in s.split():
        cand = f"{cur} {word}".strip()
        if cur and tw(cand, size) > width:
            lines.append(cur)
            cur = word
        else:
            cur = cand
    if cur:
        lines.append(cur)
    return lines or [""]


def strip_name(text: str, name: str) -> str:
    """«DPmetaApp — одно приложение…» в рамке DPmetaApp → «одно приложение…»: имя уже в заголовке рамки."""
    for sep in (" — ", ": "):
        if text.startswith(name + sep):
            return text[len(name) + len(sep):]
    return text


def _text(x: float, y: float, s: str, cls: str, anchor: str = "start") -> str:
    a = "" if anchor == "start" else f' text-anchor="{anchor}"'
    return f'<text x="{x:.1f}" y="{y:.1f}" class="{cls}"{a}>{E(s)}</text>'


def _lines(x: float, y: float, lines: list[str], cls: str, lh: float, anchor: str = "start") -> str:
    return "".join(_text(x, y + i * lh, s, cls, anchor) for i, s in enumerate(lines))


# ------------------------------------------------------------------ маршрут
def _slots(stations: list[dict]) -> list[tuple[str, dict | None]]:
    """Станции и «сейчас» между пройденным и планом."""
    out, placed = [], False
    for st in stations:
        if st.get("plan") and not placed:
            out.append(("now", None))
            placed = True
        out.append(("st", st))
    if not placed:
        out.append(("now", None))
    return out


def route_svg(data: dict, lang: str, goal_name: str) -> str:
    """Широкий рисунок маршрута: линия станций; над станцией — квадраты репозиториев с работой к ней."""
    lab = L[lang]
    nodes = data["nodes"]
    slots = _slots(data["stations"])
    W, x0 = W_WIDE, 44
    step = (W - 2 * x0) / (len(slots) - 1)
    SQ, GAP = 12, 3
    BASE, LINE = 140, 160
    parts, cum, years = [], [], []
    xs = [x0 + i * step for i in range(len(slots))]
    # линия: пройденное — сплошная; годы между первыми проектами и игрой — точками; план — пунктир
    for i in range(len(slots) - 1):
        s1, s2 = slots[i][1], slots[i + 1][1]
        if (s1 and s1.get("plan")) or (s2 and s2.get("plan")):
            cls = "r-line r-plan"
        elif s1 and s2 and _date(s1.get("date")) and _date(s2.get("date")) and \
                (_date(s2["date"]) - _date(s1.get("date_to") or s1["date"])).days > 60:
            cls = "r-line r-gap"
        else:
            cls = "r-line"
        parts.append(f'<line x1="{xs[i]:.1f}" y1="{LINE}" x2="{xs[i + 1]:.1f}" y2="{LINE}" class="{cls}"/>')
    for i, (kind, st) in enumerate(slots):
        x = xs[i]
        if kind == "now":
            parts.append(f'<line x1="{x:.1f}" y1="{LINE - 130}" x2="{x:.1f}" y2="{LINE - 12}" class="r-nowline"/>')
            parts.append(f'<circle cx="{x:.1f}" cy="{LINE}" r="9" class="r-now"/>'
                         f'<circle cx="{x:.1f}" cy="{LINE}" r="3.5" class="r-nowdot"/>')
            parts.append(_text(x, LINE + 24, when_short({"date": data["as_of"]}, lang), "t-axis t-num", "middle"))
            parts.append(_text(x, LINE + 44, lab["now"], "t-lab r-nowt", "middle"))
            years.append((x, _date(data["as_of"]).year))
            continue
        new = [r for r in st.get("repos") or [] if r not in cum]
        cum += new
        plan = bool(st.get("plan"))
        last = i == len(slots) - 1
        # квадраты: два столбца снизу вверх, в порядке появления
        for k, rid in enumerate(cum):
            col, row = k % 2, k // 2
            sx = x - SQ - GAP / 2 + col * (SQ + GAP)
            sy = BASE - SQ - row * (SQ + GAP)
            node = nodes[rid]
            cls = f"sq pv-{node.get('group', 'core')}" + (" sq-new" if rid in new else "") + (" sq-fin" if plan else "")
            parts.append(f'<rect x="{sx:.1f}" y="{sy:.1f}" width="{SQ}" height="{SQ}" rx="2" class="{cls}">'
                         f'<title>{E(name_of(node, lang))}</title></rect>')
        if last and plan and cum:
            rows = math.ceil(len(cum) / 2)
            top = BASE - rows * (SQ + GAP) - 6
            fw = 2 * SQ + GAP + 14
            parts.append(f'<rect x="{x - fw / 2:.1f}" y="{top:.1f}" width="{fw:.1f}" height="{BASE - top + 6:.1f}" '
                         f'rx="6" class="r-frame"/>')
            parts.append(_text(x, top - 7, goal_name, "t-lab r-framet", "middle"))
        dot = "r-dot r-dot-plan" if plan else "r-dot"
        parts.append(f'<circle cx="{x:.1f}" cy="{LINE}" r="7" class="{dot}"/>')
        parts.append(_text(x, LINE + 24, when_short(st, lang), "t-axis t-num", "middle"))
        if st.get("date"):
            years.append((x, _date(st["date"]).year))
        parts.append(_lines(x, LINE + 44, wrap(label(st["title"], lang), step - 8, 13), "t-lab r-title", 16, "middle"))
    # год скобкой под станциями, у которых подпись дня без года
    yb = LINE + 44 + 2 * 16 + 6
    for year in sorted({yr for _, yr in years}):
        xs_y = [x for x, yr in years if yr == year]
        if year < 2026 and len(xs_y) == 1:
            continue
        a, b = min(xs_y) - step / 2 + 8, max(xs_y) + step / 2 - 8
        parts.append(f'<path d="M{a:.1f} {yb - 5} V{yb} H{b:.1f} V{yb - 5}" class="r-year"/>')
        parts.append(_text((a + b) / 2, yb + 18, str(year), "t-axis t-num", "middle"))
    H = yb + 26
    return (f'<svg class="chart route-svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}" role="img" '
            f'aria-label="{E(lab["route_aria"])}">{"".join(parts)}</svg>')


def route_legend(lang: str, groups_used: list[str]) -> str:
    lab = L[lang]
    keys = "".join(f'<span class="key"><span class="sw pv-{g}" aria-hidden="true"></span>{E(lab["groups"][g])}</span>'
                   for g in groups_used)
    keys += (f'<span class="key"><span class="sw sw-fin" aria-hidden="true"></span>{E(lab["finished"])}</span>')
    return f'<div class="legend">{keys}</div>'


def route_list(data: dict, lang: str, say) -> str:
    """Маршрут списком — на всех ширинах; на телефоне это и есть рисунок пути."""
    lab = L[lang]
    nodes = data["nodes"]
    items = []
    for kind, st in _slots(data["stations"]):
        if kind == "now":
            items.append(f'<li class="st st-now"><p class="st-when"><time datetime="{data["as_of"]}">'
                         f'{E(day_text(data["as_of"], lang))}</time></p><p class="st-nowt">{E(lab["now"])}</p></li>')
            continue
        texts = [t for t in (say(src) for src in st["texts"]) if t]
        dt_attr = f' datetime="{st["date"]}"' if st.get("date") else ""
        chips = ""
        if st.get("repos"):
            chips = '<ul class="chips">' + "".join(
                f'<li class="chip"><span class="sw pv-{nodes[r].get("group", "core")}" aria-hidden="true"></span>'
                f'{E(name_of(nodes[r], lang))}</li>' for r in st["repos"]) + "</ul>"
        cls = "st st-plan" if st.get("plan") else "st"
        items.append(f'<li class="{cls}" id="st-{E(st["id"])}"><p class="st-when"><time{dt_attr}>{E(when(st, lang))}</time></p>'
                     f'<h3>{E(label(st["title"], lang))}</h3>'
                     + "".join(f"<p>{E(sentence(t))}</p>" for t in texts) + chips + "</li>")
    return f'<ol class="route">{"".join(items)}</ol>'


# ------------------------------------------------------------------ конечная цель
class _Goal:
    def __init__(self, data: dict, lang: str, say):
        self.data, self.lang, self.say = data, lang, say
        self.nodes = data["nodes"]
        self.goal = data["goal"]

    def node_chip(self, nid: str) -> tuple[str, str, bool]:
        n = self.nodes[nid]
        return name_of(n, self.lang), n.get("group") or "none", bool(n.get("plan"))

    def chip(self, x: float, y: float, w: float, lines: list[str], group: str, plan: bool) -> tuple[str, float]:
        h = 10 + len(lines) * 15
        cls = "g-chip g-plan" if plan else "g-chip"
        out = f'<rect x="{x:.1f}" y="{y:.1f}" width="{w:.1f}" height="{h}" rx="4" class="{cls}"/>'
        if group != "none":
            out += f'<rect x="{x:.1f}" y="{y:.1f}" width="4" height="{h}" rx="2" class="pv-{group}"/>'
        out += _lines(x + 12, y + 18, lines, "t-lab g-chipt", 15)
        return out, h

    def box(self, x, y, w, title, text_lines, plan, title_cls="g-boxt", extra="") -> tuple[str, float]:
        h = 30 + len(text_lines) * 16 + (6 if text_lines else 0)
        cls = "g-box g-plan" if plan else "g-box"
        out = f'<rect x="{x:.1f}" y="{y:.1f}" width="{w:.1f}" height="{h}" rx="8" class="{cls}"/>'
        out += _text(x + w / 2, y + 21, title, f"t-lab {title_cls}", "middle")
        out += _lines(x + w / 2, y + 40, text_lines, "t-axis g-boxx", 16, "middle")
        if extra:
            out += _text(x + w - 10, y + 17, extra, "t-axis g-tag", "end")
        return out, h

    def app_and_cores(self, W: float, narrow: bool) -> tuple[list[str], float, list[tuple[float, float]]]:
        lab = L[self.lang]
        parts = []
        app = self.goal["app"]
        app_name = name_of(self.nodes[app["node"]], self.lang)
        app_text = strip_name(self.say(app["text"]) or "", app_name)
        aw = W - 16 if narrow else 440
        ax = (W - aw) / 2
        p, ah = self.box(ax, 4, aw, app_name, wrap(app_text, aw - 24, 12), True, "g-appt", lab["plan"])
        parts.append(p)
        cores = self.goal["cores"]
        gap = 12 if narrow else 36
        cw = ((W - 16 - gap * (len(cores) - 1)) / len(cores)) if narrow else min(320, (W - 80 - gap) / len(cores))
        total = cw * len(cores) + gap * (len(cores) - 1)
        cx0 = (W - total) / 2
        cy = 4 + ah + (26 if narrow else 30)
        mid = 4 + ah + (13 if narrow else 15)
        bottoms = []
        hs = []
        boxes = []
        for i, c in enumerate(cores):
            x = cx0 + i * (cw + gap)
            p, h = self.box(x, cy, cw, label(c["title"], self.lang), wrap(self.say(c["text"]) or "", cw - 20, 12), True)
            boxes.append(p)
            hs.append(h)
            bottoms.append((x + cw / 2, cy))
        hmax = max(hs)
        # связи приложения с ядрами — план
        parts.append(f'<path d="M{W / 2:.1f} {4 + ah} V{mid:.1f} M{bottoms[0][0]:.1f} {mid:.1f} H{bottoms[-1][0]:.1f} '
                     + " ".join(f"M{bx:.1f} {mid:.1f} V{cy}" for bx, _ in bottoms) + '" class="g-link"/>')
        parts += boxes
        return parts, cy + hmax, [(bx, cy + h) for (bx, _), h in zip(bottoms, hs)]

    def svg(self, narrow: bool) -> str:
        lab = L[self.lang]
        W = W_NARROW if narrow else W_WIDE
        parts, y, core_bottoms = self.app_and_cores(W, narrow)
        doms = self.goal["domains"]
        base = self.goal["base"]
        if narrow:
            y += 18
            parts.append(f'<path d="M{W / 2:.1f} {y - 18} V{y}" class="g-link"/>')
            for j, d in enumerate(doms):
                x, w = 8, W - 16
                inner = []
                cy, cx, row_h = y + 34, x + 8, 0
                for nid in d["nodes"]:
                    name, group, plan = self.node_chip(nid)
                    cwid = tw(name, 12) + 22
                    lines = [name]
                    if cwid > w - 16:
                        lines = wrap(name, w - 16 - 22, 12)
                        cwid = w - 16
                    if cx + cwid > x + w - 8 and cx > x + 8:
                        cx, cy = x + 8, cy + row_h + 6
                        row_h = 0
                    p, h = self.chip(cx, cy, cwid, lines, group, plan)
                    inner.append(p)
                    row_h = max(row_h, h)
                    cx += cwid + 6
                h = cy + row_h + 8 - y
                parts.append(f'<rect x="{x}" y="{y:.1f}" width="{w}" height="{h:.1f}" rx="8" class="g-dom"/>')
                parts.append(_text(x + 10, y + 22, label(d["title"], self.lang), "t-lab g-domt"))
                parts += inner
                y += h + 16
                if j < len(doms) - 1:
                    parts.append(f'<path d="M{W / 2:.1f} {y - 16:.1f} V{y:.1f}" class="g-link"/>')
            parts.append(f'<path d="M{W / 2:.1f} {y - 16:.1f} V{y:.1f}" class="g-link"/>')
        else:
            gap = 12
            cw = (W - gap * (len(doms) - 1)) / len(doms)
            bus = y + 20
            top = y + 40
            xs = [i * (cw + gap) for i in range(len(doms))]
            centers = [x + cw / 2 for x in xs]
            parts.append('<path d="' + " ".join(f"M{bx:.1f} {by:.1f} V{bus:.1f}" for bx, by in core_bottoms)
                         + f' M{centers[0]:.1f} {bus:.1f} H{centers[-1]:.1f} '
                         + " ".join(f"M{c:.1f} {bus:.1f} V{top:.1f}" for c in centers) + '" class="g-link"/>')
            cols, hmax = [], 0
            for x, d in zip(xs, doms):
                inner, cy = [], top + 36
                for nid in d["nodes"]:
                    name, group, plan = self.node_chip(nid)
                    p, h = self.chip(x + 8, cy, cw - 16, wrap(name, cw - 16 - 18, 12), group, plan)
                    inner.append(p)
                    cy += h + 6
                hmax = max(hmax, cy + 2 - top)
                cols.append((x, d, inner))
            for x, d, inner in cols:
                parts.append(f'<rect x="{x:.1f}" y="{top:.1f}" width="{cw:.1f}" height="{hmax:.1f}" rx="8" class="g-dom"/>')
                parts.append(_text(x + cw / 2, top + 22, label(d["title"], self.lang), "t-lab g-domt", "middle"))
                parts += inner
            y = top + hmax
            bus2 = y + 16
            parts.append('<path d="' + " ".join(f"M{c:.1f} {y:.1f} V{bus2:.1f}" for c in centers)
                         + f' M{centers[0]:.1f} {bus2:.1f} H{centers[-1]:.1f} M{W / 2:.1f} {bus2:.1f} V{bus2 + 16:.1f}"'
                         ' class="g-link"/>')
            y = bus2 + 16
        bname = name_of(self.nodes[base["node"]], self.lang)
        bw = W - 16 if narrow else 440
        p, bh = self.box((W - bw) / 2, y, bw, bname, wrap(self.say(base["text"]) or "", bw - 24, 12),
                         bool(self.nodes[base["node"]].get("plan")))
        parts.append(p)
        H = y + bh + 4
        cls = "fig-narrow" if narrow else "fig-wide"
        return (f'<div class="{cls}"><svg class="chart goal-svg" viewBox="0 0 {W} {H:.0f}" width="{W}" '
                f'height="{H:.0f}" role="img" aria-label="{E(lab["goal_aria"])}">{"".join(parts)}</svg></div>')

    def directions(self, role_of) -> str:
        """Направления цели списком: имя — роль (стадия) из реестра фактов; план — помечен."""
        lab = L[self.lang]
        secs = []
        for d in self.goal["domains"]:
            lis = []
            for nid in d["nodes"]:
                n = self.nodes[nid]
                role, stage = role_of(n)
                tail = f" — {E(role)}" if role else ""
                if stage:
                    tail += f' <span class="muted">({E(stage)})</span>'
                if n.get("plan"):
                    tail += f' <span class="muted">({E(lab["plan"])})</span>'
                lis.append(f"<li><strong>{E(name_of(n, self.lang))}</strong>{tail}</li>")
            secs.append(f'<div class="dir"><h3>{E(label(d["title"], self.lang))}</h3><ul>{"".join(lis)}</ul></div>')
        return f'<div class="dirs">{"".join(secs)}</div>'


# ------------------------------------------------------------------ страница
def page_main(data: dict, lang: str, say, role_of, rule_date: str | None, links: list[tuple[str, str, str]]) -> str:
    """links: [(текст перед ссылкой, адрес, подпись ссылки)] — в конце страницы."""
    lab = L[lang]
    nodes = data["nodes"]
    goal = _Goal(data, lang, say)
    groups = []
    for st in data["stations"]:
        for r in st.get("repos") or []:
            g = nodes[r].get("group", "core")
            if g not in groups:
                groups.append(g)
    order = [g for g in ("game", "core", "new", "hidden") if g in groups]
    app_name = name_of(nodes[data["goal"]["app"]["node"]], lang)
    rule = say(data["goal"]["rule"])
    quote = ""
    if rule:
        by = lab["rule_by"] + (f", {day_text(rule_date, lang)}" if rule_date else "")
        quote = (f'<blockquote class="goal-rule"><p>«{E(rule)}…»</p>'
                 f'<footer>— {E(by)}</footer></blockquote>') if lang == "ru" else \
                (f'<blockquote class="goal-rule"><p>“{E(rule)}…”</p><footer>— {E(by)}</footer></blockquote>')
    tail = "".join(f'<p>{E(before)} <a href="{E(href)}">{E(text)}</a>.</p>' for before, href, text in links)
    return (
        '<article class="page page-path">'
        f'<h1>{E(lab["title"])}</h1>'
        f'<p class="lead">{E(lab["lead"])}</p>'
        f'<h2 id="route">{E(lab["route"])}</h2>'
        f'<figure class="pfig fig-wide">{route_legend(lang, order)}{route_svg(data, lang, app_name)}'
        f'<figcaption>{E(lab["route_note"])}</figcaption></figure>'
        f'{route_list(data, lang, say)}'
        f'<h2 id="goal">{E(lab["goal"])}</h2>'
        f'{quote}'
        f'<figure class="pfig">{goal.svg(False)}{goal.svg(True)}'
        f'<figcaption>{E(lab["goal_note"])}</figcaption></figure>'
        f'<details class="fdata dirs-d"><summary>{E(lab["directions"])}</summary>{goal.directions(role_of)}</details>'
        f'<p class="muted path-src">{E(lab["sources"])}</p>'
        f'{tail}'
        '</article>'
    )


def home_block(data: dict, lang: str, href: str) -> str:
    """Главная: маршрут одной строкой — станции и «сейчас», ссылка на раздел."""
    lab = L[lang]
    items = []
    for kind, st in _slots(data["stations"]):
        if kind == "now":
            items.append(f'<li class="rs-now"><span class="rs-when">{E(day_text(data["as_of"], lang))}</span>'
                         f'{E(lab["now"])}</li>')
            continue
        cls = ' class="rs-plan"' if st.get("plan") else ""
        items.append(f'<li{cls}><span class="rs-when">{E(when(st, lang))}</span>{E(label(st["title"], lang))}</li>')
    return (f'<h2>{E(lab["home_h2"])}</h2><ol class="route-strip">{"".join(items)}</ol>'
            f'<p><a href="{E(href)}">{E(lab["home_link"])} →</a></p>')
