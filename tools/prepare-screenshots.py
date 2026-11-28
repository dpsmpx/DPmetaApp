#!/usr/bin/env python3
"""Подготовка проверенного образа Robolectric; повторяется только сетевой отказ HTTP."""
import argparse
from pathlib import Path
import re
import subprocess
import sys
import time


ROOT = Path(__file__).resolve().parents[1]
COMMAND = ("./gradlew", "--console=plain", ":shell:prepareRobolectricImage", "-Pdpmeta.screenshots")
# Только явно временные ответы сервера; ошибки сборки, сумм и TLS не исправляет повтор.
TRANSIENT = re.compile(r"Received status code (?:429|502|503|504) from server:")
PERMANENT = re.compile(
    r"Dependency verification failed|verification failed|checksum.*(?:failed|mismatch)|"
    r"Script compilation error|Compilation error|^e:|"
    r"Received status code (?!429\b|502\b|503\b|504\b)\d+ from server:",
    re.IGNORECASE | re.MULTILINE,
)


def prepare(root: Path, logs: Path, sleep=time.sleep) -> int:
    logs.mkdir(parents=True, exist_ok=True)
    for attempt in range(1, 4):
        print(f"Robolectric: preparation {attempt}/3", flush=True)
        path = logs / f"prepare-{attempt}.log"
        with path.open("w", encoding="utf-8") as log:
            with subprocess.Popen(COMMAND, cwd=root, stdout=subprocess.PIPE,
                                  stderr=subprocess.STDOUT, text=True,
                                  encoding="utf-8", errors="replace") as process:
                for line in process.stdout:
                    log.write(line)
                    print(line, end="", flush=True)
                code = process.wait()
        if code == 0:
            return 0
        output = path.read_text(encoding="utf-8")
        if attempt == 3 or not TRANSIENT.search(output) or PERMANENT.search(output):
            return code if code > 0 else 1
        delay = 15 * attempt
        print(f"Robolectric: temporary HTTP failure; retry in {delay} s. Tests have not run.", flush=True)
        sleep(delay)
    return 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--log-dir", type=Path, default=ROOT / "build/reports/robolectric-preparation")
    args = parser.parse_args()
    try:
        return prepare(ROOT, args.log_dir)
    except OSError as error:
        print(f"Robolectric: preparation could not run: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
