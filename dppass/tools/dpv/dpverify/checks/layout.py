"""repo.layout — структура репозитория: корень, обязательное, запрещённое, размер (ТЗ, §8.1)."""
import os

from ..errors import UsageError
from ..globs import MaskSet
from ..model import Finding, Outcome
from ..profile import REQUIRED
from .base import Check

# Набор «raster» — из VoxelRPG:tools/hostcheck/check_raster.py (свой код автора).
RASTER_EXTENSIONS = {
    "png", "apng", "jpg", "jpeg", "jpe", "jfif", "jp2", "j2k", "tga", "bmp",
    "dib", "gif", "webp", "psd", "psb", "xcf", "kra", "ora", "dds", "ktx",
    "ktx2", "basis", "crn", "pvr", "astc", "pkm", "exr", "hdr", "tif", "tiff",
    "ico", "icns", "qoi", "avif", "heic", "heif", "jxl", "pcx", "ppm", "pgm",
    "pbm", "pnm", "sgi",
}
HEAD = 32


def _bmp(head, size):
    # «BM» — частое начало и у текста: BMP — только если поле размера совпадает с размером файла.
    return head[:2] == b"BM" and len(head) >= 6 and int.from_bytes(head[2:6], "little") == size


SIGNATURES = {
    "png": lambda h, s: h.startswith(b"\x89PNG\r\n\x1a\n"),
    "jpeg": lambda h, s: h.startswith(b"\xff\xd8\xff"),
    "gif": lambda h, s: h.startswith((b"GIF87a", b"GIF89a")),
    "webp": lambda h, s: h[:4] == b"RIFF" and h[8:12] == b"WEBP",
    "bmp": _bmp,
    "tiff": lambda h, s: h.startswith((b"II*\x00", b"MM\x00*")),
    "psd": lambda h, s: h.startswith(b"8BPS"),
    "dds": lambda h, s: h.startswith(b"DDS "),
    "ktx": lambda h, s: h.startswith((b"\xabKTX 11\xbb", b"\xabKTX 20\xbb")),
    "qoi": lambda h, s: h.startswith(b"qoif"),
    "exr": lambda h, s: h.startswith(b"\x76\x2f\x31\x01"),
    "hdr": lambda h, s: h.startswith((b"#?RADIANCE", b"#?RGBE")),
    "astc": lambda h, s: h.startswith(b"\x13\xab\xa1\x5c"),
    "pkm": lambda h, s: h.startswith(b"PKM "),
    "jxl": lambda h, s: h.startswith(b"\xff\x0a") or h.startswith(b"\x00\x00\x00\x0cJXL \r\n\x87\n"),
    "jp2": lambda h, s: h.startswith(b"\x00\x00\x00\x0cjP  \r\n\x87\n"),
    "avif": lambda h, s: h[4:8] == b"ftyp" and h[8:12] in (b"avif", b"avis", b"heic", b"heix", b"mif1", b"msf1"),
    # Короткие сигнатуры — только у двоичных файлов (иначе ловят текст).
    "ico": lambda h, s: h.startswith(b"\x00\x00\x01\x00"),
    "zip": lambda h, s: h.startswith(b"PK\x03\x04"),
    "elf": lambda h, s: h.startswith(b"\x7fELF"),
    "pe": lambda h, s: h.startswith(b"MZ"),
}
BINARY_ONLY = {"ico", "zip", "pe"}
RASTER_MAGIC = [k for k in SIGNATURES if k not in ("ico", "zip", "elf", "pe")]
FORBID = {"glob": ("str", None), "magic": ("list", []), "preset": ("enum:raster", None),
          "reason": ("str", REQUIRED)}


class Layout(Check):
    ID = "repo.layout"
    MANDATORY = True
    SCANNED = "файлы репозитория"
    SUMMARY = "лишнее в корне, отсутствие обязательного, запрещённые пути и содержимое, большие файлы"
    ORIGIN = "check_root DPlang, VoxelRPG:tools/hostcheck/check_raster.py"
    RULES = {
        "root-extra": ("файл в корне вне root_allow (если список задан)",
                       "перенести в свой каталог или добавить в root_allow", "текст находки"),
        "required-missing": ("нет пути из required", "вернуть путь или поправить required; файлы [process] "
                             "сверяет process.files", "текст находки"),
        "forbidden-path": ("путь совпал с forbid[].glob или расширением набора preset",
                           "убрать файл из репозитория", "«путь запрещён» или «расширение .X»"),
        "forbidden-content": ("содержимое совпало с сигнатурой forbid[].magic или набора preset — "
                              "переименованный файл не проходит", "убрать файл из репозитория", "«содержимое — X»"),
        "file-too-large": ("файл больше max_file_kb и не в large_allow",
                           "вынести из репозитория или large_allow с причиной", "текст находки"),
    }
    SETTINGS = {
        "root_allow": ("list", None), "required": ("list", []), "forbid": (("tables", FORBID), []),
        "max_file_kb": ("int", 1024), "large_allow": ("list", []),
    }
    MASK_KEYS = ("large_allow",)

    @classmethod
    def validate(cls, settings, modules, where):
        super().validate(settings, modules, where)
        for i, rule in enumerate(settings["forbid"]):
            if not (rule["glob"] or rule["magic"] or rule["preset"]):
                raise UsageError(f"{where}.forbid[{i + 1}]: нужен glob, magic или preset")
            bad = [m for m in rule["magic"] if m not in SIGNATURES]
            if bad:
                raise UsageError(f"{where}.forbid[{i + 1}].magic: неизвестная сигнатура {bad[0]!r} "
                                 f"(есть: {', '.join(SIGNATURES)})")
            if rule["glob"]:
                MaskSet([rule["glob"]], modules)

    def run(self, ctx):
        s, repo, findings = self.settings, ctx.repo, []
        files = repo.files()
        if s["root_allow"] is not None:
            allowed = set(s["root_allow"])
            for path in files:
                if "/" not in path and path not in allowed:
                    findings.append(Finding("root-extra", "в корне только точки входа (root_allow)",
                                            path=path, hint="перенести в свой каталог или добавить в root_allow"))
        for path in s["required"]:
            if not os.path.exists(repo.abspath(path)):
                findings.append(Finding("required-missing", "нет обязательного пути", path=path,
                                        hint='[checks."repo.layout"].required'))
        rules = self._rules(ctx.profile.modules)
        large = MaskSet(s["large_allow"], ctx.profile.modules)
        limit = s["max_file_kb"] * 1024
        for path in files:
            raw = repo.read_bytes(path)
            hit = self.forbidden(path, raw, rules)
            if hit:
                findings.append(Finding(hit[0], f"{hit[1]} — {hit[2]}", path=path, evidence=hit[1],
                                        hint="убрать файл из репозитория; правило — forbid профиля"))
            if len(raw) > limit and not large.match(path):
                findings.append(Finding("file-too-large", f"{len(raw) // 1024} КиБ больше {s['max_file_kb']} КиБ",
                                        path=path, hint="вынести из репозитория или large_allow с причиной"))
        return Outcome(len(files), findings, f"файлов {len(files)}")

    def _rules(self, modules):
        out = []
        for rule in self.settings["forbid"]:
            exts = RASTER_EXTENSIONS if rule["preset"] == "raster" else set()
            magic = list(rule["magic"]) + (RASTER_MAGIC if rule["preset"] == "raster" else [])
            glob = MaskSet([rule["glob"]], modules) if rule["glob"] else None
            out.append((glob, exts, magic, rule["reason"]))
        return out

    @staticmethod
    def forbidden(path, raw, rules):
        """(правило, что найдено, причина) или None."""
        head, size = raw[:HEAD], len(raw)
        binary = b"\0" in raw[:8192]
        name = os.path.basename(path)
        ext = name.rsplit(".", 1)[-1].lower() if "." in name else ""
        for glob, exts, magic, reason in rules:
            for kind in magic:
                if (binary or kind not in BINARY_ONLY) and SIGNATURES[kind](head, size):
                    return "forbidden-content", f"содержимое — {kind}", reason
            if (glob and glob.match(path)) or ext in exts:
                return "forbidden-path", "путь запрещён" if glob and glob.match(path) else f"расширение .{ext}", reason
        return None
