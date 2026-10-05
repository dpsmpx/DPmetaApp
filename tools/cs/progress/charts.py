"""Графики страницы прогресса — статический SVG при сборке (Д38).

CSP сайта запрещает скрипты и встроенные стили (`script-src 'none'`,
`style-src 'self'`), поэтому график — разметка без JS: цвета — классы из
`website/static/style.css` (токены бренда, VISUAL_SYSTEM §2, §7), подсказка
при наведении — `<title>` у метки, значения без наведения — подписи и
таблица рядом. У каждого графика две раскладки: широкая и узкая (телефон,
390 px); показывает нужную CSS по ширине экрана.

Правила (dataviz): одна ось Y, ноль внизу, тонкие метки, 2 px просвета между
сегментами, подписи — только у важных точек, сетка — волосяные линии.
"""
from __future__ import annotations

import html
import math

E = html.escape

WIDE = {"w": 960, "font": 12}
NARROW = {"w": 360, "font": 11}


def nice_ticks(vmax: float, count: int) -> tuple[float, list[float]]:
    """Круглые деления от нуля: (верх шкалы, деления)."""
    if vmax <= 0:
        return 1, [0, 1]
    raw = vmax / count
    p = 10 ** math.floor(math.log10(raw))
    m = raw / p
    step = (1 if m <= 1 else 2 if m <= 2 else 2.5 if m <= 2.5 else 5 if m <= 5 else 10) * p
    top = math.ceil(vmax / step - 1e-9) * step
    ticks, v = [], 0.0
    while v <= top + 1e-9:
        ticks.append(v)
        v += step
    return top, ticks


def fmt_tick(v: float, lang: str) -> str:
    if v >= 1000 and v % 1000 == 0:
        k = int(v // 1000)
        return f"{k} тыс." if lang == "ru" else f"{k}k"
    s = f"{v:,.0f}" if v == int(v) else f"{v:,.1f}"
    return s.replace(",", " ") if lang == "ru" else s


class Svg:
    def __init__(self, w: int, h: int, uid: str, title: str, desc: str):
        self.w, self.h, self.uid = w, h, uid
        self.parts = [f'<svg class="chart" viewBox="0 0 {w} {h}" role="img" aria-labelledby="{uid}-t {uid}-d" '
                      f'xmlns="http://www.w3.org/2000/svg">',
                      f'<title id="{uid}-t">{E(title)}</title><desc id="{uid}-d">{E(desc)}</desc>']

    def add(self, s: str) -> None:
        self.parts.append(s)

    def text(self, x: float, y: float, s: str, cls: str = "t-axis", anchor: str = "start") -> None:
        a = "" if anchor == "start" else f' text-anchor="{anchor}"'
        self.parts.append(f'<text x="{x:.1f}" y="{y:.1f}" class="{cls}"{a}>{E(s)}</text>')

    def line(self, x1, y1, x2, y2, cls: str) -> None:
        self.parts.append(f'<line x1="{x1:.1f}" y1="{y1:.1f}" x2="{x2:.1f}" y2="{y2:.1f}" class="{cls}"/>')

    def done(self) -> str:
        return "".join(self.parts) + "</svg>"


def col_path(x: float, y: float, w: float, h: float, r: float = 3) -> str:
    """Столбец со скруглённым верхом и прямым основанием."""
    r = min(r, h, w / 2)
    return (f"M{x:.1f},{y + h:.1f}V{y + r:.1f}Q{x:.1f},{y:.1f} {x + r:.1f},{y:.1f}H{x + w - r:.1f}"
            f"Q{x + w:.1f},{y:.1f} {x + w:.1f},{y + r:.1f}V{y + h:.1f}Z")


def bar_path(x: float, y: float, w: float, h: float, r: float = 3) -> str:
    """Горизонтальная полоса со скруглённым концом."""
    r = min(r, w, h / 2)
    return (f"M{x:.1f},{y:.1f}H{x + w - r:.1f}Q{x + w:.1f},{y:.1f} {x + w:.1f},{y + r:.1f}V{y + h - r:.1f}"
            f"Q{x + w:.1f},{y + h:.1f} {x + w - r:.1f},{y + h:.1f}H{x:.1f}Z")


def _x_axis(s: Svg, m: dict, iw: float, ih: float, labels: list[str], months: dict[int, str], every: int,
            band: float, centered: bool = True) -> None:
    for i, lab in enumerate(labels):
        cx = m["l"] + band * i + (band / 2 if centered else 0)
        if i % every == 0 and lab:
            s.text(cx, m["t"] + ih + 15, lab, "t-axis", "middle")
    for i, mon in months.items():
        x = m["l"] + band * i + (band / 2 if centered else 0)
        s.text(x, m["t"] + ih + 29, mon, "t-axis t-mon", "middle" if i else "start")


def columns(uid: str, lay: dict, title: str, desc: str, labels: list[str], months: dict[int, str],
            series: list[tuple[str, list[int], str]], lang: str, height: int, tips: list[str],
            value_at: tuple = (), markers: dict[int, tuple[str, str]] | None = None, weekend: list[bool] | None = None,
            ticks: int = 4) -> str:
    """Столбцы по дням, сложенные снизу вверх в порядке series: (класс, значения, имя)."""
    w = lay["w"]
    narrow = w < 500
    m = {"l": 40 if not narrow else 32, "r": 6, "t": 20, "b": 34 + (14 if markers else 0)}
    n = len(labels)
    iw, ih = w - m["l"] - m["r"], height - m["t"] - m["b"]
    band = iw / n
    bw = min(22.0, band * 0.68)
    totals = [sum(se[1][i] for se in series) for i in range(n)]
    top, tks = nice_ticks(max(totals + [1]), 3 if narrow else ticks)
    y = lambda v: m["t"] + ih - v / top * ih
    s = Svg(w, height, uid, title, desc)
    if weekend:
        for i, we in enumerate(weekend):
            if we:
                s.add(f'<rect x="{m["l"] + band * i:.1f}" y="{m["t"]}" width="{band:.1f}" height="{ih:.1f}" class="c-wash"/>')
    for v in tks:
        s.line(m["l"], y(v), w - m["r"], y(v), "c-grid" if v else "c-base")
        s.text(m["l"] - 6, y(v) + 4, fmt_tick(v, lang), "t-axis", "end")
    for i in range(n):
        x = m["l"] + band * i + (band - bw) / 2
        base = y(0)
        segs = [(cls, vals[i]) for cls, vals, _ in series if vals[i] > 0]
        g = [f"<g><title>{E(tips[i])}</title>",
             f'<rect x="{m["l"] + band * i:.1f}" y="{m["t"]}" width="{band:.1f}" height="{ih:.1f}" class="c-hit"/>']
        for j, (cls, v) in enumerate(segs):
            hgt = v / top * ih
            gap = 2 if j else 0
            yy = base - hgt
            hh = hgt - gap
            if hh > 0.3:
                d = col_path(x, yy, bw, hh) if j == len(segs) - 1 else f"M{x:.1f},{yy:.1f}h{bw:.1f}v{hh:.1f}h{-bw:.1f}Z"
                g.append(f'<path d="{d}" class="{cls}"/>')
            base = yy
        g.append("</g>")
        s.add("".join(g))
        if i in value_at and totals[i]:
            s.text(x + bw / 2, y(totals[i]) - 6, fmt_tick(totals[i], lang), "t-val", "middle")
    every = max(1, math.ceil((22 if narrow else 18) / band))
    _x_axis(s, m, iw, ih, labels, months, every, band)
    if markers:
        for i, (cls, tip) in markers.items():
            cx = m["l"] + band * i + band / 2
            cy = m["t"] + ih + 40
            s.add(f'<g><title>{E(tip)}</title><rect x="{cx - 3.5:.1f}" y="{cy - 3.5:.1f}" width="7" height="7" '
                  f'transform="rotate(45 {cx:.1f} {cy:.1f})" class="{cls}"/></g>')
    return s.done()


def stacked_area(uid: str, lay: dict, title: str, desc: str, labels: list[str], months: dict[int, str],
                 layers: list[tuple[str, list[int], str]], lang: str, height: int, tips: list[str],
                 end_labels: list[str] | None = None) -> str:
    """Площади друг на друге (снизу вверх): точки — измерения на конец каждого дня, без сглаживания."""
    w = lay["w"]
    narrow = w < 500
    m = {"l": 52 if not narrow else 40, "r": 8 if narrow or not end_labels else 150, "t": 14, "b": 34}
    n = len(labels)
    iw, ih = w - m["l"] - m["r"], height - m["t"] - m["b"]
    cum, acc = [], [0] * n
    for cls, vals, name in layers:
        lo = acc[:]
        acc = [a + v for a, v in zip(acc, vals)]
        cum.append((cls, lo, acc[:], name))
    top, tks = nice_ticks(max(acc + [1]), 3 if narrow else 4)
    x = lambda i: m["l"] + iw * i / max(n - 1, 1)
    y = lambda v: m["t"] + ih - v / top * ih
    s = Svg(w, height, uid, title, desc)
    for v in tks:
        s.line(m["l"], y(v), m["l"] + iw, y(v), "c-grid" if v else "c-base")
        s.text(m["l"] - 6, y(v) + 4, fmt_tick(v, lang), "t-axis", "end")
    for cls, lo, hi, _ in cum:
        d = f"M{x(0):.1f},{y(hi[0]):.1f}" + "".join(f"L{x(i):.1f},{y(hi[i]):.1f}" for i in range(1, n))
        back = "".join(f"L{x(i):.1f},{y(lo[i]):.1f}" for i in range(n - 1, -1, -1))
        s.add(f'<path d="{d}{back}Z" class="{cls} c-fill"/>')
        s.add(f'<path d="{d}" class="{cls}-line c-line"/>')
    step = iw / max(n - 1, 1)
    every = max(1, math.ceil((22 if narrow else 18) / step))
    for i, lab in enumerate(labels):
        if i % every == 0:
            s.text(x(i), m["t"] + ih + 15, lab, "t-axis", "middle")
    for i, mon in months.items():
        s.text(x(i), m["t"] + ih + 29, mon, "t-axis t-mon", "middle" if i else "start")
    for i in range(n):  # подсказки по дням
        x0 = x(i) - step / 2
        s.add(f'<g><title>{E(tips[i])}</title><rect x="{max(m["l"], x0):.1f}" y="{m["t"]}" '
              f'width="{min(step, m["l"] + iw - max(m["l"], x0)):.1f}" height="{ih:.1f}" class="c-hit"/></g>')
    if end_labels and not narrow:
        ys = [(y(lo[-1]) + y(hi[-1])) / 2 for _, lo, hi, _ in cum]
        for k in range(len(ys) - 2, -1, -1):
            if ys[k] - ys[k + 1] < 30:
                ys[k] = ys[k + 1] + 30
        for (cls, _, _, name), yy, lab in zip(cum, ys, end_labels):
            s.add(f'<rect x="{m["l"] + iw + 12:.1f}" y="{yy - 12:.1f}" width="9" height="9" rx="2" class="{cls}"/>')
            s.text(m["l"] + iw + 26, yy - 4, lab, "t-val")
            s.text(m["l"] + iw + 26, yy + 11, name, "t-axis")
    return s.done()


def points(uid: str, lay: dict, title: str, desc: str, days: list[str], labels: list[str], months: dict[int, str],
           pts: list[tuple[int, int, str]], cls: str, lang: str, height: int) -> str:
    """Отдельные измерения (индекс дня, значение, подсказка), соединённые отрезками: между точками данных нет."""
    w = lay["w"]
    narrow = w < 500
    m = {"l": 48 if not narrow else 40, "r": 14, "t": 22, "b": 34}
    n = len(days)
    iw, ih = w - m["l"] - m["r"], height - m["t"] - m["b"]
    top, tks = nice_ticks(max([v for _, v, _ in pts] + [1]), 3)
    x = lambda i: m["l"] + iw * i / max(n - 1, 1)
    y = lambda v: m["t"] + ih - v / top * ih
    s = Svg(w, height, uid, title, desc)
    for v in tks:
        s.line(m["l"], y(v), m["l"] + iw, y(v), "c-grid" if v else "c-base")
        s.text(m["l"] - 6, y(v) + 4, fmt_tick(v, lang), "t-axis", "end")
    step = iw / max(n - 1, 1)
    every = max(1, math.ceil((22 if narrow else 18) / step))
    for i, lab in enumerate(labels):
        if i % every == 0:
            s.text(x(i), m["t"] + ih + 15, lab, "t-axis", "middle")
    for i, mon in months.items():
        s.text(x(i), m["t"] + ih + 29, mon, "t-axis t-mon", "middle" if i else "start")
    if len(pts) > 1:
        d = "M" + "L".join(f"{x(i):.1f},{y(v):.1f}" for i, v, _ in pts)
        s.add(f'<path d="{d}" class="{cls}-line c-line c-thin"/>')
    for k, (i, v, tip) in enumerate(pts):
        s.add(f'<g><title>{E(tip)}</title><circle cx="{x(i):.1f}" cy="{y(v):.1f}" r="12" class="c-hit"/>'
              f'<circle cx="{x(i):.1f}" cy="{y(v):.1f}" r="4.5" class="{cls} c-dot"/></g>')
        if k in (0, len(pts) - 1):
            anchor = "end" if k == len(pts) - 1 and i > n * 0.7 else "start"
            dx = -8 if anchor == "end" else 8
            s.text(x(i) + dx, y(v) - 8, fmt_tick(v, lang), "t-val", anchor)
    return s.done()


def hbars(uid: str, lay: dict, title: str, desc: str, rows: list[dict], lang: str, label_w: int | None = None,
          row_h: int = 30) -> str:
    """Горизонтальные полосы: строка — {label, segs: [(класс, значение, имя)], value, tip}."""
    w = lay["w"]
    narrow = w < 500
    lw = label_w or (110 if narrow else 170)
    rw = 64 if narrow else 90
    h = len(rows) * row_h + 4
    s = Svg(w, h, uid, title, desc)
    vmax = max((sum(v for _, v, _ in r["segs"]) for r in rows), default=1) or 1
    iw = w - lw - rw
    bh = 12
    for k, r in enumerate(rows):
        yy = k * row_h + (row_h - bh) / 2
        g = [f"<g><title>{E(r['tip'])}</title>", f'<rect x="0" y="{k * row_h}" width="{w}" height="{row_h}" class="c-hit"/>']
        g.append(f'<text x="0" y="{yy + bh - 1:.1f}" class="t-lab">{E(r["label"])}</text>')
        xx = lw
        segs = [sg for sg in r["segs"] if sg[1] > 0]
        for j, (cls, v, _) in enumerate(segs):
            bw = v / vmax * iw
            gap = 2 if j else 0
            if bw - gap > 0.3:
                d = bar_path(xx + gap, yy, bw - gap, bh) if j == len(segs) - 1 else \
                    f"M{xx + gap:.1f},{yy:.1f}h{bw - gap:.1f}v{bh}h{-(bw - gap):.1f}Z"
                g.append(f'<path d="{d}" class="{cls}"/>')
            xx += bw
        g.append(f'<text x="{xx + 8:.1f}" y="{yy + bh - 1:.1f}" class="t-val">{E(r["value"])}</text></g>')
        s.add("".join(g))
    return s.done()


def heatmap(uid: str, lay: dict, title: str, desc: str, labels: list[str], months: dict[int, str],
            groups: list[tuple[str, list[dict]]], bins: list[int], lang: str, right_head: str) -> str:
    """Сетка репозиторий × день: цвет — число коммитов по корзинам bins (одна шкала для всех строк)."""
    w = lay["w"]
    narrow = w < 500
    lw, rw = (100, 36) if narrow else (178, 88)
    top, rh, gh = 26, 20, 26
    n = len(labels)
    band = (w - lw - rw) / n
    cw = max(band - 2, 2)
    rows_n = sum(len(r) for _, r in groups)
    h = top + gh * len(groups) + rh * rows_n + 24
    s = Svg(w, h, uid, title, desc)
    every = max(1, math.ceil((18 if narrow else 16) / band))
    for i, lab in enumerate(labels):
        if i % every == 0:
            s.text(lw + band * i + band / 2, top - 10, lab, "t-axis", "middle")
    for i, mon in months.items():
        s.text(lw + band * i + band / 2, h - 6, mon, "t-axis t-mon", "middle" if i else "start")
    s.text(w, top - 10, right_head, "t-axis", "end")
    yy = top
    for gname, rows in groups:
        s.text(0, yy + 17, gname, "t-axis t-grp")
        yy += gh
        for r in rows:
            name = r["label"]
            limit = 14 if narrow else 24
            if len(name) > limit:
                name = name[:limit - 1] + "…"
            s.text(0, yy + 13, name, "t-lab")
            for i, v in enumerate(r["values"]):
                lvl = 0 if not v else next((k + 1 for k, b in enumerate(bins) if v <= b), len(bins) + 1)
                s.add(f'<g><title>{E(r["tips"][i])}</title><rect x="{lw + band * i + (band - cw) / 2:.1f}" y="{yy + 2}" '
                      f'width="{cw:.1f}" height="{rh - 5}" rx="2" class="c-h{lvl}"/></g>')
            s.text(w - (0 if narrow else 40), yy + 13, r["total"], "t-lab t-num", "end")
            if not narrow:
                s.text(w, yy + 13, r["extra"], "t-axis t-num", "end")
            yy += rh
    return s.done()
