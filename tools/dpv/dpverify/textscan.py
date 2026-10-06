"""Общие разборщики текста: Markdown (блоки кода, ссылки, заголовки, якоря)."""
import re

FENCE = re.compile(r"^\s{0,3}(`{3,}|~{3,})")
HEADING = re.compile(r"^\s{0,3}(#{1,6})\s+(.*?)\s*#*\s*$")
LINK = re.compile(r"!?\[(?:[^\]\\]|\\.)*\]\(\s*(<[^>]*>|[^)\s]+)(?:\s+(?:\"[^\"]*\"|'[^']*'))?\s*\)")
REF_DEF = re.compile(r"^\s{0,3}\[[^\]]+\]:\s*(<[^>]*>|\S+)")
INLINE_CODE = re.compile(r"(`+)(.+?)\1")
ANCHOR_TAG = re.compile(r"<a\s+(?:[^>]*?\s)?(?:id|name)\s*=\s*[\"']([^\"']+)[\"']", re.I)


def fenced(lines):
    """Множество номеров строк (с 1) внутри блоков кода ``` и ~~~, включая сами ограды."""
    inside, marker, out = False, "", set()
    for n, line in enumerate(lines, 1):
        m = FENCE.match(line)
        if inside:
            out.add(n)
            if m and m.group(1)[0] == marker[0] and len(m.group(1)) >= len(marker):
                inside = False
        elif m:
            inside, marker = True, m.group(1)
            out.add(n)
    return out


def blank_inline_code(line):
    """Встроенный код → пробелы той же длины (позиции остальных символов сохраняются)."""
    return INLINE_CODE.sub(lambda m: " " * len(m.group(0)), line)


def links(text):
    """Ссылки Markdown вне кода → [(строка, цель)]: [..](цель), ![..](цель), [ref]: цель."""
    lines = text.splitlines()
    skip = fenced(lines)
    out = []
    for n, line in enumerate(lines, 1):
        if n in skip:
            continue
        plain = blank_inline_code(line)
        for m in LINK.finditer(plain):
            out.append((n, m.group(1).strip("<>")))
        m = REF_DEF.match(plain)
        if m:
            out.append((n, m.group(1).strip("<>")))
    return out


def headings(text):
    """[(уровень, текст, номер строки)] заголовков ATX вне блоков кода."""
    lines = text.splitlines()
    skip = fenced(lines)
    out = []
    for n, line in enumerate(lines, 1):
        if n in skip:
            continue
        m = HEADING.match(line)
        if m:
            out.append((len(m.group(1)), m.group(2), n))
    return out


# Выделенное начало абзаца или пункта, в том числе после метки (- `М` **Название**).
BOLD_LEAD = re.compile(r"^\s*(?:>\s*)*(?:[-*+]\s+|\d+[.)]\s+)?(?:`[^`\n]+`\s+)?\*\*([^*\n]+?)\*\*")
# Строка-подпись: «Как читать:» — отдельной строкой.
LABEL = re.compile(r"^(\S[^`*#|\n]{0,60}?):\s*$")


def plain_heading(text):
    """Текст заголовка без HTML-якорей и тегов: «<a id="6"></a>6. Цель» → «6. Цель»."""
    text = re.sub(r"!?\[([^\]]*)\]\([^)]*\)", r"\1", text)
    return re.sub(r"</?[a-zA-Z][^>]*>", "", text).strip()


def sections(text):
    """Названия разделов документа: заголовки и выделенные начала абзацев и пунктов
    (**Окно по содержимому.**) — так на разделы ссылаются в проектах автора."""
    lines = text.splitlines()
    skip = fenced(lines)
    out = [plain_heading(h) for _, h, _ in headings(text)]
    for n, line in enumerate(lines, 1):
        if n not in skip:
            m = BOLD_LEAD.match(line) or LABEL.match(line)
            if m:
                out.append(m.group(1).strip().rstrip(".:"))
    return out


def slug(heading):
    """Якорь заголовка по правилам GitHub: без разметки, нижний регистр,
    знаки препинания убираются, пробел — «-», буквы любого алфавита остаются."""
    text = ANCHOR_TAG.sub("", heading)
    text = re.sub(r"</?[a-zA-Z][^>]*>", "", text)
    text = re.sub(r"!?\[([^\]]*)\]\([^)]*\)", r"\1", text)
    text = text.replace("`", "").replace("*", "").lower()
    text = "".join(ch for ch in text if ch.isalnum() or ch in " -_")
    return text.replace(" ", "-")


def anchors(text):
    """Все якоря документа: заголовки (повтор — -1, -2) и явные <a id|name>."""
    seen, out = {}, set()
    for _, title, _ in headings(text):
        base = slug(title)
        n = seen.get(base, 0)
        seen[base] = n + 1
        out.add(base if n == 0 else f"{base}-{n}")
    out |= {m.group(1) for m in ANCHOR_TAG.finditer(text)}
    return out


# ---- комментарии и строки кода (code.forbidden; ТЗ, §8.2, L-04) ----

C_LIKE = (".c", ".h", ".cpp", ".hpp", ".cc", ".java", ".kt", ".kts", ".glsl", ".vert", ".frag", ".comp")
NESTED = (".kt", ".kts")  # в Kotlin блоки /* */ вкладываются; в C, C++, Java, GLSL — нет
CHAR = re.compile(r"'(?:\\u[0-9a-fA-F]{4}|\\.|[^'\\\n])'")
PY_STRING = re.compile(r"[rRbBuUfF]{0,2}('''|\"\"\"|'|\")")


def language(path):
    """Разборщик по расширению: c, kotlin, python, shell или None (только scope = "text")."""
    low = path.lower()
    if low.endswith(NESTED):
        return "kotlin"
    if low.endswith(C_LIKE):
        return "c"
    if low.endswith(".py"):
        return "python"
    if low.endswith((".sh", ".bash")):
        return "shell"
    return None


def _blank(chunk):
    return "".join(ch if ch == "\n" else " " for ch in chunk)


def _literal(chunk, keep, quote):
    """Строковый литерал: как есть или пробелами между кавычками (длина и переводы строк — те же)."""
    if keep or len(chunk) < 2 * quote:
        return chunk if keep else _blank(chunk)
    return chunk[:quote] + _blank(chunk[quote:-quote]) + chunk[-quote:]


def _scan_quoted(text, i, quote, multiline):
    """Конец литерала с экранированием обратной косой: индекс после закрывающей кавычки."""
    j, n = i + len(quote), len(text)
    while j < n:
        if text[j] == "\\":
            j += 2
            continue
        if text.startswith(quote, j):
            return j + len(quote)
        if text[j] == "\n" and not multiline:
            return j
        j += 1
    return n


def code_view(text, lang, strings=True, nested=None):
    """Текст той же длины и с теми же переводами строк: комментарии — пробелами; при
    strings=False — и содержимое строк. Номер строки и столбец находки не сдвигаются.
    `nested` (список) получает номера строк, где в Kotlin внутри комментария открыт вложенный."""
    if lang in ("c", "kotlin"):
        return _c_like(text, lang == "kotlin", strings, nested)
    if lang == "python":
        return _python(text, strings)
    if lang == "shell":
        return _shell(text, strings)
    raise ValueError(lang)


def _c_like(text, nesting, strings, nested=None):
    out, i, n, depth = [], 0, len(text), 0
    while i < n:
        c = text[i]
        if depth:
            if nesting and text.startswith("/*", i):
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
            out.append(_literal(text[i:j], strings, 3))
            i = j
        elif c == '"':
            j = _scan_quoted(text, i, '"', False)
            out.append(_literal(text[i:j], strings, 1))
            i = j
        elif c == "'" and CHAR.match(text, i):
            j = CHAR.match(text, i).end()
            out.append(_literal(text[i:j], strings, 1))
            i = j
        else:
            out.append(c)
            i += 1
    return "".join(out)


def _python(text, strings):
    out, i, n = [], 0, len(text)
    while i < n:
        c = text[i]
        m = PY_STRING.match(text, i) if (c in "'\"" or (c.isalpha() and (i == 0 or not (text[i - 1].isalnum()
                                                                                        or text[i - 1] == "_")))) else None
        if m:
            quote = m.group(1)
            j = _scan_quoted(text, m.start(1), quote, len(quote) == 3)
            out.append(text[i:m.start(1)] + _literal(text[m.start(1):j], strings, len(quote)))
            i = j
        elif c == "#":
            j = text.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
        else:
            out.append(c)
            i += 1
    return "".join(out)


def _shell(text, strings):
    out, i, n = [], 0, len(text)
    while i < n:
        c = text[i]
        if c == "\\" and i + 1 < n:
            out.append(text[i:i + 2])
            i += 2
        elif c == "'":
            j = text.find("'", i + 1)
            j = n if j < 0 else j + 1
            out.append(_literal(text[i:j], strings, 1))
            i = j
        elif c == '"':
            j = _scan_quoted(text, i, '"', True)
            out.append(_literal(text[i:j], strings, 1))
            i = j
        elif c == "#" and (i == 0 or text[i - 1] in " \t\n;|&("):
            j = text.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
        else:
            out.append(c)
            i += 1
    return "".join(out)
