"""Модель данных: находка, результат проверки, статусы (ТЗ, §6.3)."""
import enum
import hashlib
from dataclasses import dataclass, field


class Status(enum.Enum):
    PASS = "pass"
    FAIL = "fail"
    SKIP = "skip"
    ERROR = "error"


SYMBOL = {Status.PASS: "✓", Status.FAIL: "✗", Status.SKIP: "○", Status.ERROR: "!"}

ERROR = "error"
WARNING = "warning"


@dataclass
class Finding:
    """Одно нарушение. `evidence` — нормализованная улика для отпечатка; в отчёт не идёт.
    `subject` — по чему ищет `match` исключения, если не по улике (значение секрета: улика
    секрета — его SHA-256); не пишется никуда (F-24)."""

    rule: str
    message: str
    path: str = None
    line: int = None
    hint: str = ""
    severity: str = ERROR
    evidence: str = ""
    check: str = ""
    fingerprint: str = ""
    accepted: str = None
    subject: str = None

    def match_text(self):
        """Текст для `match` исключения (F-16)."""
        return self.subject if self.subject is not None else (self.evidence or self.message)

    def sort_key(self):
        return ((self.path or "").encode(), self.line or 0, self.rule, self.message)

    def to_json(self):
        return {
            "rule": self.rule, "path": self.path, "line": self.line,
            "severity": self.severity, "message": self.message, "hint": self.hint,
            "fingerprint": self.fingerprint, "accepted": self.accepted,
        }


@dataclass
class Outcome:
    """Что вернула встроенная проверка: сколько проверено, что найдено, итоговая строка.
    `skip` — причина пропуска (○): проверке не на чем работать по окружению, а не «пусто»."""

    scanned: int
    findings: list = field(default_factory=list)
    summary: str = ""
    skip: str = None


@dataclass
class CheckResult:
    id: str
    kind: str  # builtin | command
    status: Status
    scanned: int = 0
    findings: list = field(default_factory=list)
    skip_reason: str = None
    summary: str = ""
    excerpt: list = field(default_factory=list)  # строки сбоя команды — только на экран и в журнал
    duration: float = 0.0

    def errors(self):
        return [f for f in self.findings if f.severity == ERROR and f.accepted is None]

    def warnings(self):
        return [f for f in self.findings if f.severity == WARNING and f.accepted is None]

    def to_json(self):
        return {
            "id": self.id, "kind": self.kind, "status": self.status.value,
            "scanned": self.scanned, "skip_reason": self.skip_reason,
            "findings": [f.to_json() for f in self.findings],
        }


def assign_fingerprints(check_id, findings):
    """F-03: отпечаток не зависит от строки; одинаковые улики различает порядковый номер."""
    seen = {}
    for f in sorted(findings, key=Finding.sort_key):
        f.check = check_id
        base = "\0".join([check_id, f.rule, f.path or "", f.evidence or f.message])
        n = seen.get(base, 0)
        seen[base] = n + 1
        digest = hashlib.sha256(f"{base}\0{n}".encode()).hexdigest()
        f.fingerprint = "sha256:" + digest
