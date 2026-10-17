"""docs.links и docs.refs — ссылки в документах и коде ведут на существующее (ТЗ, §8.1; V-17)."""
import os
import re
from urllib.parse import unquote

from .. import textscan
from ..globs import MaskSet
from ..model import WARNING, Finding, Outcome
from ..profile import REQUIRED
from .base import Check, default_settings

BACKTICK = re.compile(r"`([^`\n]+)`")
FOREIGN = re.compile(r"^([A-Za-z][\w.-]*):(\S.*)$")
FOREIGN_BEFORE = re.compile(r"([A-Za-z][\w.-]*):$")
DOC_NAME = re.compile(r"^[A-Z][A-Z0-9_]*\.md$")
PLACEHOLDER = re.compile(r"[<>{}$…]")
SCHEME = re.compile(r"^(?:[a-zA-Z][a-zA-Z0-9+.-]*:|//)")
LINK_TARGET = re.compile(r"\]\([^)]*\)")
PATH_ENTRY = {"path": ("str", REQUIRED), "reason": ("str", REQUIRED)}


def _rel(ctx, path):
    return os.path.relpath(path, ctx.repo.root).replace(os.sep, "/")


class PathEntries:
    """Записи external или planned: маска пути, причина, использована ли."""

    def __init__(self, entries, modules):
        self.items = [(e["path"], MaskSet([e["path"]], modules), e["reason"]) for e in entries]
        self.used = set()

    @staticmethod
    def _base(path):
        return re.sub(r"(/\*\*)?/?$", "", path)

    def appeared(self, repo):
        """Записи planned, чей путь появился (находка planned-exists проверки dpv.profile)."""
        return [p for p, _, _ in self.items
                if "*" not in self._base(p) and os.path.exists(repo.abspath(self._base(p)))]

    def match(self, ref):
        ref_base = self._base(ref)
        for path, mask, _ in self.items:
            base = self._base(path)
            if mask.match(ref) or ref_base == base or ref_base.startswith(base + "/"):
                self.used.add(path)
                return True
        return False

    def match_name(self, name):
        for path, _, _ in self.items:
            if os.path.basename(self._base(path)) == name:
                self.used.add(path)
                return True
        return False

    def unused(self):
        return [p for p, _, _ in self.items if p not in self.used]


class Links(Check):
    ID = "docs.links"
    MANDATORY = True
    SCANNED = "ссылки Markdown, пути с префиксами path_prefixes и имена документов"
    SUMMARY = "ссылки и пути в документах и коде, ведущие в пустоту (V-17)"
    ORIGIN = "check_links.py DPlang, check_docs.py DPpass"
    RULES = {
        "missing-target": ("[текст](путь) ведёт на несуществующий файл или за пределы репозитория",
                           "исправить ссылку", "цель ссылки"),
        "missing-anchor": ("у путь#якорь или #якорь нет заголовка с таким якорем или <a id>",
                           "исправить якорь", "цель ссылки"),
        "missing-path-ref": ("путь с префиксом из path_prefixes — в документе или коде — не существует; "
                             "маска со * ни с чем не совпала",
                             "исправить путь; другой репозиторий — Проект:путь; раскладка целевого — external; "
                             "будущий — planned", "путь"),
        "missing-doc-name": ("`ИМЯ.md` без пути — такого файла нет",
                             "исправить имя; документ другого репозитория — Проект:ИМЯ.md", "имя документа"),
        "unknown-project": ("Проект:путь, а проекта нет в [process].foreign_projects",
                            "добавить проект или исправить имя", "имя проекта"),
        "external-unused": ("на запись external никто не ссылается", "убрать запись", "путь записи"),
        "planned-unused": ("на запись planned никто не ссылается", "убрать запись", "путь записи"),
    }
    SETTINGS = {
        "path_prefixes": ("list", ["docs/", "tools/"]), "anchors": ("bool", True),
        "historical": ("list", []), "planned": (("tables", PATH_ENTRY), []),
        "external": (("tables", PATH_ENTRY), []), "skip": ("list", []),
    }
    MASK_KEYS = ("historical", "skip")

    @classmethod
    def validate(cls, settings, modules, where):
        super().validate(settings, modules, where)
        for key in ("planned", "external"):
            MaskSet([e["path"] for e in settings[key]], modules)

    def run(self, ctx):
        s, repo = self.settings, ctx.repo
        modules = ctx.profile.modules
        self.ctx, self.findings, self.scanned = ctx, [], 0
        self.foreign = set(ctx.profile.process["foreign_projects"])
        self.files = repo.files()
        self.fileset = set(self.files)
        self.dirs = {p.rsplit("/", i)[0] for p in self.files for i in range(1, p.count("/") + 1)}
        self.names = {os.path.basename(p) for p in self.files}
        self.planned = PathEntries(s["planned"], modules)
        self.external = PathEntries(s["external"], modules)
        self._anchors = {}
        prefixes = sorted(s["path_prefixes"], key=len, reverse=True)
        self.prefixes = tuple(prefixes)
        self.plain = re.compile(r"(?<![\w/.-])((?:" + "|".join(map(re.escape, prefixes))
                                + r")[A-Za-z0-9_./*-]*[A-Za-z0-9_*/])")
        skip = MaskSet(s["skip"], modules)
        historical = MaskSet(s["historical"], modules)
        # Пути профиля сверяет dpv.profile, иначе external «используется» сам собой (V-18);
        # базовая линия цитирует находки — проверять её значит находить их второй раз (V-19).
        own = {_rel(ctx, ctx.profile.path), ctx.profile.baseline}
        for path in self.files:
            if path in own or skip.match(path) or repo.is_binary(path):
                continue
            text = repo.text(path)
            is_md = path.endswith(".md")
            if is_md:
                for n, target in textscan.links(text):
                    self._link(path, n, target)
            if historical.match(path):
                continue  # запись о прошлом: путь, который тогда был, — верен
            lines = text.splitlines()
            fenced = textscan.fenced(lines) if is_md else set()
            for n, line in enumerate(lines, 1):
                if n not in fenced:
                    self._line(path, n, line)
        where = _rel(ctx, ctx.profile.path)
        appeared = set(self.planned.appeared(repo))  # о них говорит dpv.profile (ТЗ, §8.1)
        for kind, entries in (("planned", self.planned), ("external", self.external)):
            for p in entries.unused():
                if p not in appeared:
                    self.findings.append(Finding(
                        f"{kind}-unused", f'запись {kind} «{p}» — на неё никто не ссылается', path=where,
                        hint=f'убрать запись из [checks."docs.links"].{kind}', evidence=p))
        return Outcome(self.scanned, self.findings, f"ссылок и путей {self.scanned}")

    # ---- ссылки Markdown ----

    def _link(self, path, n, target):
        if SCHEME.match(target):
            return
        self.scanned += 1
        target = unquote(target)
        file_part, _, anchor = target.partition("#")
        if not file_part:
            dest = path
        elif file_part.startswith("/"):
            dest = os.path.normpath(file_part.lstrip("/")).replace(os.sep, "/")
        else:
            dest = os.path.normpath(os.path.join(os.path.dirname(path), file_part)).replace(os.sep, "/")
        if dest.startswith("../") or dest == "..":
            self._missing("missing-target", path, n, f"ссылка «{target}» ведёт за пределы репозитория", target)
            return
        if not os.path.exists(self.ctx.repo.abspath(dest)):
            self._missing("missing-target", path, n, f"ссылка «{target}»: нет {dest}", target)
            return
        if anchor and self.settings["anchors"] and dest.endswith(".md") and os.path.isfile(self.ctx.repo.abspath(dest)):
            if anchor.lower() not in self._anchors_of(dest):
                self._missing("missing-anchor", path, n, f"ссылка «{target}»: в {dest} нет якоря #{anchor}", target)

    def _anchors_of(self, dest):
        if dest not in self._anchors:
            with open(self.ctx.repo.abspath(dest), encoding="utf-8", errors="replace") as f:
                self._anchors[dest] = {a.lower() for a in textscan.anchors(f.read())}
        return self._anchors[dest]

    # ---- пути и имена документов ----

    def _line(self, path, n, line):
        for m in BACKTICK.finditer(line):
            self._code_ref(path, n, m.group(1).strip())
        plain = LINK_TARGET.sub(lambda m: " " * len(m.group(0)), BACKTICK.sub(lambda m: " " * len(m.group(0)), line))
        for m in self.plain.finditer(plain):
            before = FOREIGN_BEFORE.search(plain[:m.start()])
            if before:
                self._foreign(path, n, before.group(1), m.group(1))
            else:
                self._path(path, n, [m.group(1)])

    def _code_ref(self, path, n, content):
        m = FOREIGN.match(content)
        if m:
            rest = m.group(2)
            rest = rest[2:] if rest.startswith("./") else rest
            if rest.startswith(self.prefixes) or DOC_NAME.match(rest.split()[0]):
                self._foreign(path, n, m.group(1), rest)
            return
        ref = content[2:] if content.startswith("./") else content
        if DOC_NAME.match(ref):
            self.scanned += 1
            if ref not in self.names and not self.planned.match_name(ref) and not self.external.match_name(ref):
                self._missing("missing-doc-name", path, n, f"нет документа {ref}", ref)
        elif ref.startswith(self.prefixes):
            self._path(path, n, [ref, ref.split()[0]])

    def _foreign(self, path, n, project, ref):
        self.scanned += 1
        if project not in self.foreign:
            self._missing("unknown-project", path, n,
                          f"«{project}:{ref}» — проекта {project} нет в [process].foreign_projects", project)

    def _path(self, path, n, candidates):
        cands = []
        for c in candidates:
            c = c.split("#", 1)[0].rstrip("/.,;:)")
            if c and not PLACEHOLDER.search(c) and c not in cands:
                cands.append(c)
        if not cands:
            return
        self.scanned += 1
        for c in cands:
            if self._resolves(c):
                return
        self._missing("missing-path-ref", path, n, f"нет {cands[0]}", cands[0])

    def _resolves(self, ref):
        if "*" in ref:
            mask = MaskSet([ref])
            if any(mask.match(p) for p in self.files):
                return True
        elif ref in self.fileset or ref in self.dirs or os.path.exists(self.ctx.repo.abspath(ref)):
            return True
        return self.external.match(ref) or self.planned.match(ref)

    def _missing(self, rule, path, n, message, evidence):
        hints = {
            "missing-path-ref": "исправить путь; путь другого репозитория — Проект:путь (V-17); "
                                "раскладка целевого — external, будущий — planned",
            "missing-doc-name": "исправить имя; документ другого репозитория — Проект:ИМЯ.md (V-17)",
            "unknown-project": "добавить проект в [process].foreign_projects или исправить имя",
        }
        self.findings.append(Finding(rule, f"{message}", path=path, line=n, evidence=evidence,
                                     hint=hints.get(rule, "исправить ссылку")))


KIND = {"name": ("str", REQUIRED), "ref": ("regex", REQUIRED), "context": ("regex", None),
        "defined_in": ("list", REQUIRED), "definition": ("regex", REQUIRED)}
DOC = r"`?(?:([A-Za-z][\w.-]*):)?((?:[\w.-]+/)*[\w.-]+\.md)`?"
SECTION_REF = re.compile(DOC + r"\s*,\s*(?:раздел\s+)?«([^»]+)»")
PARA_REF = re.compile(DOC + r"\s*,?\s*§\s*(\d+(?:\.\d+)*)")
# Латиница ↔ кириллица одного начертания.
HOMOGLYPHS = dict(zip("ABCEHKMOPTXaceopxy", "АВСЕНКМОРТХасеорху"))
HOMOGLYPHS.update({v: k for k, v in HOMOGLYPHS.items()})


def _key(m):
    return m.group(1) if m.re.groups else m.group(0)


class Refs(Check):
    ID = "docs.refs"
    MANDATORY = True
    SCANNED = "ссылки на решения, виды профиля и разделы документов"
    SUMMARY = "ссылки на решения, разделы, § и виды ссылок проекта"
    ORIGIN = "check_docs.py DPpass"
    RULES = {
        "undefined-<вид>": ("ключ вида не определён: undefined-decision — номер решения нет в "
                            "[process].decisions, иначе — в defined_in вида профиля",
                            "исправить ссылку или записать определение", "текст ссылки"),
        "empty-kind": ("у вида нет ни одной ссылки или ни одного определения — опечатка в регэкспе? (V-07)",
                       "исправить регэксп вида", "имя вида"),
        "missing-section": ("«`документ`, «Раздел»»: нет раздела — заголовка, выделенного начала абзаца "
                            "или пункта, строки-подписи «Название:»", "исправить ссылку или название раздела",
                            "документ и раздел"),
        "missing-paragraph": ("«`документ`, §N»: нет заголовка, начинающегося с N",
                              "исправить ссылку или заголовок", "документ и номер"),
        "homoglyph": ("предупреждение: номер решения набран буквой другого алфавита (R-06)", "перенабрать букву",
                      "номер со смешанными буквами"),
    }
    SETTINGS = {"kind": (("tables", KIND), []), "skip": ("list", []), "homoglyph_context": ("regex", None)}
    MASK_KEYS = ("skip",)

    @classmethod
    def validate(cls, settings, modules, where):
        super().validate(settings, modules, where)
        for k in settings["kind"]:
            MaskSet(k["defined_in"], modules)

    def run(self, ctx):
        repo, proc = ctx.repo, ctx.profile.process
        findings, scanned = [], 0
        files = repo.files()
        kinds = self._kinds(ctx, files)
        foreign = proc["foreign_projects"]
        window = proc["foreign_window"]
        foreign_rx = re.compile("|".join(map(re.escape, foreign))) if foreign else None
        names = {}
        for p in files:
            names.setdefault(os.path.basename(p), []).append(p)
        swapped = self._swapped(proc)
        context = self.settings["homoglyph_context"] or (
            re.compile(re.escape(os.path.basename(proc["decisions"]))) if proc["decisions"] else None)
        skip = MaskSet(self.settings["skip"], ctx.profile.modules)
        # Запись о прошлом (historical docs.links): раздел, который тогда был, — верен;
        # номера решений не исчезают — их ссылки проверяются.
        links = ctx.profile.checks.get(Links.ID) or default_settings(Links)
        historical = MaskSet(links["historical"], ctx.profile.modules)
        heads = {}
        counts = {k["name"]: 0 for k in kinds}
        for path in files:
            if path == ctx.profile.baseline or skip.match(path) or repo.is_binary(path):
                continue  # базовая линия цитирует находки (V-19)
            lines = repo.text(path).splitlines()
            fenced = textscan.fenced(lines) if path.endswith(".md") else set()
            past = historical.match(path)
            for n, line in enumerate(lines, 1):
                if n in fenced:
                    continue
                for k in kinds:
                    if k["context"] is not None and not k["context"].search(line):
                        continue
                    for m in k["ref"].finditer(line):
                        if foreign_rx and foreign_rx.search(line[max(0, m.start() - window):m.start()]):
                            continue  # решение другого проекта
                        scanned += 1
                        counts[k["name"]] += 1
                        key = _key(m)
                        if key not in k["defs"]:
                            findings.append(Finding(
                                f"undefined-{k['name']}", f"{m.group(0)} не определено в {k['where']}",
                                path=path, line=n, evidence=m.group(0),
                                hint=f"исправить ссылку или определить в {k['where']}"))
                section_like = not past and ".md" in line and ("«" in line or "§" in line)
                for rx, rule in ((SECTION_REF, "missing-section"), (PARA_REF, "missing-paragraph")):
                    for m in (rx.finditer(line) if section_like else ()):
                        if m.group(1):
                            continue  # документ другого репозитория (V-17)
                        target = self._resolve(m.group(2), path, names, repo)
                        if target is None:
                            continue  # документа нет в репозитории — не наш раздел
                        scanned += 1
                        if target not in heads:
                            heads[target] = textscan.sections(repo.text(target) or "")
                        if not self._has(heads[target], m.group(3), rule):
                            what = f"«{m.group(3)}»" if rule == "missing-section" else f"§{m.group(3)}"
                            findings.append(Finding(rule, f"в {target} нет раздела {what}",
                                                    path=path, line=n, evidence=f"{target}\0{m.group(3)}",
                                                    hint="исправить ссылку или заголовок"))
                if swapped and context and context.search(line):
                    for m in swapped.finditer(line):
                        findings.append(Finding(
                            "homoglyph", f"«{m.group(0)}» — номер решения набран буквой другого алфавита",
                            path=path, line=n, severity=WARNING, evidence=m.group(0),
                            hint="перенабрать букву номера (R-06)"))
        for k in kinds:
            if not k["defs"] or not counts[k["name"]]:
                findings.append(Finding(
                    "empty-kind", f"вид ссылок «{k['name']}»: определений {len(k['defs'])}, ссылок "
                                  f"{counts[k['name']]} — опечатка в регэкспе? (V-07)",
                    path=_rel(ctx, ctx.profile.path), evidence=k["name"],
                    hint="исправить регэксп вида или allow_empty с причиной"))
        return Outcome(scanned, findings, f"ссылок {scanned}: " + ", ".join(f"{k} {v}" for k, v in counts.items()))

    def _kinds(self, ctx, files):
        proc, repo, out = ctx.profile.process, ctx.repo, []
        if proc["decisions"] and proc["decision_heading"] and proc["decision_ref"]:
            text = repo.text(proc["decisions"]) if proc["decisions"] in set(files) else ""
            out.append({"name": "decision", "ref": proc["decision_ref"], "context": None,
                        "defs": {_key(m) for m in proc["decision_heading"].finditer(text or "")},
                        "where": proc["decisions"]})
        for k in self.settings["kind"]:
            mask = MaskSet(k["defined_in"], ctx.profile.modules)
            defs = set()
            for p in files:
                if mask.match(p) and not repo.is_binary(p):
                    defs |= {_key(m) for m in k["definition"].finditer(repo.text(p))}
            out.append({"name": k["name"], "ref": k["ref"], "context": k["context"], "defs": defs,
                        "where": ", ".join(k["defined_in"])})
        return out

    @staticmethod
    def _swapped(proc):
        """Регэксп номера решения с буквами другого алфавита — для правила homoglyph."""
        rx = proc["decision_ref"]
        if rx is None:
            return None
        src = rx.pattern
        out, i, changed = [], 0, False
        while i < len(src):
            if src[i] == "\\" and i + 1 < len(src):
                out.append(src[i:i + 2])
                i += 2
                continue
            ch = src[i]
            if ch in HOMOGLYPHS and not (i > 0 and src[i - 1] == "?"):
                out.append(HOMOGLYPHS[ch])
                changed = True
            else:
                out.append(ch)
            i += 1
        return re.compile("".join(out)) if changed else None

    @staticmethod
    def _resolve(doc, here, names, repo):
        if "/" in doc:
            for base in ("", os.path.dirname(here)):
                cand = os.path.normpath(os.path.join(base, doc)).replace(os.sep, "/")
                if os.path.isfile(repo.abspath(cand)):
                    return cand
            return None
        same = os.path.join(os.path.dirname(here), doc).replace(os.sep, "/")
        if os.path.isfile(repo.abspath(same)):
            return same
        found = names.get(doc, [])
        return found[0] if len(found) == 1 else None

    @staticmethod
    def _has(heads, want, rule):
        if rule == "missing-section":
            return any(want.strip().lower() in h.lower() for h in heads)
        return any(re.match(r"(§\s*)?" + re.escape(want) + r"(?=[.\s]|$)", h) for h in heads)
