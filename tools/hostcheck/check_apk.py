#!/usr/bin/env python3
"""Собранный APK — такой, как обещают исходники.

`check_manifest.py` проверяет манифест из исходников, но в APK попадает
итоговый манифест, слитый с манифестами зависимостей, и код всех
зависимостей. Эта проверка смотрит на сам APK (инструменты build-tools
Android SDK: aapt2 и dexdump):

  * итоговый манифест: ровно утверждённые разрешения
    (`check_manifest.PERMISSIONS`), без резервных копий и открытого HTTP,
    компоненты — только объявленные в исходном манифесте (зависимость не
    добавит свой provider или receiver), экспортировано только
    утверждённое, у сервиса VPN — BIND_VPN_SERVICE и тип specialUse;
  * код (dex): классы только из утверждённых корней пакетов — свой код
    и компоненты из матрицы лицензий; SDK аналитики, отчётов о падениях
    и прочий чужой код не попадут незамеченными (D-12, D-20). Классы
    `android.*`/`dalvik.*` допустимы только синтетические — это заглушки,
    которые D8 создаёт для классов платформы новее minSdk;
  * нативных библиотек до этапа 2 нет (с ядром Xray — вместе с проверкой
    выравнивания по 16 КБ, `TODO.md`);
  * размер APK печатается (бюджет по ABI — этап 2, docs/PERFORMANCE_BUDGET.md).

    python3 tools/hostcheck/check_apk.py [APK]
        по умолчанию app/build/outputs/apk/debug/app-debug.apk

Код возврата: 0 — чисто; 1 — нарушения; 2 — нет APK или build-tools
(в полном режиме hostcheck это ошибка, а не пропуск — docs/PROCESS.md).
"""
import os
import re
import subprocess
import sys
import tempfile
import zipfile
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from check_manifest import EXPORTED_OK, PERMISSIONS  # noqa: E402 — один список утверждённого

PROJ = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
APK = os.path.join(PROJ, "app", "build", "outputs", "apk", "debug", "app-debug.apk")
SOURCE_MANIFEST = os.path.join(PROJ, "app", "src", "main", "AndroidManifest.xml")
ANDROID_NS = "http://schemas.android.com/apk/res/android"
A = "{" + ANDROID_NS + "}"
SPECIAL_USE = 0x40000000  # ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE

# Корни пакетов, чьи классы могут быть в APK, — с причиной (строка матрицы лицензий).
CODE_ROOTS = {
    "com/dpsmpx/dppass/": "свой код",
    "kotlin/": "kotlin-stdlib (матрица, §1.1)",
    "kotlinx/": "kotlinx-coroutines (матрица, §1.1)",
    "_COROUTINE/": "kotlinx-coroutines: метки кадров стека (матрица, §1.1)",
    "org/jetbrains/annotations/": "org.jetbrains:annotations (матрица, §1.1)",
    "org/intellij/lang/annotations/": "org.jetbrains:annotations (матрица, §1.1)",
    "com/android/tools/r8/": "поддержка desugaring, которую D8 (AGP) добавляет в APK (матрица, §1.3)",
}
# Только синтетические: заглушки D8 для классов платформы, которых нет на minSdk.
SYNTHETIC_ONLY_ROOTS = ("android/", "dalvik/")
# Нативные библиотеки — пусто до этапа 2.
NATIVE_OK = {}


def sdk_dir():
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        d = os.environ.get(env)
        if d and os.path.isdir(d):
            return d
    local = os.path.join(PROJ, "local.properties")
    if os.path.isfile(local):
        for line in open(local, encoding="utf-8"):
            if line.startswith("sdk.dir="):
                d = line.split("=", 1)[1].strip()
                if os.path.isdir(d):
                    return d
    return None


def build_tools(sdk):
    """Каталог build-tools самой новой версии, где есть aapt2 и dexdump."""
    root = os.path.join(sdk, "build-tools")
    if not os.path.isdir(root):
        return None
    def version(v):
        return [int(x) if x.isdigit() else -1 for x in re.split(r"[.-]", v)]
    for v in sorted(os.listdir(root), key=version, reverse=True):
        d = os.path.join(root, v)
        if all(os.path.isfile(os.path.join(d, t)) for t in ("aapt2", "dexdump")):
            return d
    return None


def manifest_tree(aapt2, apk):
    """Итоговый манифест APK (`aapt2 dump xmltree`) → ElementTree с атрибутами android:…."""
    out = subprocess.run([aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", apk],
                         capture_output=True, text=True, check=True).stdout
    root, stack = None, []  # (отступ, элемент)
    for line in out.splitlines():
        m = re.match(r"^(\s*)([NEA]): (.*)$", line)
        if not m:
            continue
        indent, kind, rest = len(m.group(1)), m.group(2), m.group(3)
        if kind == "E":
            tag = rest.split(" ", 1)[0]
            while stack and stack[-1][0] >= indent:
                stack.pop()
            el = ET.Element(tag) if not stack else ET.SubElement(stack[-1][1], tag)
            root = root if root is not None else el
            stack.append((indent, el))
        elif kind == "A" and stack:
            am = re.match(r'^(?:(\S+?):)?([\w-]+)(?:\(0x[0-9a-f]+\))?=(?:"((?:[^"\\]|\\.)*)"|(\S+))', rest)
            if am:
                ns, name = am.group(1), am.group(2)
                value = am.group(3) if am.group(3) is not None else am.group(4)
                key = A + name if ns == ANDROID_NS else name
                stack[-1][1].set(key, value)
    return root


def dex_classes(dexdump, apk):
    """[(дескриптор вида com/x/Y, синтетический ли)] из всех classes*.dex."""
    classes = []
    with tempfile.TemporaryDirectory() as tmp, zipfile.ZipFile(apk) as z:
        for name in sorted(n for n in z.namelist() if re.fullmatch(r"classes\d*\.dex", n)):
            path = z.extract(name, tmp)
            out = subprocess.run([dexdump, "-l", "plain", path], capture_output=True, text=True, check=True).stdout
            current = None
            for line in out.splitlines():
                m = re.match(r"^\s*Class descriptor\s*:\s*'L([^;]+);'", line)
                if m:
                    current = m.group(1)
                    continue
                f = re.match(r"^\s*Access flags\s*:\s*0x([0-9a-f]+)", line)
                if f and current is not None:
                    classes.append((current, bool(int(f.group(1), 16) & 0x1000)))
                    current = None
    return classes


def main():
    apk = sys.argv[1] if len(sys.argv) > 1 else APK
    if not os.path.isfile(apk):
        print(f"✗ check_apk: нет {os.path.relpath(apk, PROJ)} — сначала ./gradlew :app:assembleDebug")
        return 2
    sdk = sdk_dir()
    tools = build_tools(sdk) if sdk else None
    if not tools:
        print("✗ check_apk: нет build-tools Android SDK с aapt2 и dexdump (ANDROID_HOME или sdk.dir в local.properties)")
        return 2
    problems = []

    # ---- итоговый манифест ----
    root = manifest_tree(os.path.join(tools, "aapt2"), apk)
    package = root.get("package", "")
    app = root.find("application")
    declared = {p.get(A + "name") for p in root.iter("uses-permission")} | {
        p.get(A + "name") for p in root.iter("uses-permission-sdk-23")}
    for p in sorted(declared - set(PERMISSIONS)):
        problems.append(f"манифест APK: разрешение {p} не утверждено — пришло из зависимости? (список — check_manifest.py)")
    for p in sorted(set(PERMISSIONS) - declared):
        problems.append(f"манифест APK: нет разрешения {p}")
    if app is None:
        problems.append("манифест APK: нет <application>")
    else:
        for attr, want in (("allowBackup", "false"), ("fullBackupContent", "false"), ("usesCleartextTraffic", "false")):
            if app.get(A + attr) != want:
                problems.append(f"манифест APK: android:{attr} = {app.get(A + attr)}, нужно {want}")
        for attr in ("networkSecurityConfig", "dataExtractionRules"):
            if not app.get(A + attr):
                problems.append(f"манифест APK: нет android:{attr}")

        def full(name):
            return package + name if name.startswith(".") else name

        source = ET.parse(SOURCE_MANIFEST).getroot().find("application")
        own = {full(el.get(A + "name")) for el in source} if source is not None else set()
        exported_ok = {full(n) for n in EXPORTED_OK}
        for el in app:
            if el.tag not in ("activity", "activity-alias", "service", "receiver", "provider"):
                continue
            name = full(el.get(A + "name", ""))
            if name not in own:
                problems.append(f"манифест APK: <{el.tag} {name}> не объявлен в исходном манифесте — добавлен зависимостью")
            if el.get(A + "exported") == "true":
                if name not in exported_ok:
                    problems.append(f"манифест APK: <{el.tag} {name}> экспортирован без причины")
                if el.tag in ("service", "receiver", "provider") and not el.get(A + "permission"):
                    problems.append(f"манифест APK: <{el.tag} {name}> экспортирован без android:permission")
            actions = {a.get(A + "name") for f in el.iter("intent-filter") for a in f.iter("action")}
            if "android.net.VpnService" in actions:
                if el.get(A + "permission") != "android.permission.BIND_VPN_SERVICE":
                    problems.append(f"манифест APK: {name} — VpnService без BIND_VPN_SERVICE")
                fgs = el.get(A + "foregroundServiceType", "0")
                if not int(fgs, 16 if fgs.startswith("0x") else 10) & SPECIAL_USE:
                    problems.append(f"манифест APK: {name} — тип foreground-сервиса не specialUse (D-17)")

    # ---- код ----
    classes = dex_classes(os.path.join(tools, "dexdump"), apk)
    by_root = {}
    for desc, synthetic in classes:
        root_ok = next((r for r in CODE_ROOTS if desc.startswith(r)), None)
        if root_ok:
            by_root[root_ok] = by_root.get(root_ok, 0) + 1
        elif desc.startswith(SYNTHETIC_ONLY_ROOTS) and synthetic:
            by_root["заглушки платформы (D8)"] = by_root.get("заглушки платформы (D8)", 0) + 1
        else:
            problems.append(f"dex: класс {desc.replace('/', '.')} — не из утверждённых корней "
                            "(свой код и компоненты матрицы лицензий; новый корень — с причиной в check_apk.py)")
    if not classes:
        problems.append("dex: в APK не найдено ни одного класса")

    # ---- нативные библиотеки и размер ----
    with zipfile.ZipFile(apk) as z:
        natives = [n for n in z.namelist() if n.startswith("lib/") and n.endswith(".so")]
    for n in natives:
        if os.path.basename(n) not in NATIVE_OK:
            problems.append(f"нативная библиотека {n}: до этапа 2 их нет; с ядром — вместе с проверкой выравнивания 16 КБ")
    size = os.path.getsize(apk)

    if problems:
        print("✗ APK:")
        for p in problems[:60]:
            print("    " + p)
        if len(problems) > 60:
            print(f"    … и ещё {len(problems) - 60}")
        return 1
    roots = ", ".join(f"{r.rstrip('/').replace('/', '.')} {n}" for r, n in sorted(by_root.items(), key=lambda kv: -kv[1]))
    print(f"✓ APK {os.path.relpath(apk, PROJ)}: {size / 1024 / 1024:.1f} МиБ; манифест — утверждённые разрешения и компоненты; "
          f"{len(classes)} классов только из утверждённых корней ({roots}); нативных библиотек нет")
    return 0


if __name__ == "__main__":
    sys.exit(main())
