"""Ключевая метка SVG: дрожание координат по ключу (Д41; исследование §4).

Ставит: каждая координата фигуры (`x`, `y`, `x1`…`cy`, `points`, `d` без дуг)
записывается двумя знаками после запятой больше, чем было, и сдвигается на
±3 единицы последнего знака: «300» → «300.03», «12.5» → «12.497» — меньше
шага исходной записи, так что исходник восстанавливается округлением, а
сдвиг — не больше 0,03 px. Знак сдвига — от ключа и
скелета фигуры (текст SVG без чисел-координат), так что у разных фигур
знаки разные. Сотые доли пикселя глазом не видны; метка переживает
копирование файла и вставку фигуры в чужую страницу, но не растеризацию,
округление чисел и перерисовку — тогда остаётся отпечаток в реестре.

Не трогает: `viewBox`, размеры, толщины, шрифты, текст и всё вне атрибутов
координат; в `d` с дугами (`A`/`a`) — ничего: флаги дуги — 0 и 1, их
сдвиг ломает фигуру. Числа с показателем степени не меняются.

Читает (`read`) без исходника: у координаты с k ≥ 2 знаками после запятой
исходник — то же число, округлённое до k − 2 знаков; сдвиг ±3·10⁻ᵏ — след
метки, его знак сравнивается со знаком от ключа. Без метки знак совпадения
— монетка, поэтому вероятность ложной находки считается точно
(биномиально). Повторяемо: тот же вход и ключ дают тот же текст.
"""
from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass

from .key import stream
from .stats import P_FOUND, binom_tail

METHOD = "svg-jitter-v1"
SHIFT = 3  # сдвиг — столько единиц последнего (нового) знака: 0,03 px у целых координат
COORD_ATTRS = ("x", "y", "x1", "y1", "x2", "y2", "cx", "cy", "points", "d")
ATTR = re.compile(r'(?<=\s)(' + "|".join(COORD_ATTRS) + r')="([^"]*)"')
NUM = re.compile(r"-?(?:\d+\.\d*|\.\d+|\d+)(?:[eE][-+]?\d+)?")
INLINE = re.compile(r"<svg\b.*?</svg>", re.S)


def _spans(svg: str) -> list[tuple[int, int]]:
    """Места чисел-координат в тексте SVG по порядку: (начало, конец)."""
    out = []
    for m in ATTR.finditer(svg):
        if m.group(1) == "d" and re.search(r"[Aa]", m.group(2)):
            continue  # флаги дуги — 0/1: не трогать
        base = m.start(2)
        for n in NUM.finditer(m.group(2)):
            if "e" in n.group(0).lower():
                continue
            out.append((base + n.start(), base + n.end()))
    return out


def skeleton(svg: str) -> str:
    """Фигура без чисел-координат: одинакова у исходника и меченой копии."""
    out, pos = [], 0
    for a, b in _spans(svg):
        out += [svg[pos:a], "#"]
        pos = b
    out.append(svg[pos:])
    return "".join(out)


def _decimals(s: str) -> int:
    return len(s.split(".", 1)[1]) if "." in s else 0


def signs(key: bytes, svg: str, n: int) -> list[int]:
    """Знаки сдвигов (+1/−1) для n координат — от ключа и скелета фигуры."""
    if n == 0:
        return []
    label = "svg:" + hashlib.sha256(skeleton(svg).encode("utf-8")).hexdigest()
    return [1 if b & 1 else -1 for b in stream(key, label, n)]


def embed(svg: str, key: bytes) -> str:
    """Текст SVG → тот же текст со сдвинутыми координатами."""
    spans = _spans(svg)
    sg = signs(key, svg, len(spans))
    out, pos = [], 0
    for (a, b), s in zip(spans, sg):
        tok = svg[a:b]
        k = _decimals(tok) + 2
        out += [svg[pos:a], f"{float(tok) + s * SHIFT * 10 ** -k:.{k}f}"]
        pos = b
    out.append(svg[pos:])
    return "".join(out)


def embed_inline(html: str, key: bytes) -> tuple[str, int]:
    """Страница → (страница с метками во всех встроенных <svg>, сколько фигур помечено)."""
    n = 0

    def one(m: re.Match) -> str:
        nonlocal n
        marked = embed(m.group(0), key)
        n += marked != m.group(0)
        return marked

    return INLINE.sub(one, html), n


@dataclass
class Match:
    agree: int  # сдвигов в сторону метки
    moved: int  # координат со сдвигом метки
    total: int  # координат в фигуре
    p: float  # вероятность совпасть так же у фигуры без метки

    @property
    def found(self) -> bool:
        return self.moved > 0 and self.p <= P_FOUND


def read(svg: str, key: bytes) -> Match:
    """Прочитать метку без исходника: сдвиг от числа, округлённого на два знака короче."""
    spans = _spans(svg)
    sg = signs(key, svg, len(spans))
    agree = moved = 0
    for (a, b), s in zip(spans, sg):
        tok = svg[a:b]
        k = _decimals(tok)
        if k < 2:
            continue
        d = float(tok) - round(float(tok), k - 2)
        if abs(abs(d) - SHIFT * 10 ** -k) > 10 ** -k / 2:
            continue
        moved += 1
        agree += (d > 0) == (s > 0)
    return Match(agree, moved, len(spans), binom_tail(moved, agree) if moved else 1.0)


def read_inline(html: str, key: bytes) -> list[Match]:
    """Метки всех встроенных фигур страницы по порядку."""
    return [read(m, key) for m in INLINE.findall(html)]
