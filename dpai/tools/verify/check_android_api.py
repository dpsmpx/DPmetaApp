#!/usr/bin/env python3
"""API Java в модулях приложения — есть ли в Android minSdk (D-28, D-34).

Байт-код Java 17 принимает D8, но наличие методов он не проверяет: вызов
метода JDK, которого нет в Android 11, падает только на устройстве
(`SplittableRandom.nextBytes`, 7 октября 2026). Lint проверяет код модуля
`android`, а не модулей ядра, поэтому сверка — здесь:

  * модули — замыкание зависимостей `android` по таблице
    docs/ARCHITECTURE.md, §2; их собранные jar;
  * обращения к `java.*` и `javax.*` (вызовы, поля, классы) — из
    `javap -c -p`; invokedynamic пропускается: его переписывает D8;
  * каждое — в `android.jar` уровня minSdk с учётом наследования.

Код, который на телефоне не исполняется (запуск процессов, агент target),
перечислен в tools/verify/registry/android_api_allow.txt с причиной.

    python3 tools/verify/check_android_api.py   # после ./gradlew build
    python3 tools/verify/check_android_api.py --jars   # только выбор jar, без SDK

Jar модуля — только текущей версии (`gradle.properties`): после смены
версии прежние jar остаются в build/libs, и сверка по ним находила
обращения уже удалённого кода (1 ноября 2026: `AgentRuntime` из
0.1.0-dev.1 с `ProcessHandle` при чистом 0.1.0-dev.5).

Код возврата: 0 — чисто; 1 — нарушения или нет сборки/SDK.
"""
import glob
import os
import re
import subprocess
import sys

from common import Findings, main, read, root

MIN_SDK = 30
ALLOW = "tools/verify/registry/android_api_allow.txt"
TABLE = re.compile(r"<!-- check_modules: begin -->(.*?)<!-- check_modules: end -->", re.S)
REF = re.compile(r"//\s*(Method|InterfaceMethod|Field|class)\s+\"?((?:java|javax)/[\w/$]+)\"?(?:\.(\"?<?[\w$]+>?\"?):(\S+))?")
HEADER = re.compile(r"^(?:[\w ]*\s)?(?:class|interface|enum)\s+([\w.$]+)(?:<[^{]*?>)?(?:\s+extends\s+([\w.$<>, ?]+?))?(?:\s+implements\s+([\w.$<>, ?]+?))?\s*\{")


def javap():
    home = os.environ.get("JAVA_HOME")
    exe = "javap.exe" if os.name == "nt" else "javap"
    return os.path.join(home, "bin", exe) if home else "javap"


def android_jar():
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    local = os.path.join(root(), "local.properties")
    if not sdk and os.path.isfile(local):
        for line in open(local, encoding="utf-8"):
            if line.startswith("sdk.dir="):
                sdk = line.split("=", 1)[1].strip().replace("\\:", ":").replace("\\\\", "\\")
    jar = os.path.join(sdk, "platforms", f"android-{MIN_SDK}", "android.jar") if sdk else None
    return jar if jar and os.path.isfile(jar) else None


def closure():
    m = TABLE.search(read("docs/ARCHITECTURE.md"))
    table = {}
    for line in m.group(1).splitlines():
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) >= 3 and cells[0].startswith("`"):
            table[cells[0].strip("`")] = set(re.findall(r"`([\w/-]+)`", cells[2]))
    seen, todo = set(), list(table.get("android", ()))
    while todo:
        x = todo.pop()
        if x not in seen:
            seen.add(x)
            todo.extend(table.get(x, ()))
    return sorted(seen)


def project_version():
    m = re.search(r"^version\s*=\s*(\S+)\s*$", read("gradle.properties"), re.M)
    return m.group(1) if m else None


def module_jars(m, version):
    """Собранные jar модуля [m] версии [version]; прежние версии в build/libs не берутся."""
    found = glob.glob(os.path.join(root(), m, "build", "libs", "*.jar"))
    return sorted(p for p in found if os.path.basename(p).endswith(f"-{version}.jar"))


def select_jars(f):
    """Jar всех модулей замыкания `android`; нет jar текущей версии — находка."""
    version = project_version()
    if not version:
        f.add("gradle.properties", "нет строки version=: какой jar сверять, неизвестно")
        return [], []
    mods = closure()
    jars = []
    for m in mods:
        found = module_jars(m, version)
        if not found:
            f.add(f"{m}/build/libs", f"нет собранного jar версии {version}: сначала ./gradlew build")
        jars += found
    return mods, jars


def run_javap(args):
    r = subprocess.run([javap(), *args], capture_output=True, text=True, encoding="utf-8", errors="replace")
    return r.stdout + r.stderr


def references(jar):
    """(наш класс, вид, владелец, имя, дескриптор) для обращений к java.* и javax.*."""
    import zipfile
    names = [n[:-6].replace("/", ".") for n in zipfile.ZipFile(jar).namelist()
             if n.endswith(".class") and not n.endswith("module-info.class")]
    refs = set()
    for i in range(0, len(names), 200):
        current = None
        for line in run_javap(["-c", "-p", "-cp", jar, *names[i:i + 200]]).splitlines():
            h = HEADER.match(line.strip())
            if h and not line.startswith(" "):
                current = h.group(1).replace(".", "/")
                continue
            if "invokedynamic" in line:
                continue
            r = REF.search(line)
            if r and current:
                kind, owner, name, desc = r.groups()
                refs.add((current, kind, owner, (name or "").strip('"'), desc or ""))
    return refs


class AndroidApi:
    """Члены классов android.jar с наследованием; javap пачками."""

    def __init__(self, jar):
        self.jar = jar
        self.members = {}   # класс → {(имя, дескриптор)}
        self.supers = {}    # класс → [суперклассы и интерфейсы]

    def load(self, classes):
        todo = [c for c in classes if c not in self.members]
        while todo:
            batch, todo = todo[:150], todo[150:]
            out = run_javap(["-p", "-s", "-bootclasspath", self.jar, *[c.replace("/", ".") for c in batch]])
            for c in batch:
                self.members.setdefault(c, None)
            current, pending = None, None
            for line in out.splitlines():
                s = line.strip()
                h = HEADER.match(s)
                if h and not line.startswith(" "):
                    current = h.group(1).replace(".", "/")
                    parents = []
                    for grp in (h.group(2), h.group(3)):
                        if grp:
                            parents += [p.strip().split("<")[0].replace(".", "/") for p in re.split(r",(?![^<]*>)", grp)]
                    if not parents and current != "java/lang/Object":
                        parents = ["java/lang/Object"]
                    self.members[current] = set()
                    self.supers[current] = parents
                    todo += [p for p in parents if p not in self.members and p not in todo]
                    continue
                if current is None:
                    continue
                if s.startswith("descriptor:") and pending:
                    self.members[current].add((pending, s.split(":", 1)[1].strip()))
                    pending = None
                    continue
                m = re.search(r"([\w$]+)\s*\(", s)
                if m and s.endswith(";"):
                    name = m.group(1)
                    simple = current.split("/")[-1].split("$")[-1]
                    pending = "<init>" if name == simple else name
                elif s.endswith(";") and not s.startswith("descriptor:"):
                    pending = s.rstrip(";").split()[-1]

    def has_class(self, c):
        self.load([c])
        return self.members.get(c) is not None

    def has_member(self, c, name, desc, seen=None):
        seen = seen or set()
        if c in seen:
            return False
        seen.add(c)
        self.load([c])
        mem = self.members.get(c)
        if mem is None:
            return False
        if (name, desc) in mem:
            return True
        return any(self.has_member(p, name, desc, seen) for p in self.supers.get(c, []))


def allowlist():
    rules = []
    path = os.path.join(root(), ALLOW)
    for line in open(path, encoding="utf-8"):
        line = line.split("#", 1)[0].strip()
        if line:
            rules.append(line.split()[0])
    return rules


def check_jars():
    """Только выбор jar (`--jars`): самотест правила версии без Android SDK."""
    f = Findings("API Android: jar")
    mods, jars = select_jars(f)
    return f.finish(f"модулей — {len(mods)}, jar — {len(jars)}")


def check():
    f = Findings("API Android")
    jar = android_jar()
    if not jar:
        f.add("android.jar", f"нет платформы android-{MIN_SDK} в Android SDK: сверка API невозможна")
        return f.finish("")
    mods, jars = select_jars(f)
    if f.items:
        return f.finish("")
    allow = allowlist()
    api = AndroidApi(jar)
    refs = set()
    for j in jars:
        refs |= references(j)
    api.load(sorted({r[2] for r in refs}))
    checked = 0
    for ours, kind, owner, name, desc in sorted(refs):
        if any(ours.startswith(a) for a in allow):
            continue
        checked += 1
        if not api.has_class(owner):
            f.add(ours.replace("/", "."), f"класса {owner.replace('/', '.')} нет в Android {MIN_SDK}")
        elif kind != "class" and name and not api.has_member(owner, name, desc):
            f.add(ours.replace("/", "."), f"{owner.replace('/', '.')}.{name}{desc} нет в Android {MIN_SDK}")
    return f.finish(f"модулей — {len(mods)}, обращений к JDK — {checked}, всё есть в Android {MIN_SDK}")


if __name__ == "__main__":
    main(check_jars if "--jars" in sys.argv[1:] else check)
