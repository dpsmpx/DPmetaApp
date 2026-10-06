"""dpv.integrity — копия DP Verify не правилась на месте (V-02; ТЗ, §8.1)."""
import hashlib
import os

from .. import VERSION, version
from ..model import Finding, Outcome
from .base import Check

MANIFEST = "MANIFEST.sha256"
VENDOR_DIR = "tools/dpv"


def read_manifest(path):
    """MANIFEST.sha256 → ({путь: sha256}, {ключ заголовка: значение})."""
    entries, header = {}, {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if line.startswith("#"):
                key, sep, value = line[1:].partition(":")
                if sep:
                    header[key.strip()] = value.strip()
            elif line.strip():
                digest, _, rel = line.partition("  ")
                entries[rel] = digest
    return entries, header


def sha256(path):
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()


class Integrity(Check):
    ID = "dpv.integrity"
    MANDATORY = True
    SCANNED = "файлы копии из манифеста; в исходном репозитории и при внешнем запуске — версия"
    SUMMARY = "правку вендоренной копии tools/dpv/ на месте и несовместимую версию DP Verify (V-02)"
    ORIGIN = "новое для всех трёх проектов: копий DP Verify до него не было"
    RULES = {
        "file-modified": ("файл копии изменён после поставки",
                          "правка — только в DP-Verify и новой поставкой: python3 tools/vendor.py --into <репозиторий>",
                          "текст находки"),
        "file-missing": ("файла из манифеста нет", "поставить копию заново", "текст находки"),
        "file-extra": ("в копии файл, которого нет в манифесте", "убрать файл или поставить копию заново",
                       "текст находки"),
        "manifest-missing": ("нет tools/dpv/MANIFEST.sha256 — копию не сверить", "поставить копию заново",
                             "текст находки"),
        "version-mismatch": ("версия пакета, VERSION и манифеста расходятся или не входят в dpv профиля",
                             "поставить нужную версию или поправить dpv в профиле", "версия или «copy»"),
        "external-copy": ("CI-прогон запущен не копией tools/dpv/ этого репозитория",
                          "запускать tools/dpv/dpv (V-02, V-05)", "«external»"),
    }

    def run(self, ctx):
        root = os.path.realpath(ctx.repo.root)
        pkg = os.path.realpath(ctx.package_root)
        if pkg == root:
            return Outcome(1, self._range(ctx), f"исходный репозиторий DP Verify {VERSION}: манифеста нет, сверена версия")
        if pkg != os.path.realpath(os.path.join(root, VENDOR_DIR)):
            findings = self._range(ctx)
            if ctx.mode == "ci":
                findings.append(Finding(
                    "external-copy", f"CI-прогон — только копией {VENDOR_DIR}/ этого репозитория, а запущена {pkg}",
                    hint="запустить tools/dpv/dpv (V-02, V-05)", evidence="external"))
            return Outcome(1, findings, f"внешняя копия DP Verify {VERSION} ({pkg}): манифест не сверяется")
        return self._vendored(ctx, pkg)

    def _range(self, ctx):
        if version.satisfies(VERSION, ctx.profile.dpv_range):
            return []
        return [Finding("version-mismatch", f"DP Verify {VERSION} не входит в dpv = \"{ctx.profile.dpv_range}\"",
                        evidence=VERSION)]

    def _vendored(self, ctx, pkg):
        manifest = os.path.join(pkg, MANIFEST)
        if not os.path.isfile(manifest):
            return Outcome(1, [Finding(
                "manifest-missing", f"нет {VENDOR_DIR}/{MANIFEST}: копию не сверить",
                path=f"{VENDOR_DIR}/{MANIFEST}", hint="поставить копию заново: tools/vendor.py в DP-Verify")])
        entries, header = read_manifest(manifest)
        findings = []
        for rel, digest in sorted(entries.items()):
            path = os.path.join(pkg, rel)
            where = f"{VENDOR_DIR}/{rel}"
            if not os.path.isfile(path):
                findings.append(Finding("file-missing", "нет файла из манифеста", path=where,
                                        hint="правка копии — только новой поставкой (V-02)"))
            elif sha256(path) != digest:
                findings.append(Finding("file-modified", "изменён после поставки", path=where,
                                        hint="правка копии — только в DP-Verify и новой поставкой (V-02)"))
        for dirpath, dirs, files in os.walk(pkg):
            dirs[:] = sorted(d for d in dirs if d != "__pycache__")
            for name in sorted(files):
                rel = os.path.relpath(os.path.join(dirpath, name), pkg).replace(os.sep, "/")
                if rel != MANIFEST and rel not in entries:
                    findings.append(Finding("file-extra", "файла нет в манифесте",
                                            path=f"{VENDOR_DIR}/{rel}",
                                            hint="правка копии — только новой поставкой (V-02)"))
        stated = header.get("version")
        file_version = None
        if os.path.isfile(os.path.join(pkg, "VERSION")):
            with open(os.path.join(pkg, "VERSION"), encoding="utf-8") as f:
                file_version = f.read().strip()
        if stated != VERSION or file_version != VERSION:
            findings.append(Finding(
                "version-mismatch",
                f"версия копии: пакет {VERSION}, VERSION {file_version}, манифест {stated}",
                path=f"{VENDOR_DIR}/VERSION", evidence="copy"))
        findings += self._range(ctx)
        source = header.get("source", "?")
        return Outcome(len(entries), findings, f"{len(entries)} файлов копии {VERSION} сходятся с манифестом ({source})")
