"""Ключевая метка изображений: шаблон средних частот DCT 8×8 по яркости у краёв (Д41; исследование §3).

Ставит: шаблон знаков от ключа и отпечатка исходника (у каждого файла свой)
повторяется плиткой 16×16 блоков (128 px). Его поправка яркости ложится
только туда, где её не видно:

- у краёв (текст, линии, детали снимка) — по градиенту яркости; на ровном
  фоне, в середине букв и на светлых полях метки нет;
- не больше доли местного контраста (3×3) и не больше CAP уровней: бледная
  сетка меняется на доли уровня, тонкая линия не становится пунктиром;
- ко всем трём каналам — одна и та же поправка: цветность и прозрачность
  не меняются.

Читает (`match`) сверкой с исходником из реестра: копию приводят к размеру
исходника, находят её место в нём, остаток (копия − исходник) сравнивают с
ожидаемой меткой — по местам плитки, знаковым тестом. Без метки знак
совпадения на каждом месте — монетка, поэтому вероятность ложной находки
считается точно (биномиально). Без ключа метку не поставить и не проверить.

Повторяемо: тот же вход и ключ дают те же байты (Pillow, без метаданных;
JPEG — с таблицами квантования и прореживанием цвета исходника).
"""
from __future__ import annotations

import hashlib
import io
import math
from dataclasses import dataclass

import numpy as np
from PIL import Image, JpegImagePlugin

from .key import stream

METHOD = "dct8-edge-v1"
TILE = (16, 16)  # плитка шаблона, в блоках 8×8
COEFS = [(u, v) for u in range(8) for v in range(8) if 2 <= u + v <= 4]  # средние частоты: переживают уменьшение вдвое
ALPHA = 80.0  # шаг шаблона в единицах ортонормированного DCT — с запасом: силу задают маска и пределы
EDGE_FULL = 24.0  # градиент яркости (уровней на пиксель), у которого метка — полной силы
CAP = 4.0  # предел поправки пикселя, уровней из 255
CONTRAST = 0.10  # предел поправки — доля местного контраста 3×3
P_FOUND = 1e-9  # «метка есть»: вероятность такого совпадения у копии без метки не выше этой
MIN_SIDE = 32  # копия меньше — не сверяется (мест плитки слишком мало)
MODES = ("RGB", "RGBA", "L", "LA", "P")

_n = np.arange(8)
_C = np.sqrt(2 / 8) * np.cos(np.pi * (2 * _n[None, :] + 1) * _n[:, None] / 16)
_C[0] /= np.sqrt(2)
_U = np.array([u for u, _ in COEFS])
_V = np.array([v for _, v in COEFS])


def signs(key: bytes, original: bytes) -> np.ndarray:
    """Знаки шаблона (16, 16, len(COEFS)) — от ключа и sha256 исходника: у каждого файла свои,
    так что среднее по многим меченым файлам ключ не выдаёт."""
    ty, tx = TILE
    n = ty * tx * len(COEFS)
    label = "image:" + hashlib.sha256(original).hexdigest()
    bits = np.frombuffer(stream(key, label, n), dtype=np.uint8) & 1
    return (bits.astype(np.float64) * 2 - 1).reshape(ty, tx, -1)


def _luma(rgb: np.ndarray) -> np.ndarray:
    return rgb[..., 0] * 0.299 + rgb[..., 1] * 0.587 + rgb[..., 2] * 0.114


def _window3(a: np.ndarray) -> np.ndarray:
    p = np.pad(a, 1, mode="edge")
    return np.stack([p[i:i + a.shape[0], j:j + a.shape[1]] for i in range(3) for j in range(3)])


def pixel_mask(y: np.ndarray) -> np.ndarray:
    """Доля силы метки у пикселя: у краёв — полная, на ровном — ноль."""
    gy, gx = np.zeros_like(y), np.zeros_like(y)
    gy[1:-1] = (y[2:] - y[:-2]) / 2
    gx[:, 1:-1] = (y[:, 2:] - y[:, :-2]) / 2
    return np.clip(_window3(np.hypot(gx, gy)).mean(axis=0) / EDGE_FULL, 0.0, 1.0)


def pixel_limit(y: np.ndarray) -> np.ndarray:
    """Предел поправки пикселя: доля местного контраста, не больше CAP."""
    w = _window3(y)
    return np.minimum(CAP, CONTRAST * (w.max(axis=0) - w.min(axis=0)))


def _rgb(img: Image.Image) -> tuple[np.ndarray, np.ndarray | None]:
    if img.mode not in MODES:
        raise ValueError(f"метка изображений: режим {img.mode} не поддержан")
    if img.mode in ("RGBA", "LA") or (img.mode == "P" and "transparency" in img.info):
        rgba = np.asarray(img.convert("RGBA"), dtype=np.float64)
        return rgba[..., :3], rgba[..., 3]
    return np.asarray(img.convert("RGB"), dtype=np.float64), None


def mark_delta(y: np.ndarray, sg: np.ndarray) -> np.ndarray:
    """Поправка яркости (float), которую метка вносит в изображение с яркостью y."""
    h, w = y.shape
    bh, bw = h // 8, w // 8
    out = np.zeros((h, w))
    if bh == 0 or bw == 0:
        return out
    rows, cols = np.arange(bh) % TILE[0], np.arange(bw) % TILE[1]
    d = np.zeros((bh, bw, 8, 8))
    d[:, :, _U, _V] = ALPHA * sg[rows][:, cols]
    spatial = np.einsum("ki,abkl,lj->abij", _C, d, _C)  # обратное DCT: Cᵀ·D·C
    out[:bh * 8, :bw * 8] = spatial.transpose(0, 2, 1, 3).reshape(bh * 8, bw * 8)
    lim = pixel_limit(y)
    return np.clip(out * pixel_mask(y), -lim, lim)


def _encode(img: Image.Image, rgb: np.ndarray, alpha_ch: np.ndarray | None) -> bytes:
    px = np.clip(np.rint(rgb), 0, 255).astype(np.uint8)
    gray = img.mode in ("L", "LA")
    if alpha_ch is not None:
        a = alpha_ch.astype(np.uint8)
        out = Image.fromarray(np.dstack([px[..., 0], a]), "LA") if gray else Image.fromarray(np.dstack([px, a]), "RGBA")
    else:
        out = Image.fromarray(px[..., 0], "L") if gray else Image.fromarray(px, "RGB")
    buf = io.BytesIO()
    if img.format == "JPEG":
        # те же таблицы квантования и прореживание цвета, без EXIF/ICC (SD-META)
        out.save(buf, "JPEG", qtables=img.quantization, subsampling=JpegImagePlugin.get_sampling(img),
                 optimize=False, progressive=False)
    else:
        out.save(buf, "PNG", optimize=False, compress_level=9)
    return buf.getvalue()


def _open(data: bytes) -> Image.Image:
    img = Image.open(io.BytesIO(data))
    img.load()
    return img


def embed(data: bytes, key: bytes) -> bytes:
    """Файл PNG/JPEG → тот же формат с меткой."""
    img = _open(data)
    if img.format not in ("PNG", "JPEG"):
        raise ValueError(f"метка изображений: PNG или JPEG, а не {img.format}")
    rgb, alpha_ch = _rgb(img)
    delta = mark_delta(_luma(rgb), signs(key, data))
    return _encode(img, rgb + delta[..., None], alpha_ch)


def expected(original: bytes, key: bytes) -> tuple[np.ndarray, np.ndarray]:
    """(яркость исходника, поправка метки) — до округления и сжатия: общий для двух
    пересжатий шум квантования не должен сойти за метку."""
    y0 = _luma(_rgb(_open(original))[0])
    return y0, mark_delta(y0, signs(key, original))


def binom_tail(n: int, k: int) -> float:
    """P(Bin(n, ½) ≥ k) — точно."""
    if k <= 0:
        return 1.0
    return sum(math.comb(n, i) for i in range(k, n + 1)) / 2 ** n


@dataclass
class Match:
    """Итог сверки копии с исходником из реестра."""
    agree: int  # мест плитки, где остаток копии совпал с меткой по знаку
    cells: int  # мест плитки, где метка есть; без метки совпадает около половины
    p: float  # вероятность совпасть так же у копии без метки (с поправкой на перебор масштабов)
    scale: float  # во сколько раз копию увеличили до исходника
    shift: tuple  # где копия лежит в исходнике (y, x), px
    overlap: float  # доля исходника, которую покрывает копия

    @property
    def found(self) -> bool:
        return self.cells > 0 and self.p <= P_FOUND


def _place(sy: np.ndarray, y0: np.ndarray) -> tuple[int, int]:
    """Где копия (не больше исходника) лежит в исходнике: максимум взаимной корреляции (БПФ)."""
    h, w = sy.shape
    H, W = y0.shape
    if (h, w) == (H, W):
        return 0, 0
    a = np.zeros((H, W))
    a[:h, :w] = sy - sy.mean()
    corr = np.fft.ifft2(np.fft.fft2(y0 - y0.mean()) * np.conj(np.fft.fft2(a))).real
    corr[H - h + 1:, :] = -np.inf  # копия целиком внутри исходника
    corr[:, W - w + 1:] = -np.inf
    dy, dx = np.unravel_index(int(np.argmax(corr)), corr.shape)
    return int(dy), int(dx)


def _cells(r: np.ndarray, m: np.ndarray, dy: int, dx: int) -> tuple[int, int]:
    """Знаковый тест по местам плитки: (мест с положительной корреляцией, мест с меткой).
    Вклады блоков одного места связаны (знаки шаблона повторяются), поэтому сначала —
    сумма по месту; без метки знак суммы — монетка: знаки от ключа не связаны с остатком."""
    ty, tx = TILE
    h, w = m.shape
    cy = ((np.arange(h) + dy) // 8) % ty
    cx = ((np.arange(w) + dx) // 8) % tx
    cell = (cy[:, None] * tx + cx[None, :]).ravel()
    c = np.bincount(cell, weights=(r * m).ravel(), minlength=ty * tx)
    has = np.bincount(cell, weights=np.abs(m).ravel(), minlength=ty * tx) > 0
    return int((c[has] > 0).sum()), int(has.sum())


def _resample(a: np.ndarray, size: tuple) -> np.ndarray:
    return np.asarray(Image.fromarray(a.astype(np.float32), "F").resize(size, Image.LANCZOS), dtype=np.float64)


def match(img: Image.Image, original: bytes, key: bytes) -> Match:
    """Сверить копию с исходником: масштаб — 1:1 или по ширине/высоте исходника; место —
    взаимной корреляцией; метка проходит то же уменьшение, что и копия (согласованный фильтр)."""
    y0, m = expected(original, key)
    H, W = y0.shape
    w, h = img.size
    rgb = _rgb(img if img.mode in MODES else img.convert("RGB"))[0]
    best, best_p, tried = Match(0, 0, 1.0, 1.0, (0, 0), 0.0), 2.0, 0
    for sc in sorted({1.0, round(W / w, 4), round(H / h, 4)}):
        nw, nh = round(w * sc), round(h * sc)
        if nw > W + 1 or nh > H + 1 or min(nw, nh) < MIN_SIDE:
            continue
        nw, nh = min(nw, W), min(nh, H)
        sy = _luma(rgb) if (nw, nh) == (w, h) else _resample(_luma(rgb), (nw, nh))
        dy, dx = _place(sy, y0)
        mm = m[dy:dy + nh, dx:dx + nw]
        if (nw, nh) != (w, h):
            mm = _resample(_resample(mm, (w, h)), (nw, nh))
        k, n = _cells(sy - y0[dy:dy + nh, dx:dx + nw], mm, dy, dx)
        tried += 1
        p = binom_tail(n, k) if n else 1.0
        if p < best_p:
            best, best_p = Match(k, n, p, sc, (dy, dx), nw * nh / (W * H)), p
    best.p = min(1.0, tried * best.p)
    return best


def psnr(a: bytes, b: bytes) -> float:
    """Заметность метки: PSNR исходника и меченого файла, дБ (выше — незаметнее)."""
    x = np.asarray(_open(a).convert("RGB"), dtype=np.float64)
    y = np.asarray(_open(b).convert("RGB"), dtype=np.float64)
    mse = float(((x - y) ** 2).mean())
    return float("inf") if mse == 0 else 10 * np.log10(255 ** 2 / mse)


def dhash(img: Image.Image) -> str:
    """Перцептивный хэш (64 бита, разностный): находит пересжатую и уменьшенную копию."""
    g = np.asarray(img.convert("L").resize((9, 8), Image.LANCZOS), dtype=np.int16)
    bits = (g[:, 1:] > g[:, :-1]).flatten()
    return f"{int(''.join('1' if b else '0' for b in bits), 2):016x}"


def hamming(a: str, b: str) -> int:
    return bin(int(a, 16) ^ int(b, 16)).count("1")
