#!/usr/bin/env python3
"""Эталоны компилятора Xray проверяет сам Xray (этап 2, docs/TESTING.md, «Эталоны Xray»).

Шаг hostcheck «Xray». Эталоны `tests/data/xray/` пишет и сверяет с
компилятором `CorpusTest` (Kotlin); здесь их принимает настоящее ядро
закреплённой версии:

  * сборка: upstream `xray` (`github.com/xtls/xray-core/main`) и
    собственный `dppass-xray-probe` — из `tools/xray/go.mod` и `go.sum`
    (`-mod=readonly`: суммы модулей не меняются, отключённая сверка с
    sum.golang.org — ошибка, T10); версия ядра — та, что в
    `gradle/libs.versions.toml` (D-18);
  * геоданные — синтетические, из таблиц корпуса (`*.probe.json`); код
    без таблицы — пустой: ни одно условие по нему не выполняется, как у
    `GeoResolver.None`;
  * каждая конфигурация-эталон проходит `xray run -test` (сборка
    конфигурации без запуска, `main/run.go`);
  * каждый `*.probe.json`: решения маршрутизации Xray совпадают с
    `RouteEvaluator` (`dppass-xray-probe route`).

Нужен Go (любой 1.21+: нужная версия toolchain скачивается сама по
`go.mod`, проверяется по sum.golang.org) и доступ к proxy.golang.org при
первой сборке.

    python3 tools/xray/check_xray.py

Код возврата: 0 — всё принято и совпало; 1 — расхождение или ошибка; 3 — нет Go.
"""
import json
import os
import re
import shutil
import subprocess
import sys
import tomllib

PROJ = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TOOL = os.path.join(PROJ, "tools", "xray")
GOLDEN = os.path.join(PROJ, "tests", "data", "xray")
CATALOG = os.path.join(PROJ, "gradle", "libs.versions.toml")
OUT = os.path.join(PROJ, "build", "xray")
GEO_RULE = re.compile(r'"(geoip|geosite):([^"]+)"')
# Эти переменные выключают сверку модулей с sum.golang.org или пускают мимо прокси без неё.
UNSAFE_ENV = ("GONOSUMDB", "GONOSUMCHECK", "GONOSUMCHECKS", "GOINSECURE", "GOPRIVATE", "GONOPROXY")


def fail(msg):
    print(f"✗ {msg}")
    return 1


def go_env():
    env = dict(os.environ)
    for k in UNSAFE_ENV:
        if env.get(k):
            raise SystemExit(fail(f"{k}={env[k]}: сверка модулей Go с sum.golang.org выключена — проверка не запускается (T10)"))
    if env.get("GOSUMDB", "").strip().lower() == "off":
        raise SystemExit(fail("GOSUMDB=off: сверка модулей Go выключена — проверка не запускается (T10)"))
    if "-insecure" in env.get("GOFLAGS", ""):
        raise SystemExit(fail("GOFLAGS содержит -insecure — проверка не запускается (T10)"))
    env["GOFLAGS"] = "-mod=readonly"
    # Версию toolchain задаёт go.mod; local запретил бы её скачать, а чужая версия — не та сборка.
    env["GOTOOLCHAIN"] = "auto"
    return env


def run(cmd, env=None, cwd=None):
    return subprocess.run(cmd, cwd=cwd, env=env, capture_output=True, text=True)


def build(env):
    os.makedirs(OUT, exist_ok=True)
    xray = os.path.join(OUT, "xray")
    probe = os.path.join(OUT, "dppass-xray-probe")
    for target, pkg in ((xray, "github.com/xtls/xray-core/main"), (probe, ".")):
        r = run(["go", "build", "-trimpath", "-o", target, pkg], env=env, cwd=TOOL)
        if r.returncode != 0:
            raise SystemExit(fail(f"сборка {pkg}:\n{r.stdout}{r.stderr}"))
    return xray, probe


def expected_version():
    with open(CATALOG, "rb") as f:
        versions = tomllib.load(f).get("versions", {})
    v = versions.get("xray")
    if not v:
        raise SystemExit(fail("gradle/libs.versions.toml: нет версии xray"))
    return v


def goldens():
    configs, probes = [], []
    for dirpath, _, files in os.walk(GOLDEN):
        for fn in sorted(files):
            path = os.path.join(dirpath, fn)
            (probes if fn.endswith(".probe.json") else configs).append(path)
    return sorted(configs), sorted(probes)


def geo_tables(configs, probes):
    tables = {"geoip": {}, "geosite": {}}
    for p in probes:
        with open(p, encoding="utf-8") as f:
            geo = json.load(f).get("geo", {})
        for kind in tables:
            for code, values in geo.get(kind, {}).items():
                code = code.lower()
                if code in tables[kind] and tables[kind][code] != values:
                    raise SystemExit(fail(f"{os.path.relpath(p, PROJ)}: {kind}:{code} задан иначе, чем в другом файле корпуса"))
                tables[kind][code] = values
    for c in configs:
        with open(c, encoding="utf-8") as f:
            text = f.read()
        for kind, code in GEO_RULE.findall(text):
            tables[kind].setdefault(code.lower(), [])
    return tables


def main():
    if shutil.which("go") is None:
        print("нет Go (go в PATH) — Xray не собрать")
        return 3
    env = go_env()
    want = expected_version()
    xray, probe = build(env)
    r = run([xray, "version"])
    first = (r.stdout.splitlines() or [""])[0]
    if not first.startswith(f"Xray {want} "):
        return fail(f"собран «{first}», а в gradle/libs.versions.toml xray = {want}: go.mod и каталог разошлись")

    configs, probes = goldens()
    if not configs:
        return fail(f"нет эталонов в {os.path.relpath(GOLDEN, PROJ)} — CorpusTest их не записал?")
    geo_dir = os.path.join(OUT, "geodata")
    geo_json = os.path.join(OUT, "geodata.json")
    with open(geo_json, "w", encoding="utf-8") as f:
        json.dump(geo_tables(configs, probes), f, ensure_ascii=False, indent=2, sort_keys=True)
    r = run([probe, "geodata", "-in", geo_json, "-out", geo_dir])
    if r.returncode != 0:
        return fail(f"геоданные: {r.stdout}{r.stderr}")

    xenv = dict(os.environ, XRAY_LOCATION_ASSET=geo_dir)
    problems = 0
    for c in configs:
        r = run([xray, "run", "-test", "-c", c], env=xenv)
        if r.returncode != 0 or "Configuration OK." not in r.stdout:
            problems += 1
            tail = "\n".join((r.stdout + r.stderr).strip().splitlines()[-8:])
            print(f"✗ xray run -test {os.path.relpath(c, PROJ)} (код {r.returncode}):\n{tail}")
    decisions = 0
    for p in probes:
        r = run([probe, "route", "-probe", p, "-assets", geo_dir])
        if r.returncode != 0:
            problems += 1
            print("\n".join(line for line in (r.stdout + r.stderr).splitlines() if line.startswith("✗")))
        else:
            with open(p, encoding="utf-8") as f:
                decisions += len(json.load(f)["cases"])
    if problems:
        return fail(f"Xray {want}: {problems} расхождений с эталонами (tests/data/xray/)")
    print(f"✓ Xray {want}: {len(configs)} конфигураций корпуса приняты ядром (xray run -test), "
          f"{decisions} решений маршрутизации в {len(probes)} файлах совпали с RouteEvaluator")
    return 0


if __name__ == "__main__":
    sys.exit(main())
