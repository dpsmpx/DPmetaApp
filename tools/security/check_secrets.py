#!/usr/bin/env python3
"""Секретов в репозитории нет — ни в коде, ни в данных, ни в документах.

Проверяет все файлы, которые попадут в коммит (отслеживаемые и новые,
не исключённые .gitignore):

  * файлы ключей и хранилищ по расширению (.jks, .keystore, .p12, .pfx,
    .pem, .key, .env) — их место вне репозитория (.gitignore);
  * закрытые ключи PEM/OpenSSH, токены известных сервисов (GitHub,
    AWS, Google, Slack, Telegram-бот, Stripe);
  * ссылки прокси (`vless://`, `trojan://`, `ss://`, `vmess://`,
    `hysteria2://`, `tuic://` …) с адресом не из документационных
    диапазонов — это настоящий сервер и, как правило, его ключ;
  * UUID не синтетического вида `00000000-0000-…` — в DPpass UUID
    бывает только идентификатором VLESS, то есть секретом;
  * то же внутри base64: подписки часто приходят списком ссылок в base64,
    и настоящий сервер в таком виде не должен пройти незамеченным.

Ложное срабатывание — строка в `tools/security/secrets_allow.txt`
(«путь: регулярное выражение # причина»), а не ослабление правила.

    python3 tools/security/check_secrets.py

Код возврата: 0 — чисто; 1 — найдены секреты.
"""
import ipaddress
import os
import re
import subprocess
import sys

PROJ = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ALLOW_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "secrets_allow.txt")

KEY_FILES = re.compile(r"\.(jks|keystore|p12|pfx|pem|key|env)$|(^|/)\.env$", re.I)
PATTERNS = [
    ("закрытый ключ", re.compile(r"-----BEGIN (?:[A-Z]+ )?PRIVATE KEY-----")),
    ("токен GitHub", re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{40,})\b")),
    ("ключ AWS", re.compile(r"\b(?:AKIA|ASIA)[0-9A-Z]{16}\b")),
    ("ключ Google API", re.compile(r"\bAIza[0-9A-Za-z_-]{35}\b")),
    ("токен Slack", re.compile(r"\bxox[abprs]-[0-9A-Za-z-]{10,}")),
    ("токен Telegram-бота", re.compile(r"\b\d{8,10}:AA[0-9A-Za-z_-]{33}\b")),
    ("ключ Stripe", re.compile(r"\b[sr]k_live_[0-9A-Za-z]{20,}")),
]
PROXY_LINK = re.compile(r"\b(vless|vmess|trojan|ss|ssr|hysteria2|hy2|tuic|anytls|wireguard|wg|socks5?)://([^\s\"'<>`]+)", re.I)
UUID = re.compile(r"\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b")
SYNTHETIC_UUID = re.compile(r"^00000000-0000-")

# Документационные и служебные диапазоны: настоящего сервера там нет.
RESERVED_NETS = [ipaddress.ip_network(n) for n in (
    "192.0.2.0/24", "198.51.100.0/24", "203.0.113.0/24", "198.18.0.0/15",  # RFC 5737, RFC 2544
    "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8", "0.0.0.0/8", "100.64.0.0/10", "169.254.0.0/16",
    "2001:db8::/32", "fc00::/7", "fe80::/10", "::1/128", "::/128",  # RFC 3849, ULA, link-local
)]
RESERVED_DOMAINS = ("example.com", "example.net", "example.org", "example", "test", "invalid", "localhost")


def reserved_host(host):
    host = host.strip("[]").lower().rstrip(".")
    if "$" in host or ("." not in host and ":" not in host):
        return True  # шаблон Kotlin или не адрес вовсе («h», пусто): заведомо нерабочий ввод тестов
    try:
        ip = ipaddress.ip_address(host)
        return any(ip in n for n in RESERVED_NETS)
    except ValueError:
        return any(host == d or host.endswith("." + d) for d in RESERVED_DOMAINS)


BASE64_TOKEN = re.compile(r"[A-Za-z0-9+/_-]{24,}={0,2}")


def base64_text(token):
    """Текст ссылок из base64-токена — или None (не base64, не текст, ссылок нет)."""
    import base64
    import binascii
    padded = token + "=" * (-len(token) % 4)
    for decode in (base64.b64decode, base64.urlsafe_b64decode):
        try:
            text = decode(padded.encode("ascii")).decode("utf-8")
        except (binascii.Error, ValueError, UnicodeDecodeError):
            continue
        if "://" in text:
            return text
    return None


def link_host(rest):
    """Хост из `секрет@хост:порт?…` (или из base64-тела, тогда None)."""
    authority = re.split(r"[/?#]", rest, maxsplit=1)[0]
    if "@" not in authority:
        return None
    hostport = authority.rsplit("@", 1)[1]
    if hostport.startswith("["):
        return hostport[:hostport.find("]") + 1]
    return hostport.rsplit(":", 1)[0] if ":" in hostport else hostport


def files():
    try:
        out = subprocess.run(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
                             cwd=PROJ, capture_output=True, check=True).stdout.decode("utf-8").split("\0")
        return sorted(p for p in out if p and os.path.isfile(os.path.join(PROJ, p)))
    except (OSError, subprocess.CalledProcessError):
        print("✗ check_secrets: нужен git (список файлов коммита)")
        sys.exit(2)


def load_allow():
    allow = []
    if os.path.isfile(ALLOW_FILE):
        for line in open(ALLOW_FILE, encoding="utf-8"):
            line = line.split(" #", 1)[0].strip()
            if line and not line.startswith("#"):
                path, _, rx = line.partition(":")
                allow.append((path.strip(), re.compile(rx.strip())))
    return allow


def main():
    allow = load_allow()
    problems, scanned = [], 0

    def report(rel, n, what, text):
        if any(rel == p and rx.search(text) for p, rx in allow):
            return
        problems.append(f"{rel}:{n}: {what}")

    for rel in files():
        if KEY_FILES.search(rel):
            report(rel, 0, "файл ключа или хранилища в репозитории", rel)
            continue
        with open(os.path.join(PROJ, rel), "rb") as f:
            raw = f.read()
        if b"\0" in raw[:8192]:
            continue  # двоичный файл (wrapper jar): происхождение — docs/PROVENANCE.md
        scanned += 1
        text = raw.decode("utf-8", "replace")
        for n, line in enumerate(text.splitlines(), 1):
            for what, rx in PATTERNS:
                if rx.search(line):
                    report(rel, n, what, line)
            # Сама строка и то, что в ней спрятано в base64 (тело подписки), — по одним правилам.
            views = [("", line)] + [(" (в base64)", d) for d in map(base64_text, BASE64_TOKEN.findall(line)) if d]
            for where, view in views:
                for m in PROXY_LINK.finditer(view):
                    host = link_host(m.group(2))
                    if host is not None and not reserved_host(host):
                        report(rel, n, f"ссылка {m.group(1)}:// на адрес не из документационных диапазонов ({host}){where}", line)
                for m in UUID.finditer(view):
                    if not SYNTHETIC_UUID.match(m.group(0)):
                        report(rel, n, f"UUID не синтетического вида ({m.group(0)[:8]}…){where} — похоже на идентификатор VLESS", line)

    if problems:
        print("✗ Возможные секреты в репозитории:")
        for p in problems:
            print("    " + p)
        print("  Убрать из коммита (и сменить, если секрет настоящий). Ложное срабатывание — tools/security/secrets_allow.txt с причиной.")
        return 1
    print(f"✓ Секреты: {scanned} текстовых файлов — ключей, токенов, настоящих ссылок и UUID нет")
    return 0


if __name__ == "__main__":
    sys.exit(main())
