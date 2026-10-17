"""Порог находки и точная вероятность ложного совпадения — общее для меток изображений и SVG."""
from __future__ import annotations

import math

P_FOUND = 1e-9  # «метка есть»: вероятность такого совпадения у копии без метки не выше этой


def binom_tail(n: int, k: int) -> float:
    """P(Bin(n, ½) ≥ k) — точно."""
    if k <= 0:
        return 1.0
    return sum(math.comb(n, i) for i in range(k, n + 1)) / 2 ** n
