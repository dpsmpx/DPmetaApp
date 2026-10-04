#!/usr/bin/env python3
"""Запрещённые API в коде Kotlin — правила, которых нет у компилятора.

Каждое правило — с причиной и списком мест, где вызов разрешён. Комментарии
не проверяются (в KDoc можно написать, чего делать нельзя); строки —
проверяются (имя алгоритма шифрования — строка).

Правила:
  dns-resolve      InetAddress.getByName/getAllByName/getLocalHost — для
                   не-литерала идёт в DNS мимо туннеля (утечка, T4);
                   IP разбирается core.IpAddress, адрес — getByAddress.
  secret-reveal    Secret.reveal() — только там, где секрет нужен как
                   значение: разбор и запись конфигурации, компиляторы
                   ядер, хранилище (D-11). Тесты — можно.
  stack-trace      printStackTrace — стек может нести секреты; причина
                   — ErrorSummary через Redactor (D-22).
  console          println/System.out/System.err — только в cli/;
                   остальное пишет Logger (редактирование по умолчанию).
  wall-clock       System.currentTimeMillis/nanoTime, Instant.now… —
                   время только через core.Clock (детерминизм тестов,
                   D-21); исключения — Clock.SYSTEM и замеры cli.
  weak-random      kotlin.random/java.util.Random/Math.random в основном
                   коде — для ключей нужен SecureRandom, для логики —
                   явное время и порядок, а не случай (D-15).
  weak-crypto      MD5, SHA-1, DES, RC4, ECB, Cipher «AES» без режима.
  trust-override   свои TrustManager/HostnameVerifier — отключение
                   проверки сертификата (T5); запрещено везде, и в тестах.
  android-log      android.util.Log — мимо редактирования журнала.
  blocking         Thread.sleep, GlobalScope, runBlocking вне cli/ —
                   ожидание через pause/Clock, жизненный цикл — у scope.
  native-load      System.load/loadLibrary, Runtime.load*, external fun —
                   нативный код только моста ядра (D-25): объявления JNI
                   в engine.xray.XrayRuntime, загрузка — в AppGraph и в
                   тесте JNI на хосте; чужая .so в процессе — мимо
                   сверки версии и экспорта (native.py, check_apk.py).
  nested-comment   «/*» внутри комментария: в Kotlin блоки вкладываются,
                   и путь вида `tests/data/**` в KDoc открывает новый
                   комментарий — код после него молча становится
                   комментарием (или файл не собирается). Пишите
                   `tests/data/…` или без звёздочки после косой черты.

    python3 tools/security/check_forbidden_apis.py

Код возврата: 0 — чисто; 1 — нарушения.
"""
import os
import re
import subprocess
import sys

PROJ = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def main_code(path):
    return "/src/main/" in path


def test_code(path):
    # Любой набор исходников, кроме основного: src/test и отдельные тестовые (src/xrayJni).
    return "/src/" in path and not main_code(path)


def under(*prefixes):
    return lambda path: any(path.startswith(p) for p in prefixes)


# (имя, регулярное выражение, где разрешено (path → bool), причина)
RULES = [
    ("dns-resolve", r"\b(getByName|getAllByName|getLocalHost)\s*\(", lambda p: False,
     "резолв имени мимо туннеля; IP — core.IpAddress, байты → InetAddress.getByAddress"),
    ("secret-reveal", r"\.reveal\s*\(\s*\)",
     lambda p: test_code(p) or under(
         "diagnostics/src/main/kotlin/com/dpsmpx/dppass/diagnostics/Secret.kt",
         "config/src/main/kotlin/com/dpsmpx/dppass/config/ConfigValidator.kt",
         "config/src/main/kotlin/com/dpsmpx/dppass/config/ConfigWriter.kt",
         "engine/src/main/",
         "storage/src/main/",
     )(p),
     "значение секрета — только разбор/запись конфигурации, компиляторы ядер, хранилище"),
    ("stack-trace", r"\bprintStackTrace\s*\(", lambda p: False,
     "стек может нести секреты; причина — Diagnostic.fromThrowable"),
    ("console", r"(?<![\w.])(println|print)\s*\(|\bSystem\.(out|err)\b",
     lambda p: not main_code(p) or under("cli/")(p),
     "вывод в консоль — только cli/; остальное — Logger"),
    ("wall-clock", r"\bSystem\.(currentTimeMillis|nanoTime)\s*\(|\b(Instant|LocalDateTime|LocalDate|ZonedDateTime|OffsetDateTime)\.now\s*\(|\bDate\s*\(\s*\)",
     lambda p: not main_code(p) or under(
         "core/src/main/kotlin/com/dpsmpx/dppass/core/Support.kt",
         "cli/src/main/kotlin/com/dpsmpx/dppass/cli/Bench.kt",
     )(p),
     "время — только через core.Clock"),
    ("weak-random", r"\bkotlin\.random\b|\bjava\.util\.Random\b|\bMath\.random\s*\(|(?<![\w.])Random\s*\(",
     lambda p: not main_code(p),
     "ключи — SecureRandom; решения — детерминированно"),
    ("weak-crypto", r'"(MD5|SHA-?1|DES|DESede|RC4|ARCFOUR)"|"[A-Za-z0-9]+/ECB/|Cipher\.getInstance\s*\(\s*"AES"\s*\)',
     lambda p: False,
     "слабый алгоритм или режим шифрования"),
    ("trust-override", r"\b(X509TrustManager|X509ExtendedTrustManager|HostnameVerifier|setHostnameVerifier|setDefaultHostnameVerifier|TrustAllCerts|ALLOW_ALL_HOSTNAME_VERIFIER)\b",
     lambda p: False,
     "своя проверка сертификата = её отключение (T5)"),
    ("android-log", r"\bandroid\.util\.Log\b|(?<![\w.])Log\.(v|d|i|w|e|wtf)\s*\(",
     lambda p: False,
     "журнал — только Logger с редактированием"),
    ("blocking", r"\bThread\.sleep\s*\(|\bGlobalScope\b|\brunBlocking\b",
     lambda p: not main_code(p) or under("cli/")(p),
     "ожидание — через pause/Clock; корутины — в своём scope"),
    ("native-load", r"\bSystem\.load(Library)?\s*\(|\bRuntime\.getRuntime\(\)\.load|\bexternal\s+fun\b",
     under(
         "app/src/main/kotlin/com/dpsmpx/dppass/app/AppGraph.kt",
         "engine/src/main/kotlin/com/dpsmpx/dppass/engine/xray/XrayRuntime.kt",
         "tests/integration/src/xrayJni/",
     ),
     "нативный код — только мост ядра (D-25): JNI — XrayRuntime, загрузка — AppGraph и тест JNI"),
]


def kotlin_files():
    try:
        out = subprocess.run(["git", "ls-files", "--cached", "--others", "--exclude-standard", "*.kt", "*.kts"],
                             cwd=PROJ, capture_output=True, text=True, check=True).stdout.split()
        return [p for p in out if os.path.isfile(os.path.join(PROJ, p))]
    except (OSError, subprocess.CalledProcessError):
        found = []
        for dirpath, dirs, files in os.walk(PROJ):
            dirs[:] = [d for d in dirs if d not in (".git", "build", ".gradle")]
            found += [os.path.relpath(os.path.join(dirpath, f), PROJ) for f in files if f.endswith((".kt", ".kts"))]
        return found


def strip_comments(text, nested=None):
    """Комментарии → пробелы (переводы строк сохраняются); строки остаются.

    [nested] (если передан) получает номера строк, где внутри комментария
    открывается вложенный — см. правило nested-comment.
    """
    out, i, n = [], 0, len(text)
    depth = 0  # вложенность /* */ (в Kotlin блоки вкладываются)
    while i < n:
        c = text[i]
        if depth:
            if text.startswith("/*", i):
                if nested is not None:
                    nested.append(text.count("\n", 0, i) + 1)
                depth += 1
                out.append("  ")
                i += 2
            elif text.startswith("*/", i):
                depth -= 1
                out.append("  ")
                i += 2
            else:
                out.append("\n" if c == "\n" else " ")
                i += 1
        elif text.startswith("//", i):
            j = text.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
        elif text.startswith("/*", i):
            depth = 1
            out.append("  ")
            i += 2
        elif text.startswith('"""', i):
            j = text.find('"""', i + 3)
            j = n if j < 0 else j + 3
            out.append(text[i:j])
            i = j
        elif c == '"':
            j = i + 1
            while j < n and text[j] != '"' and text[j] != "\n":
                j += 2 if text[j] == "\\" else 1
            out.append(text[i:j + 1])
            i = j + 1
        elif c == "'":
            m = re.match(r"'(\\.|[^'\\])'", text[i:i + 8])
            j = i + (len(m.group(0)) if m else 1)
            out.append(text[i:j])
            i = j
        else:
            out.append(c)
            i += 1
    return "".join(out)


def main():
    rules = [(name, re.compile(rx), ok, why) for name, rx, ok, why in RULES]
    problems, files = [], 0
    for rel in sorted(kotlin_files()):
        files += 1
        with open(os.path.join(PROJ, rel), encoding="utf-8") as f:
            nested = []
            code = strip_comments(f.read(), nested)
        for n in nested:
            problems.append(f"{rel}:{n}: [nested-comment] «/*» внутри комментария — в Kotlin это вложенный комментарий, а не текст")
        for n, line in enumerate(code.splitlines(), 1):
            for name, rx, ok, why in rules:
                if rx.search(line) and not ok(rel):
                    problems.append(f"{rel}:{n}: [{name}] {line.strip()[:120]} — {why}")
    if problems:
        print("✗ Запрещённые API:")
        for p in problems:
            print("    " + p)
        print("  Правила и разрешённые места — tools/security/check_forbidden_apis.py; новое исключение — с причиной.")
        return 1
    print(f"✓ Запрещённые API: {files} файлов Kotlin, {len(rules) + 1} правил — нарушений нет")
    return 0


if __name__ == "__main__":
    sys.exit(main())
