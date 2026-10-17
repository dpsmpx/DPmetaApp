"""provenance.deps — каждая зависимость записана в реестрах с той же версией (ТЗ, §8.2).

Источники: каталог Gradle (`gradle-catalog`: библиотеки, плагины, версии
по ссылкам и BOM своей группы; `wrapper` — версия и SHA-256 дистрибутива),
регэксп по файлу (`regex`: именованные группы `name` и `version`) и команда
(`command`: печатает JSON `[{"name": …, "version": …}]`). Реестр — документ,
где у каждой зависимости есть строка с `` `name` `` и той же версией
(отдельным словом). SHA-256 дистрибутива Gradle должен быть записан хотя бы
в одном реестре.

Идея — DPpass:tools/provenance/check_dependencies.py (свой код автора).
"""
import json
import os
import re
import subprocess
import tomllib

from ..errors import UsageError
from ..model import Finding, Outcome
from ..profile import REQUIRED
from .base import Check

SOURCE = {"type": ("enum:gradle-catalog|regex|command", REQUIRED), "catalog": ("str", None), "wrapper": ("str", None),
          "file": ("str", None), "pattern": ("regex", None), "run": ("list", None)}
KEYS = {"gradle-catalog": ({"catalog"}, {"wrapper"}), "regex": ({"file", "pattern"}, set()),
        "command": ({"run"}, set())}
DIST = re.compile(r"gradle-(\d[\w.-]*?)-(?:bin|all)\.zip")
COMMAND_TIMEOUT = 600


def _version_in(line, version):
    return re.search(r"(?<![\w.])" + re.escape(version) + r"(?![\w.])", line) is not None


class Deps(Check):
    ID = "provenance.deps"
    SCANNED = "зависимости из источников"
    SUMMARY = "зависимости сборки без строки в реестрах (матрица лицензий, PROVENANCE) или с другой версией"
    ORIGIN = "DPpass:tools/provenance/check_dependencies.py"
    SETTINGS = {"registries": ("list", []), "source": (("tables", SOURCE), [])}
    RULES = {
        "dep-unregistered": ("в реестре нет строки с `name` зависимости",
                             "добавить строку: координаты, версия, лицензия, откуда", "имя и реестр"),
        "dep-version-mismatch": ("строка зависимости в реестре — не с той версией; у Gradle — SHA-256 "
                                 "дистрибутива не записан или не задан в wrapper",
                                 "обновить запись вместе с версией (каталог изменён без записи?)", "имя и версия"),
        "registry-missing": ("нет документа-реестра из registries", "вернуть документ или поправить профиль",
                             "путь реестра"),
        "source-error": ("источник зависимостей не прочитан: нет файла, не разобран, команда упала",
                         "вернуть файл или поправить source в профиле", "путь или команда источника"),
    }

    @classmethod
    def validate(cls, settings, modules, where):
        if settings["enabled"] and not (settings["registries"] and settings["source"]):
            raise UsageError(f"{where}: нужны registries и хотя бы один source")
        for i, src in enumerate(settings["source"]):
            at = f"{where}.source[{i + 1}]"
            need, may = KEYS[src["type"]]
            given = {k for k, v in src.items() if k != "type" and v is not None}
            for key in sorted(need - given):
                raise UsageError(f"{at}: для type = \"{src['type']}\" нужен {key}")
            for key in sorted(given - need - may):
                raise UsageError(f"{at}: {key} — не для type = \"{src['type']}\"")
            if src["pattern"] is not None and not {"name", "version"} <= set(src["pattern"].groupindex):
                raise UsageError(f"{at}.pattern: нужны именованные группы (?P<name>…) и (?P<version>…)")
            if src["run"] == []:
                raise UsageError(f"{at}.run: пустая команда")

    def run(self, ctx):
        repo, findings = ctx.repo, []
        deps, hashes = {}, []  # (имя, версия) → откуда; [(SHA-256, что, откуда)]
        for src in self.settings["source"]:
            try:
                if src["type"] == "gradle-catalog":
                    self._catalog(repo, src, deps, hashes)
                elif src["type"] == "regex":
                    text = self._read(repo, src["file"])
                    for m in src["pattern"].finditer(text):
                        deps.setdefault((m.group("name"), m.group("version")), src["file"])
                else:
                    self._command(repo, src, deps)
            except _SourceError as e:
                findings.append(Finding("source-error", str(e), path=e.path, evidence=e.path or "",
                                        hint='вернуть файл или поправить source в [checks."provenance.deps"]'))
        texts = {}
        for reg in self.settings["registries"]:
            path = repo.abspath(reg)
            if not os.path.isfile(path):
                findings.append(Finding("registry-missing", f"нет реестра зависимостей {reg}", path=reg,
                                        hint="вернуть документ или поправить registries в профиле"))
                continue
            texts[reg] = repo.text(reg) or ""
        for (name, version), origin in sorted(deps.items(), key=lambda kv: (kv[0][0], kv[0][1] or "")):
            for reg, text in texts.items():
                lines = [ln for ln in text.splitlines() if f"`{name}`" in ln]
                where = f"`{name}`" + (f" {version}" if version else "") + f" ({origin})"
                if not lines:
                    findings.append(Finding("dep-unregistered", f"{where} — нет строки в {reg}", path=reg,
                                            evidence=name, hint="строка зависимости: имя в `…`, версия, лицензия, откуда"))
                elif version is None:
                    findings.append(Finding("dep-version-mismatch", f"{where}: версия не задана ни источником, "
                                                                    "ни BOM своей группы", path=reg,
                                            evidence=f"{name}\0", hint="закрепить версию в источнике"))
                elif not any(_version_in(ln, version) for ln in lines):
                    findings.append(Finding("dep-version-mismatch", f"{where}: в {reg} строка не с версией {version}",
                                            path=reg, evidence=f"{name}\0{version}",
                                            hint="обновить запись вместе с версией"))
        for digest, what, origin in hashes:
            if digest is None:
                findings.append(Finding("dep-version-mismatch", f"{what}: в {origin} нет distributionSha256Sum — "
                                                                "дистрибутив не сверяется при загрузке",
                                        path=origin, evidence=f"{what}\0", hint="записать distributionSha256Sum"))
            elif len(texts) == len(self.settings["registries"]) and not any(digest in t for t in texts.values()):
                # Реестра нет — сумма, может быть, в нём: о нём уже registry-missing.
                findings.append(Finding("dep-version-mismatch", f"{what}: SHA-256 {digest[:16]}… не записан ни в "
                                                                "одном реестре", path=origin,
                                        evidence=f"{what}\0{digest}", hint="записать SHA-256 в PROVENANCE"))
        return Outcome(len(deps), findings, f"зависимостей {len(deps)}, реестров {len(texts)}")

    @staticmethod
    def _read(repo, rel):
        path = repo.abspath(rel)
        if not os.path.isfile(path):
            raise _SourceError(f"нет {rel}", rel)
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read()

    def _catalog(self, repo, src, deps, hashes):
        rel = src["catalog"]
        try:
            catalog = tomllib.loads(self._read(repo, rel))
        except tomllib.TOMLDecodeError as e:
            raise _SourceError(f"{rel} не разобран: {e}", rel) from None
        versions = catalog.get("versions", {})

        def version_of(entry):
            if isinstance(entry, str):
                parts = entry.split(":")
                return parts[-1] if len(parts) == 3 else None
            v = entry.get("version")
            if isinstance(v, dict):
                return versions.get(v["ref"]) if "ref" in v else v.get("strictly") or v.get("require") or v.get("prefer")
            return v if isinstance(v, str) else None

        libs, boms = [], {}
        for alias, lib in catalog.get("libraries", {}).items():
            if isinstance(lib, str):
                coord = ":".join(lib.split(":")[:2])
            else:
                coord = lib.get("module") or f"{lib.get('group')}:{lib.get('name')}"
            ver = version_of(lib)
            if coord.endswith("-bom") and ver:
                boms[coord.split(":")[0]] = ver
            libs.append((coord, ver))
        for coord, ver in libs:
            if ver is None:  # версия — из BOM той же группы (junit-bom → junit-jupiter)
                group = coord.split(":")[0]
                ver = next((v for g, v in boms.items() if group == g or group.startswith(g + ".")), None)
            deps.setdefault((coord, ver), rel)
        for alias, plugin in catalog.get("plugins", {}).items():
            if isinstance(plugin, str):  # «id:версия»
                pid, _, ver = plugin.partition(":")
                deps.setdefault((pid, ver or None), rel)
            else:
                deps.setdefault((plugin.get("id"), version_of(plugin)), rel)
        if src["wrapper"]:
            wrapper = src["wrapper"]
            props = {}
            for line in self._read(repo, wrapper).splitlines():
                key, sep, value = line.partition("=")
                if sep and not line.lstrip().startswith("#"):
                    props[key.strip()] = value.strip()
            m = DIST.search(props.get("distributionUrl", ""))
            if not m:
                raise _SourceError(f"{wrapper}: distributionUrl без версии Gradle", wrapper)
            deps.setdefault(("gradle", m.group(1)), wrapper)
            hashes.append((props.get("distributionSha256Sum") or None, f"дистрибутив Gradle {m.group(1)}", wrapper))

    @staticmethod
    def _command(repo, src, deps):
        label = " ".join(src["run"])
        try:
            r = subprocess.run(src["run"], cwd=repo.root, capture_output=True, stdin=subprocess.DEVNULL,
                               timeout=COMMAND_TIMEOUT)
        except (OSError, subprocess.TimeoutExpired) as e:
            raise _SourceError(f"команда {label} не выполнилась: {type(e).__name__}", None) from None
        if r.returncode != 0:
            raise _SourceError(f"команда {label}: код {r.returncode}", None)
        try:
            items = json.loads(r.stdout.decode("utf-8"))
            pairs = [(str(x["name"]), str(x["version"])) for x in items]
        except (ValueError, TypeError, KeyError) as e:
            raise _SourceError(f"команда {label}: нужен JSON [{{\"name\": …, \"version\": …}}] ({e})", None) from None
        for pair in pairs:
            deps.setdefault(pair, label)


class _SourceError(Exception):
    def __init__(self, message, path):
        super().__init__(message)
        self.path = path
