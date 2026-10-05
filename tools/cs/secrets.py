"""Поиск секретов и личных данных во всех файлах репозитория (Д2, Д3, docs/SECURITY.md).

Замена поиску секретов GitHub, которого нет в закрытом репозитории личного
аккаунта. Проверяются отслеживаемые и новые файлы (кроме .gitignore).
Ошибка — ключи, токены, приватные ключи, cookies, подписанные адреса,
файлы секретов и слова из списка личных слов автора (Д40); предупреждение —
адреса почты и приглашения Discord.

Список личных слов лежит у автора вне Git (`private_words_path()`): в
репозитории его быть не может — в нём то, что он защищает. Найденное слово
не печатается, только файл и строка. Без списка проверка помечена LIMITED.
"""
from __future__ import annotations

import os
import re
from pathlib import Path

from .common import ROOT, Report, is_text, rel, repo_files

PRIVATE_WORDS_ENV = "DPSMPX_PRIVATE_WORDS"
PRIVATE_WORDS_DEFAULT = "~/.config/dpsmpx/private-words.txt"

PATTERNS = [
    ("приватный ключ", re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |DSA |PGP )?PRIVATE KEY")),
    ("токен GitHub", re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{50,})\b")),
    ("ключ AWS", re.compile(r"\bAKIA[0-9A-Z]{16}\b")),
    ("ключ Google API", re.compile(r"\bAIza[0-9A-Za-z_-]{35}\b")),
    ("ключ Anthropic/OpenAI", re.compile(r"\bsk-(?:ant-)?[A-Za-z0-9_-]{32,}\b")),
    ("токен Slack", re.compile(r"\bxox[abprs]-[A-Za-z0-9-]{10,}\b")),
    ("токен бота Telegram", re.compile(r"\b\d{8,10}:AA[A-Za-z0-9_-]{33}\b")),
    ("токен Discord", re.compile(r"\b[MNO][A-Za-z\d_-]{23,25}\.[A-Za-z\d_-]{6}\.[A-Za-z\d_-]{27,}\b")),
    ("JWT", re.compile(r"\beyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}")),
    ("строка cookies", re.compile(r"^[.\w-]+\.\w+\t(?:TRUE|FALSE)\t/", re.M)),
    ("подписанный адрес или сессия", re.compile(
        r"[?&](?:x-signature|signature|sig|refresh_token|access_token|sessionid|session_id|sid|auth|_t|_r)=[^&\s)\"']{4,}",
        re.I)),
    ("пароль или ключ в присваивании", re.compile(
        r"(?i)\b(?:password|passwd|pwd|api[_-]?key|secret[_-]?key|client[_-]?secret|auth[_-]?token)\b\s*[:=]\s*"
        r"['\"]?(?=[^\s'\"]*\d)(?=[^\s'\"]*[A-Za-z])[^\s'\"<>${}]{12,}")),
]
WARN_PATTERNS = [
    ("адрес почты (личные данные)", re.compile(r"\b[\w.+-]+@(?!example\.|users\.noreply\.github\.com|anthropic\.com)[\w-]+\.[\w.]+\b")),
    ("приглашение Discord", re.compile(r"(?:discord\.gg|discord(?:app)?\.com/invite)/[\w-]+", re.I)),
]
SECRET_FILES = re.compile(r"(^|/)(\.env(\..*)?|.*\.(pem|key|p12|pfx)|id_(rsa|ed25519|ecdsa)|cookies?.*\.txt|.*\.cookies)$")


def private_words_path() -> Path:
    return Path(os.environ.get(PRIVATE_WORDS_ENV) or PRIVATE_WORDS_DEFAULT).expanduser()


def norm(text: str) -> str:
    """Для сравнения со списком личных слов: без регистра, ё = е."""
    return text.lower().replace("ё", "е")


def private_words(path: Path | None = None) -> list[str] | None:
    """Список личных слов автора (Д40): слово или основа на строку, # — комментарий.

    None — списка нет (облачная сессия): проверка ограничена. Слова короче
    трёх знаков не берутся — они совпадали бы с чем угодно.
    """
    p = path or private_words_path()
    if not p.is_file():
        return None
    words = set()
    for line in p.read_text(encoding="utf-8", errors="replace").splitlines():
        w = norm(line.split("#", 1)[0].strip())
        if len(w) >= 3:
            words.add(w)
    return sorted(words)


def private_lines(text: str, words: list[str]) -> list[int]:
    """Номера строк, где есть слово из списка (сами слова не возвращаются)."""
    return [i for i, line in enumerate(norm(text).splitlines(), 1) if any(w in line for w in words)]


def scan_text(text: str) -> list[tuple[str, str, int]]:
    """(уровень, что найдено, строка) — для тестов и проверки."""
    out = []
    for level, pats in (("error", PATTERNS), ("warn", WARN_PATTERNS)):
        for name, pat in pats:
            for m in pat.finditer(text):
                out.append((level, name, text.count("\n", 0, m.start()) + 1))
    return out


def check() -> Report:
    r = Report("secrets — секреты и личные данные")
    words = private_words()
    wp = private_words_path().resolve()
    if wp.is_file() and ROOT in wp.parents:
        r.error(f"{rel(wp)}: список личных слов внутри репозитория — его место вне Git (docs/SECURITY.md)")
    n = 0
    for f in repo_files():
        rp = rel(f)
        if SECRET_FILES.search(rp):
            r.error(f"{rp}: файл секретов в репозитории")
        text = is_text(f)
        if words and f.resolve() != wp:
            data = f.read_bytes().decode("utf-8", "ignore")
            for line in private_lines(data, words) if text else ([0] if private_lines(data, words) else []):
                r.error(f"{rp}{f':{line}' if line else ''}: слово из списка личных слов (Д40) — само слово не печатается")
        if not text:
            continue
        n += 1
        for level, name, line in scan_text(f.read_text(encoding="utf-8", errors="replace")):
            (r.error if level == "error" else r.warn)(f"{rp}:{line}: {name}")
    r.note(f"текстовых файлов {n}")
    if words is None:
        r.note(f"личные слова: LIMITED — списка нет ({PRIVATE_WORDS_DEFAULT} или ${PRIVATE_WORDS_ENV}; docs/SECURITY.md)")
    else:
        r.note(f"личные слова: список автора, слов {len(words)}")
    return r
