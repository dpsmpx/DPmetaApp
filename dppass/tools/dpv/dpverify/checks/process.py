"""process.files, provenance.binaries и process.scope — файлы процесса, происхождение
двоичных, границы задачи (ТЗ, §8.1, §8.2)."""
import hashlib
import os
import re

from .. import scope, select, textscan
from ..model import WARNING, Finding, Outcome
from .base import Check, default_settings
from .layout import Layout

DONE = re.compile(r"^\s*[-*]\s+\[(?:\+|x|X)\]")
TOP_ITEM = re.compile(r"^[-*]\s")
UNFILLED = "<!-- dpv:fill"
HEX64 = re.compile(r"\b[0-9a-f]{64}\b")
NEAR = 3  # строк после упоминания пути, в которых ищется его SHA-256


class ProcessFiles(Check):
    ID = "process.files"
    MANDATORY = True
    SCANNED = "файлы процесса из [process], решения, пункты TODO.md"
    SUMMARY = "файлы процесса, журнал решений, TODO.md"
    ORIGIN = "правила CLAUDE.md трёх проектов"
    RULES = {
        "missing-file": ("нет файла из [process]", "создать или поправить [process]", "текст находки"),
        "decision-duplicate": ("номер решения определён дважды", "номер не переиспользуется (TR-1)",
                               "номер решения"),
        "decision-order": ("предупреждение: номера не по возрастанию", "переставить (TR-1)", "номер решения"),
        "decision-field-missing": ("в теле решения нет строки из decision_required_fields",
                                   "дописать проверку решения (TR-2)", "номер решения и строка"),
        "todo-done": ("в TODO.md закрытый пункт [+], [x]", "удалить пункт (TR-4)", "текст пункта"),
        "todo-item": ("пункт верхнего уровня TODO.md не совпал с todo_item", "метка в начале пункта (TR-4)",
                      "текст пункта"),
        "kit-unfilled": ("предупреждение: осталась заглушка шаблона <!-- dpv:fill … -->", "заполнить",
                         "номер строки"),
    }

    def run(self, ctx):
        repo, proc, findings, scanned = ctx.repo, ctx.profile.process, [], 0
        keys = ("agent_rules", "todo", "decisions", "roadmap", "provenance")
        present = []
        for key in keys:
            path = proc[key]
            if not path:
                continue
            scanned += 1
            if not os.path.isfile(repo.abspath(path)):
                findings.append(Finding("missing-file", f"нет {path} ([process].{key})", path=path,
                                        hint="создать файл (dpv init — этап 5) или поправить [process]"))
            else:
                present.append(path)
        for path in present:
            text = repo.text(path) or ""
            for n, line in enumerate(text.splitlines(), 1):
                if UNFILLED in line:
                    findings.append(Finding("kit-unfilled", f"незаполненная заглушка шаблона",
                                            path=path, line=n, severity=WARNING, evidence=str(n),
                                            hint="заполнить по смыслу проекта"))
        if proc["decisions"] in present and proc["decision_heading"]:
            scanned += self._decisions(repo, proc, findings)
        if proc["todo"] in present:
            scanned += self._todo(repo, proc, findings)
        return Outcome(scanned, findings, f"файлов процесса {len(present)}, проверено {scanned}")

    @staticmethod
    def _decisions(repo, proc, findings):
        path, rx = proc["decisions"], proc["decision_heading"]
        lines = (repo.text(path) or "").splitlines()
        fenced = textscan.fenced(lines)
        heads = []  # (номер строки, уровень, ключ)
        for n, line in enumerate(lines, 1):
            if n in fenced:
                continue
            m = rx.search(line)
            if m:
                level = len(line) - len(line.lstrip("#"))
                heads.append((n, level, m.group(1) if m.re.groups else m.group(0)))
        seen, prev = {}, None
        for i, (n, level, key) in enumerate(heads):
            if key in seen:
                findings.append(Finding("decision-duplicate", f"решение {key} уже определено в строке {seen[key]}",
                                        path=path, line=n, evidence=key, hint="номер не переиспользуется (TR-1)"))
            seen.setdefault(key, n)
            if prev is not None and _num(key) is not None and _num(prev) is not None and _num(key) <= _num(prev):
                findings.append(Finding("decision-order", f"решение {key} идёт после {prev}",
                                        path=path, line=n, severity=WARNING, evidence=key,
                                        hint="номера — по возрастанию (TR-1)"))
            prev = key
            end = len(lines)
            for m in range(n, len(lines)):
                ln = lines[m]
                if (m + 1) not in fenced and ln.startswith("#") and len(ln) - len(ln.lstrip("#")) <= max(level, 1):
                    end = m
                    break
            body = "\n".join(lines[n:end])
            for field in proc["decision_required_fields"]:
                if field not in body:
                    findings.append(Finding("decision-field-missing", f"у решения {key} нет «{field}»",
                                            path=path, line=n, evidence=f"{key}\0{field}",
                                            hint="у решения есть проверка — иначе это пожелание (TR-2)"))
        return len(heads)

    @staticmethod
    def _todo(repo, proc, findings):
        path = proc["todo"]
        lines = (repo.text(path) or "").splitlines()
        fenced = textscan.fenced(lines)
        items = 0
        for n, line in enumerate(lines, 1):
            if n in fenced:
                continue
            if DONE.match(line):
                findings.append(Finding("todo-done", f"закрытый пункт — сделанное удаляется",
                                        path=path, line=n, evidence=line.strip(), hint="удалить пункт (TR-4)"))
            if TOP_ITEM.match(line):
                items += 1
                if proc["todo_item"] is not None and not proc["todo_item"].match(line):
                    findings.append(Finding("todo-item", f"пункт не по форме todo_item",
                                            path=path, line=n, evidence=line.strip(),
                                            hint="метка сложности в начале пункта (TR-4)"))
        return items


def _num(key):
    m = re.search(r"\d+", key)
    return int(m.group(0)) if m else None


class ProvenanceBinaries(Check):
    ID = "provenance.binaries"
    MANDATORY = True
    SCANNED = "файлы репозитория (двоичные сверяются с документом происхождения)"
    SUMMARY = "двоичные файлы без записи в документе происхождения с SHA-256"
    ORIGIN = "DPpass:tools/provenance/check_dependencies.py"
    RULES = {
        "binary-unrecorded": ("двоичный файл не упомянут в [process].provenance вместе со своим SHA-256 "
                              "(в той же строке или трёх следующих)",
                              "строка с путём и SHA-256: откуда файл и на каком основании", "SHA-256 файла"),
        "binary-hash-mismatch": ("упомянут, но SHA-256 другой", "обновить запись", "SHA-256 файла"),
    }

    def run(self, ctx):
        repo, proc, findings = ctx.repo, ctx.profile.process, []
        files = repo.files()
        layout = ctx.profile.checks.get(Layout.ID) or default_settings(Layout)
        forbid = Layout(layout)._rules(ctx.profile.modules)
        doc = proc["provenance"]
        lines = (repo.text(doc) or "").splitlines() if doc and doc in set(files) else []
        for path in files:
            if not repo.is_binary(path) or path == doc:
                continue
            raw = repo.read_bytes(path)
            if Layout.forbidden(path, raw, forbid):
                continue  # о запрещённом уже сказал repo.layout
            digest = hashlib.sha256(raw).hexdigest()
            near = [h for i, ln in enumerate(lines) if path in ln
                    for h in HEX64.findall(" ".join(lines[i:i + 1 + NEAR]))]
            if digest in near:
                continue
            if near:
                findings.append(Finding("binary-hash-mismatch", f"SHA-256 в {doc} не тот ({digest[:12]}…)",
                                        path=path, evidence=digest, hint=f"обновить запись в {doc}"))
            else:
                where = doc or "[process].provenance (не задан)"
                findings.append(Finding("binary-unrecorded", f"двоичный файл не записан в {where} с SHA-256",
                                        path=path, evidence=digest,
                                        hint=f"строка с путём и SHA-256 {digest} — откуда файл и на каком основании"))
        return Outcome(len(files), findings, f"файлов {len(files)}")


class ProcessScope(Check):
    """Необязательная, только на уровне affected: файлы, изменённые с начала задачи вне её
    границ, — предупреждения (L-12). Логика — dpverify/scope.py."""

    ID = "process.scope"
    LEVEL = "affected"
    SCANNED = "файлы, изменённые с начала задачи, и пути её границ"
    SUMMARY = "файлы, изменённые с начала задачи вне её границ; только находит, не запрещает (L-12)"
    ORIGIN = "VoxelRPG:tools/hostcheck/scope_check.py, фаза 1"
    RULES = {
        "out-of-scope": ("предупреждение: файл изменён с начала задачи и не входит в её пути",
                         "убрать правку, объяснить в PR или спросить автора; нужный файл — dpv scope start "
                         "с ним заново", "«out-of-scope»"),
    }

    def run(self, ctx):
        record = scope.load(ctx.repo, ctx.profile)
        if record is None:
            return Outcome(0, skip='границы не заданы — dpv scope start "<задача>" ПУТЬ…')
        if ctx.repo.head() is None or select.commit(ctx.repo, record.start) is None:
            return Outcome(0, skip=f"начало задачи {record.start[:7]} не найдено в git — dpv scope start заново")
        files, stray = scope.check(ctx.repo, ctx.profile, record)
        findings = [Finding("out-of-scope", f"изменён вне границ задачи «{record.task}»", path=path,
                            severity=WARNING, evidence="out-of-scope",
                            hint="убрать правку, объяснить в PR или спросить автора; "
                                 "нужный файл — dpv scope start с ним заново")
                    for path in stray]
        return Outcome(len(files) + len(record.paths), findings,
                       f"задача «{record.task}» от {record.start[:7]}: изменено {len(files)}, вне границ {len(stray)}")
