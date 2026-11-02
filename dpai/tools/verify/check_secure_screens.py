#!/usr/bin/env python3
"""Экраны, показывающие секреты, закрыты от снимков (D-86, T8).

Экран терминала несёт код входа Claude (`/login`) и содержимое сессии,
экран запросов — тексты предложений. Такие окна обязаны стоять с
`FLAG_SECURE`: иначе содержимое попадает в превью «недавних», в снимок
экрана и в запись экрана, хотя в файлы приложения оно не пишется.

Реестр — `tools/verify/registry/secure_screens.txt`: путь файла экрана и
(через `:`) имя класса, в котором флаг обязан стоять. Проверка:

  * каждый файл реестра существует и содержит `FLAG_SECURE` в `onCreate`;
  * диалоги экрана — тоже: `AlertDialog.Builder(...).show()` без
    `setFlags(FLAG_SECURE)` у окна диалога — находка (у диалога своё окно);
  * новый экран, показывающий секреты, — строка в реестре, в том же
    изменении (`docs/PROCESS.md`, «Правила»).

    python3 tools/verify/check_secure_screens.py

Код возврата: 0 — чисто; 1 — нарушения.
"""
import os
import re

from common import Findings, main, read, root

REGISTRY = "tools/verify/registry/secure_screens.txt"

# Диалог со своим окном: FLAG_SECURE активности его не закрывает.
DIALOG = re.compile(r"\b(?:AlertDialog|Dialog)\b")
FLAG = "FLAG_SECURE"
# Диалог Compose ставит флаг своему окну сам — через `DialogProperties(securePolicy = …)`.
COMPOSE_SECURE = "SecureFlagPolicy.SecureOn"


def entries():
    path = os.path.join(root(), REGISTRY)
    if not os.path.isfile(path):
        return None
    out = []
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.split("#", 1)[0].strip()
            if line:
                out.append(line)
    return out


def blank_strings(text):
    """Текст без комментариев и содержимого строк: ищем код, а не упоминания.

    Переводы строк сохраняются: иначе номер строки в находке сдвигается.
    """
    def keep_lines(m):
        return "\n" * m.group(0).count("\n")

    text = re.sub(r"/\*.*?\*/", keep_lines, text, flags=re.S)
    text = re.sub(r"//[^\n]*", "", text)
    return re.sub(r'"(?:\\.|[^"\\\n])*"', '""', text)


def check():
    f = Findings("экраны с секретами")
    rows = entries()
    if rows is None:
        f.add(REGISTRY, "нет реестра экранов, которые обязаны стоять с FLAG_SECURE")
        return f.finish("экранов — 0")
    for row in rows:
        rel = row.split(":", 1)[0]
        if not os.path.isfile(os.path.join(root(), rel)):
            f.add(row, f"экран {rel} из реестра не найден")
            continue
        code = blank_strings(read(rel))
        if FLAG not in code and COMPOSE_SECURE not in code:
            f.add(rel, f"экран показывает секреты, а {FLAG} не стоит (D-86)")
        # Диалог со своим окном: от объявления диалога до его показа флаг обязан быть.
        # Внутри диалога бывают `Toast.show()` — поэтому берём последний показ до
        # следующего диалога, а не первый.
        starts = [m.start() for m in DIALOG.finditer(code)]
        for i, start in enumerate(starts):
            end = starts[i + 1] if i + 1 < len(starts) else len(code)
            block = code[start:min(end, start + 4000)]
            shows = list(re.finditer(r"\.\s*show\s*\(\s*\)", block))
            if not shows:
                continue
            line = code.count("\n", 0, start) + 1
            if FLAG not in block[:shows[-1].end()]:
                f.add(f"{rel}:{line}", f"у диалога своё окно: {FLAG} нужен и ему (D-86)")
    return f.finish(f"экранов — {len(rows)}")


if __name__ == "__main__":
    main(check)
