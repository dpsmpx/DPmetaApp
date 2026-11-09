"""Генератор сайта dpsmpx.com (Д21, Д32; docs/platforms/WEBSITE.md).

Сайт — ещё один «адаптер» той же модели: проекты и факты (страницы
проектов без ручного переписывания чисел, §22), каноны и варианты
`website.*` (главная, «обо мне», записи), журнал публикаций (что
опубликовано в окружении). Выход — статические файлы:

    production  только одобренное автором и опубликованное (журнал production)
    staging     всё с вариантами website, черновики — с плашкой; noindex

Кроме страниц: значок `/favicon.svg` из токенов бренда, заголовки
безопасности для хостинга (CSP без внешних источников и встроенных
скриптов и стилей) — `/.htaccess` для Apache (Beget, Д35) или `/_headers`
для Cloudflare Pages и Netlify, по `hosting` в site.yaml; sitemap с
`lastmod` (дата самого нового источника страницы) и парами `hreflang`.

build(repo, env, out) → BuildReport. Ничего не скачивает и не отправляет.
"""
from __future__ import annotations

import datetime as _dt
import html
import json
import re
import shutil
import struct
import urllib.parse
from dataclasses import dataclass, field
from pathlib import Path
from string import Template

from ..common import image_size
from ..core import markup, sensitive
from ..core.content import (POST_CATEGORIES, CItem, Variant, approval_state, load_items, resolved_alt, resolved_body,
                            stale_reasons)
from ..core.inject import progress_set, resolve
from ..core.ledger import Ledger
from ..core.repo import Repo
from ..progress.dataset import fmt_date
from ..progress.figures import FIGURE_MARK
from ..progress.figures import render as render_figure
from . import ecosystem, feeds
from . import path as journey
from .i18n import PLATFORM_NAMES, t

E = html.escape

# Заголовки для хостинга (П2-12). CSP — только свои файлы: внешних скриптов,
# шрифтов и картинок у сайта нет; JSON-LD — блок данных, не исполняется.
# Проверка site следит, чтобы страницы не нарушали эту политику.
CSP = ("default-src 'self'; script-src 'none'; style-src 'self'; img-src 'self'; font-src 'self'; "
       "connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'")
SECURITY_HEADERS = {
    "Content-Security-Policy": CSP,
    "X-Content-Type-Options": "nosniff",
    "Referrer-Policy": "strict-origin-when-cross-origin",
    "Permissions-Policy": "camera=(), microphone=(), geolocation=(), payment=(), usb=()",
    "X-Frame-Options": "DENY",
    "Cross-Origin-Opener-Policy": "same-origin",
}


TEXT_TOKENS = ("paper", "mist", "human", "ai", "verified")
DESCRIPTION_MAX = 160  # знаков в meta description: длиннее поиск обрезает (P-14 аудита страниц 8.11.2026)
"""Токены, которыми на сайте пишется текст: им нужен контраст к фону и панелям."""


def theme_contrast(tokens: dict) -> list[str]:
    """Контраст текста к фону (`ink`) и панелям (`graphite`) в обеих темах сайта (Д9, Д49):
    не ниже `limits.min_contrast_normal` — иначе ошибка сборки."""
    from ..visual import contrast, luminance
    need = tokens["limits"]["min_contrast_normal"]
    out = []
    themes = [("тёмная", tokens["color"])]
    if (tokens.get("site_light") or {}).get("color"):
        themes.append(("светлая", tokens["site_light"]["color"]))
    for name, c in themes:
        for fg in TEXT_TOKENS:
            for bg in ("ink", "graphite"):
                v = contrast(luminance(c[fg]["hex"]), luminance(c[bg]["hex"]))
                if v < need:
                    out.append(f"тема сайта {name}: {fg} к {bg} — контраст {v:.2f} < {need}")
    return out


def date_tag(day: str, lang: str) -> str:
    """Дата записи по языку страницы — «5 октября 2026», «5 October 2026»; ISO — в `datetime` (P-23)."""
    return f'<time datetime="{E(day)}">{E(fmt_date(day, lang))}</time>'


def img_tag(e: "Entry", cls: str | None = None) -> str:
    """Изображение записи с размерами из файла (S-02): браузер занимает место до загрузки."""
    attrs = f' class="{cls}"' if cls else ""
    size = f' width="{e.image_size[0]}" height="{e.image_size[1]}"' if e.image_size else ""
    return f'<img{attrs} src="{e.image}" alt="{E(e.image_alt or "")}"{size}>'


@dataclass
class Entry:
    """Запись или страница сайта из варианта website.<язык>."""
    item: CItem
    variant: Variant
    lang: str
    path: str
    title: str
    summary: str
    html: str
    date: str
    category: str
    draft: bool
    stale: bool
    image: str | None = None
    image_alt: str | None = None
    image_size: tuple[int, int] | None = None
    social: list = field(default_factory=list)

    @property
    def is_post(self) -> bool:
        return self.category in POST_CATEGORIES

    @property
    def is_article(self) -> bool:
        return self.category == "article"


@dataclass
class Page:
    path: str
    lang: str
    title: str
    description: str
    main: str
    kind: str = "website"
    alternates: dict = field(default_factory=dict)
    image: str | None = None
    jsonld: list = field(default_factory=list)
    draft: bool = False
    lastmod: str | None = None
    head: list = field(default_factory=list)  # свои стили и скрипты страницы (только раздел «Экосистема», Д43)


@dataclass
class BuildReport:
    env: str
    out: Path
    pages: list = field(default_factory=list)
    files: list = field(default_factory=list)
    warnings: list = field(default_factory=list)
    errors: list = field(default_factory=list)
    entries: list = field(default_factory=list)


class SiteBuild:
    def __init__(self, repo: Repo, env: str = "staging", include: tuple = (), drafts: bool | None = None):
        if env not in ("staging", "production", "development"):
            raise ValueError(f"окружение сайта {env}")
        self.repo, self.env = repo, env
        self.cfg = repo.site()
        envcfg = (self.cfg.get("environments") or {}).get("staging" if env == "development" else env) or {}
        self.base = (envcfg.get("base_url") or "").rstrip("/")
        self.noindex = bool(envcfg.get("noindex", env != "production"))
        self.drafts = envcfg.get("drafts", env != "production") if drafts is None else drafts
        self.include = set(include)  # {(item_id, variant_id)} — публикуется сейчас
        self.report = BuildReport(env, Path("."))
        self.facts = repo.facts()
        self.links = repo.links()
        self._about: dict = {}
        self._warned: set = set()
        self._og: str | None = None
        self._used: set = set()  # факты текущей страницы — для lastmod
        self._pages: list = []  # страницы из вариантов кроме «Обо мне» (category: page, website.path)
        self._progress: set = set()  # наборы прогресса на видимых страницах — для /api/v1/progress.json
        self._articles: list = []  # статьи (category: article) — раздел /articles/
        self._tokens: dict | None = None
        self._path_data: dict | None = None

    # ------------------------------------------------------- данные
    def _published(self) -> dict[tuple, dict]:
        env = "staging" if self.env == "development" else self.env
        out = {}
        led = Ledger(self.repo, env)
        for p in led.publications().values():
            f = p.first
            if p.state in ("PUBLISHED", "SUPERSEDED"):
                out.setdefault((f["item"], f["variant"]), p.last("PUBLISHED"))
        return out

    def _social(self, item: CItem) -> list[tuple[str, str]]:
        if self.env != "production":
            return []
        out = []
        for p in Ledger(self.repo, "production").publications().values():
            f = p.first
            if f.get("item") == item.id and p.state == "PUBLISHED" and f.get("platform") != "website":
                url = p.remote.get("remote_url")
                if url:
                    out.append((f["platform"], url))
        return out

    def entries(self) -> list[Entry]:
        pub = self._published()
        out = []
        for item in load_items(self.repo):
            if item.schema_version < 2:
                continue
            for vid, v in item.variants().items():
                if v.platform != "website":
                    continue
                approved = approval_state(v) == "valid"
                stale = bool(stale_reasons(v))
                published = (item.id, vid) in pub or (item.id, vid) in self.include
                if self.env == "production" and not (published and approved and not stale):
                    if published and (not approved or stale):
                        self.report.errors.append(f"{item.id}/{vid}: опубликовано, но одобрение недействительно или устарело — не выводится")
                    continue
                if self.env != "production" and not (self.drafts or published):
                    continue
                res = resolved_body(v, self.base)
                if res.errors:
                    self.report.errors += [f"{item.id}/{vid}: {e}" for e in res.errors]
                blocks = markup.parse(res.text)  # заголовки текста — h2/h3: h1 у страницы один
                rec = pub.get((item.id, vid)) or {}
                date = str(rec.get("ts") or "")[:10] or str(v.m("date") or item.data.get("created") or "")
                title = v.m("title") or item.data.get("title")
                summary = v.m("summary") or markup.first_text(blocks)
                img = alt = size = None
                media = v.m("media") or []
                if media:
                    img = f"/media/{item.id}/{Path(media[0]).name}"
                    alts = resolved_alt(v, self.base)
                    alt = alts[0].text if alts else None
                    if not alt:
                        self.report.errors.append(f"{item.id}/{vid}: у {media[0]} нет media_alt — изображение без текста-замены")
                    # Размеры — из файла: место под картинку занято до загрузки, страница не сдвигается (аудит сайта, S-02).
                    try:
                        w, h, _ = image_size(item.dir / media[0])
                        size = (w, h)
                    except (OSError, ValueError, struct.error) as err:
                        self.report.errors.append(f"{item.id}/{vid}: размеры {media[0]} не прочитать ({err}) — страница сдвинется при загрузке")
                out.append(Entry(item, v, v.lang, item.url_path(v.lang), title, summary,
                                 markup.to_html(blocks), date, item.category,
                                 draft=not (approved and published), stale=stale, image=img, image_alt=alt, image_size=size,
                                 social=self._social(item)))
        return out

    def fact(self, fid: str, lang: str, idx: int = 0) -> str | None:
        self._used.add(fid)
        f = self.facts.get(fid)
        if not f or not f.get("public"):
            self.report.errors.append(f"факт {fid} не найден или не публичный")
            return None
        words = (f.get("allowed_wording") or {}).get(lang) or []
        if len(words) <= idx:
            if lang == "ru" or fid not in self._warned:
                self.report.warnings.append(f"факт {fid}: нет формулировки на {lang} — строка пропущена")
                self._warned.add(fid)
            return None
        return words[idx]

    def author(self, lang: str) -> str:
        """Как сайт называет автора: только dpsmpx — имени нет нигде (Д37, Д40)."""
        return "dpsmpx"

    def signature(self, lang: str) -> str:
        return self.author(lang)

    def check_sensitive(self, out: Path) -> None:
        """Фильтр чувствительных данных (Д39): каждый файл выхода; совпадение — ошибка сборки."""
        f = sensitive.Filter(self.repo)
        for h in f.scan_tree(out):
            self.report.errors.append(h.msg)

    # ------------------------------------------------------- страницы
    def nav(self, lang: str, current: str, alt: dict) -> str:
        pre = "/en" if lang == "en" else ""
        items = [("home", f"{pre}/")]
        if self.path_cfg():
            items.append(("path", self.path_href(lang)))
        items.append(("projects", f"{pre}/projects/"))
        if self.eco_cfg() and lang == "ru":
            items.append(("ecosystem", self.eco_cfg()["path"]))
        items += [(k, path) for k, path in self.nav_pages(lang)]
        items += [("articles", self.articles_path(lang)), ("news", "/news/"), ("about", f"{pre}/about/")]
        out = []
        for key, href in items:
            if key == "about" and not self._about.get(lang):
                continue
            if key == "articles" and not self._articles:
                continue
            cls = ' class="current" aria-current="page"' if key == current else ""
            out.append(f'<a href="{href}"{cls}>{E(t(lang, key))}</a>')
        other = "en" if lang == "ru" else "ru"
        if other in alt:
            out.append(f'<a class="lang" href="{alt[other]}" hreflang="{other}" lang="{other}" '
                       f'aria-label="{E(t(other, "other_lang"))}">{other.upper()}</a>')
        return "".join(out)

    def person_jsonld(self, lang: str) -> dict:
        ids = [(x if isinstance(x, str) else x.get("link")) for x in (self.cfg.get("home") or {}).get("social_links", [])]
        same = [self.links[i]["url"] for i in ids if i in self.links and self.links[i].get("status") in ("ok", "limited")]
        return {"@context": "https://schema.org", "@type": "Person", "name": self.author(lang),
                "url": self.base + ("/en/" if lang == "en" else "/"), "sameAs": same}

    def project_card(self, p: dict, lang: str, primary: bool) -> str:
        pre = "/en" if lang == "en" else ""
        tag = self.fact(p.get("tagline", ""), lang) if p.get("tagline") else ""
        status = self.fact(p["status"], lang) if p.get("status") else None
        cls = "card primary" if primary else "card"
        out = f'<div class="{cls}"><h3><a href="{pre}/projects/{p["id"]}/">{E(p["name"])}</a></h3>'
        if tag:
            out += f"<p>{E(tag)}</p>"
        if status:
            out += f'<p class="status">{E(status)}</p>'
        return out + "</div>"

    def link_list(self, refs: list, lang: str) -> str:
        """Ссылки: id из links.yaml или {link|id, ru, en} — публичная подпись; служебная role не выводится."""
        lis = []
        for ref in refs:
            lid = ref if isinstance(ref, str) else (ref.get("link") or ref.get("id"))
            label = "" if isinstance(ref, str) else (ref.get(lang) or ref.get("ru") or "")
            l = self.links.get(lid)
            if not l or not l.get("url") or l.get("status") in ("broken_for_public", "stale"):
                if l is not None:
                    self.report.warnings.append(f"ссылка {lid}: статус {l.get('status')} — не выводится")
                continue
            name = PLATFORM_NAMES.get(l.get("platform"), l.get("platform"))
            lis.append(f'<li><a href="{E(l["url"])}" rel="me noopener">{E(name)}</a> <span class="muted">{E(label)}</span></li>')
        return f'<ul class="links">{"".join(lis)}</ul>'

    def draft_note(self, e: Entry) -> str:
        notes = []
        if e.draft:
            notes.append(f'<p class="draft">{E(t(e.lang, "draft"))}</p>')
        if e.stale:
            notes.append(f'<p class="draft">{E(t(e.lang, "stale"))}</p>')
        return "".join(notes)

    def page_home(self, lang: str, entries: list[Entry]) -> Page:
        home = self.cfg.get("home") or {}
        name = self.author(lang)
        role = self.fact("AU-ROLE", lang, 1 if lang == "ru" else 1)
        about = self._about.get(lang)
        line = E((role or "")[:1].upper() + (role or "")[1:])  # Д37, Д40: h1 — dpsmpx, роль — отдельной строкой
        parts = ['<section class="hero">', f"<h1>{E(name)}</h1>", f'<p class="role">{line}</p>']
        if about:
            parts.append(self.draft_note(about))
            parts.append(f'<p class="lead">{E(about.summary)}</p>')
            if about.image:
                parts.append(img_tag(about))
        parts.append("</section>")
        projects = [p for p in sorted(self.repo.projects().values(), key=lambda x: x.get("order", 99)) if p.get("page")]
        prim = [p for p in projects if p.get("weight") == "primary"]
        rest = [p for p in projects if p.get("weight") != "primary"]
        if prim:
            parts.append(f'<h2>{E(t(lang, "main_project"))}</h2><div class="cards">'
                         + "".join(self.project_card(p, lang, True) for p in prim) + "</div>")
        if rest:
            parts.append(f'<h2>{E(t(lang, "other_projects"))}</h2><div class="cards">'
                         + "".join(self.project_card(p, lang, False) for p in rest) + "</div>")
        if self.path_cfg() and self.path_data():
            parts.append(journey.home_block(self.path_data(), lang, self.path_href(lang)))
        arts = self.articles_for(lang)[:3]
        if arts:
            parts.append(f'<h2>{E(t(lang, "latest_articles"))}</h2>' + self.post_list(arts, lang, 3)
                         + f'<p><a href="{self.articles_path(lang)}">{E(t(lang, "all_articles"))} →</a></p>')
        posts = [e for e in entries if e.is_post and not e.is_article and e.lang == "ru"][:3]
        dates = [e.date for e in posts + arts + ([about] if about else [])]
        if posts:
            parts.append(f'<h2>{E(t(lang, "latest"))}</h2>' + self.post_list(posts, lang, 3)
                         + f'<p><a href="/news/">{E(t(lang, "all_posts"))} →</a></p>')
        parts.append(f'<h2>{E(t(lang, "follow"))}</h2>' + self.link_list(home.get("social_links", []), lang))
        if home.get("contact"):
            c = self.links.get(home["contact"]) or {}
            if c.get("url"):
                parts.append(f'<p>{E(t(lang, "contact"))}: <a href="{E(c["url"])}">{E(c["url"])}</a></p>')
        desc = about.summary if about else (role or name)
        path = "/en/" if lang == "en" else "/"
        img = (about.image if about else None) or self.og_default()
        return Page(path, lang, name if name == "dpsmpx" else name + " — dpsmpx", desc, "\n".join(parts),
                    "profile", self._alt("/", "/en/"), img,
                    [self.person_jsonld(lang), {"@context": "https://schema.org", "@type": "WebSite",
                                                "name": self.signature(lang), "url": self.base + path, "inLanguage": lang}],
                    draft=bool(about and about.draft), lastmod=max(dates, default=None))

    def page_about(self, lang: str) -> Page | None:
        e = self._about.get(lang)
        if not e:
            return None
        home = self.cfg.get("home") or {}
        main = (f"<h1>{E(e.title)}</h1>" + self.draft_note(e) + e.html
                + f'<h2>{E(t(lang, "follow"))}</h2>' + self.link_list(home.get("social_links", []), lang))
        return Page(e.path, lang, e.title, e.summary, main, "profile", self._alt(self._about_path("ru"), self._about_path("en")),
                    e.image or self.og_default(), [self.person_jsonld(lang)], e.draft, lastmod=e.date)

    def nav_pages(self, lang: str) -> list[tuple[str, str]]:
        """Пункты меню страниц из вариантов: website.nav в объекте (например, progress)."""
        out = []
        for e in self._pages:
            key = (e.item.data.get("website") or {}).get("nav")
            if key and e.lang == lang:
                out.append((key, e.path))
        return out

    def figures(self, body: str, lang: str) -> str:
        """Абзацы {{figure:<набор>.<фигура>}} → фигуры набора прогресса (Д38)."""
        def sub(m: re.Match) -> str:
            ds, _, name = m.group(1).partition(".")
            try:
                html_, facts = render_figure(progress_set(self.repo, ds), name, lang)
            except (FileNotFoundError, KeyError) as e:
                self.report.errors.append(f"фигура {m.group(1)}: {e}")
                return ""
            self._used.update(facts)
            self._progress.add(ds)
            return html_
        return FIGURE_MARK.sub(sub, body)

    @staticmethod
    def slug(text: str, taken: set[str]) -> str:
        """Якорь из заголовка (P-22): «Как считалось» → «как-считалось»; занятый — с номером."""
        base = re.sub(r"[^\w]+", "-", html.unescape(re.sub(r"<[^>]+>", "", text)).lower()).strip("-_") or "section"
        out, n = base, 1
        while out in taken:
            n += 1
            out = f"{base}-{n}"
        taken.add(out)
        return out

    @classmethod
    def toc(cls, body: str, lang: str) -> tuple[str, str]:
        """Оглавление по заголовкам h2 длинной страницы (website.toc): (оглавление, текст с якорями).

        Нумерованный список в столбик; якорь — из текста заголовка, а не «s1…s14» (P-22)."""
        heads: list[tuple[str, str]] = []
        taken = set(re.findall(r'\bid="([^"]+)"', body))

        def anchor(m: re.Match) -> str:
            heads.append((cls.slug(m.group(1), taken), m.group(1)))
            return f'<h2 id="{E(heads[-1][0])}">{m.group(1)}</h2>'
        body = re.sub(r"<h2>(.*?)</h2>", anchor, body)
        if len(heads) < 3:
            return "", body
        items = "".join(f'<li><a href="#{E(a)}">{h}</a></li>' for a, h in heads)
        return f'<nav class="toc" aria-label="{E(t(lang, "toc"))}"><ol>{items}</ol></nav>', body

    def page_entry(self, e: Entry) -> Page:
        """Страница из варианта website.* с website.path (кроме «Обо мне»): текст, фигуры, оглавление."""
        body = self.figures(e.html, e.lang)
        web = e.item.data.get("website") or {}
        toc, body = self.toc(body, e.lang) if web.get("toc") else ("", body)
        lead = f'<p class="lead">{E(e.summary)}</p>' if e.summary else ""
        main = f'<article class="page-{E(web.get("nav") or "plain")}"><h1>{E(e.title)}</h1>{self.draft_note(e)}{lead}{toc}{body}</article>'
        langs = {x.lang: x.path for x in self._pages if x.item.id == e.item.id}
        used = sorted({m.split(".")[0] for m in FIGURE_MARK.findall(e.html)})
        dates = [e.date] + [progress_set(self.repo, ds).data["period"]["cutoff"][:10] for ds in used]
        ld = {"@context": "https://schema.org", "@type": "WebPage", "name": e.title, "description": e.summary,
              "inLanguage": e.lang, "url": self.base + e.path, "author": {"@type": "Person", "name": self.author(e.lang)}}
        return Page(e.path, e.lang, e.title, e.summary, main, "website", self._alt(langs.get("ru"), langs.get("en")),
                    e.image or self.og_default(), [ld], e.draft, lastmod=max(d for d in dates if d))

    def _about_path(self, lang):
        e = self._about.get(lang)
        return e.path if e else None

    def page_projects(self, lang: str) -> Page:
        projects = [p for p in sorted(self.repo.projects().values(), key=lambda x: x.get("order", 99)) if p.get("page")]
        main = f"<h1>{E(t(lang, 'projects_title'))}</h1><div class=\"cards\">" + "".join(
            self.project_card(p, lang, p.get("weight") == "primary") for p in projects) + "</div>"
        pre = "/en" if lang == "en" else ""
        return Page(f"{pre}/projects/", lang, t(lang, "projects_title"), t(lang, "projects_title"), main,
                    alternates=self._alt("/projects/", "/en/projects/"), image=self.og_default())

    def page_project(self, p: dict, lang: str, entries: list[Entry]) -> Page:
        pre = "/en" if lang == "en" else ""
        rows = []
        for row in p.get("facts", []):
            vals = [self.fact(fid, lang) for fid in row.get("facts", [])]
            vals = [v for v in vals if v]
            if vals:
                rows.append(f'<tr><th>{E(t(lang, row["label"]))}</th><td>{"<br>".join(E(v) for v in vals)}</td></tr>')
        head = f"<h1>{E(p['name'])}</h1>"
        if p.get("former_names"):
            head += f'<p class="muted">{E(t(lang, "formerly"))}: {E(", ".join(p["former_names"]))}</p>'
        tag = self.fact(p["tagline"], lang) if p.get("tagline") else None
        if tag:
            head += f'<p class="lead">{E(tag)}</p>'
        main = head + f'<table class="facts">{"".join(rows)}</table>'
        if p.get("links"):
            main += f'<h2>{E(t(lang, "links"))}</h2>' + self.link_list(p["links"], lang)
        related = [e for e in entries if e.is_post and p["id"] in (e.item.data.get("source_projects") or [])]
        if related:
            main += f'<h2>{E(t(lang, "related"))}</h2>' + self.post_list(related, lang, 3)
        desc = tag or p["name"]
        return Page(f"{pre}/projects/{p['id']}/", lang, p["name"], desc, main,
                    alternates=self._alt(f"/projects/{p['id']}/", f"/en/projects/{p['id']}/"), image=self.og_default(),
                    jsonld=[{"@context": "https://schema.org", "@type": "CreativeWork", "name": p["name"],
                             "description": desc, "url": self.base + f"{pre}/projects/{p['id']}/",
                             "author": {"@type": "Person", "name": self.author(lang)}}])

    def post_list(self, posts: list[Entry], lang: str, level: int = 2) -> str:
        """Список записей; заголовок записи — h{level} (под разделом h2 страницы — h3), дата — по языку (P-23)."""
        lis = []
        for e in posts:
            cat = t(lang, "cat").get(e.category, e.category)
            mark = " · черновик" if e.draft and lang == "ru" else (" · draft" if e.draft else "")
            mins = f" · {self.minutes(e)} {t(lang, 'minutes')}" if e.is_article else ""
            lis.append(f'<li>{date_tag(e.date, lang)} · <span class="muted">{E(cat)}{E(mins)}{mark}</span>'
                       f'<h{level} class="pt"><a href="{e.path}">{E(e.title)}</a></h{level}>'
                       f'<span class="muted">{E(e.summary)}</span></li>')
        return f'<ul class="posts">{"".join(lis)}</ul>'

    def page_news(self, entries: list[Entry]) -> Page:
        posts = [e for e in entries if e.is_post and not e.is_article and e.lang == "ru"]
        if posts:
            body = self.post_list(posts, "ru")
        else:
            devlog = self.links.get("L-TG-DEVLOG") or {}
            body = f'<p class="muted">{E(t("ru", "no_posts"))}'
            if devlog.get("url"):
                body += f' <a href="{E(devlog["url"])}">{E(devlog["url"])}</a>'
            body += "</p>"
        feeds_html = (f'<p class="muted feeds">{E(t("ru", "feeds"))}: <a href="/feed.xml">Atom</a> · '
                      f'<a href="/rss.xml">RSS</a> · <a href="/feed.json">JSON Feed</a></p>')
        return Page("/news/", "ru", t("ru", "news_title"), t("ru", "news_title"),
                    f"<h1>{E(t('ru', 'news_title'))}</h1>{body}{feeds_html}", image=self.og_default(),
                    lastmod=max((e.date for e in posts), default=None))

    def page_post(self, e: Entry) -> Page:
        cat = t(e.lang, "cat").get(e.category, e.category)
        meta = f'<p class="meta">{date_tag(e.date, e.lang)} · {E(cat)}'
        if e.is_article:
            meta += f" · {self.minutes(e)} {E(t(e.lang, 'minutes'))}"
        projects = self.repo.projects()
        for pid in e.item.data.get("source_projects") or []:
            if pid in projects and projects[pid].get("page"):
                meta += f' · <a href="/projects/{pid}/">{E(projects[pid]["name"])}</a>'
        meta += "</p>"
        tags = "".join(f'<a class="tag" href="/tags/{E(tg)}/">#{E(tg)}</a>' for tg in e.item.data.get("tags") or [])
        body, lead = e.html, ""
        if e.is_article:  # подзаголовок (summary) и оглавление по h2
            toc, body = self.toc(body, e.lang)
            lead = (f'<p class="lead">{E(e.summary)}</p>' if e.summary else "") + toc
        crumbs = self.breadcrumbs(e)
        main = (f'<article class="{"article" if e.is_article else "post"}">{crumbs[0]}<h1>{E(e.title)}</h1>'
                f"{meta}{self.draft_note(e)}")
        if e.image:
            main += img_tag(e, "cover")
        main += lead + body + (f"<p>{tags}</p>" if tags else "")
        if e.social:
            main += f'<h3>{E(t(e.lang, "discuss"))}</h3><ul class="links">' + "".join(
                f'<li><a href="{E(u)}">{E(PLATFORM_NAMES.get(pl, pl))}</a></li>' for pl, u in e.social) + "</ul>"
        main += "</article>"
        ld = {"@context": "https://schema.org", "@type": "Article" if e.is_article else "BlogPosting", "headline": e.title,
              "datePublished": e.date, "dateModified": e.date, "inLanguage": e.lang,
              "mainEntityOfPage": self.base + e.path, "description": e.summary,
              "author": {"@type": "Person", "name": self.author(e.lang), "url": self.base + "/"}}
        if e.is_article:
            ld["wordCount"] = self.words(e)
        if e.item.data.get("tags"):
            ld["keywords"] = ", ".join(e.item.data["tags"])
        img = e.image or self.og_default()
        if img:
            ld["image"] = self.base + img
        return Page(e.path, e.lang, e.title, e.summary, main, "article", image=img, jsonld=[ld, crumbs[1]],
                    draft=e.draft, lastmod=e.date)

    # ------------------------------------------------------- статьи
    def articles_for(self, lang: str) -> list[Entry]:
        """Статьи на языке страницы; на английской, пока английских нет, — русские (как записи)."""
        own = [e for e in self._articles if e.lang == lang]
        return own or ([e for e in self._articles if e.lang == "ru"] if lang == "en" else [])

    def articles_path(self, lang: str) -> str:
        return "/en/articles/" if lang == "en" and any(e.lang == "en" for e in self._articles) else "/articles/"

    @staticmethod
    def words(e: Entry) -> int:
        return len(re.sub(r"<[^>]+>", " ", e.html).split())

    def minutes(self, e: Entry) -> int:
        """Время чтения: 180 слов в минуту — медленное чтение длинного текста со схемами."""
        return max(1, round(self.words(e) / 180))

    def breadcrumbs(self, e: Entry) -> tuple[str, dict]:
        """Цепочка «Главная → раздел → запись»: (HTML, BreadcrumbList для поисковиков)."""
        home = "/en/" if e.lang == "en" else "/"
        sec = (self.articles_path(e.lang), t(e.lang, "articles_title")) if e.is_article else ("/news/", t(e.lang, "news"))
        chain = [(home, t(e.lang, "home")), sec]
        html_ = (f'<nav class="crumbs" aria-label="{E(t(e.lang, "breadcrumbs"))}">'
                 + " / ".join(f'<a href="{h}">{E(n)}</a>' for h, n in chain) + "</nav>")
        ld = {"@context": "https://schema.org", "@type": "BreadcrumbList", "itemListElement": [
            {"@type": "ListItem", "position": i, "name": n, "item": self.base + h} for i, (h, n) in enumerate(chain, 1)]
            + [{"@type": "ListItem", "position": len(chain) + 1, "name": e.title, "item": self.base + e.path}]}
        return html_, ld

    def page_articles(self, lang: str) -> Page | None:
        arts = [e for e in self._articles if e.lang == lang]
        if not arts:
            return None
        path = "/en/articles/" if lang == "en" else "/articles/"
        main = (f"<h1>{E(t(lang, 'articles_title'))}</h1><p class=\"lead\">{E(t(lang, 'articles_lead'))}</p>"
                + self.post_list(arts, lang))
        ld = {"@context": "https://schema.org", "@type": "CollectionPage", "name": t(lang, "articles_title"),
              "url": self.base + path, "inLanguage": lang,
              "hasPart": [{"@type": "Article", "headline": e.title, "url": self.base + e.path} for e in arts]}
        en = "/en/articles/" if any(e.lang == "en" for e in self._articles) else None
        return Page(path, lang, t(lang, "articles_title"), t(lang, "articles_lead"), main,
                    alternates=self._alt("/articles/", en), image=self.og_default(), jsonld=[ld],
                    lastmod=max((e.date for e in arts), default=None))

    def page_tag(self, tag: str, posts: list[Entry]) -> Page:
        return Page(f"/tags/{tag}/", "ru", f"#{tag}", f"#{tag}", f"<h1>#{E(tag)}</h1>" + self.post_list(posts, "ru"),
                    lastmod=max((e.date for e in posts), default=None))

    def _alt(self, ru: str | None, en: str | None) -> dict:
        out = {}
        if ru:
            out["ru"] = ru
        if en:
            out["en"] = en
        return out

    def og_default(self) -> str | None:
        return self._og

    def _fact_date(self, fid: str) -> str | None:
        """Дата самого нового свидетельства факта — когда содержимое страницы последний раз сверено."""
        ev = (self.facts.get(fid) or {}).get("evidence") or []
        return max((str(x.get("date")) for x in ev if x.get("date")), default=None)

    def _page(self, fn, *args) -> Page | None:
        """Страница + lastmod: самая поздняя из дат записей страницы и свидетельств её фактов."""
        self._used = set()
        page = fn(*args)
        if page is not None:
            dates = [self._fact_date(f) for f in self._used] + [page.lastmod]
            page.lastmod = max((d for d in dates if d), default=None)
        return page

    def _resolve_og(self) -> str | None:
        """Изображение по умолчанию; на production — только из утверждённого объекта (Д12)."""
        og = self.cfg.get("og_default") or {}
        item = next((i for i in load_items(self.repo) if i.id == og.get("item")), None)
        if item is None:
            return None
        if self.env == "production" and item.data.get("state") not in ("APPROVED", "EXPORTED", "PUBLISHED"):
            self.report.warnings.append(f"og_default: {item.id} не утверждён — изображения по умолчанию на production нет")
            return None
        return f"/media/{og['item']}/{Path(og['file']).name}"

    # ------------------------------------------------------- запись
    def doc_title(self, page: Page) -> str:
        """`<title>` — с именем сайта: «Путь — dpsmpx» (P-14 аудита страниц 8.11.2026); og:title — без него,
        имя сайта — в og:site_name."""
        site = self.signature(page.lang)
        return page.title if page.title == site or page.title.endswith(" — " + site) else f"{page.title} — {site}"

    def render(self, page: Page) -> str:
        tpl = Template(self.repo.path("website", "templates", "base.html").read_text(encoding="utf-8"))
        url = self.base + page.path
        # Карточка с большим изображением — только когда изображение есть (P-14): без og:image она пустая.
        card = "summary_large_image" if page.image else "summary"
        head = [f'<link rel="canonical" href="{E(self.repo.canonical_base().rstrip("/") + page.path if self.env == "production" else url)}">',
                f'<meta property="og:title" content="{E(page.title)}">',
                f'<meta property="og:description" content="{E(page.description)}">',
                f'<meta property="og:type" content="{page.kind}">',
                f'<meta property="og:url" content="{E(url)}">',
                f'<meta property="og:site_name" content="{E(self.signature(page.lang))}">',
                f'<meta property="og:locale" content="{"ru_RU" if page.lang == "ru" else "en_US"}">',
                f'<meta name="twitter:card" content="{card}">']
        if page.image:
            head.append(f'<meta property="og:image" content="{E(self.base + page.image)}">')
        if self.noindex or page.draft:
            head.append('<meta name="robots" content="noindex, nofollow">')
        if len(page.alternates) > 1:
            for lg, p in page.alternates.items():
                head.append(f'<link rel="alternate" hreflang="{lg}" href="{E(self.base + p)}">')
            head.append(f'<link rel="alternate" hreflang="x-default" href="{E(self.base + page.alternates.get("ru", page.path))}">')
        jsonld = page.jsonld
        if not jsonld and not page.path.endswith("404.html"):
            # Страница без своего JSON-LD («Путь», «Экосистема», списки) — WebPage (P-14).
            jsonld = [{"@context": "https://schema.org", "@type": "WebPage", "name": page.title,
                       "description": page.description, "inLanguage": page.lang, "url": url}]
        for ld in jsonld:
            head.append('<script type="application/ld+json">' + json.dumps(ld, ensure_ascii=False).replace("</", "<\\/") + "</script>")
        head += page.head
        banner = f'<div class="banner" role="note">{E(t(page.lang, "staging"))}</div>' if self.noindex else ""
        navs = {path: key for lg in ("ru", "en") for key, path in self.nav_pages(lg)}
        if self.eco_cfg():
            navs[self.eco_cfg()["path"]] = "ecosystem"
        if self.path_cfg():
            navs.update({self.path_href(lg): "path" for lg in ("ru", "en")})
        current = {"/": "home", "/en/": "home"}.get(page.path) or navs.get(page.path) or ("projects" if "/projects/" in page.path else
                                                                    "news" if page.path.startswith("/news/") else
                                                                    "articles" if "/articles/" in page.path else
                                                                    "about" if "/about/" in page.path else "")
        footer = (f'<p>{E(self.signature(page.lang))} · '
                  f'<a href="/feed.xml">Atom</a> · <a href="/rss.xml">RSS</a> · <a href="/feed.json">JSON Feed</a></p>'
                  + (f'<p>Шрифты IBM Plex — SIL OFL 1.1 (<a href="/fonts/OFL-IBM-Plex.txt">лицензия</a>).</p>' if page.lang == "ru" else
                     f'<p>IBM Plex fonts — SIL OFL 1.1 (<a href="/fonts/OFL-IBM-Plex.txt">license</a>).</p>'))
        return tpl.substitute(lang=page.lang, title=E(self.doc_title(page)), description=E(page.description), head="\n".join(head),
                              banner=banner, home="/en/" if page.lang == "en" else "/", theme=self.tokens()["color"]["ink"]["hex"],
                              theme_light=((self.tokens().get("site_light") or {}).get("color") or self.tokens()["color"])["ink"]["hex"],
                              skip=E(t(page.lang, "skip")), nav_label=E(t(page.lang, "nav_label")),
                              brand="dpsmpx", nav=self.nav(page.lang, current, page.alternates), main=page.main, footer=footer)

    def write(self, out: Path, rel: str, data: str | bytes) -> None:
        p = out / rel.lstrip("/")
        if rel.endswith("/"):
            p = p / "index.html"
        p.parent.mkdir(parents=True, exist_ok=True)
        (p.write_bytes if isinstance(data, bytes) else lambda d: p.write_text(d, encoding="utf-8"))(data)
        self.report.files.append(str(p.relative_to(out)))

    def tokens(self) -> dict:
        if self._tokens is None:
            self._tokens = json.loads(self.repo.path("assets", "brand", "tokens.json").read_text(encoding="utf-8"))
        return self._tokens

    def css(self) -> str:
        """Токены — переменными :root: тёмная тема — основная (Д9), светлая — по настройке системы (Д49)."""
        tok = self.tokens()
        root = ":root { color-scheme: dark light; " + " ".join(f"--{k}: {v['hex']};" for k, v in tok["color"].items()) + " }\n"
        light = (tok.get("site_light") or {}).get("color") or {}
        if light:
            root += ("@media (prefers-color-scheme: light) { :root { "
                     + " ".join(f"--{k}: {v['hex']};" for k, v in light.items()) + " } }\n")
        for msg in theme_contrast(tok):
            self.report.errors.append(msg)
        return root + self.repo.path("website", "static", "style.css").read_text(encoding="utf-8")

    def favicon(self) -> str:
        """Значок (П2-11): «d» и точка-акцент, как в знаке «dpsmpx.», — цвета из токенов; без шрифтов."""
        c = {k: v["hex"] for k, v in self.tokens()["color"].items()}
        return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32">'
                f'<rect width="32" height="32" rx="7" fill="{c["ink"]}"/>'
                f'<circle cx="13" cy="19" r="6" fill="none" stroke="{c["paper"]}" stroke-width="3.4"/>'
                f'<path d="M19 6.5V25" stroke="{c["paper"]}" stroke-width="3.4" stroke-linecap="round"/>'
                f'<circle cx="25.3" cy="23.6" r="2.7" fill="{c["human"]}"/></svg>\n')

    def _headers(self) -> dict:
        h = dict(SECURITY_HEADERS)
        if self.noindex:
            h["X-Robots-Tag"] = "noindex, nofollow"
        return h

    def headers_file(self) -> tuple[str, str]:
        """Файл заголовков хостинга (П2-12, Д35): (путь, текст)."""
        if self.cfg.get("hosting") == "beget":
            return "/.htaccess", self.htaccess()
        lines = ["/*"] + [f"  {k}: {v}" for k, v in self._headers().items()]
        for prefix, hdrs in self.path_headers().items():
            lines += [f"{prefix}*"] + [f"  {k}: {v}" for k, v in hdrs.items()]
        return "/_headers", "\n".join(lines) + "\n"

    def htaccess(self) -> str:
        """Apache (Beget): 404, кодировка, без списка файлов каталогов, типы, заголовки.
        Заголовки к статике и Options в .htaccess Beget принимает (05.10.2026, первая
        штатная выгрузка: / — 200, /fonts/ — 403)."""
        lines = ["# Создаёт tools/cs/site/build.py (Д35); на сервере не править — перезапишет выгрузка.",
                 "Options -Indexes",
                 "AddDefaultCharset utf-8",
                 "ErrorDocument 404 /404.html",
                 "<IfModule mod_mime.c>",
                 "  AddType font/woff2 .woff2",
                 "  AddType image/svg+xml .svg",
                 "</IfModule>"]
        lines += self.lang_redirect()
        lines += ["<IfModule mod_headers.c>"]
        lines += [f'  Header always set {k} "{v}"' for k, v in self._headers().items()]
        return "\n".join(lines + ["</IfModule>"]) + "\n"

    def lang_redirect(self) -> list[str]:
        """Выбор языка на хостинге (П2-18): вход на «/» извне, когда первым в Accept-Language
        стоит английский, — на «/en/» (302). Свои переходы (Referer — этот сайт) не
        перенаправляются: кнопка «RU» на /en/ ведёт на «/» и должна там остаться.
        Поисковые роботы Accept-Language обычно не шлют — видят «/». Без mod_rewrite блок
        пропускается."""
        if "en" not in (self.cfg.get("languages") or []):
            return []
        host = re.escape(urllib.parse.urlsplit(self.repo.canonical_base()).hostname or "")
        return ["<IfModule mod_rewrite.c>",
                "  RewriteEngine On",
                "  RewriteCond %{HTTP:Accept-Language} ^en [NC]",
                f"  RewriteCond %{{HTTP_REFERER}} !^https?://(www\\.)?{host}(/|$) [NC]",
                "  RewriteRule ^$ /en/ [R=302,L]",
                "</IfModule>"]

    # ------------------------------------------------------- раздел «Экосистема» (Д43)
    def eco_cfg(self) -> dict | None:
        c = self.cfg.get("ecosystem")
        return c if c and c.get("path") and c.get("kit") else None

    def eco_kit(self) -> Path:
        return self.repo.path(*self.eco_cfg()["kit"].split("/"))

    def page_ecosystem(self) -> Page | None:
        """Система по слоям — статичным списком из данных комплекта DPvisualisation (Д51: без скрипта схемы);
        без комплекта — ошибка сборки."""
        c = self.eco_cfg()
        kit = self.eco_kit()
        bad = ecosystem.kit_problems(kit)
        if bad:
            self.report.errors += bad
            return None
        # «ID@N» — N-я формулировка факта (как what_idx у «Прогресса»); без номера — первая.
        def fact(spec: str) -> str | None:
            fid, _, idx = spec.partition("@")
            return self.fact(fid, "ru", int(idx) if idx.isdigit() else 0)
        data, warn = ecosystem.apply_facts(ecosystem.load(kit), c.get("roles") or {}, c.get("stages") or {}, fact)
        self.report.warnings += warn
        from ..claims import scan
        from ..facts import rules
        for text in ecosystem.texts(data):
            for rule, frag in scan(text, rules()):
                self.report.errors.append(f"экосистема: «{frag}» — {rule['id']}: {rule.get('why') or rule.get('reason') or 'запрещённая формулировка'}")
        as_of = (data.get("state") or {}).get("as_of") or data["model"].get("as_of") or ""
        day = as_of[:10]
        as_of_text = f"{day[8:10]}.{day[5:7]}.{day[:4]}" if len(day) == 10 else "—"
        path = c["path"]
        page = Page(path, "ru", t("ru", "ecosystem"),
                    "Система проектов dpsmpx по слоям: что делает каждый проект и на какой он стадии.",
                    ecosystem.page_main(data, path, as_of_text, self.path_href("ru") if self.path_cfg() else None),
                    lastmod=day or None)
        return page

    def path_headers(self) -> dict:
        """Заголовки по пути: своих нет — на сайте ни одного скрипта, CSP везде общая (Д51)."""
        return {}

    # ------------------------------------------------------- раздел «Путь» (Д44)
    def path_cfg(self) -> dict | None:
        c = self.cfg.get("path_section")
        return c if c and c.get("path") and c.get("data") else None

    def path_href(self, lang: str) -> str:
        return ("/en" if lang == "en" else "") + self.path_cfg()["path"]

    def path_data(self) -> dict | None:
        if self._path_data is None:
            self._path_data = self.repo.yaml(self.path_cfg()["data"]) or {}
        return self._path_data or None

    def _path_fact_ok(self, fid: str, idx: int, lang: str) -> str | None:
        f = self.facts.get(fid)
        if not f or not f.get("public"):
            return f"факт {fid} не найден или не публичный"
        if len((f.get("allowed_wording") or {}).get(lang) or []) <= idx:
            return f"у факта {fid} нет формулировки {lang} № {idx}"
        return None

    def page_path(self, lang: str) -> Page | None:
        """Путь по датам и конечная цель (Д44): тексты — факты реестра и вехи прогресса; рисунки — SVG без скриптов."""
        data = self.path_data()
        if not data:
            self.report.errors.append(f"раздел «Путь»: нет данных {self.path_cfg()['data']}")
            return None
        milestones = {m["id"]: m for m in (self.repo.yaml("data/progress/milestones.yaml") or {}).get("milestones", [])}
        bad = journey.problems(data, self._path_fact_ok, milestones)
        if bad:
            if lang == "ru":
                self.report.errors += bad
            return None

        def say(src: dict) -> str | None:
            if "milestone" in src:
                return milestones[src["milestone"]][lang]
            return self.fact(src["fact"], lang, src.get("idx", 0))

        def role_of(n: dict) -> tuple:
            return tuple(say(n[k]) if n.get(k) else None for k in ("role", "stage"))

        from ..claims import scan
        from ..facts import rules
        texts = journey.labels(data) + [say(t) or "" for st in data["stations"] for t in st["texts"]]
        for text in texts:
            for rule, frag in scan(text, rules()):
                self.report.errors.append(f"путь: «{frag}» — {rule['id']}: {rule.get('why') or rule.get('reason') or 'запрещённая формулировка'}")
        links = []
        if self.eco_cfg():
            links.append((journey.L[lang]["see_eco"], self.eco_cfg()["path"], journey.L[lang]["eco"]))
        prog = dict(self.nav_pages(lang)).get("progress")
        if prog:
            links.append((journey.L[lang]["see_progress"], prog, journey.L[lang]["progress"]))
        rule_ev = (self.facts.get((data["goal"].get("rule") or {}).get("fact", "")) or {}).get("evidence") or []
        rule_date = str(rule_ev[0]["date"]) if rule_ev else None
        main = journey.page_main(data, lang, say, role_of, rule_date, links)
        href = self.path_href(lang)
        return Page(href, lang, journey.L[lang]["title"], journey.L[lang]["description"], main,
                    alternates=self._alt(self.path_href("ru"), self.path_href("en")), lastmod=str(data.get("as_of") or "") or None)

    def not_found_page(self, lang: str) -> Page:
        """Страница 404 на языке раздела (П2-18): ссылка — на главную того же языка."""
        home, path = ("/en/", "/en/404.html") if lang == "en" else ("/", "/404.html")
        return Page(path, lang, t(lang, "not_found"), t(lang, "not_found"),
                    f"<h1>{E(t(lang, 'not_found'))}</h1><p>{E(t(lang, 'not_found_text'))} "
                    f"<a href=\"{home}\">{E(t(lang, 'not_found_home'))}</a>.</p>")

    def build(self, out: Path) -> BuildReport:
        self.report = BuildReport(self.env, out)
        self._warned = set()
        self._og = self._resolve_og()
        if out.exists():
            shutil.rmtree(out)
        out.mkdir(parents=True)
        entries = sorted(self.entries(), key=lambda e: (e.date, e.item.id), reverse=True)
        self.report.entries = entries
        self._about = {e.lang: e for e in entries if e.category == "page" and e.path.rstrip("/").endswith("/about")}
        self._pages = [e for e in entries if e.category == "page" and not e.path.rstrip("/").endswith("/about")
                       and (e.item.data.get("website") or {}).get("path")]
        self._progress = set()
        self._articles = [e for e in entries if e.is_article]
        langs = self.cfg.get("languages") or ["ru"]
        pg = self._page
        pages = [pg(self.page_home, "ru", entries), pg(self.page_projects, "ru"), pg(self.page_news, entries)]
        for lang in ("ru", "en"):
            ap = pg(self.page_articles, lang)
            if ap:
                pages.append(ap)
        if "en" in langs:
            pages += [pg(self.page_home, "en", entries), pg(self.page_projects, "en")]
        for lang in langs:
            ab = pg(self.page_about, lang)
            if ab:
                pages.append(ab)
        for p in self.repo.projects().values():
            if p.get("page"):
                for lang in langs:
                    pages.append(pg(self.page_project, p, lang, entries))
        if self.path_cfg():
            for lang in langs:
                pp = pg(self.page_path, lang)
                if pp:
                    pages.append(pp)
        eco_page = pg(self.page_ecosystem) if self.eco_cfg() else None
        if eco_page:
            pages.append(eco_page)
        for e in self._pages:
            pages.append(pg(self.page_entry, e))
        posts = [e for e in entries if e.is_post]
        for e in posts:
            pages.append(pg(self.page_post, e))
        tags: dict[str, list] = {}
        for e in posts:
            for tg in e.item.data.get("tags") or []:
                tags.setdefault(tg, []).append(e)
        for tg, ps in sorted(tags.items()):
            pages.append(pg(self.page_tag, tg, ps))
        for page in pages:
            if len(page.description) > DESCRIPTION_MAX:
                # Описание — текст объекта (Д12): короче — только новой версией варианта, сборка лишь говорит (P-14).
                self.report.warnings.append(f"{page.path}: описание {len(page.description)} знаков — длиннее "
                                            f"{DESCRIPTION_MAX}, поиск его обрежет")
            self.write(out, page.path, self.render(page))
            self.report.pages.append(page.path)
        for lang in ("ru", "en") if "en" in langs else ("ru",):
            nf = self.not_found_page(lang)
            self.write(out, nf.path, self.render(nf))
        # медиа вариантов и изображение по умолчанию
        for e in entries:
            for ref in e.variant.m("media") or []:
                src = e.item.dir / ref
                if src.is_file():
                    self.write(out, f"/media/{e.item.id}/{src.name}", src.read_bytes())
        og = self.cfg.get("og_default") or {}
        if self._og:
            from ..core.content import find
            src = find(self.repo, og["item"]).dir / og["file"]
            if src.is_file():
                self.write(out, self._og, src.read_bytes())
            else:
                self.report.warnings.append(f"og_default {og['file']} — файла нет")
        # тема, значок, заголовки, шрифты, фиды, карта, robots, API
        css = self.css()
        self.write(out, "/style.css", css)
        self.write(out, "/favicon.svg", self.favicon())
        self.write(out, *self.headers_file())
        if self.cfg.get("hosting") == "beget" and "en" in langs:
            # Apache: своя 404 для /en/ (П2-18); заголовки и Options наследуются из корня
            self.write(out, "/en/.htaccess", "# Создаёт tools/cs/site/build.py (П2-18); на сервере не править.\n"
                                             "ErrorDocument 404 /en/404.html\n")
        # Шрифты — только те, на которые ссылаются стили (Д51), и лицензия.
        used = set(re.findall(r'url\("/fonts/([^"]+)"\)', css))
        for f in sorted(self.repo.path("assets", "brand", "fonts").glob("*")):
            if f.suffix == ".txt" or f.name in used:
                self.write(out, f"/fonts/{f.name}", f.read_bytes())
        visible = [e for e in posts if not (self.env == "production" and e.draft)]
        self.write(out, "/feed.xml", feeds.atom(self, visible))
        self.write(out, "/rss.xml", feeds.rss(self, visible))
        self.write(out, "/feed.json", feeds.json_feed(self, visible))
        self.write(out, "/sitemap.xml", feeds.sitemap(self, [p for p in pages if not p.draft]))
        robots = "User-agent: *\nDisallow: /\n" if self.noindex else f"User-agent: *\nAllow: /\nSitemap: {self.base}/sitemap.xml\n"
        self.write(out, "/robots.txt", robots)
        api = feeds.api(self, entries)
        api["index.json"]["build"] = feeds.build_marker(self.repo.root)  # Д48: коммит и время сборки
        if self._progress:  # данные графиков страницы прогресса — только если страница видна в этом окружении
            api["progress.json"] = {"version": 1, "datasets": [progress_set(self.repo, ds).public() for ds in sorted(self._progress)]}
            api["index.json"]["resources"]["progress"] = "/api/v1/progress.json"
        for name, data in api.items():
            self.write(out, f"/api/v1/{name}", json.dumps(data, ensure_ascii=False, indent=1))
        self.check_sensitive(out)
        return self.report


def build(repo: Repo, env: str, out: Path | None = None, include: tuple = (), drafts: bool | None = None) -> BuildReport:
    out = out or repo.path("build", "site", env)
    return SiteBuild(repo, env, include, drafts).build(out)
