"""Рендер композиций: спецификация (composition/v1) → HTML → Chromium → PNG/JPEG.

Д11: изображения собираются детерминированно из спецификации; портрет —
только из REFERENCE_IMAGE (Д10, Д18), с кадрированием и тоном, без
изменения геометрии. Каждый формат рендерится заново под свой размер и
безопасную зону, а не масштабируется из мастера.

Макеты: professional (A), technical (B), cinematic (C), minimal (D).
"""
from __future__ import annotations

import html
import json
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path

from .common import ROOT, dump_yaml, load_json, load_yaml, rel, sha256_file, today

TOKENS_PATH = ROOT / "assets" / "brand" / "tokens.json"
FONTS_DIR = ROOT / "assets" / "brand" / "fonts"
RENDER_JS = ROOT / "tools" / "render" / "render.js"
BUILD = ROOT / "build" / "render"

SRC_W, SRC_H = None, None  # размеры фото берутся из файла


@dataclass
class Fmt:
    name: str
    width: int
    height: int
    file: str
    safe_area: dict | None = None
    max_bytes: int | None = None


def formats() -> dict[str, Fmt]:
    spec = load_yaml("data/platforms/export_specs.yaml")["formats"]
    return {
        k: Fmt(k, v["width"], v["height"], v["file"], v.get("safe_area"), v.get("max_bytes"))
        for k, v in spec.items()
    }


def tokens() -> dict:
    return load_json(TOKENS_PATH)


def _c(tok: dict, name: str) -> str:
    return tok["color"][name]["hex"]


def _rgba(hex_: str, a: float) -> str:
    h = hex_.lstrip("#")
    r, g, b = int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)
    return f"rgba({r},{g},{b},{a})"


def _e(s: str) -> str:
    return html.escape(s, quote=True)


def _font_faces() -> str:
    faces = []
    for fname, family, weight in [
        ("IBMPlexSans-Regular.woff2", "IBM Plex Sans", 400),
        ("IBMPlexSans-Medium.woff2", "IBM Plex Sans", 500),
        ("IBMPlexSans-SemiBold.woff2", "IBM Plex Sans", 600),
        ("IBMPlexSans-Bold.woff2", "IBM Plex Sans", 700),
        ("IBMPlexMono-Regular.woff2", "IBM Plex Mono", 400),
        ("IBMPlexMono-Medium.woff2", "IBM Plex Mono", 500),
    ]:
        url = (FONTS_DIR / fname).resolve().as_uri()
        faces.append(
            f"@font-face{{font-family:'{family}';src:url('{url}') format('woff2');"
            f"font-weight:{weight};font-style:normal;}}"
        )
    return "\n".join(faces)


# ------------------------------------------------------------------ портрет

def _asset_path(asset_id: str) -> Path:
    reg = load_yaml("data/provenance/assets.yaml")["assets"]
    for a in reg:
        if a["id"] == asset_id:
            if a["kind"] != "REFERENCE_IMAGE":
                raise ValueError(f"{asset_id}: портрет допускается только из REFERENCE_IMAGE (Д10)")
            return ROOT / a["path"]
    raise KeyError(f"{asset_id}: нет в data/provenance/assets.yaml")


def _jpeg_size(path: Path) -> tuple[int, int]:
    from .common import image_size
    w, h, _ = image_size(path)
    return w, h


def portrait_html(spec: dict, tok: dict, pw: float, ph: float, target=(0.5, 0.38),
                  vignette: float = 1.0, fade_right: bool = True, radius: float = 0) -> str:
    """Портрет в рамке pw×ph: точка фокуса фото → точка target рамки; только масштаб и сдвиг."""
    p = spec["portrait"]
    src = _asset_path(p["input"])
    sw, sh = _jpeg_size(src)
    zoom = p.get("zoom", 1.0)
    scale = max(pw / sw, ph / sh) * zoom
    iw, ih = sw * scale, sh * scale
    left = pw * target[0] - p["focus"]["x"] * iw
    top = ph * target[1] - p["focus"]["y"] * ih
    left = min(0, max(pw - iw, left))
    top = min(0, max(ph - ih, top))
    ink = _c(tok, "ink")
    filt = {
        "natural": "saturate(0.80) contrast(1.06) brightness(0.97)",
        "mono": "grayscale(1) contrast(1.12) brightness(0.95)",
        "duotone": "grayscale(1) contrast(1.22) brightness(0.92)",
    }[p["treatment"]]
    fx = (left + p["focus"]["x"] * iw) / pw * 100
    fy = (top + p["focus"]["y"] * ih) / ph * 100
    v = vignette
    layers = [
        # Виньетка вокруг лица: интерьер уходит в ink (Д18 — фон не должен читаться).
        f"radial-gradient(ellipse {30 * v:.1f}% {27 * v:.1f}% at {fx:.1f}% {fy + 4:.1f}%, "
        f"{_rgba(ink, 0)} 0%, {_rgba(ink, 0.25)} 50%, {_rgba(ink, 0.90)} 100%)",
        f"radial-gradient(ellipse {58 * v:.1f}% {70 * v:.1f}% at {fx:.1f}% {fy + 32:.1f}%, "
        f"{_rgba(ink, 0)} 30%, {_rgba(ink, 0.96)} 100%)",
        f"linear-gradient(90deg, {_rgba(ink, 0.88)} 0%, {_rgba(ink, 0.35)} 22%, {_rgba(ink, 0)} 40%)",
        f"linear-gradient(180deg, {_rgba(ink, 0.55)} 0%, {_rgba(ink, 0)} 18%, {_rgba(ink, 0)} 60%, {_rgba(ink, 0.96)} 100%)",
    ]
    if fade_right:
        layers.append(f"linear-gradient(90deg, {_rgba(ink, 0)} 58%, {_rgba(ink, 1)} 100%)")
    duo = ""
    if p["treatment"] == "duotone":
        duo = (f'<div style="position:absolute;inset:0;background:{_c(tok, "human")};'
               f'mix-blend-mode:soft-light;opacity:0.55"></div>')
    src_uri = src.resolve().as_uri()
    return (
        f'<div class="portrait" data-role="portrait" data-group="person" data-text="0" '
        f'style="position:absolute;left:0;top:0;width:{pw:.1f}px;height:{ph:.1f}px;overflow:hidden;'
        f'border-radius:{radius:.1f}px">'
        f'<img src="{src_uri}" style="position:absolute;left:{left:.1f}px;top:{top:.1f}px;'
        f'width:{iw:.1f}px;height:{ih:.1f}px;filter:{filt}">'
        f'{duo}'
        f'<div style="position:absolute;inset:0;background:{", ".join(layers)}"></div>'
        f'<div data-role="face" data-group="person" data-text="0" style="position:absolute;'
        f'left:{fx - 9:.1f}%;top:{fy - 13:.1f}%;width:18%;height:24%"></div>'
        f'</div>'
    )


# ------------------------------------------------------------------ общие элементы

def _page(tok: dict, w: int, h: int, body: str, grid: bool = True) -> str:
    ink, line = _c(tok, "ink"), _c(tok, "line")
    cell = round(h / 18)
    grid_css = (
        f"background-image: linear-gradient({_rgba(line, 0.45)} 1px, transparent 1px),"
        f"linear-gradient(90deg, {_rgba(line, 0.45)} 1px, transparent 1px);"
        f"background-size:{cell}px {cell}px;" if grid else ""
    )
    return f"""<!doctype html><html><head><meta charset="utf-8"><style>
{_font_faces()}
html,body{{margin:0;padding:0;width:{w}px;height:{h}px;overflow:hidden;background:{ink};}}
.canvas{{position:relative;width:{w}px;height:{h}px;overflow:hidden;background:{ink};
 font-family:'IBM Plex Sans',sans-serif;color:{_c(tok, 'paper')};
 -webkit-font-smoothing:antialiased;text-rendering:geometricPrecision;}}
.grid{{position:absolute;inset:0;{grid_css}
 -webkit-mask-image:radial-gradient(ellipse 70% 80% at 72% 45%, #000 0%, transparent 100%);}}
.mono{{font-family:'IBM Plex Mono',monospace;}}
</style></head><body><div class="canvas"><div class="grid"></div>{body}</div></body></html>"""


def name_html(tok: dict, name: str, x, y, size, color="paper") -> str:
    dot = size * 0.16
    return (
        f'<div data-role="name" data-group="name" data-fg="{color}" data-bg="ink" '
        f'style="position:absolute;left:{x:.1f}px;top:{y:.1f}px;font-size:{size:.1f}px;'
        f'font-weight:600;letter-spacing:-0.02em;line-height:1;white-space:nowrap;color:{_c(tok, color)}">'
        f'{_e(name)}<span style="display:inline-block;width:{dot:.1f}px;height:{dot:.1f}px;'
        f'margin-left:{dot * 0.5:.1f}px;background:{_c(tok, "human")};border-radius:1px"></span></div>'
    )


def text_html(tok: dict, role: str, group: str, text: str, x, y, w, size, color="paper",
              weight=400, mono=False, line=1.3, nowrap=False) -> str:
    fam = "mono" if mono else ""
    ws = "white-space:nowrap;" if nowrap else ""
    return (
        f'<div class="{fam}" data-role="{role}" data-group="{group}" data-fg="{color}" data-bg="ink" '
        f'style="position:absolute;left:{x:.1f}px;top:{y:.1f}px;width:{w:.1f}px;font-size:{size:.1f}px;'
        f'font-weight:{weight};line-height:{line};color:{_c(tok, color)};text-wrap:balance;{ws}">{_e(text)}</div>'
    )


KIND_TOKEN = {
    "intent": "human", "architecture": "human", "implementation": "ai",
    "verification": "verified", "git": "mist", "product": "paper",
}


def _cube_svg(size: float, tok: dict) -> str:
    """Воксель — знак «продукта»: свой векторный рисунок, не ассет игры."""
    s = size
    top = _rgba(_c(tok, "paper"), 0.95)
    left = _rgba(_c(tok, "paper"), 0.55)
    right = _rgba(_c(tok, "paper"), 0.30)
    return (
        f'<svg width="{s:.1f}" height="{s:.1f}" viewBox="0 0 20 20" style="display:block">'
        f'<polygon points="10,1 19,5.5 10,10 1,5.5" fill="{top}"/>'
        f'<polygon points="1,5.5 10,10 10,19 1,14.5" fill="{left}"/>'
        f'<polygon points="19,5.5 10,10 10,19 19,14.5" fill="{right}"/></svg>'
    )


def metro_html(tok: dict, steps: list[dict], gates: list[dict], x, y, w, size, alt=True) -> str:
    """Цепочка работ линией: узлы цветом смысла, подписи над и под линией поочерёдно."""
    n = len(steps)
    r = size * 0.42
    xs = [x + r + i * (w - 2 * r) / (n - 1) for i in range(n)]
    parts = []
    # отрезки линии — цветом начала отрезка
    for i in range(n - 1):
        col = _c(tok, KIND_TOKEN[steps[i]["kind"]])
        parts.append(
            f'<div style="position:absolute;left:{xs[i]:.1f}px;top:{y - size * 0.06:.1f}px;'
            f'width:{xs[i + 1] - xs[i]:.1f}px;height:{size * 0.12:.1f}px;'
            f'background:linear-gradient(90deg,{_rgba(col, 0.9)},{_rgba(_c(tok, KIND_TOKEN[steps[i + 1]["kind"]]), 0.9)})"></div>'
        )
    for i, st in enumerate(steps):
        col = _c(tok, KIND_TOKEN[st["kind"]])
        if st["kind"] == "product":
            parts.append(f'<div style="position:absolute;left:{xs[i] - r * 1.25:.1f}px;top:{y - r * 1.25:.1f}px">'
                         f'{_cube_svg(r * 2.5, tok)}</div>')
        else:
            parts.append(
                f'<div style="position:absolute;left:{xs[i] - r:.1f}px;top:{y - r:.1f}px;width:{2 * r:.1f}px;'
                f'height:{2 * r:.1f}px;border-radius:50%;background:{_c(tok, "ink")};'
                f'border:{size * 0.14:.1f}px solid {col};box-sizing:border-box"></div>'
            )
        above = alt and i % 2 == 0
        ty = y - r - size * 1.75 if above else y + r + size * 0.45
        tw = (w / (n - 1)) * 1.9
        tx = min(max(xs[i] - tw / 2, x - size * 0.2), x + w - tw + size * 0.2)
        align = "center"
        if i == 0:
            tx, align = x - size * 0.1, "left"
        elif i == n - 1:
            tx, align = x + w - tw + size * 0.1, "right"
        token = KIND_TOKEN[st["kind"]]
        parts.append(
            f'<div class="mono" data-role="pipeline-label" data-group="pipeline" data-fg="{token}" '
            f'data-bg="ink" style="position:absolute;left:{tx:.1f}px;top:{ty:.1f}px;width:{tw:.1f}px;'
            f'text-align:{align};font-size:{size:.1f}px;font-weight:500;line-height:1.25;white-space:nowrap;'
            f'color:{_c(tok, token)}">{_e(st["label"])}</div>'
        )
    # ворота решений человека: ромб на отрезке после шага after
    for g in gates:
        i = g["after"]
        if i >= n - 1:
            continue
        gx = (xs[i] + xs[i + 1]) / 2
        d = size * 0.62
        parts.append(
            f'<div data-role="gate" data-group="pipeline" data-text="0" title="{_e(g["label"])}" '
            f'style="position:absolute;left:{gx - d / 2:.1f}px;top:{y - d / 2:.1f}px;width:{d:.1f}px;'
            f'height:{d:.1f}px;background:{_c(tok, "human")};transform:rotate(45deg);'
            f'box-shadow:0 0 0 {size * 0.18:.1f}px {_c(tok, "ink")}"></div>'
        )
    return "".join(parts)


def legend_html(tok: dict, items: list[dict], x, y, size, gate_label: str | None) -> str:
    parts = []
    cx = x
    for it in items:
        col = _c(tok, it["token"])
        parts.append(
            f'<div class="mono" data-role="legend" data-group="pipeline" data-fg="mist" data-bg="ink" '
            f'style="position:absolute;left:{cx:.1f}px;top:{y:.1f}px;font-size:{size:.1f}px;white-space:nowrap;'
            f'color:{_c(tok, "mist")}"><span style="display:inline-block;width:{size * 0.6:.1f}px;'
            f'height:{size * 0.6:.1f}px;border-radius:50%;background:{col};margin-right:{size * 0.45:.1f}px;'
            f'vertical-align:{size * 0.02:.1f}px"></span>{_e(it["label"])}</div>'
        )
        cx += size * (1.3 + 0.62 * len(it["label"])) + size * 1.4
    if gate_label:
        d = size * 0.5
        parts.append(
            f'<div class="mono" data-role="legend" data-group="pipeline" data-fg="mist" data-bg="ink" '
            f'style="position:absolute;left:{cx:.1f}px;top:{y:.1f}px;font-size:{size:.1f}px;white-space:nowrap;'
            f'color:{_c(tok, "mist")}"><span style="display:inline-block;width:{d:.1f}px;height:{d:.1f}px;'
            f'background:{_c(tok, "human")};transform:rotate(45deg);margin-right:{size * 0.55:.1f}px;'
            f'vertical-align:{size * 0.05:.1f}px"></span>{_e(gate_label)}</div>'
        )
    return "".join(parts)


def chip_html(tok: dict, text: str, x, y, size, cube: bool = False) -> str:
    pad = size * 0.65
    icon = (f'<span style="display:inline-block;vertical-align:{-size * 0.18:.1f}px;margin-right:{size * 0.55:.1f}px">'
            f'{_cube_svg(size * 1.05, tok)}</span>') if cube else ""
    return (
        f'<div class="mono" data-role="evidence" data-group="evidence" data-fg="paper" data-bg="graphite" '
        f'style="position:absolute;left:{x:.1f}px;top:{y:.1f}px;font-size:{size:.1f}px;line-height:1.2;'
        f'white-space:nowrap;padding:{pad * 0.6:.1f}px {pad:.1f}px;background:{_c(tok, "graphite")};'
        f'border:1px solid {_c(tok, "line")};border-radius:{size * 0.3:.1f}px;color:{_c(tok, "paper")}">'
        f'{icon}{_e(text)}</div>'
    )


# ------------------------------------------------------------------ макеты

def layout_professional(spec, t, tok, fmt: Fmt) -> str:
    W, H = fmt.width, fmt.height
    if fmt.safe_area:
        return layout_banner(spec, t, tok, fmt)
    s = H / 720
    ts = tok["type_scale"]
    disp, title, label = ts["display"] * H, ts["title"] * H, ts["label"] * H
    aspect = W / H
    pw = W * (0.44 if aspect < 1.9 else 0.40)
    m = 56 * s
    x0 = pw + W * 0.03
    cw = W - x0 - m
    body = [portrait_html(spec, tok, pw, H, target=(0.56, 0.36))]
    y = H * 0.17
    body.append(name_html(tok, t["name"], x0, y, disp))
    y += disp * 1.25
    body.append(text_html(tok, "role", "identity", t["role"], x0, y, cw, title, weight=500, line=1.2))
    y += title * 1.55
    if t.get("sub"):
        body.append(text_html(tok, "sub", "identity", t["sub"], x0, y, cw * 0.95, label, color="mist", line=1.45))
    py = H * 0.585
    if t.get("pipeline"):
        body.append(metro_html(tok, t["pipeline"], t.get("gates", []), x0 + label * 0.2, py, cw - label * 0.4, label))
        if t.get("legend"):
            gl = t["gates"][0]["label"] if t.get("gates") else None
            body.append(legend_html(tok, t["legend"], x0, py + label * 3.0, label * 0.92, gl))
    ev = t.get("evidence", [])
    ey = H - m - (label * 2.2) * len(ev) - label * 0.4 * (len(ev) - 1)
    for i, e in enumerate(ev):
        body.append(chip_html(tok, e, x0, ey + i * label * 2.6, label * 0.92, cube=(i == 0)))
    return "".join(body)


def layout_banner(spec, t, tok, fmt: Fmt) -> str:
    """Шапка YouTube: всё смысловое — в безопасной зоне; вне её — сетка и продолжение линии."""
    sa = fmt.safe_area
    sx, sy, sw, sh = sa["x"], sa["y"], sa["w"], sa["h"]
    unit = sh / 423
    disp, title, label = 92 * unit, 34 * unit, 22 * unit
    ps = sh  # портрет — квадрат высотой зоны
    por = portrait_html(spec, tok, ps, ps, target=(0.5, 0.40), vignette=1.05, fade_right=False, radius=14 * unit)
    body = [f'<div style="position:absolute;left:{sx:.1f}px;top:{sy:.1f}px;width:{ps:.1f}px;height:{ps:.1f}px">{por}</div>']
    x0 = sx + ps + 56 * unit
    cw = sx + sw - x0
    body.append(name_html(tok, t["name"], x0, sy + 40 * unit, disp))
    body.append(text_html(tok, "role", "identity", t["role"], x0, sy + 40 * unit + disp * 1.22, cw, title, weight=500, line=1.2))
    if t.get("pipeline"):
        py = sy + sh - 92 * unit
        body.append(metro_html(tok, t["pipeline"], t.get("gates", []), x0 + label * 0.2, py, cw - label * 0.4, label))
    # продолжение линии за пределы зоны — декор (видно на ТВ)
    line = _rgba(_c(tok, "line"), 0.9)
    body.append(f'<div style="position:absolute;left:0;top:{sy + sh - 92 * unit - 2 * unit:.1f}px;width:{sx:.1f}px;'
                f'height:{4 * unit:.1f}px;background:linear-gradient(90deg,transparent,{line})"></div>')
    body.append(f'<div style="position:absolute;left:{sx + sw:.1f}px;top:{sy + sh - 92 * unit - 2 * unit:.1f}px;'
                f'width:{fmt.width - sx - sw:.1f}px;height:{4 * unit:.1f}px;'
                f'background:linear-gradient(90deg,{line},transparent)"></div>')
    return "".join(body)


def layout_technical(spec, t, tok, fmt: Fmt) -> str:
    W, H = fmt.width, fmt.height
    s = H / 720
    ts = tok["type_scale"]
    disp, title, label = ts["display"] * H * 0.8, ts["title"] * H, ts["label"] * H * 0.8
    pw = W * 0.32
    m = 48 * s
    x0 = pw + W * 0.03
    cw = W - x0 - m
    body = [portrait_html(spec, tok, pw, H, target=(0.5, 0.33))]
    body.append(name_html(tok, t["name"], x0, H * 0.09, disp))
    body.append(text_html(tok, "role", "identity", t["role"], x0, H * 0.09 + disp * 1.2, cw, title, weight=500))
    if t.get("sub"):
        body.append(text_html(tok, "sub", "sub", t["sub"], x0, H * 0.09 + disp * 1.2 + title * 1.5, cw, label,
                              color="mist", mono=True, line=1.4))
    # схема — коробками в две строки по три
    steps = t.get("pipeline", [])
    bw = (cw - 2 * 34 * s) / 3
    bh = label * 2.6
    for i, st in enumerate(steps):
        row, col = divmod(i, 3)
        bx = x0 + col * (bw + 34 * s)
        by = H * 0.43 + row * (bh + 46 * s)
        token = KIND_TOKEN[st["kind"]]
        body.append(
            f'<div class="mono" data-role="pipeline-label" data-group="pipeline" data-fg="{token}" data-bg="graphite" '
            f'style="position:absolute;left:{bx:.1f}px;top:{by:.1f}px;width:{bw:.1f}px;height:{bh:.1f}px;'
            f'box-sizing:border-box;border:2px solid {_c(tok, token)};border-radius:{6 * s:.1f}px;'
            f'background:{_c(tok, "graphite")};color:{_c(tok, token)};font-size:{label:.1f}px;'
            f'display:flex;align-items:center;justify-content:center;white-space:nowrap">{_e(st["label"])}</div>'
        )
    for g in t.get("gates", []):
        i = g["after"]
        row, col = divmod(i, 3)
        gx = x0 + col * (bw + 34 * s) + bw + 4 * s
        gy = H * 0.43 + row * (bh + 46 * s) - label * 1.3
        body.append(text_html(tok, "gate-label", "gates", "◆ " + g["label"], gx - bw * 0.2, gy, bw * 0.6, label * 0.85,
                              color="human", mono=True, nowrap=True))
    ev = t.get("evidence", [])
    tile_w = (cw - 2 * 24 * s) / 3
    for i, e in enumerate(ev[:3]):
        num, _, cap = e.partition("|")
        tx = x0 + i * (tile_w + 24 * s)
        ty = H - m - 92 * s
        body.append(text_html(tok, "metric", "metrics", num.strip(), tx, ty, tile_w, title * 1.25, weight=600, nowrap=True))
        body.append(text_html(tok, "metric-cap", "metrics", cap.strip(), tx, ty + title * 1.6, tile_w, label * 0.9,
                              color="mist", mono=True))
    return "".join(body)


def layout_cinematic(spec, t, tok, fmt: Fmt) -> str:
    W, H = fmt.width, fmt.height
    s = H / 720
    ts = tok["type_scale"]
    disp, title, label = ts["display"] * H * 1.15, ts["title"] * H, ts["label"] * H
    pw = W * 0.78
    por = portrait_html(spec, tok, pw, H, target=(0.62, 0.40), vignette=1.25, fade_right=False)
    body = [f'<div style="position:absolute;left:{W - pw:.1f}px;top:0;width:{pw:.1f}px;height:{H}px">{por}</div>',
            f'<div style="position:absolute;inset:0;background:linear-gradient(90deg,{_c(tok, "ink")} 18%,'
            f'{_rgba(_c(tok, "ink"), 0)} 55%)"></div>']
    x0 = 64 * s
    body.append(name_html(tok, t["name"], x0, H * 0.56, disp))
    body.append(text_html(tok, "role", "identity", t["role"], x0, H * 0.56 + disp * 1.2, W * 0.42, title, weight=500))
    if t.get("pipeline"):
        body.append(metro_html(tok, t["pipeline"], t.get("gates", []), x0, H * 0.86, W * 0.40, label * 0.9, alt=False))
    return "".join(body)


def layout_minimal(spec, t, tok, fmt: Fmt) -> str:
    W, H = fmt.width, fmt.height
    s = H / 720
    ts = tok["type_scale"]
    disp, title, label = ts["display"] * H * 1.6, ts["title"] * H, ts["label"] * H
    x0 = W * 0.12
    cw = W * 0.76
    body = [name_html(tok, t["name"], x0, H * 0.22, disp)]
    body.append(text_html(tok, "role", "identity", t["role"], x0, H * 0.22 + disp * 1.2, cw, title, weight=500))
    if t.get("pipeline"):
        body.append(metro_html(tok, t["pipeline"], t.get("gates", []), x0 + label * 0.2, H * 0.70, cw, label))
    return "".join(body)


LAYOUTS = {
    "professional": layout_professional,
    "technical": layout_technical,
    "cinematic": layout_cinematic,
    "minimal": layout_minimal,
}


def build_html(spec: dict, lang: str, fmt: Fmt) -> str:
    tok = tokens()
    t = spec["text"][lang]
    body = LAYOUTS[spec["layout"]](spec, t, tok, fmt)
    return _page(tok, fmt.width, fmt.height, body)


# ------------------------------------------------------------------ запуск рендера

def node_available() -> bool:
    return shutil.which("node") is not None


def render_one(spec_path: Path, lang: str, fmt: Fmt, out: Path, quality: int = 90) -> dict:
    """Рендер одной композиции; возвращает раскладку. HTML — в build/ (не в Git)."""
    spec = load_yaml(spec_path)
    page = build_html(spec, lang, fmt)
    BUILD.mkdir(parents=True, exist_ok=True)
    html_path = BUILD / f"{spec_path.stem}-{lang}-{fmt.name}.html"
    html_path.write_text(page, encoding="utf-8")
    out.parent.mkdir(parents=True, exist_ok=True)
    layout_path = out.with_suffix(".layout.json")
    cmd = ["node", str(RENDER_JS), str(html_path), str(out), str(fmt.width), str(fmt.height), str(layout_path), str(quality)]
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode != 0:
        raise RuntimeError(f"рендер {rel(out)} не удался:\n{res.stderr[-2000:]}")
    layout = json.loads(layout_path.read_text(encoding="utf-8"))
    # Раскладка хранится рядом с файлом без путей машины — для проверок без повторного рендера.
    layout["spec"] = rel(spec_path)
    layout["lang"] = lang
    layout["format"] = fmt.name
    layout_path.write_text(json.dumps(layout, ensure_ascii=False, indent=1), encoding="utf-8")
    return layout


def record_output(item_dir: Path, item_id: str, out: Path, spec_path: Path, spec: dict, lang: str,
                  fmt_name: str, kind: str, variant: str) -> None:
    """Запись в provenance.yaml объекта: файл, хэш, вход, спецификация, инструмент."""
    prov_path = item_dir / "provenance.yaml"
    prov = load_yaml(prov_path) if prov_path.exists() else {"schema": "item_provenance/v1", "item": item_id, "outputs": []}
    outputs = [o for o in prov["outputs"] if o["file"] != rel(out)]
    inputs = [spec["portrait"]["input"]] if spec.get("portrait") else []
    inputs += ["A-002"]  # шрифты IBM Plex
    mods = ["кадрирование и масштаб портрета", f"тон: {spec['portrait']['treatment']}", "виньетка и маска краёв"] \
        if spec.get("portrait") else []
    outputs.append({
        "file": rel(out),
        "sha256": sha256_file(out),
        "variant": variant,
        "format": fmt_name,
        "lang": lang,
        "kind": kind,
        "method": "программная композиция: спецификация → HTML/SVG → Chromium (Д11)",
        "inputs": inputs,
        "spec": rel(spec_path),
        "spec_sha256": sha256_file(spec_path),
        "prompt": "prompts/image/presentation-composition.v1.md",
        "tool": f"tools/cs/render.py + tools/render/render.js (Playwright, Chromium)",
        "modifications": mods,
        "layout": rel(out.with_suffix(".layout.json")),
        "date": today().isoformat(),
    })
    prov["outputs"] = sorted(outputs, key=lambda o: o["file"])
    dump_yaml(prov, prov_path)
