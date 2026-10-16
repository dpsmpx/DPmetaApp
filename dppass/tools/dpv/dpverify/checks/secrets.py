"""security.secrets — секретов в репозитории нет (ТЗ, §8.1).

Шаблоны — объединение DPpass, VoxelRPG и DPlang; минимальные длины — по
самому строгому из трёх (проверка ловит больше, а не меньше). Значение
секрета не печатается и не пишется: только первые 4 символа (F-24).
"""
import hashlib
import os
import re

from ..model import Finding, Outcome
from ..profile import REQUIRED
from .base import Check

PATTERNS = [
    ("github-token", "токен GitHub", r"\b(?:gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{40,})"),
    ("aws-key", "ключ AWS", r"\b(?:AKIA|ASIA)[0-9A-Z]{16}\b"),
    ("google-api-key", "ключ Google API", r"\bAIza[0-9A-Za-z_\-]{35}"),
    ("slack-token", "токен Slack", r"\bxox[abprs]-[0-9A-Za-z\-]{10,}"),
    ("telegram-bot-token", "токен Telegram-бота", r"\b\d{8,10}:AA[0-9A-Za-z_\-]{33}"),
    ("stripe-live-key", "ключ Stripe", r"\b[sr]k_live_[0-9A-Za-z]{20,}"),
    ("llm-api-key", "ключ Anthropic или OpenAI", r"\bsk-(?:ant-)?[A-Za-z0-9_\-]{32,}"),
    ("private-key", "закрытый ключ", r"-----BEGIN (?:[A-Z]+ )*PRIVATE KEY(?: BLOCK)?-----"),
]
COMBINED = re.compile("|".join(f"(?P<{rule.replace('-', '_')}>{rx})" for rule, _, rx in PATTERNS))
NAMES = {rule.replace("-", "_"): (rule, what) for rule, what, _ in PATTERNS}
KEY_FILE = re.compile(r"(?:\.(?:jks|keystore|p12|pfx|key)|(?:^|/)\.env|(?:^|/)\.git-credentials"
                      r"|(?:^|/)google-services\.json|(?:^|/)local\.properties)$", re.I)
EXTRA = {"id": ("str", REQUIRED), "regex": ("regex", REQUIRED), "reason": ("str", REQUIRED)}


def masked(value):
    return value[:4] + "…"


class Secrets(Check):
    ID = "security.secrets"
    MANDATORY = True
    SCANNED = "файлы, которые попадут в коммит (двоичные — только по имени)"
    SUMMARY = "токены, ключи и файлы ключей; значение не печатается и не пишется (F-24)"
    ORIGIN = "три check_secrets.py — DPpass, VoxelRPG, DPlang; длины — по самому строгому"
    _FIX = "убрать из коммита и в .gitignore; настоящий — сменить; базовая линия секреты не принимает (F-20)"
    _VALUE = "найденное значение (только в памяти)"
    RULES = {
        "github-token": ("gh[pousr]_… от 36 символов, github_pat_… от 40", _FIX, _VALUE),
        "aws-key": ("AKIA…, ASIA…", _FIX, _VALUE),
        "google-api-key": ("AIza…", _FIX, _VALUE),
        "slack-token": ("xox[abprs]-…", _FIX, _VALUE),
        "telegram-bot-token": ("токен Telegram-бота", _FIX, _VALUE),
        "stripe-live-key": ("sk_live_…, rk_live_…", _FIX, _VALUE),
        "llm-api-key": ("sk-ant-…, sk-… от 32 символов", _FIX, _VALUE),
        "private-key": ("-----BEGIN … PRIVATE KEY-----", _FIX, _VALUE),
        "key-file": ("файл ключа по имени: .jks, .keystore, .p12, .pfx, .key, .env, .git-credentials, "
                     "google-services.json, local.properties", _FIX, "путь файла"),
        "extra:<id>": ("шаблон профиля extra = [{ id, regex, reason }]; совпадение в самом профиле — не находка",
                       _FIX, _VALUE),
    }
    SETTINGS = {"extra": (("tables", EXTRA), [])}

    def run(self, ctx):
        repo, findings = ctx.repo, []
        extra = [(f"extra:{e['id']}", e["reason"], e["regex"]) for e in self.settings["extra"]]
        own = os.path.relpath(ctx.profile.path, repo.root).replace(os.sep, "/")
        files = repo.files()
        for path in files:
            if KEY_FILE.search(path):
                findings.append(Finding("key-file", f"файлу ключа или хранилища в репозитории не место",
                                        path=path, hint="убрать из git и в .gitignore; ключ — сменить",
                                        evidence=path))
            text = repo.text(path)
            if text is None:
                continue
            for n, line in enumerate(text.splitlines(), 1):
                for m in COMBINED.finditer(line):
                    rule, what = NAMES[m.lastgroup]
                    findings.append(self._finding(rule, what, path, n, m.group(0)))
                for rule, what, rx in extra if path != own else ():  # шаблон в профиле — не находка
                    for m in rx.finditer(line):
                        findings.append(self._finding(rule, what, path, n, m.group(0)))
        return Outcome(len(files), findings, f"файлов {len(files)}")

    @staticmethod
    def _finding(rule, what, path, line, value):
        return Finding(rule, f"{what} ({masked(value)})", path=path, line=line,
                       hint="убрать из коммита и сменить, если настоящий; ложное срабатывание — [[waiver]] "
                            "с причиной и match; базовая линия секреты не принимает (F-20)",
                       evidence=hashlib.sha256(value.encode()).hexdigest(), subject=value)
