"""Разметка канона: малое подмножество Markdown, которое каждая площадка умеет отдать честно.

Канон не содержит разметки площадок (PLATFORM_VISION §9, Д22) — только
смысловую: заголовки `##`/`###`, абзацы, списки `- `, **жирный**, *курсив*,
`код`, блоки кода между строками ```` ``` ```` (схемы, отступы сохраняются),
ссылки [текст](адрес) и голые https-адреса. Всё остальное — просто
текст. Рендеры: HTML (сайт), HTML Telegram, Markdown Discord, обычный
текст (VK, YouTube, пакеты для ручной публикации).

Подстановки `{{fact:…}}` и др. раскрываются до разбора (inject.py).
"""
from __future__ import annotations

import html
import re
from dataclasses import dataclass

# --------------------------------------------------------------- разбор

INLINE = re.compile(
    r"(?P<code>`[^`\n]+`)"
    r"|(?P<link>\[(?P<ltext>[^\]\n]+)\]\((?P<lurl>(?:https?://|/)[^)\s]*)\))"
    r"|(?P<bold>\*\*(?P<btext>[^*\n]+?)\*\*)"
    r"|(?P<italic>(?<![*\w])\*(?P<itext>[^*\s][^*\n]*?)(?<!\s)\*(?![*\w]))"
    r"|(?P<url>https?://[^\s<>()\[\]]+[^\s<>()\[\].,;:!?»\"'])"
)


@dataclass(frozen=True)
class Span:
    kind: str  # text | bold | italic | code | link
    text: str
    url: str = ""


@dataclass(frozen=True)
class Block:
    kind: str  # heading | para | list | pre
    level: int = 0
    lines: tuple = ()  # para, heading, pre: (spans,), list: (spans per item,); pre — один текстовый span


def inline(s: str) -> tuple[Span, ...]:
    out: list[Span] = []
    pos = 0
    for m in INLINE.finditer(s):
        if m.start() > pos:
            out.append(Span("text", s[pos:m.start()]))
        if m.group("code"):
            out.append(Span("code", m.group("code")[1:-1]))
        elif m.group("link"):
            out.append(Span("link", m.group("ltext"), m.group("lurl")))
        elif m.group("bold"):
            out.append(Span("bold", m.group("btext")))
        elif m.group("italic"):
            out.append(Span("italic", m.group("itext")))
        else:
            out.append(Span("link", m.group("url"), m.group("url")))
        pos = m.end()
    if pos < len(s):
        out.append(Span("text", s[pos:]))
    return tuple(out)


def parse(text: str) -> list[Block]:
    blocks: list[Block] = []
    para: list[str] = []
    items: list[str] = []

    def flush():
        nonlocal para, items
        if para:
            blocks.append(Block("para", 0, (inline(" ".join(x.strip() for x in para)),)))
            para = []
        if items:
            blocks.append(Block("list", 0, tuple(inline(i) for i in items)))
            items = []

    fence: list[str] | None = None
    for raw in text.splitlines():
        line = raw.rstrip()
        if fence is not None:
            if re.match(r"^\s*```\s*$", line):
                blocks.append(Block("pre", 0, ((Span("text", "\n".join(fence).strip("\n")),),)))
                fence = None
            else:
                fence.append(line)
            continue
        if re.match(r"^\s*```[\w-]*\s*$", line):
            flush()
            fence = []
            continue
        if not line.strip():
            flush()
            continue
        h = re.match(r"^(#{2,3})\s+(.+)$", line)
        li = re.match(r"^\s*[-•]\s+(.+)$", line)
        if h:
            flush()
            blocks.append(Block("heading", len(h.group(1)), (inline(h.group(2).strip()),)))
        elif li:
            if para:
                flush()
            items.append(li.group(1).strip())
        elif items and raw.startswith("  "):
            items[-1] += " " + line.strip()  # продолжение пункта
        else:
            if items:
                flush()
            para.append(line)
    flush()
    if fence is not None:  # незакрытый блок — текст до конца, а не потеря строк
        blocks.append(Block("pre", 0, ((Span("text", "\n".join(fence).strip("\n")),),)))
    return blocks


# --------------------------------------------------------------- рендеры

def _abs(url: str, base: str) -> str:
    return base.rstrip("/") + url if url.startswith("/") and base else url


def _spans_html(spans, base: str, tg: bool = False) -> str:
    out = []
    for s in spans:
        t = html.escape(s.text, quote=False)
        if s.kind == "text":
            out.append(t)
        elif s.kind == "bold":
            out.append(f"<b>{t}</b>" if tg else f"<strong>{t}</strong>")
        elif s.kind == "italic":
            out.append(f"<i>{t}</i>" if tg else f"<em>{t}</em>")
        elif s.kind == "code":
            out.append(f"<code>{t}</code>")
        else:
            href = html.escape(_abs(s.url, base), quote=True)
            out.append(f'<a href="{href}">{t}</a>')
    return "".join(out)


def to_html(blocks: list[Block], base: str = "") -> str:
    """HTML сайта. Ссылки на сайт (/…) — относительные, если base не задан."""
    out = []
    for b in blocks:
        if b.kind == "heading":
            out.append(f"<h{b.level}>{_spans_html(b.lines[0], base)}</h{b.level}>")
        elif b.kind == "para":
            out.append(f"<p>{_spans_html(b.lines[0], base)}</p>")
        elif b.kind == "pre":
            out.append(f"<pre><code>{html.escape(b.lines[0][0].text, quote=False)}</code></pre>")
        else:
            out.append("<ul>" + "".join(f"<li>{_spans_html(i, base)}</li>" for i in b.lines) + "</ul>")
    return "\n".join(out)


def to_telegram_html(blocks: list[Block], base: str) -> str:
    """parse_mode=HTML Bot API: <b>, <i>, <code>, <a>; заголовки — жирной строкой, списки — «•»."""
    out = []
    for b in blocks:
        if b.kind == "heading":
            out.append(f"<b>{_spans_html(b.lines[0], base, tg=True)}</b>")
        elif b.kind == "para":
            out.append(_spans_html(b.lines[0], base, tg=True))
        elif b.kind == "pre":
            out.append(f"<pre>{html.escape(b.lines[0][0].text, quote=False)}</pre>")
        else:
            out.append("\n".join(f"• {_spans_html(i, base, tg=True)}" for i in b.lines))
    return "\n\n".join(out)


_MD_ESC = re.compile(r"([\\*_~`|>])")


def _spans_md(spans, base: str) -> str:
    out = []
    for s in spans:
        t = _MD_ESC.sub(r"\\\1", s.text)
        if s.kind == "text":
            out.append(t)
        elif s.kind == "bold":
            out.append(f"**{t}**")
        elif s.kind == "italic":
            out.append(f"*{t}*")
        elif s.kind == "code":
            out.append(f"`{s.text}`")
        else:
            url = _abs(s.url, base)
            out.append(url if s.text == s.url else f"[{t}]({url})")
    return "".join(out)


def to_discord_md(blocks: list[Block], base: str) -> str:
    out = []
    for b in blocks:
        if b.kind == "heading":
            out.append("#" * b.level + " " + _spans_md(b.lines[0], base))
        elif b.kind == "para":
            out.append(_spans_md(b.lines[0], base))
        elif b.kind == "pre":
            out.append(f"```\n{b.lines[0][0].text}\n```")
        else:
            out.append("\n".join(f"- {_spans_md(i, base)}" for i in b.lines))
    return "\n\n".join(out)


def _spans_plain(spans, base: str) -> str:
    out = []
    for s in spans:
        if s.kind == "link":
            url = _abs(s.url, base)
            out.append(url if s.text == s.url else f"{s.text} — {url}")
        else:
            out.append(s.text)
    return "".join(out)


def to_plain(blocks: list[Block], base: str) -> str:
    out = []
    for b in blocks:
        if b.kind in ("heading", "para", "pre"):
            out.append(_spans_plain(b.lines[0], base))
        else:
            out.append("\n".join(f"• {_spans_plain(i, base)}" for i in b.lines))
    return "\n\n".join(out)


def visible_length(rendered_html: str) -> int:
    """Длина текста «после разбора разметки» (так считает Bot API)."""
    return len(html.unescape(re.sub(r"<[^>]+>", "", rendered_html)))


def links(blocks: list[Block]) -> list[str]:
    return [s.url for b in blocks for line in b.lines for s in line if s.kind == "link"]


def first_text(blocks: list[Block], limit: int = 200) -> str:
    """Первый абзац простым текстом — для описаний (meta, фиды)."""
    for b in blocks:
        if b.kind == "para":
            t = _spans_plain(b.lines[0], "")
            return t if len(t) <= limit else t[: limit - 1].rsplit(" ", 1)[0] + "…"
    return ""
