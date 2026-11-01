"""Предпросмотр объекта или кампании до публикации (PLATFORM_VISION §49–50).

build/preview/<id>/ — staging-сборка сайта в корне (тот же генератор, что
и публикация; страницы ссылаются на свои файлы от корня), а
build/preview/<id>/_preview/index.html — одна страница со всеми вариантами:
  - сайт — настоящая страница этой сборки во фрейме: это и есть итоговая
    вёрстка;
  - Telegram, VK, Discord, YouTube, GitHub — текст в разметке площадки,
    как его отправит адаптер, с медиа, длиной и лимитом; вид карточки —
    приближение (помечено), а текст и разметка — точные.
У каждого — адресат, проверка валидатора, одобрение и устаревание.
Открывать через сервер (`preview <id> --serve`): из file:// корень сайта —
корень диска, и фрейм остаётся без стилей.
"""
from __future__ import annotations

import html
import shutil
from pathlib import Path

from ..adapters import adapter_class
from ..core.campaigns import targets
from ..core.content import approval_state, find, stale_reasons
from ..core.repo import Repo
from ..core.validator import validate_variant
from .build import build

E = html.escape
PAGE_DIR = "_preview"  # страница предпросмотра; адресов сайта с «_» в начале нет

CSS = """body{font:15px/1.5 system-ui,sans-serif;background:#111214;color:#EEEEEC;margin:0;padding:24px}
h1{font-size:22px} .v{border:1px solid #2E3036;border-radius:8px;margin:18px 0;padding:14px;background:#1A1B1E}
.meta{color:#A3A6AD;font-size:13px} .err{color:#FF7A59} .ok{color:#7FD68E} .warn{color:#F2B33D}
.tg{background:#212d3b;border-radius:12px;padding:10px 14px;max-width:520px;color:#fff}
.v img{max-width:100%;height:auto;border-radius:8px} .card{background:#fff;color:#111;border-radius:8px;padding:12px;max-width:560px}
.dc{background:#313338;color:#dbdee1;border-radius:6px;padding:10px 14px;max-width:560px;white-space:pre-wrap}
pre{white-space:pre-wrap;background:#0b0c0d;padding:10px;border-radius:6px} iframe{width:100%;height:640px;border:1px solid #2E3036;background:#fff}
.note{color:#A3A6AD;font-style:italic;font-size:12px}"""


def _media_html(payload, out: Path, item_id: str) -> str:
    parts = []
    for m in payload.media:
        if m.path.is_file():
            dst = out / "media" / item_id / m.path.name
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(m.path, dst)
            parts.append(f'<img src="media/{item_id}/{E(m.path.name)}" alt="">')
    return "".join(parts)


def _card(platform: str, payload, media: str) -> str:
    if platform == "telegram":
        return f'<div class="tg">{media}<div>{payload.text.replace(chr(10), "<br>")}</div></div>'
    if platform == "discord":
        return f'<div class="dc">{E(payload.text)}</div>{media}'
    if platform in ("vk",):
        return f'<div class="card">{E(payload.text).replace(chr(10), "<br>")}{media}</div>'
    if platform == "youtube":
        return f'<div class="card"><b>{E(payload.title or "")}</b><pre>{E(payload.text)}</pre></div>{media}'
    return f"<pre>{E(payload.text)}</pre>{media}"


def preview(repo: Repo, target_id: str, out: Path | None = None) -> Path:
    if target_id.startswith("CMP-"):
        c = repo.campaigns()[target_id]
        pairs = [(find(repo, i), c) for i in c.get("items", [])]
    else:
        pairs = [(find(repo, target_id), None)]
    out = out or repo.path("build", "preview", target_id)
    rep = build(repo, "staging", out, drafts=True)  # очищает out
    page_dir = out / PAGE_DIR
    page_dir.mkdir()
    dests = repo.destinations()
    body = [f"<h1>Предпросмотр {E(target_id)}</h1>",
            '<p class="meta">Сайт — настоящая staging-сборка (<a href="/" style="color:inherit">весь сайт</a>); карточки площадок — '
            'приближение вида, текст и разметка — точные (так их отправит адаптер).</p>']
    for item, camp in pairs:
        tmap = {t["variant"]: t for t in targets(item, camp)}
        for vid, v in item.variants().items():
            t = tmap.get(vid, {})
            dest = dests.get(t.get("destination") or "")
            ad = adapter_class(v.platform)(dest or {"id": "preview", "environment": "staging"}, repo)
            payload = ad.render(v)
            findings = validate_variant(v, ad, dest)
            errs = [f.msg for f in findings if f.level == "error"]
            warns = [f.msg for f in findings if f.level == "warn"]
            ap = approval_state(v)
            stale = stale_reasons(v)
            lim = (ad.limits().get("caption_max_chars") if payload.format in ("photo_post", "album") else
                   ad.limits().get("text_max_chars"))
            head = (f'<h2>{E(item.id)} · {E(vid)}</h2><p class="meta">площадка {E(v.platform)} · формат {E(payload.format)} · '
                    f'язык {E(v.lang)} · адресат {E((dest or {}).get("id", t.get("na") or "не задан"))} '
                    f'({E((dest or {}).get("environment", "—"))}, статус {E((dest or {}).get("status", "—"))}) · '
                    f'длина {payload.visible_len}{" / " + str(lim) if lim else ""}</p>')
            status = (f'<p class="{"ok" if ap == "valid" else "warn"}">одобрение автора: {E(ap)}</p>'
                      + (f'<p class="warn">устарел: {E("; ".join(stale))}</p>' if stale else "")
                      + "".join(f'<p class="err">✗ {E(e)}</p>' for e in errs)
                      + "".join(f'<p class="warn">! {E(w)}</p>' for w in warns)
                      + ('<p class="ok">✓ валидатор: ошибок нет</p>' if not errs else ""))
            if v.platform == "website":
                page = item.url_path(v.lang)
                view = (f'<iframe src="{E(page)}" title="{E(page)}"></iframe>'
                        f'<p class="note">итоговая вёрстка страницы {E(page)} (staging)</p>')
            else:
                view = _card(v.platform, payload, _media_html(payload, page_dir, item.id)) + '<p class="note">вид карточки — приближение</p>'
            body.append(f'<div class="v">{head}{status}{view}</div>')
    if rep.errors:
        body.append("<h2>Сборка сайта</h2>" + "".join(f'<p class="err">✗ {E(e)}</p>' for e in rep.errors))
    (page_dir / "index.html").write_text(f"<!doctype html><html lang='ru'><meta charset='utf-8'>"
                                         f"<meta name='robots' content='noindex, nofollow'><title>Предпросмотр {E(target_id)}</title>"
                                         f"<style>{CSS}</style>{''.join(body)}", encoding="utf-8")
    return page_dir / "index.html"
