#!/usr/bin/env python3
"""Секретов в репозитории нет (D-14, T11).

Ищет файлы ключей и закрытые ключи, токены провайдеров и сервисов,
присваивания секретов в коде и конфигурации. Значение найденного не
печатается: только правило и место. Образцы для тестов собираются в коде
из частей, а не лежат готовыми.

Исключение — строка `путь:правило — причина` в tools/verify/secrets_allow.txt.

    python3 tools/verify/check_secrets.py

Код возврата: 0 — чисто; 1 — находки.
"""
import fnmatch
import os
import re

from common import Findings, files, is_text, main, read, root

ALLOW_FILE = "tools/verify/secrets_allow.txt"

FILE_RULES = [
    ("key-file", ["*.pem", "*.key", "*.p12", "*.pfx", "*.jks", "*.keystore", "id_rsa", "id_ed25519", "id_dsa",
                  "id_ecdsa", ".env", "*.env", "credentials.json", ".netrc", ".npmrc", ".pypirc"]),
]

TEXT_RULES = [
    ("private-key", r"-----BEGIN (?:[A-Z]+ )?PRIVATE KEY-----"),
    ("anthropic-key", r"\bsk-ant-[A-Za-z0-9_-]{20,}"),
    ("openai-key", r"\bsk-(?:proj-)?[A-Za-z0-9]{32,}"),
    ("github-token", r"\b(?:gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{40,})"),
    ("aws-key", r"\b(?:AKIA|ASIA)[0-9A-Z]{16}\b"),
    ("slack-token", r"\bxox[abprs]-[A-Za-z0-9-]{10,}"),
    ("google-key", r"\bAIza[0-9A-Za-z_-]{35}\b"),
    ("telegram-token", r"\b\d{8,10}:AA[A-Za-z0-9_-]{33}\b"),
    ("assignment",
     r"(?i)\b(?:api[_-]?key|secret|token|password|passwd)\s*[:=]\s*[\"'][A-Za-z0-9+/_=.-]{16,}[\"']"),
]


def allowlist():
    out = set()
    path = os.path.join(root(), ALLOW_FILE)
    if os.path.isfile(path):
        for line in read(ALLOW_FILE).splitlines():
            line = line.split("#", 1)[0].strip()
            if not line:
                continue
            key, _, reason = line.partition("—")
            if not reason.strip():
                continue  # исключение без причины не действует
            out.add(key.strip())
    return out


def check():
    f = Findings("секреты")
    allowed = allowlist()
    all_files = files()
    for rel in all_files:
        name = os.path.basename(rel)
        for rule, masks in FILE_RULES:
            if any(fnmatch.fnmatch(name, m) for m in masks) and f"{rel}:{rule}" not in allowed:
                f.add(rel, f"[{rule}] файл ключа или секрета в репозитории")
        if not is_text(rel) or rel == ALLOW_FILE:
            continue
        for no, line in enumerate(read(rel).splitlines(), 1):
            for rule, pattern in TEXT_RULES:
                if re.search(pattern, line) and f"{rel}:{rule}" not in allowed:
                    f.add(f"{rel}:{no}", f"[{rule}] похоже на секрет (значение не показывается)")
    return f.finish(f"файлов — {len(all_files)}, правил — {len(TEXT_RULES) + len(FILE_RULES)}")


if __name__ == "__main__":
    main(check)
