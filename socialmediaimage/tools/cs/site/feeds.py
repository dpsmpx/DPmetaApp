"""Фиды, карта сайта и статический API (Д32; WEBSITE.md, §6–7).

Atom 1.0 (RFC 4287) — /feed.xml; RSS 2.0 — /rss.xml; JSON Feed 1.1 —
/feed.json; sitemap.xml (sitemaps.org); /api/v1/*.json — для своих
клиентов (бот, рассылка, приложение — §83, §85). id записи — её
канонический адрес, не меняется.
"""
from __future__ import annotations

import datetime as _dt
import email.utils
from xml.sax.saxutils import escape as X

from .i18n import t


def _iso(d: str) -> str:
    if not d:
        return "1970-01-01T00:00:00Z"
    return d if "T" in d else f"{d}T00:00:00Z"


def _rfc822(d: str) -> str:
    dt = _dt.datetime.fromisoformat(_iso(d).replace("Z", "+00:00"))
    return email.utils.format_datetime(dt)


def _title(site) -> str:
    return (site.cfg.get("title") or {}).get("ru") or "dpsmpx"


def atom(site, posts) -> str:
    updated = _iso(max((e.date for e in posts), default=""))
    out = ['<?xml version="1.0" encoding="utf-8"?>', '<feed xmlns="http://www.w3.org/2005/Atom" xml:lang="ru">',
           f"<title>{X(_title(site))}</title>", f"<id>{X(site.base)}/</id>",
           f'<link href="{X(site.base)}/"/>', f'<link rel="self" href="{X(site.base)}/feed.xml"/>',
           f"<updated>{updated}</updated>",
           f"<author><name>{X(site.author('ru'))}</name><uri>{X(site.base)}/</uri></author>"]
    for e in posts[:20]:
        url = site.base + e.path
        out += ["<entry>", f"<title>{X(e.title)}</title>", f"<id>{X(url)}</id>", f'<link href="{X(url)}"/>',
                f"<published>{_iso(e.date)}</published>", f"<updated>{_iso(e.date)}</updated>",
                f"<summary>{X(e.summary)}</summary>", f'<content type="html">{X(e.html)}</content>']
        out += [f'<category term="{X(c)}"/>' for c in [e.category] + list(e.item.data.get("tags") or [])]
        out.append("</entry>")
    out.append("</feed>")
    return "\n".join(out) + "\n"


def rss(site, posts) -> str:
    out = ['<?xml version="1.0" encoding="utf-8"?>', '<rss version="2.0" xmlns:atom="http://www.w3.org/2005/Atom">', "<channel>",
           f"<title>{X(_title(site))}</title>", f"<link>{X(site.base)}/</link>",
           f"<description>{X(t('ru', 'news_title'))}</description>", "<language>ru</language>",
           f'<atom:link href="{X(site.base)}/rss.xml" rel="self" type="application/rss+xml"/>']
    for e in posts[:20]:
        url = site.base + e.path
        out += ["<item>", f"<title>{X(e.title)}</title>", f"<link>{X(url)}</link>",
                f'<guid isPermaLink="true">{X(url)}</guid>', f"<pubDate>{_rfc822(e.date)}</pubDate>",
                f"<description>{X(e.summary)}</description>", f"<category>{X(e.category)}</category>", "</item>"]
    out += ["</channel>", "</rss>"]
    return "\n".join(out) + "\n"


def json_feed(site, posts) -> str:
    import json
    data = {
        "version": "https://jsonfeed.org/version/1.1",
        "title": _title(site), "home_page_url": site.base + "/", "feed_url": site.base + "/feed.json",
        "language": "ru", "authors": [{"name": site.author("ru"), "url": site.base + "/"}],
        "items": [{"id": site.base + e.path, "url": site.base + e.path, "title": e.title, "summary": e.summary,
                   "content_html": e.html, "date_published": _iso(e.date), "language": e.lang,
                   "tags": [e.category] + list(e.item.data.get("tags") or [])} for e in posts[:20]],
    }
    return json.dumps(data, ensure_ascii=False, indent=1) + "\n"


def sitemap(site, pages) -> str:
    """lastmod — дата самого нового источника страницы (нет даты — нет lastmod, без выдумки);
    пары RU/EN — xhtml:link hreflang у каждого адреса пары, включая сам адрес, и x-default (П2-13)."""
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:xhtml="http://www.w3.org/1999/xhtml">']
    for p in pages:
        row = [f"<loc>{X(site.base + p.path)}</loc>"]
        if p.lastmod:
            row.append(f"<lastmod>{X(p.lastmod[:10])}</lastmod>")
        if len(p.alternates) > 1:
            for lg, path in p.alternates.items():
                row.append(f'<xhtml:link rel="alternate" hreflang="{lg}" href="{X(site.base + path)}"/>')
            default = p.alternates.get("ru", p.path)
            row.append(f'<xhtml:link rel="alternate" hreflang="x-default" href="{X(site.base + default)}"/>')
        out.append("<url>" + "".join(row) + "</url>")
    out.append("</urlset>")
    return "\n".join(out) + "\n"


def api(site, entries) -> dict:
    """Статический API v1 (§35): только то, что уже есть на страницах; версия — в адресе."""
    projects = []
    for p in sorted(site.repo.projects().values(), key=lambda x: x.get("order", 99)):
        if not p.get("page"):
            continue
        projects.append({"id": p["id"], "name": p["name"], "weight": p.get("weight"),
                         "url": {"ru": site.base + f"/projects/{p['id']}/", "en": site.base + f"/en/projects/{p['id']}/"},
                         "tagline": {lg: site.fact(p["tagline"], lg) for lg in ("ru", "en")} if p.get("tagline") else {},
                         "links": [site.links[r["id"]]["url"] for r in p.get("links", []) if r["id"] in site.links and site.links[r["id"]].get("url")]})
    posts = [{"id": e.item.id, "url": site.base + e.path, "title": e.title, "summary": e.summary, "date": e.date,
              "category": e.category, "tags": e.item.data.get("tags") or [], "projects": e.item.data.get("source_projects") or [],
              "lang": e.lang, "draft": e.draft} for e in entries if e.is_post]
    return {
        "index.json": {"version": 1, "site": site.base + "/", "environment": site.env,
                       "resources": {"projects": "/api/v1/projects.json", "news": "/api/v1/news.json",
                                     "feeds": {"atom": "/feed.xml", "rss": "/rss.xml", "json": "/feed.json"}}},
        "projects.json": {"version": 1, "projects": projects},
        "news.json": {"version": 1, "news": posts},
    }
