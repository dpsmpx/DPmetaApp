#!/usr/bin/env python3
"""Запрещённые API и места (D-03, D-04, D-07, D-14).

Правила проверяют код Kotlin без комментариев и строк (кроме правила
`provider-private`, которому строки и нужны). У каждого правила — список
разрешённых мест с причиной; новое место — правка списка в том же
изменении, с причиной (docs/PROCESS.md, «Правила»).

    python3 tools/verify/check_forbidden_apis.py

Код возврата: 0 — чисто; 1 — нарушения.
"""
import re

from common import Findings, kotlin_sources, line_of, main, read, strip_kotlin_comments_and_strings

CLOCK_PKG = "core/src/main/kotlin/com/dpsmpx/dpai/core/clock/"

# (id, шаблон, разрешённые префиксы путей, объяснение, смотреть ли в строки)
RULES = [
    ("system-time",
     r"\bSystem\.(?:currentTimeMillis|nanoTime)\s*\(|\b(?:Instant|LocalDateTime|LocalDate|LocalTime|"
     r"ZonedDateTime|OffsetDateTime)\.now\s*\(|\bClock\.system(?:UTC|DefaultZone)\s*\(",
     # Единственная граница с системными часами (D-07).
     [CLOCK_PKG],
     "системное время — только через Clock (D-07)", False),
    ("randomness",
     r"\bRandom\(\s*\)|\bSecureRandom\s*\(|\bMath\.random\s*\(|\bRandom\.(?:Default|next\w*)\b|"
     r"\bUUID\.randomUUID\s*\(|\bThreadLocalRandom\b",
     # Единственная граница с источниками случайности (D-07).
     [CLOCK_PKG],
     "случайность — только через RandomSource (D-07)", False),
    ("sleep", r"\bThread\.sleep\s*\(", [],
     "сон запрещён: время — через ManualClock.advance (D-07, ТЗ §90)", False),
    ("global-scope", r"\bGlobalScope\b", [], "GlobalScope запрещён (D-07)", False),
    ("run-blocking", r"\brunBlocking\b",
     ["cli/"],  # точка входа процесса
     "runBlocking — только в cli", False),
    ("print", r"(?<![\w.])(?:println|print)\s*\(|\bSystem\.(?:out|err)\b",
     ["cli/"],  # вывод пользователю — дело оболочки
     "печать — только в cli; остальное — диагностика и журнал", False),
    ("stack-trace", r"\.printStackTrace\s*\(", [],
     "стек не печатается: исключение — сводка в Diagnostic (D-05)", False),
    ("process-spawn", r"\bProcessBuilder\b|\bRuntime\.getRuntime\s*\(\s*\)\s*\.exec",
     ["execution/src/main/"],  # запуск процессов — только execution (D-03)
     "процессы — только в модуле execution (D-03)", False),
    ("secret-reveal", r"\.reveal\s*\(\s*\)",
     [
         # Сам Secret и его тест.
         "core/src/main/kotlin/com/dpsmpx/dpai/core/diagnostics/Secret.kt",
         "core/src/test/kotlin/com/dpsmpx/dpai/core/diagnostics/SecretTest.kt",
     ],
     "reveal() — только в разрешённых местах (D-14)", False),
    ("provider-private",
     r"claude\.ai/(?:api|chat|login|oauth)|\bsessionKey\b|\bCookieManager\b|\bCookieHandler\b|"
     r"\bHttpCookie\b",
     [],
     "приватные адреса и cookies провайдера запрещены (D-04)", True),
]


def check():
    f = Findings("запрещённые API")
    srcs = kotlin_sources()
    for rel in srcs:
        raw = read(rel)
        code = strip_kotlin_comments_and_strings(raw)
        for rule, pattern, allowed, why, in_strings in RULES:
            if any(rel.startswith(a) for a in allowed):
                continue
            text = raw if in_strings else code
            for m in re.finditer(pattern, text):
                f.add(f"{rel}:{line_of(text, m.start())}", f"[{rule}] {why}")
    return f.finish(f"исходников Kotlin — {len(srcs)}, правил — {len(RULES)}")


if __name__ == "__main__":
    main(check)
