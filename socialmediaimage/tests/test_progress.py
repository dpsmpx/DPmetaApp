"""Прогресс экосистемы (Д38): величины, фигуры, границы публичного, подстановки, проверка, страница сайта."""
from __future__ import annotations

import json
import re
import tempfile
import unittest
from pathlib import Path

import yaml

from tools.cs import claims
from tools.cs.core import textdoc
from tools.cs.core.content import find, variant_sha
from tools.cs.core.inject import resolve
from tools.cs.core.repo import Repo
from tools.cs.progress import check as progress_check
from tools.cs.progress.dataset import METRICS, Progress
from tools.cs.progress.figures import FIGURES, facts_of, render
from tools.cs.site.build import SiteBuild
from tests.platform_fixture import make_repo

DS = "2026-10-09"  # последний набор: на него ссылается текущий канон CI-0002
OLD = "2026-10-05"  # прежний набор: в нём есть архив, которого больше нет в счёте (Д38)


class Metrics(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.p = Progress(Repo(), DS)

    def test_every_metric_computes_in_both_languages(self):
        for mid in METRICS:
            self.assertTrue(self.p.formatted(mid, "ru"), mid)
            self.assertTrue(self.p.formatted(mid, "en"), mid)

    def test_values_follow_the_series(self):
        p = self.p
        self.assertEqual(p.value("days"), p.n)
        self.assertEqual(p.value("span_days"), p.n - 1)
        self.assertEqual(p.value("commits"), p.value("commits_ai") + p.value("commits_author"))
        self.assertEqual(p.value("branches_first_weeks"), max(p.total("branches")[:21]))
        self.assertLessEqual(p.value("branches_first_weeks"), p.value("peak_branches"))
        self.assertEqual(p.value("repos_core"), len([k for k in p.core() if any(p.daily(k, "commits"))]))

    def test_undisclosed_only_in_totals(self):
        p = self.p
        hidden = [k for k in p.keys if p.specs[k]["visibility"] == "undisclosed"]
        self.assertTrue(hidden, "в наборе есть закрытый проект без имени")
        self.assertNotIn(hidden[0], p.listed())
        self.assertEqual(p.hidden_count(), len(hidden))
        self.assertEqual(sum(p.total("commits")), sum(sum(p.daily(k, "commits")) for k in p.keys))


class Figures(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.p = Progress(Repo(), DS)

    def test_all_render_with_table_and_source(self):
        for lang in ("ru", "en"):
            for fid in FIGURES:
                html_, _ = render(self.p, fid, lang)
                self.assertIn(f'id="fig-{fid}"', html_)
                if fid not in ("snapshot", "ecosystem", "timeline", "method"):
                    self.assertIn('class="fdata"', html_, f"{fid}: таблица значений рядом с графиком")
                if fid != "method":
                    self.assertIn("figcaption", html_, fid)

    def test_no_per_repo_view_of_undisclosed(self):
        listed_core = len(self.p.listed(self.p.core()))
        heat, _ = render(self.p, "repos", "ru")
        self.assertIn(f"Ядро · {listed_core}", heat)
        self.assertIn("входит только в общие суммы", heat)
        eco, _ = render(self.p, "ecosystem", "ru")
        self.assertNotIn("node-hidden", eco)
        for fid in ("codebase", "verification", "knowledge"):
            html_, _ = render(self.p, fid, "en")
            self.assertNotIn("undisclosed private project —", html_)

    def test_personal_repos_without_names(self):
        html_, _ = render(self.p, "method", "ru")
        for x in self.p.scope["excluded"]:
            name = x["slug"].split("/")[-1]
            (self.assertNotIn if x["kind"] == "personal" else self.assertIn)(name, html_)
        self.assertIn("личные репозитории — 2", html_)
        # архив — по именам: его репозитории открыты (слово автора 10.10.2026); у каждого — why
        for x in self.p.scope["excluded"]:
            if x["kind"] == "archive":
                self.assertIn(x.get("why"), ("early", "removed"), x["slug"])
        self.assertIn("ранние открытые проекты до репозитория игры — <code>Andors-Love</code>", html_)
        self.assertIn("убраны автором из текущей системы — <code>DP_Ecosystem</code>", html_)
        en, _ = render(self.p, "method", "en")
        self.assertIn("early open projects that predate the game repository — <code>Andors-Love</code>", en)

    def test_three_groups_and_no_archive(self):
        # Д38, слово автора 09.10.2026: только текущая система — ядро, управление и поддерживающий слой;
        # 10.10.2026: DPmeta — слой синхронизации
        self.assertEqual({self.p.specs[k]["group"] for k in self.p.keys}, {"core", "control", "sync", "support"})
        self.assertEqual(self.p.specs["dpmeta"]["group"], "sync")
        self.assertNotIn("dpmeta", self.p.core())
        act, _ = render(self.p, "activity", "ru")
        self.assertIn("управление, синхронизация и поддерживающий слой", act)
        heat, _ = render(self.p, "repos", "ru")
        self.assertIn(f"Управление, синхронизация и поддерживающий слой · {len(self.p.listed(self.p.supporting()))}", heat)
        snap, _ = render(self.p, "snapshot", "ru")
        for k in self.p.supporting():
            self.assertIn(self.p.specs[k]["name"], snap)
        # ядро — сверху, управление, синхронизация и поддерживающий слой — отдельной частью таблицы под ним
        core_end = max(snap.index(self.p.specs[k]["name"]) for k in self.p.listed(self.p.core()))
        self.assertLess(core_end, snap.index('colspan="3">Управление, синхронизация и поддерживающий слой'))
        # стадия DPmeta выводится и вне ядра — и числится в фактах фигуры (зависимости варианта, Д28)
        _, used = render(self.p, "snapshot", "ru")
        self.assertIn("META-STAGE", used)
        self.assertEqual(set(used), set(facts_of(self.p, "snapshot")))
        # на странице прогресса нет схемы DPvisualisation: формулировка без «эта схема» (what_idx)
        for fid in ("snapshot", "ecosystem"):
            for lang, word in (("ru", "эта схема"), ("en", "this diagram")):
                self.assertNotIn(word, render(self.p, fid, lang)[0], f"{fid} [{lang}]")
        # подпись неполного последнего дня — без чужой даты (раньше было зашито «5 октября»)
        act, _ = render(self.p, "activity", "ru")
        self.assertNotIn("5 октября учтено", act)
        # Лёгкий сайт (Д51): одна раскладка, без подсказок наведения и целей под них; значения — в таблице.
        self.assertEqual(act.count("<svg"), 1)
        self.assertNotIn("<g><title>", act)
        self.assertNotIn("c-hit", act)
        self.assertIn("Таблица значений", act)
        archived = {x["slug"].split("/")[-1] for x in self.p.scope["excluded"] if x["kind"] == "archive"}
        for fid in ("repos", "ecosystem", "snapshot"):
            html_, _ = render(self.p, fid, "ru")
            self.assertFalse([n for n in archived if n in html_], fid)

    def test_old_dataset_hides_archive(self):
        old = Progress(Repo(), OLD)
        archived = old.archived()
        self.assertTrue(archived & set(old.data["repos"]), "в прежнем наборе есть архив")
        self.assertFalse(archived & set(old.keys), "на странице его нет")
        for fid in ("repos", "ecosystem", "snapshot"):
            html_, _ = render(old, fid, "ru")
            for x in old.scope["excluded"]:
                if x["kind"] == "archive":
                    self.assertNotIn(x["slug"].split("/")[-1], html_, fid)


class Validator(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.repo = Repo()
        cls.item = find(cls.repo, "CI-0002")
        rules = (cls.repo.yaml("data/facts/forbidden.yaml") or {}).get("rules", [])
        cls.check = lambda self, text, res=None: [f.msg for f in claims.text_findings(
            self.item, text, [], "t", False, self.repo.facts(), rules, res) if f.level == "error"]

    def test_progress_values_are_a_source_of_numbers(self):
        res = resolve(f"За период — {{{{progress:{DS}.commits}}}} коммит.", "ru", self.repo)
        self.assertEqual(res.progress_used, ["commits"])
        self.assertEqual(self.check(res.text, res), [])
        self.assertTrue(any("число 981" in m for m in self.check("За период — 981 коммит.")))

    def test_keywords_backed_by_progress_metrics(self):
        res = resolve(f"Строк стало {{{{progress:{DS}.lines_end}}}}.", "ru", self.repo)
        self.assertEqual(self.check(res.text, res), [])
        res = resolve(f"Строк много, коммитов {{{{progress:{DS}.commits}}}}.", "ru", self.repo)
        self.assertTrue(any("KW-LOC" in m for m in self.check(res.text, res)))

    def test_figure_placeholder_is_not_text(self):
        self.assertEqual(self.check(f"{{{{figure:{DS}.verification}}}}"), [])

    def test_summary_is_part_of_approval(self):
        repo = make_repo(git=False)
        v = find(repo, "CI-0101").variants()["website.ru"]
        before = variant_sha(v)
        v.doc.meta["summary"] = "Другая подводка."
        self.assertNotEqual(variant_sha(v), before)


class Check(unittest.TestCase):
    def test_real_repo_green(self):
        r = progress_check.check(Repo())
        self.assertEqual(r.errors, [], r.errors)

    def test_catches_broken_data(self):
        repo = make_repo(git=False)
        path = repo.path(f"data/progress/datasets/{DS}.json")
        data = json.loads(path.read_text(encoding="utf-8"))
        data["repos"]["galdhold"]["daily"]["ai"][0] += 1
        data["repos"]["stray"] = data["repos"]["dpmeta"]
        path.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
        kw = repo.path("data/facts/claim_keywords.yaml")
        rules = yaml.safe_load(kw.read_text(encoding="utf-8"))
        rules["rules"][0]["progress"] = ["no_such_metric"]
        kw.write_text(yaml.safe_dump(rules, allow_unicode=True), encoding="utf-8")
        repo.reset()
        msgs = " | ".join(progress_check.check(repo).errors)
        for want in ("Claude + автор ≠ коммиты", "stray есть в наборе, но не в scope.yaml", "нет величины no_such_metric"):
            self.assertIn(want, msgs)

    def test_stage_fact_outside_core_is_guarded(self):
        # таблица среза выводит стадию и вне ядра (DPmeta — слой синхронизации): её факт проверяется так же
        repo = make_repo(git=False)
        fp = repo.path("data/facts/facts.yaml")
        reg = yaml.safe_load(fp.read_text(encoding="utf-8"))
        for f in reg["facts"]:
            if f["id"] == "META-STAGE":
                f["public"] = False
        fp.write_text(yaml.safe_dump(reg, allow_unicode=True), encoding="utf-8")
        repo.reset()
        self.assertIn("META-STAGE", " | ".join(progress_check.check(repo).errors))


class SitePage(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.repo = Repo()
        cls.st = Path(tempfile.mkdtemp(prefix="cs-progress-")) / "staging"
        cls.rep = SiteBuild(cls.repo, "staging").build(cls.st)

    def test_staging_has_both_pages_with_figures(self):
        self.assertEqual(self.rep.errors, [])
        for path, nav in (("progress/index.html", "Прогресс"), ("en/progress/index.html", "Progress")):
            html_ = (self.st / path).read_text(encoding="utf-8")
            self.assertEqual(len(re.findall(r'<figure class="pfig', html_)), len(FIGURES), path)
            self.assertIn(f'aria-current="page">{nav}</a>', html_)
            self.assertIn('<nav class="toc"', html_)
            self.assertNotIn("{{", html_, "все подстановки раскрыты")
        api = json.loads((self.st / "api/v1/progress.json").read_text(encoding="utf-8"))
        self.assertEqual(api["datasets"][0]["dataset"], DS)

    def test_production_waits_for_approval(self):
        pr = Path(tempfile.mkdtemp(prefix="cs-progress-")) / "production"
        rep = SiteBuild(self.repo, "production").build(pr)
        self.assertFalse((pr / "progress").exists(), "без одобрения автора страницы нет (Д12)")
        self.assertFalse((pr / "api/v1/progress.json").exists())
        self.assertNotIn("/progress/", (pr / "index.html").read_text(encoding="utf-8"))
        self.assertEqual(rep.errors, [])




class PublicText(unittest.TestCase):
    """На «Прогрессе» внутренних кодов нет (Д50): номера контрактов и решений убираются, текст остаётся."""

    def test_codes_are_removed(self):
        from tools.cs.progress.figures import public_text
        self.assertEqual(public_text("DPmeta C-01"), "DPmeta")
        self.assertEqual(public_text("форк чужого проекта — вне иерархии проектов (Д5)"), "форк чужого проекта — вне иерархии проектов")
        self.assertEqual(public_text("README профиля GitHub — не проект; правит только автор (DPmeta C-07)"),
                         "README профиля GitHub — не проект; правит только автор")
        # Скобки без кода и текст без кода — как были.
        self.assertEqual(public_text("реестр (через DPmeta)"), "реестр (через DPmeta)")
        self.assertEqual(public_text("одиночный скрипт вне карты проектов"), "одиночный скрипт вне карты проектов")


if __name__ == "__main__":
    unittest.main()
