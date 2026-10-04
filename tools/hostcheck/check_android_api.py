#!/usr/bin/env python3
"""Модули ядра — только с API, которые есть на Android minSdk.

Модули ядра (diagnostics … connection) — чистый Kotlin/JVM: компилятор
JVM не знает, что их код потом работает на Android 8.0, и пропустит,
например, `ByteArrayOutputStream.toString(Charset)` (есть только с API
33). Android Lint такие вызовы в JVM-модулях не ловит (проверено
4 октября 2026 пробой `java.util.List.of` в :core). Эта проверка
закрывает дыру:

  * читает пул констант class-файлов модулей ядра (build/classes);
  * каждую ссылку на класс, метод или поле из java.*, javax.*, android.*
    сверяет с `platforms/android-N/data/api-versions.xml` из Android SDK,
    с учётом наследования (метод может быть объявлен у предка);
  * ссылка, которой нет на Android или которая появилась позже minSdk,
    — ошибка; исключения — `android_api_allow.txt` (бэкпорты D8 и
    invokedynamic, которые D8 переписывает сам), каждое с причиной.

    python3 tools/hostcheck/check_android_api.py [--min-sdk 26] [--sdk DIR]

Код возврата: 0 — чисто; 1 — найдены нарушения; 2 — нет SDK или классов
(без SDK шаг падает с причиной, а не пропускается — docs/PROCESS.md).
"""
import os
import re
import struct
import sys
import xml.etree.ElementTree as ET

PROJ = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MODULES = ["diagnostics", "core", "routing", "config", "engine", "network", "storage", "subscriptions", "tunnel", "connection"]
ALLOW_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "android_api_allow.txt")
CHECKED = ("java/", "javax/", "android/")


def class_refs(path):
    """(классы, [(класс, имя, дескриптор, 'm'|'f')]) из пула констант."""
    with open(path, "rb") as f:
        data = f.read()
    if data[:4] != b"\xca\xfe\xba\xbe":
        return set(), []
    count = struct.unpack(">H", data[8:10])[0]
    i, idx = 10, 1
    utf8, classes, nat, refs = {}, {}, {}, []
    while idx < count:
        tag = data[i]
        if tag == 1:
            ln = struct.unpack(">H", data[i + 1:i + 3])[0]
            utf8[idx] = data[i + 3:i + 3 + ln].decode("utf-8", "replace")
            i += 3 + ln
        elif tag == 7:
            classes[idx] = struct.unpack(">H", data[i + 1:i + 3])[0]
            i += 3
        elif tag in (9, 10, 11):
            c, n = struct.unpack(">HH", data[i + 1:i + 5])
            refs.append((tag, c, n))
            i += 5
        elif tag == 12:
            nat[idx] = struct.unpack(">HH", data[i + 1:i + 5])
            i += 5
        elif tag in (3, 4):
            i += 5
        elif tag in (5, 6):
            i += 9
            idx += 1
        elif tag in (8, 16, 19, 20):
            i += 3
        elif tag == 15:
            i += 4
        elif tag in (17, 18):
            i += 5
        else:
            raise ValueError(f"{path}: неизвестный тег пула констант {tag}")
        idx += 1
    names = {utf8[v] for v in classes.values() if v in utf8}
    out = []
    for tag, c, n in refs:
        cname = utf8.get(classes.get(c))
        nm, desc = nat.get(n, (None, None))
        if cname and nm in utf8:
            out.append((cname, utf8[nm], utf8.get(desc, ""), "f" if tag == 9 else "m"))
    return names, out


class Api:
    def __init__(self, xml_path):
        self.classes = {}
        for c in ET.parse(xml_path).getroot().iter("class"):
            since = int(c.get("since", "1"))
            methods = {m.get("name"): int(m.get("since", since)) for m in c.findall("method")}
            fields = {fl.get("name"): int(fl.get("since", since)) for fl in c.findall("field")}
            supers = [e.get("name") for e in c.findall("extends")] + [e.get("name") for e in c.findall("implements")]
            self.classes[c.get("name")] = (since, methods, fields, supers)

    def class_since(self, name):
        c = self.classes.get(name)
        return c[0] if c else None

    def member_since(self, cls, key, kind, seen=None):
        seen = seen or set()
        if cls in seen or cls not in self.classes:
            return None
        seen.add(cls)
        since, methods, fields, supers = self.classes[cls]
        table = methods if kind == "m" else fields
        if key in table:
            return max(since, table[key])
        for s in supers:
            r = self.member_since(s, key, kind, seen)
            if r is not None:
                return r
        return None


def load_allow():
    allow = []
    if os.path.isfile(ALLOW_FILE):
        for line in open(ALLOW_FILE, encoding="utf-8"):
            line = line.split("#", 1)[0].strip()
            if line:
                allow.append(re.compile(line))
    return allow


def main(argv):
    min_sdk = 26
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if "--min-sdk" in argv:
        min_sdk = int(argv[argv.index("--min-sdk") + 1])
    if "--sdk" in argv:
        sdk = argv[argv.index("--sdk") + 1]
    if not sdk or not os.path.isdir(os.path.join(sdk, "platforms")):
        print("✗ check_android_api: нет Android SDK (ANDROID_HOME) — уровни API не проверить")
        return 2
    platforms = sorted((p for p in os.listdir(os.path.join(sdk, "platforms")) if re.fullmatch(r"android-\d+", p)),
                       key=lambda p: int(p.split("-")[1]))
    if not platforms:
        print("✗ check_android_api: в SDK нет платформы android-N")
        return 2
    api = Api(os.path.join(sdk, "platforms", platforms[-1], "data", "api-versions.xml"))
    allow = load_allow()

    problems, scanned = [], 0
    for m in MODULES:
        root = os.path.join(PROJ, m, "build", "classes", "kotlin", "main")
        if not os.path.isdir(root):
            print(f"✗ check_android_api: нет классов {m} — сначала сборка (./gradlew :{m}:classes)")
            return 2
        for dirpath, _, files in os.walk(root):
            for fn in files:
                if not fn.endswith(".class"):
                    continue
                path = os.path.join(dirpath, fn)
                scanned += 1
                names, refs = class_refs(path)
                rel = os.path.relpath(path, PROJ)
                for cname in names:
                    if cname.startswith("[") or not cname.startswith(CHECKED):
                        continue
                    s = api.class_since(cname)
                    sig = cname
                    if (s is None or s > min_sdk) and not any(a.search(sig) for a in allow):
                        problems.append(f"{rel}: класс {cname} — {'нет на Android' if s is None else f'API {s}'}")
                for cname, name, desc, kind in refs:
                    if cname.startswith("[") or not cname.startswith(CHECKED):
                        continue
                    key = name + desc if kind == "m" else name
                    s = api.member_since(cname, key, kind)
                    sig = f"{cname}.{key}"
                    if (s is None or s > min_sdk) and not any(a.search(sig) for a in allow):
                        problems.append(f"{rel}: {sig} — {'нет на Android' if s is None else f'API {s}'}")
    if problems:
        print(f"✗ Модули ядра используют API, которых нет на Android {min_sdk}:")
        for p in sorted(set(problems)):
            print("    " + p)
        print("  Заменить вызов или, если D8 его переписывает (бэкпорт), добавить с причиной в tools/hostcheck/android_api_allow.txt")
        return 1
    print(f"✓ API Android: {scanned} классов модулей ядра — только API ≤ {min_sdk} ({platforms[-1]})")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
