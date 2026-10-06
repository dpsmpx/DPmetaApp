#!/usr/bin/env python3
"""Манифест и сетевые настройки приложения — по модели безопасности.

Проверяет `app/src/main/AndroidManifest.xml` и связанные XML
(docs/SECURITY_MODEL.md, docs/research/ANDROID_NETWORK_MODEL.md, D-17, D-19):

  * разрешения — ровно утверждённый набор (новое разрешение — правка
    этого списка с причиной, а не случайность из зависимости);
  * резервные копии выключены: `allowBackup=false`,
    `fullBackupContent=false`, правила извлечения исключают все домены
    и для облака, и для переноса на другое устройство;
  * открытый HTTP запрещён (`usesCleartextTraffic=false` и
    network_security_config), доверие — только системным CA;
  * нет `debuggable`; экспортировано только то, что нужно: запуск
    Activity и VpnService под `BIND_VPN_SERVICE`; у экспортированного
    сервиса всегда есть `android:permission`;
  * один процесс (D-30): ни у приложения, ни у компонентов нет
    `android:process` — ядро, хранилище и журнал рассчитаны на один
    процесс.

    python3 tools/hostcheck/check_manifest.py

Код возврата: 0 — чисто; 1 — нарушения; 2 — нет манифеста.
"""
import os
import sys
import xml.etree.ElementTree as ET

PROJ = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MAIN = os.path.join(PROJ, "app", "src", "main")
A = "{http://schemas.android.com/apk/res/android}"

# Утверждённые разрешения — с причиной каждого.
PERMISSIONS = {
    "android.permission.INTERNET": "сокеты ядра к серверу и загрузка подписок",
    "android.permission.FOREGROUND_SERVICE": "сервис туннеля работает в foreground",
    "android.permission.FOREGROUND_SERVICE_SPECIAL_USE": "тип foreground-сервиса specialUse (Android 14+)",
    "android.permission.POST_NOTIFICATIONS": "постоянное уведомление, пока туннель поднят (Android 13+)",
    "android.permission.ACCESS_NETWORK_STATE": "контекст сети для адаптивного выбора: тип и возможности сети вне "
                                               "туннеля (docs/ADAPTIVE_ENGINE.md)",
}
BACKUP_DOMAINS = {"root", "file", "database", "sharedpref", "external"}
EXPORTED_OK = {
    ".app.MainActivity": "точка входа (LAUNCHER)",
    ".app.DPpassVpnService": "VpnService, связывает только система",
}


def resource(ref):
    """`@xml/имя` → путь к файлу ресурса."""
    if not ref or not ref.startswith("@xml/"):
        return None
    return os.path.join(MAIN, "res", "xml", ref[len("@xml/"):] + ".xml")


def main():
    manifest = os.path.join(MAIN, "AndroidManifest.xml")
    if not os.path.isfile(manifest):
        print("✗ check_manifest: нет app/src/main/AndroidManifest.xml")
        return 2
    root = ET.parse(manifest).getroot()
    app = root.find("application")
    problems = []

    declared = {p.get(A + "name") for p in root.findall("uses-permission")} | {
        p.get(A + "name") for p in root.findall("uses-permission-sdk-23")}
    for p in sorted(declared - set(PERMISSIONS)):
        problems.append(f"разрешение {p} не утверждено (список и причины — tools/hostcheck/check_manifest.py)")
    for p in sorted(set(PERMISSIONS) - declared):
        problems.append(f"нет разрешения {p}: {PERMISSIONS[p]}")

    def attr(name):
        return app.get(A + name) if app is not None else None

    if app is None:
        problems.append("нет <application>")
    else:
        if attr("allowBackup") != "false":
            problems.append("android:allowBackup должен быть false: подписки и ключи не уезжают в резервную копию (D-19)")
        if attr("fullBackupContent") != "false":
            problems.append("android:fullBackupContent должен быть false (Android ≤ 11)")
        if attr("usesCleartextTraffic") != "false":
            problems.append("android:usesCleartextTraffic должен быть false")
        if attr("process") is not None:
            problems.append("<application android:process>: приложение работает в одном процессе (D-30)")
        if attr("debuggable") is not None:
            problems.append("android:debuggable в манифесте запрещён — его ставит тип сборки")

        rules = resource(attr("dataExtractionRules"))
        if not rules or not os.path.isfile(rules):
            problems.append("нет android:dataExtractionRules (Android 12+)")
        else:
            r = ET.parse(rules).getroot()
            for section in ("cloud-backup", "device-transfer"):
                s = r.find(section)
                excluded = {e.get("domain") for e in s.findall("exclude")} if s is not None else set()
                included = s.findall("include") if s is not None else []
                if included or excluded != BACKUP_DOMAINS:
                    missing = ", ".join(sorted(BACKUP_DOMAINS - excluded)) or "—"
                    problems.append(f"{os.path.relpath(rules, PROJ)}: <{section}> должен исключать все домены (не хватает: {missing}) и ничего не включать")

        nsc = resource(attr("networkSecurityConfig"))
        if not nsc or not os.path.isfile(nsc):
            problems.append("нет android:networkSecurityConfig")
        else:
            n = ET.parse(nsc).getroot()
            rel = os.path.relpath(nsc, PROJ)
            base = n.find("base-config")
            if base is None or base.get("cleartextTrafficPermitted") != "false":
                problems.append(f"{rel}: base-config должен запрещать открытый HTTP")
            for el in n.iter():
                if el.get("cleartextTrafficPermitted") == "true":
                    problems.append(f"{rel}: <{el.tag}> разрешает открытый HTTP")
                if el.tag == "certificates" and el.get("src") not in ("system",):
                    problems.append(f"{rel}: доверие к сертификатам «{el.get('src')}» — только system (подмена TLS через пользовательский CA)")
            if n.find("debug-overrides") is not None:
                problems.append(f"{rel}: debug-overrides запрещены")

        for kind in ("activity", "activity-alias", "service", "receiver", "provider"):
            for el in app.findall(kind):
                name = el.get(A + "name")
                if el.get(A + "process") is not None:
                    problems.append(f"<{kind} {name}> android:process: компоненты — в процессе приложения (D-30)")
                exported = el.get(A + "exported")
                if exported is None and el.find("intent-filter") is not None:
                    problems.append(f"<{kind} {name}>: android:exported не указан явно")
                if exported != "true":
                    continue
                if name not in EXPORTED_OK:
                    problems.append(f"<{kind} {name}> экспортирован без причины (список — tools/hostcheck/check_manifest.py)")
                if kind in ("service", "receiver", "provider") and not el.get(A + "permission"):
                    problems.append(f"<{kind} {name}> экспортирован без android:permission")
                actions = {a.get(A + "name") for f in el.findall("intent-filter") for a in f.findall("action")}
                if "android.net.VpnService" in actions and el.get(A + "permission") != "android.permission.BIND_VPN_SERVICE":
                    problems.append(f"<{kind} {name}>: VpnService должен требовать BIND_VPN_SERVICE")

    if problems:
        print("✗ Манифест:")
        for p in problems:
            print("    " + p)
        return 1
    print(f"✓ Манифест: {len(PERMISSIONS)} утверждённых разрешения, без резервных копий и открытого HTTP, один процесс, экспорт — только {', '.join(EXPORTED_OK)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
