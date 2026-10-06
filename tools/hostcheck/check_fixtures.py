#!/usr/bin/env python3
"""Корпус регрессий `tests/data/` — целый, строгий и синтетический.

CorpusTest читает корпус по соглашениям об именах; то, что соглашению не
следует, тест молча не увидит. Эта проверка закрывает такие дыры
(docs/TESTING.md, «Корпус регрессий»):

  * пары файлов: у каждого входа есть ожидание и наоборот
    (`X.json` ↔ `X.expect.json`, `X.txt` ↔ `X.expect.json`,
    `X.headers.json` → `X.txt`), ссылки на конфигурации существуют;
  * ожидания строгие: только известные ключи и значения исходов, коды —
    из `DiagCode`, условия сети — из `NetworkCondition` (и каждое условие
    модели есть в таблице), классы — из `RestrictionClass`,
    `Reachability`, `TransportClass`; опечатка в ожидании — ошибка, а не
    тест, который ничего не проверяет;
  * коды — контракт (D-22): реестр `tools/hostcheck/diag_codes.txt`
    сверяется с `DiagCode` и `FailureKind`: пропавший или
    переименованный код — ошибка, новый код без записи в реестре — тоже;
  * эталоны компилятора Xray (`tests/data/xray/`): у каждой разбираемой
    конфигурации корпуса есть ожидание `xray`; при `compiled` — по
    эталону на каждый профиль и ничего лишнего; у каждого файла случаев
    маршрутизации — `*.probe.json` и эталон его конфигурации, а в
    `xray/routing/` нет файлов без случаев;
  * данные синтетические (и внутри base64-тел подписок): IP только из
    документационных и служебных диапазонов, домены — `example.com/.net/.org`, `.example`, `.test`,
    `.invalid` (исключения ниже — с причиной), UUID — вида
    `00000000-0000-…`. Так в корпус не попадёт настоящий сервер или ключ.

    python3 tools/hostcheck/check_fixtures.py

Код возврата: 0 — чисто; 1 — нарушения.
"""
import ipaddress
import json
import os
import re
import sys

PROJ = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DATA = os.path.join(PROJ, "tests", "data")
SRC = os.path.join(PROJ, "{m}", "src", "main", "kotlin", "com", "dpsmpx", "dppass", "{m}")

RESERVED_NETS = [ipaddress.ip_network(n) for n in (
    "192.0.2.0/24", "198.51.100.0/24", "203.0.113.0/24", "198.18.0.0/15",
    "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8", "0.0.0.0/8", "100.64.0.0/10", "169.254.0.0/16",
    "2001:db8::/32", "fc00::/7", "fe80::/10", "::1/128", "::/128",
)]
RESERVED_DOMAINS = ("example.com", "example.net", "example.org", "example", "test", "invalid", "localhost")
# Домены вне RFC 2606 — только с причиной. Трафик к ним не отправляется:
# тесты лишь сравнивают строки правил.
EXTRA_DOMAINS = {
    "example.ru": "образец «отечественного» домена для правил DIRECT и DNS по суффиксу .ru",
}
UUID = re.compile(r"\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b")
LINK = re.compile(r"^([a-z][a-z0-9+.-]*)://(.*)$", re.I)
HOST_KEYS = {"server", "host", "server_name", "address", "domain", "domain_suffix", "sni", "serverName"}
# Префиксы правил Xray в эталонах компилятора (tests/data/xray/): имя после
# `full:`/`domain:` — домен; после `keyword:`, `geosite:`, `geoip:` — не адрес.
XRAY_NAME_PREFIXES = ("full:", "domain:")
XRAY_NOT_HOST_PREFIXES = ("keyword:", "geosite:", "geoip:", "regexp:")
XRAY = os.path.join(DATA, "xray")


def enum_entries(path, enum):
    with open(path, encoding="utf-8") as f:
        text = f.read()
    text = re.sub(r"/\*.*?\*/|//[^\n]*", "", text, flags=re.S)
    m = re.search(r"enum class " + enum + r"\b[^{]*\{", text)
    if not m:
        raise SystemExit(f"✗ check_fixtures: не найден enum {enum} в {os.path.relpath(path, PROJ)}")
    depth, i = 1, m.end()
    while depth and i < len(text):
        depth += {"{": 1, "}": -1}.get(text[i], 0)
        i += 1
    body = text[m.end():i - 1].split(";", 1)[0]
    while re.search(r"\([^()]*\)", body):
        body = re.sub(r"\([^()]*\)", "", body)  # аргументы конструктора элементов
    return {t.strip() for t in body.split(",") if re.fullmatch(r"[A-Z][A-Z0-9_]*", t.strip())}


def src(module, file):
    return os.path.join(SRC.format(m=module), file)


CODES = enum_entries(src("diagnostics", "Codes.kt"), "DiagCode")
CONDITIONS = enum_entries(os.path.join(SRC.format(m="network"), "simulation", "SimulatedNetwork.kt"), "NetworkCondition")
RESTRICTIONS = enum_entries(src("network", "Model.kt"), "RestrictionClass")
REACH = enum_entries(src("network", "Model.kt"), "Reachability")
CLASSES = enum_entries(src("config", "Ir.kt"), "TransportClass")
KINDS = enum_entries(src("diagnostics", "Codes.kt"), "FailureKind")
REGISTRY = os.path.join(os.path.dirname(os.path.abspath(__file__)), "diag_codes.txt")

problems = []


def bad(path, msg):
    problems.append(f"{os.path.relpath(path, PROJ)}: {msg}")


def load(path):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError) as e:
        bad(path, f"не JSON: {e}")
        return None


def keys(path, obj, allowed, required=()):
    if not isinstance(obj, dict):
        bad(path, "ожидался объект")
        return False
    for k in sorted(set(obj) - set(allowed)):
        bad(path, f"неизвестный ключ «{k}» (допустимо: {', '.join(sorted(allowed))})")
    for k in required:
        if k not in obj:
            bad(path, f"нет ключа «{k}»")
    return True


def one_of(path, what, value, allowed):
    if value not in allowed:
        bad(path, f"{what} = {value!r}; допустимо: {', '.join(sorted(map(str, allowed)))}")


def codes(path, obj):
    lst = obj.get("codes", [])
    if not isinstance(lst, list) or not all(isinstance(c, str) for c in lst):
        bad(path, "codes — массив строк")
        return
    for c in lst:
        if c not in CODES:
            bad(path, f"код {c} — нет в DiagCode")


# ---- пары и ожидания ----

def check_config_dirs():
    for d in ("configs", "dns"):
        root = os.path.join(DATA, d)
        names = os.listdir(root)
        inputs = {n[:-5] for n in names if n.endswith(".json") and not n.endswith(".expect.json")}
        expects = {n[:-12] for n in names if n.endswith(".expect.json")}
        for n in sorted(inputs - expects):
            bad(os.path.join(root, n + ".json"), "вход без ожидания .expect.json — CorpusTest его не проверит")
        for n in sorted(expects - inputs):
            bad(os.path.join(root, n + ".expect.json"), "ожидание без входа .json")
        for n in sorted(names):
            if not n.endswith(".json"):
                bad(os.path.join(root, n), "в каталоге корпуса конфигураций — только .json")
        for n in sorted(expects & inputs):
            path = os.path.join(root, n + ".expect.json")
            e = load(path)
            if e is None or not keys(path, e, {"load", "activate", "xray"}, ("load",)):
                continue
            ld = e["load"]
            if keys(path, ld, {"outcome", "codes"}, ("outcome",)):
                one_of(path, "load.outcome", ld.get("outcome"), {"ok", "rejected"})
                codes(path, ld)
            act = e.get("activate")
            if act is not None and keys(path, act, {"backend", "outcome", "codes"}, ("outcome",)):
                one_of(path, "activate.backend", act.get("backend", "mock"), {"mock", "mock-limited"})
                one_of(path, "activate.outcome", act.get("outcome"), {"activated", "rejected"})
                codes(path, act)
                if ld.get("outcome") != "ok":
                    bad(path, "activate есть, а load.outcome не ok — активация не дойдёт до проверки")
            xr = e.get("xray")
            if xr is None and ld.get("outcome") == "ok":
                bad(path, "нет ожидания xray: разбираемая конфигурация корпуса компилируется и для Xray (этап 2)")
            if xr is not None and keys(path, xr, {"outcome", "codes"}, ("outcome",)):
                one_of(path, "xray.outcome", xr.get("outcome"), {"compiled", "rejected"})
                codes(path, xr)
                if ld.get("outcome") != "ok":
                    bad(path, "xray есть, а load.outcome не ok — компиляция не дойдёт до проверки")
                check_xray_golden(d, n, os.path.join(root, n + ".json"), xr.get("outcome") == "compiled")
        check_xray_orphans(d, expects & inputs)


def profile_ids(config_path):
    doc = load(config_path)
    if not isinstance(doc, dict):
        return set()
    return {p.get("id") for p in doc.get("profiles", []) if isinstance(p, dict) and isinstance(p.get("id"), str)}


def check_xray_golden(d, name, config_path, compiled):
    """Эталоны одной конфигурации: по файлу на профиль — ровно когда компиляция ожидается."""
    gdir = os.path.join(XRAY, d, name)
    have = sorted(os.listdir(gdir)) if os.path.isdir(gdir) else []
    if not compiled:
        if have:
            bad(gdir, "эталоны Xray у конфигурации, компиляция которой ожидается с отказом")
        return
    want = {f"{pid}.json" for pid in profile_ids(config_path)}
    for f in sorted(want - set(have)):
        bad(os.path.join(gdir, f), "нет эталона Xray для профиля (CorpusTest с -Ddppass.golden.update=true)")
    for f in sorted(set(have) - want):
        bad(os.path.join(gdir, f), "эталон Xray без профиля в конфигурации — устарел")


def check_xray_orphans(d, names):
    root = os.path.join(XRAY, d)
    if not os.path.isdir(root):
        return
    for n in sorted(os.listdir(root)):
        if n not in names:
            bad(os.path.join(root, n), f"эталоны Xray без конфигурации tests/data/{d}/{n}.json")


def check_subscriptions():
    root = os.path.join(DATA, "subscriptions")
    names = os.listdir(root)
    bodies = {n[:-4] for n in names if n.endswith(".txt")}
    expects = {n[:-12] for n in names if n.endswith(".expect.json")}
    headers = {n[:-13] for n in names if n.endswith(".headers.json")}
    for n in sorted(bodies - expects):
        bad(os.path.join(root, n + ".txt"), "тело без ожидания .expect.json")
    for n in sorted(expects - bodies):
        bad(os.path.join(root, n + ".expect.json"), "ожидание без тела .txt")
    for n in sorted(headers - bodies):
        bad(os.path.join(root, n + ".headers.json"), "заголовки без тела .txt")
    for n in sorted(names):
        if not (n.endswith(".txt") or n.endswith(".expect.json") or n.endswith(".headers.json")):
            bad(os.path.join(root, n), "неизвестный вид файла корпуса подписок")
    for n in sorted(expects & bodies):
        path = os.path.join(root, n + ".expect.json")
        e = load(path)
        if e is None or not keys(path, e, {"outcome", "profiles", "codes"}, ("outcome",)):
            continue
        one_of(path, "outcome", e.get("outcome"), {"ready", "failed"})
        if e.get("outcome") == "ready" and not isinstance(e.get("profiles"), int):
            bad(path, "для ready нужно число profiles")
        codes(path, e)
    for n in sorted(headers & bodies):
        path = os.path.join(root, n + ".headers.json")
        h = load(path)
        if h is not None and not (isinstance(h, dict) and all(isinstance(v, str) for v in h.values())):
            bad(path, "заголовки — объект «имя: строка»")


def check_routing():
    root = os.path.join(DATA, "routing")
    for n in sorted(os.listdir(root)):
        if not n.endswith(".cases.json"):
            if not n.endswith(".config.json"):
                bad(os.path.join(root, n), "в routing/ — только *.cases.json и *.config.json")
            continue
        path = os.path.join(root, n)
        c = load(path)
        if c is None or not keys(path, c, {"config", "geo", "cases"}, ("config", "cases")):
            continue
        if not os.path.isfile(os.path.join(root, str(c["config"]))):
            bad(path, f"нет конфигурации {c['config']}")
        base = n[:-len(".cases.json")]
        probe = os.path.join(XRAY, "routing", base + ".probe.json")
        if not os.path.isfile(probe):
            bad(path, f"нет {os.path.relpath(probe, PROJ)} — сверка с Xray не увидит эти случаи")
        else:
            pr = load(probe)
            if isinstance(pr, dict) and not os.path.isfile(os.path.join(os.path.dirname(probe), str(pr.get("config")))):
                bad(probe, f"нет эталона конфигурации {pr.get('config')}")
        geo = c.get("geo", {})
        if keys(path, geo, {"geoip"}):
            for code, nets in geo.get("geoip", {}).items():
                for net in nets:
                    try:
                        ipaddress.ip_network(net, strict=False)
                    except ValueError:
                        bad(path, f"geo.geoip.{code}: не подсеть {net!r}")
                        continue
                    check_cidr(path, net, f"geo.geoip.{code}")
        for i, case in enumerate(c.get("cases", [])):
            where = f"{n}#{i}"
            if not keys(path, case, {"query", "action", "rule"}, ("query", "action")):
                continue
            # CorpusTest передаёт в RouteQuery только эти поля: остальное он бы молча пропустил.
            keys(path, case["query"], {"domain", "ip", "port", "protocol", "package"})
            one_of(path, f"{where} action", case.get("action"), {"direct", "proxy", "block"})
            rule = case.get("rule")
            if rule is not None and not isinstance(rule, str):
                bad(path, f"{where}: rule — строка или null")


def check_xray_routing():
    """В tests/data/xray/ — только то, что пишет CorpusTest: configs/, dns/ и пары сверки в routing/."""
    if os.path.isdir(XRAY):
        for n in sorted(os.listdir(XRAY)):
            if n not in ("configs", "dns", "routing") or not os.path.isdir(os.path.join(XRAY, n)):
                bad(os.path.join(XRAY, n), "в xray/ — только каталоги configs/, dns/, routing/: остальное CorpusTest не пишет и не сверяет")
    root = os.path.join(XRAY, "routing")
    if not os.path.isdir(root):
        return
    for n in sorted(os.listdir(root)):
        base = next((n[:-len(sfx)] for sfx in (".probe.json", ".config.json") if n.endswith(sfx)), None)
        if base is None:
            bad(os.path.join(root, n), "в xray/routing/ — только *.probe.json и *.config.json")
        elif not os.path.isfile(os.path.join(DATA, "routing", base + ".cases.json")):
            bad(os.path.join(root, n), f"нет tests/data/routing/{base}.cases.json — эталон сверки устарел")


def check_network():
    path = os.path.join(DATA, "network", "conditions.json")
    t = load(path)
    if t is None or not keys(path, t, {"note", "config", "conditions"}, ("config", "conditions")):
        return
    if not os.path.isfile(os.path.join(DATA, "network", t["config"])):
        bad(path, f"нет конфигурации {t['config']}")
    allowed = {"condition", "restriction", "dns", "udp", "tls", "connects", "blocked_endpoints", "excluded",
               "only_candidate", "first_attempt_class", "verdict", "first_failed"}
    seen = set()
    for c in t["conditions"]:
        if not keys(path, c, allowed, ("condition", "restriction", "connects")):
            continue
        name = c["condition"]
        one_of(path, "condition", name, CONDITIONS)
        if name in seen:
            bad(path, f"условие {name} повторяется")
        seen.add(name)
        one_of(path, f"{name}.restriction", c["restriction"], RESTRICTIONS)
        for k in ("dns", "udp", "tls"):
            if k in c:
                one_of(path, f"{name}.{k}", c[k], REACH)
        if "first_attempt_class" in c:
            one_of(path, f"{name}.first_attempt_class", c["first_attempt_class"], CLASSES)
        if "verdict" in c:
            one_of(path, f"{name}.verdict", c["verdict"], CODES)
        if not isinstance(c["connects"], bool):
            bad(path, f"{name}.connects — true или false")
    for name in sorted(CONDITIONS - seen):
        bad(path, f"условия модели {name} нет в таблице — класс сети без регрессии")


def check_registry():
    if not os.path.isfile(REGISTRY):
        bad(REGISTRY, "нет реестра кодов диагностики")
        return
    known = {"kind": set(), "code": set()}
    with open(REGISTRY, encoding="utf-8") as f:
        for n, line in enumerate(f, 1):
            line = line.split("#", 1)[0].strip()
            if not line:
                continue
            kind, _, name = line.partition(" ")
            if kind not in known or not name:
                bad(REGISTRY, f"строка {n}: ожидалось «kind ИМЯ» или «code ИМЯ»")
                continue
            known[kind].add(name)
    for what, have, enum in (("code", CODES, "DiagCode"), ("kind", KINDS, "FailureKind")):
        for name in sorted(known[what] - have):
            bad(REGISTRY, f"{enum}.{name} пропал: коды не удаляются и не переименовываются (D-22)")
        for name in sorted(have - known[what]):
            bad(REGISTRY, f"{enum}.{name} нет в реестре: новый код — строка «{what} {name}» в реестре")


# ---- синтетичность данных ----

def check_host(path, host, where):
    h = host.strip().strip("[]").lower().rstrip(".")
    if not h:
        return
    try:
        ip = ipaddress.ip_address(h)
        if not any(ip in n for n in RESERVED_NETS):
            bad(path, f"{where}: IP {h} не из документационных/служебных диапазонов")
        return
    except ValueError:
        pass
    if "." not in h:
        return  # не доменное имя (заведомо неверный ввод теста)
    if any(h == d or h.endswith("." + d) for d in RESERVED_DOMAINS):
        return
    if any(h == d or h.endswith("." + d) for d in EXTRA_DOMAINS):
        return
    bad(path, f"{where}: домен {h} не зарезервирован для примеров (RFC 2606) и не в списке исключений check_fixtures.py")


def check_cidr(path, value, where):
    try:
        net = ipaddress.ip_network(value.strip(), strict=False)
    except ValueError:
        return  # неверная подсеть — предмет теста, а не утечка
    if not any(net.subnet_of(n) for n in RESERVED_NETS if n.version == net.version):
        bad(path, f"{where}: подсеть {value} не из документационных/служебных диапазонов")


def check_link(path, text, where):
    m = LINK.match(text.strip())
    if not m:
        return
    rest = m.group(2)
    authority = re.split(r"[/?#]", rest, maxsplit=1)[0]
    hostport = authority.rsplit("@", 1)[-1]
    if hostport.startswith("["):
        host = hostport[:hostport.find("]") + 1]
    else:
        host = hostport.rsplit(":", 1)[0] if hostport.count(":") == 1 else hostport
    check_host(path, host, where)
    query = rest.split("?", 1)[1].split("#", 1)[0] if "?" in rest else ""
    for pair in query.split("&"):
        k, _, v = pair.partition("=")
        if k.lower() in ("sni", "host") and v:
            check_host(path, v, f"{where} ({k})")


def walk_json(path, value, key=None, where="$"):
    if isinstance(value, dict):
        for k, v in value.items():
            walk_json(path, v, k, f"{where}.{k}")
    elif isinstance(value, list):
        for i, v in enumerate(value):
            walk_json(path, v, key, f"{where}[{i}]")
    elif isinstance(value, str):
        if LINK.match(value):
            check_link(path, value, where)
        elif key in HOST_KEYS and value.startswith(XRAY_NOT_HOST_PREFIXES):
            pass
        elif key in HOST_KEYS:
            host = value
            for prefix in XRAY_NAME_PREFIXES:
                if host.startswith(prefix):
                    host = host[len(prefix):]
            if key == "address" and value.count(":") == 1:
                host = value.split(":", 1)[0]
            elif key == "address" and value.startswith("["):
                host = value[:value.find("]") + 1]
            check_host(path, host, where)
        elif key in ("ip_cidr", "ip") and value.startswith(XRAY_NOT_HOST_PREFIXES):
            pass
        elif key in ("ip_cidr", "ip"):
            check_cidr(path, value, where)
        elif key == "blocked_endpoints":
            check_host(path, value.rsplit(":", 1)[0], where)
        for u in UUID.findall(value):
            if not u.startswith("00000000-0000-"):
                bad(path, f"{where}: UUID {u[:8]}… не синтетический")


def base64_links(line):
    """Строки ссылок, спрятанные в base64 (тело подписки), — или пусто."""
    import base64
    import binascii
    s = line.strip()
    if len(s) < 24 or not re.fullmatch(r"[A-Za-z0-9+/_-]+=*", s):
        return []
    padded = s + "=" * (-len(s) % 4)
    for decode in (base64.b64decode, base64.urlsafe_b64decode):
        try:
            text = decode(padded.encode("ascii")).decode("utf-8")
        except (binascii.Error, ValueError, UnicodeDecodeError):
            continue
        if "://" in text:
            return text.splitlines()
    return []


def check_synthetic(root):
    for dirpath, _, files in os.walk(root):
        for fn in sorted(files):
            path = os.path.join(dirpath, fn)
            if fn.endswith(".json"):
                try:
                    with open(path, encoding="utf-8") as f:
                        doc = json.load(f)
                except ValueError:
                    doc = None  # битый JSON — сам предмет теста; строки проверяются ниже
                if doc is not None:
                    walk_json(path, doc)
                    continue
            with open(path, encoding="utf-8", errors="replace") as f:
                for n, line in enumerate(f, 1):
                    s = line.strip()
                    if s.startswith("#") and ":" in s:
                        s = s.split(":", 1)[1].strip()  # «#ключ: значение» в теле подписки
                    # Строка и ссылки, спрятанные в ней в base64, — по одним правилам.
                    views = [(f"строка {n}", s)] + [(f"строка {n}, base64 → {k}", d) for k, d in enumerate(base64_links(s), 1)]
                    for where, view in views:
                        check_link(path, view, where)
                        for u in UUID.findall(view):
                            if not u.startswith("00000000-0000-"):
                                bad(path, f"{where}: UUID {u[:8]}… не синтетический")
                        for ip in re.findall(r"\b(?:\d{1,3}\.){3}\d{1,3}\b", view):
                            check_host(path, ip, where)


def main():
    check_config_dirs()
    check_subscriptions()
    check_routing()
    check_xray_routing()
    check_network()
    check_registry()
    check_synthetic(DATA)
    if problems:
        print("✗ Корпус tests/data:")
        for p in problems:
            print("    " + p)
        return 1
    count = sum(len(f) for _, _, f in os.walk(DATA))
    print(f"✓ Корпус: {count} файлов — пары полные, ожидания строгие, {len(CONDITIONS)} условий сети покрыты, "
          f"{len(CODES)} кодов диагностики по реестру, данные синтетические")
    return 0


if __name__ == "__main__":
    sys.exit(main())
