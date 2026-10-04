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
from dataclasses import dataclass, field
from pathlib import Path
from string import Template

from ..core import markup
from ..core.content import (POST_CATEGORIES, CItem, Variant, approval_state, load_items, resolved_alt, resolved_body,
                            stale_reasons)
from ..core.inject import resolve
from ..core.ledger import Ledger
from ..core.repo import Repo
from . import feeds
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
    social: list = field(default_factory=list)

    @property
    def is_post(self) -> bool:
        return self.category in POST_CATEGORIES


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
        self._tokens: dict | None = None

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
                img = alt = None
                media = v.m("media") or []
                if media:
                    img = f"/media/{item.id}/{Path(media[0]).name}"
                    alts = resolved_alt(v, self.base)
                    alt = alts[0].text if alts else None
                    if not alt:
                        self.report.errors.append(f"{item.id}/{vid}: у {media[0]} нет media_alt — изображение без текста-замены")
                out.append(Entry(item, v, v.lang, item.url_path(v.lang), title, summary,
                                 markup.to_html(blocks), date, item.category,
                                 draft=not (approved and published), stale=stale, image=img, image_alt=alt,
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
        """Как сайт называет автора: dpsmpx; имя (AU-NAME) — только при real_name: true в site.yaml (Д37)."""
        return (self.fact("AU-NAME", lang) or "dpsmpx") if self.cfg.get("real_name") else "dpsmpx"

    def signature(self, lang: str) -> str:
        name = self.author(lang)
        return name if name == "dpsmpx" else f"{name} / dpsmpx"

    def name_forms(self) -> list[str]:
        """Слова имени автора из AU-NAME (все языки) — при real_name: false их нет ни в одном файле сайта (Д37)."""
        forms = set()
        for words in ((self.facts.get("AU-NAME") or {}).get("allowed_wording") or {}).values():
            for w in words:
                forms.update(x for x in re.findall(r"\w+", w) if x != "dpsmpx" and len(x) > 2)
        return sorted(forms)

    def check_no_name(self, out: Path) -> None:
        forms = [(f, f.lower()) for f in self.name_forms()]
        for p in sorted(out.rglob("*")):
            if not p.is_file():
                continue
            text = p.read_bytes().decode("utf-8", "ignore").lower()
            hit = [f for f, low in forms if low in text]
            if hit:
                self.report.errors.append(f"{p.relative_to(out)}: имя автора на сайте ({', '.join(hit)}) — "
                                          f"по Д37 только dpsmpx")

    # ------------------------------------------------------- страницы
    def nav(self, lang: str, current: str, alt: dict) -> str:
        pre = "/en" if lang == "en" else ""
        items = [("home", f"{pre}/"), ("projects", f"{pre}/projects/"), ("news", "/news/"), ("about", f"{pre}/about/")]
        out = []
        for key, href in items:
            if key == "about" and not self._about.get(lang):
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
        name = self.author(lang)
        ld = {"@context": "https://schema.org", "@type": "Person", "name": name}
        if name != "dpsmpx":
            ld["alternateName"] = "dpsmpx"
        return {**ld, "url": self.base + ("/en/" if lang == "en" else "/"), "sameAs": same}

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
        if name == "dpsmpx":  # Д37: без имени h1 — сам dpsmpx, строка роли — отдельным предложением
            line = E((role or "")[:1].upper() + (role or "")[1:])
        else:
            line = f'<span class="handle">dpsmpx</span> · {E(role or "")}'
        parts = ['<section class="hero">', f"<h1>{E(name)}</h1>", f'<p class="role">{line}</p>']
        if about:
            parts.append(self.draft_note(about))
            parts.append(f'<p class="lead">{E(about.summary)}</p>')
            if about.image:
                parts.append(f'<img src="{about.image}" alt="{E(about.image_alt or "")}" width="1280" height="720">')
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
        posts = [e for e in entries if e.is_post and e.lang == "ru"][:3]
        dates = [e.date for e in posts + ([about] if about else [])]
        if posts:
            parts.append(f'<h2>{E(t(lang, "latest"))}</h2>' + self.post_list(posts, lang)
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
            main += f'<h2>{E(t(lang, "related"))}</h2>' + self.post_list(related, lang)
        desc = tag or p["name"]
        return Page(f"{pre}/projects/{p['id']}/", lang, p["name"], desc, main,
                    alternates=self._alt(f"/projects/{p['id']}/", f"/en/projects/{p['id']}/"), image=self.og_default(),
                    jsonld=[{"@context": "https://schema.org", "@type": "CreativeWork", "name": p["name"],
                             "description": desc, "url": self.base + f"{pre}/projects/{p['id']}/",
                             "author": {"@type": "Person", "name": self.author(lang)}}])

    def post_list(self, posts: list[Entry], lang: str) -> str:
        lis = []
        for e in posts:
            cat = t(lang, "cat").get(e.category, e.category)
            mark = " · черновик" if e.draft and lang == "ru" else (" · draft" if e.draft else "")
            lis.append(f'<li><time datetime="{E(e.date)}">{E(e.date)}</time> · <span class="muted">{E(cat)}{mark}</span><br>'
                       f'<a href="{e.path}">{E(e.title)}</a><br><span class="muted">{E(e.summary)}</span></li>')
        return f'<ul class="posts">{"".join(lis)}</ul>'

    def page_news(self, entries: list[Entry]) -> Page:
        posts = [e for e in entries if e.is_post and e.lang == "ru"]
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
        meta = f'<p class="meta">{E(e.date)} · {E(cat)}'
        projects = self.repo.projects()
        for pid in e.item.data.get("source_projects") or []:
            if pid in projects and projects[pid].get("page"):
                meta += f' · <a href="/projects/{pid}/">{E(projects[pid]["name"])}</a>'
        meta += "</p>"
        tags = "".join(f'<a class="tag" href="/tags/{E(tg)}/">#{E(tg)}</a>' for tg in e.item.data.get("tags") or [])
        main = f"<article><h1>{E(e.title)}</h1>{meta}{self.draft_note(e)}"
        if e.image:
            main += f'<img class="cover" src="{e.image}" alt="{E(e.image_alt or "")}">'
        main += e.html + (f"<p>{tags}</p>" if tags else "")
        if e.social:
            main += f'<h3>{E(t(e.lang, "discuss"))}</h3><ul class="links">' + "".join(
                f'<li><a href="{E(u)}">{E(PLATFORM_NAMES.get(pl, pl))}</a></li>' for pl, u in e.social) + "</ul>"
        main += "</article>"
        ld = {"@context": "https://schema.org", "@type": "BlogPosting", "headline": e.title,
              "datePublished": e.date, "dateModified": e.date, "inLanguage": e.lang,
              "mainEntityOfPage": self.base + e.path, "description": e.summary,
              "author": {"@type": "Person", "name": self.author(e.lang), "url": self.base + "/"}}
        img = e.image or self.og_default()
        if img:
            ld["image"] = self.base + img
        return Page(e.path, e.lang, e.title, e.summary, main, "article", image=img, jsonld=[ld], draft=e.draft, lastmod=e.date)

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
    def render(self, page: Page) -> str:
        tpl = Template(self.repo.path("website", "templates", "base.html").read_text(encoding="utf-8"))
        url = self.base + page.path
        head = [f'<link rel="canonical" href="{E(self.repo.canonical_base().rstrip("/") + page.path if self.env == "production" else url)}">',
                f'<meta property="og:title" content="{E(page.title)}">',
                f'<meta property="og:description" content="{E(page.description)}">',
                f'<meta property="og:type" content="{page.kind}">',
                f'<meta property="og:url" content="{E(url)}">',
                f'<meta property="og:locale" content="{"ru_RU" if page.lang == "ru" else "en_US"}">',
                '<meta name="twitter:card" content="summary_large_image">']
        if page.image:
            head.append(f'<meta property="og:image" content="{E(self.base + page.image)}">')
        if self.noindex or page.draft:
            head.append('<meta name="robots" content="noindex, nofollow">')
        if len(page.alternates) > 1:
            for lg, p in page.alternates.items():
                head.append(f'<link rel="alternate" hreflang="{lg}" href="{E(self.base + p)}">')
            head.append(f'<link rel="alternate" hreflang="x-default" href="{E(self.base + page.alternates.get("ru", page.path))}">')
        for ld in page.jsonld:
            head.append('<script type="application/ld+json">' + json.dumps(ld, ensure_ascii=False).replace("</", "<\\/") + "</script>")
        banner = f'<div class="banner" role="note">{E(t(page.lang, "staging"))}</div>' if self.noindex else ""
        current = {"/": "home", "/en/": "home"}.get(page.path) or ("projects" if "/projects/" in page.path else
                                                                    "news" if page.path.startswith("/news/") else
                                                                    "about" if "/about/" in page.path else "")
        footer = (f'<p>{E(self.signature(page.lang))} · '
                  f'<a href="/feed.xml">Atom</a> · <a href="/rss.xml">RSS</a> · <a href="/feed.json">JSON Feed</a></p>'
                  + (f'<p>Шрифты IBM Plex — SIL OFL 1.1 (<a href="/fonts/OFL-IBM-Plex.txt">лицензия</a>).</p>' if page.lang == "ru" else
                     f'<p>IBM Plex fonts — SIL OFL 1.1 (<a href="/fonts/OFL-IBM-Plex.txt">license</a>).</p>'))
        return tpl.substitute(lang=page.lang, title=E(page.title), description=E(page.description), head="\n".join(head),
                              banner=banner, home="/en/" if page.lang == "en" else "/", theme=self.tokens()["color"]["ink"]["hex"],
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
        root = ":root { " + " ".join(f"--{k}: {v['hex']};" for k, v in self.tokens()["color"].items()) + " }\n"
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
        return "/_headers", "\n".join(["/*"] + [f"  {k}: {v}" for k, v in self._headers().items()]) + "\n"

    def htaccess(self) -> str:
        """Apache (Beget): 404, кодировка, типы, заголовки. mod_headers для статики на Beget —
        не подтверждено (Д35): проверка curl -I после первой выгрузки."""
        lines = ["# Создаёт tools/cs/site/build.py (Д35); на сервере не править — перезапишет выгрузка.",
                 "AddDefaultCharset utf-8",
                 "ErrorDocument 404 /404.html",
                 "<IfModule mod_mime.c>",
                 "  AddType font/woff2 .woff2",
                 "  AddType image/svg+xml .svg",
                 "</IfModule>",
                 "<IfModule mod_headers.c>"]
        lines += [f'  Header always set {k} "{v}"' for k, v in self._headers().items()]
        return "\n".join(lines + ["</IfModule>"]) + "\n"

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
        langs = self.cfg.get("languages") or ["ru"]
        pg = self._page
        pages = [pg(self.page_home, "ru", entries), pg(self.page_projects, "ru"), pg(self.page_news, entries)]
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
            self.write(out, page.path, self.render(page))
            self.report.pages.append(page.path)
        self.write(out, "/404.html", self.render(Page("/404.html", "ru", t("ru", "not_found"), t("ru", "not_found"),
                                                       f"<h1>{E(t('ru', 'not_found'))}</h1><p>{E(t('ru', 'not_found_text'))} "
                                                       f"<a href=\"/\">{E(t('ru', 'not_found_home'))}</a>.</p>")))
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
        self.write(out, "/style.css", self.css())
        self.write(out, "/favicon.svg", self.favicon())
        self.write(out, *self.headers_file())
        for f in sorted(self.repo.path("assets", "brand", "fonts").glob("*")):
            if f.suffix in (".woff2", ".txt"):
                self.write(out, f"/fonts/{f.name}", f.read_bytes())
        visible = [e for e in posts if not (self.env == "production" and e.draft)]
        self.write(out, "/feed.xml", feeds.atom(self, visible))
        self.write(out, "/rss.xml", feeds.rss(self, visible))
        self.write(out, "/feed.json", feeds.json_feed(self, visible))
        self.write(out, "/sitemap.xml", feeds.sitemap(self, [p for p in pages if not p.draft]))
        robots = "User-agent: *\nDisallow: /\n" if self.noindex else f"User-agent: *\nAllow: /\nSitemap: {self.base}/sitemap.xml\n"
        self.write(out, "/robots.txt", robots)
        for name, data in feeds.api(self, entries).items():
            self.write(out, f"/api/v1/{name}", json.dumps(data, ensure_ascii=False, indent=1))
        if not self.cfg.get("real_name"):
            self.check_no_name(out)
        return self.report


def build(repo: Repo, env: str, out: Path | None = None, include: tuple = (), drafts: bool | None = None) -> BuildReport:
    out = out or repo.path("build", "site", env)
    return SiteBuild(repo, env, include, drafts).build(out)
