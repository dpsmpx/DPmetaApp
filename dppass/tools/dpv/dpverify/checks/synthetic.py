"""data.synthetic — данные синтетические: нет настоящих адресов, серверов и идентификаторов (ТЗ, §8.2; V-20).

Где ищется: `ip` — адреса в тексте, значения JSON под ключами `keys` (адрес
или подсеть) и параметры запроса ссылок с такими именами; `domain` — хосты
ссылок, значения `keys` (метка `слово:` впереди допустима) и параметры
запроса ссылок; `uuid` — UUID в тексте; `link-host` — хосты ссылок со
схемами `schemes`. Ссылки и UUID ищутся и в base64-токенах строки (тело
подписки). Шаблон вместо адреса (`$host`, значение без точки) адресом не
считается; домен — только там, где по смыслу стоит адрес: имя пакета
Android и имя файла выглядят так же (V-20).

Идеи — DPpass:tools/security/check_secrets.py и
DPpass:tools/hostcheck/check_fixtures.py (свой код автора).
"""
import base64
import binascii
import hashlib
import ipaddress
import json
import re
from urllib.parse import parse_qsl

from ..errors import UsageError
from ..globs import MaskSet
from ..model import Finding, Outcome
from ..profile import REQUIRED
from .base import Check

ALLOW = {"value": ("str", REQUIRED), "reason": ("str", REQUIRED)}
RULE = {"kind": ("enum:ip|domain|uuid|link-host", REQUIRED), "paths": ("list", REQUIRED), "schemes": ("list", None),
        "synthetic": ("regex", None), "keys": ("list", None), "allow": (("tables", ALLOW), [])}
ONLY = {"schemes": "link-host", "synthetic": "uuid"}
FINDING = {"ip": "real-ip", "domain": "real-domain", "uuid": "real-uuid", "link-host": "real-link-host"}

# Документационные и служебные диапазоны: настоящего сервера там нет.
RESERVED_NETS = [ipaddress.ip_network(n) for n in (
    "192.0.2.0/24", "198.51.100.0/24", "203.0.113.0/24",  # RFC 5737
    "2001:db8::/32",  # RFC 3849
    "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",  # RFC 1918
    "fc00::/7",  # RFC 4193
    "100.64.0.0/10",  # RFC 6598
    "198.18.0.0/15",  # RFC 2544
    "127.0.0.0/8", "::1/128", "169.254.0.0/16", "fe80::/10", "0.0.0.0/8", "::/128",  # петля, link-local, «никакой»
)]
RESERVED_DOMAINS = ("example.com", "example.net", "example.org")  # RFC 2606
RESERVED_TLDS = ("example", "test", "invalid", "localhost")  # RFC 2606, 6761

LINK = re.compile(r"\b([A-Za-z][A-Za-z0-9+.-]*)://([^\s\"'<>`]+)")
IPV4 = re.compile(r"(?<![\w.])(?:\d{1,3}\.){3}\d{1,3}(?![\w.])")
IPV6 = re.compile(r"(?<![\w:.])(?:[0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{0,4}(?![\w:.])")
UUID = re.compile(r"\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b")
BASE64 = re.compile(r"[A-Za-z0-9+/_-]{24,}={0,2}")
HOSTNAME = re.compile(r"(?:[a-z0-9_](?:[a-z0-9_-]{0,61}[a-z0-9_])?\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
LABEL = re.compile(r"^[A-Za-z][\w-]*:(?!//)")  # «domain:», «full:» перед значением


def _ip(text):
    try:
        return ipaddress.ip_address(text)
    except ValueError:
        return None


def reserved_ip(ip):
    return any(ip in net for net in RESERVED_NETS if net.version == ip.version)


def reserved_domain(host):
    return any(host == d or host.endswith("." + d) for d in RESERVED_DOMAINS) or \
        any(host == t or host.endswith("." + t) for t in RESERVED_TLDS)


def host_of(value):
    """Хост из «хост», «хост:порт», «[ipv6]:порт» → (вид: ip | domain, хост) или None (не адрес)."""
    h = value.strip().lower().rstrip(".")
    if h.startswith("["):
        if "]" not in h:
            return None  # «[::1:443» без скобки — испорченная ссылка, не адрес
        h = h[1:h.find("]")]
    elif h.count(":") == 1:
        h = h.split(":", 1)[0]
    if not h or "$" in h or ("." not in h and ":" not in h):
        return None  # шаблон или не адрес вовсе
    if _ip(h) is not None:
        return "ip", h
    return ("domain", h) if HOSTNAME.fullmatch(h) else None


def link_parts(rest):
    """Хост и параметры запроса из «секрет@хост:порт/путь?запрос#имя»."""
    authority = re.split(r"[/?#]", rest, maxsplit=1)[0]
    host = authority.rsplit("@", 1)[-1]
    query = rest.split("?", 1)[1].split("#", 1)[0] if "?" in rest else ""
    return host, parse_qsl(query, keep_blank_values=False)


def base64_texts(line):
    """Тексты со ссылками, спрятанные в base64-токенах строки."""
    out = []
    for token in BASE64.findall(line):
        padded = token + "=" * (-len(token) % 4)
        for decode in (base64.b64decode, base64.urlsafe_b64decode):
            try:
                text = decode(padded.encode("ascii")).decode("utf-8")
            except (binascii.Error, ValueError, UnicodeDecodeError):
                continue
            if "://" in text:
                out.append(text)
                break
    return out


class Synthetic(Check):
    ID = "data.synthetic"
    SCANNED = "текстовые файлы из paths правил"
    SUMMARY = "настоящие IP, домены, UUID и хосты ссылок прокси в данных и коде — в том числе в base64"
    ORIGIN = ("ссылки прокси и UUID — DPpass:tools/security/check_secrets.py; данные корпуса — "
              "DPpass:tools/hostcheck/check_fixtures.py")
    SETTINGS = {"rule": (("tables", RULE), [])}
    _FIX = ("заменить синтетическим: 192.0.2.x, 2001:db8::, example.com, 00000000-0000-…; настоящий сервер "
            "или ключ — убрать и сменить; допустимое — allow с причиной")
    RULES = {
        "real-ip": ("IPv4 или IPv6 вне документационных и служебных диапазонов", _FIX, "адрес"),
        "real-domain": ("домен вне RFC 2606 и 6761 — в ссылке или под ключом из keys", _FIX, "домен"),
        "real-uuid": ("UUID не синтетического вида (synthetic)", _FIX, "UUID (только в памяти)"),
        "real-link-host": ("ссылка со схемой из schemes ведёт на адрес вне документационных диапазонов", _FIX,
                           "хост"),
    }

    @classmethod
    def validate(cls, settings, modules, where):
        for i, rule in enumerate(settings["rule"]):
            at = f"{where}.rule[{i + 1}]"
            for key, kind in ONLY.items():
                if rule[key] is not None and rule["kind"] != kind:
                    raise UsageError(f"{at}.{key}: только для kind = \"{kind}\"")
            if rule["keys"] is not None and rule["kind"] not in ("ip", "domain"):
                raise UsageError(f"{at}.keys: только для kind = \"ip\" и \"domain\"")
            if rule["kind"] == "link-host" and not rule["schemes"]:
                raise UsageError(f"{at}: для link-host нужны schemes")
            MaskSet(rule["paths"], modules)

    @classmethod
    def masks(cls, settings, modules):
        return [(f'[checks."{cls.ID}"].rule[{i + 1}].paths ({r["kind"]})', MaskSet(r["paths"], modules))
                for i, r in enumerate(settings["rule"])]

    def run(self, ctx):
        modules = ctx.profile.modules
        rules = [(r, MaskSet(r["paths"], modules)) for r in self.settings["rule"]]
        findings, scanned = {}, 0
        for path in ctx.repo.files():
            mine = [r for r, paths in rules if paths.match(path)]
            text = ctx.repo.text(path) if mine else None
            if text is None:
                continue
            scanned += 1
            lines = text.split("\n")
            for n, line in enumerate(lines, 1):
                views = [(line, "")] + [(t, " (в base64)") for t in base64_texts(line)]
                for view, where in views:
                    for rule in mine:
                        for kind, value, extra in self._in_text(rule, view):
                            self._report(findings, rule, kind, value, path, n, where + extra)
            if path.endswith(".json") and any(r["keys"] for r in mine):
                try:
                    doc = json.loads(text)
                except ValueError:
                    doc = None  # битый JSON — сам предмет теста; строки проверены выше
                for key, value in _walk(doc):
                    for rule in mine:
                        if rule["keys"] and key in rule["keys"]:
                            for kind, found in self._value(rule, value):
                                line = _line_of(lines, value)
                                self._report(findings, rule, kind, found, path, line, f" (ключ {key})")
        out = sorted(findings.values(), key=Finding.sort_key)
        return Outcome(scanned, out, f"файлов {scanned}, правил {len(rules)}")

    @staticmethod
    def _in_text(rule, view):
        """(вид адреса, значение, пояснение) по тексту строки."""
        kind, keys = rule["kind"], set(rule["keys"] or ())
        if kind == "uuid":
            for m in UUID.finditer(view):
                yield "uuid", m.group(0), ""
            return
        if kind == "ip":
            for rx in (IPV4, IPV6):
                for m in rx.finditer(view):
                    if _ip(m.group(0)) is not None:
                        yield "ip", m.group(0), ""
        for m in LINK.finditer(view):
            scheme = m.group(1).lower()
            host, query = link_parts(m.group(2))
            if kind == "link-host" and scheme in {s.lower() for s in rule["schemes"]}:
                parsed = host_of(host)
                if parsed:
                    yield "link-host", parsed[1], f" ({scheme}://)"
            if kind == "domain":
                parsed = host_of(host)
                if parsed and parsed[0] == "domain":
                    yield "domain", parsed[1], f" ({scheme}://)"
            for name, value in query:
                parsed = host_of(value) if name in keys else None
                if parsed and parsed[0] == kind:
                    yield kind, parsed[1], f" ({scheme}://, {name})"

    @staticmethod
    def _value(rule, value):
        """Значение JSON под ключом из keys → [(вид, адрес)]."""
        value = value.strip()
        if rule["kind"] == "ip":
            try:
                net = ipaddress.ip_network(value, strict=False)
            except ValueError:
                parsed = host_of(value)
                return [("ip", parsed[1])] if parsed and parsed[0] == "ip" else []
            return [] if any(net.subnet_of(r) for r in RESERVED_NETS if r.version == net.version) else [("net", value)]
        m = LABEL.match(value)
        if m and host_of(value[m.end():]):
            value = value[m.end():]
        parsed = host_of(value)
        return [("domain", parsed[1])] if parsed and parsed[0] == "domain" else []

    def _report(self, findings, rule, kind, value, path, line, where):
        if kind == "uuid":
            if rule["synthetic"] is not None and rule["synthetic"].search(value):
                return
            shown, evidence = value[:8] + "…", hashlib.sha256(value.lower().encode()).hexdigest()
        else:
            if kind == "net":
                if _allowed(rule, value):
                    return
            else:
                ip = _ip(value)
                if (reserved_ip(ip) if ip is not None else reserved_domain(value)) or _allowed(rule, value):
                    return
            shown = evidence = value
        what = {"ip": "IP", "net": "подсеть", "domain": "домен", "uuid": "UUID", "link-host": "хост ссылки"}[kind]
        tail = "не синтетического вида" if kind == "uuid" else "не из документационных и служебных диапазонов"
        rid = FINDING[rule["kind"]]
        key = (rid, path, line, evidence)
        findings.setdefault(key, Finding(rid, f"{what} {shown}{where} — {tail}", path=path, line=line,
                                         evidence=evidence, subject=value, hint=self._FIX))


def _allowed(rule, value):
    """Значение разрешено профилем: то же, поддомен или адрес внутри подсети из allow."""
    v = value.lower()
    ip = _ip(v.split("/")[0])
    for entry in rule["allow"]:
        a = entry["value"].lower()
        if v == a or (ip is None and v.endswith("." + a)):
            return True
        if ip is not None:
            try:
                if ip in ipaddress.ip_network(a, strict=False):
                    return True
            except ValueError:
                continue
    return False


def _walk(value, key=None):
    """(ключ, строка) по документу JSON; строка в массиве — под ключом массива."""
    if isinstance(value, dict):
        for k, v in value.items():
            yield from _walk(v, k)
    elif isinstance(value, list):
        for v in value:
            yield from _walk(v, key)
    elif isinstance(value, str) and key is not None:
        yield key, value


def _line_of(lines, value):
    """Строка файла, где значение впервые встречается в кавычках; None — не найдено."""
    needle = json.dumps(value, ensure_ascii=False)
    for n, line in enumerate(lines, 1):
        if needle in line:
            return n
    return None
