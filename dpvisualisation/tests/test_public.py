"""Публичная выжимка и сборка (D-15, F-40): закрытое не уходит, проверка ловит утечки,
склейка модулей однозначна. Данные — синтетические, не экосистема."""

from __future__ import annotations

import json
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from tools.dpvis import build, public

HASH = "ab" * 20


def model() -> dict:
    cat = {
        "node_kinds": [{"id": k, "name": k} for k in ("repo", "actor", "service", "device", "upstream")],
        "flow_kinds": [{"id": k, "name": k, "driver": "data"} for k in ("work", "deploy", "build", "notify")],
        "transports": [{"id": k, "name": k} for k in ("git", "rsync", "manual", "http", "push")],
        "visibility": [{"id": k, "name": k} for k in ("public", "private", "undisclosed")],
        "drivers": [{"id": "data", "name": "данные"}],
        "statuses": [{"id": "active", "name": "работает"}],
        "groups": [{"id": "core", "name": "ядро"}],
    }
    n = lambda i, kind, vis="private", **kw: {"id": i, "kind": kind, "layer": "x", "name": i.title(), "status": "active",
                                              "visibility": vis, "refs": ["Проект:док"], **kw}
    f = lambda i, a, b, kind="work", tr="git", **kw: {"id": i, "from": a, "to": b, "kind": kind, "driver": "data",
                                                      "status": "active", "transport": tr, "what": "что-то",
                                                      "contracts": ["C-01"], "refs": ["Проект:док"],
                                                      "signal": {"type": "git-commits", "repo": a}, **kw}
    return {
        "schema": "dpvis-model/1", "title": "Тест", "as_of": "2026-10-06", "timezone": "+03:00",
        "layers": [{"id": "x", "name": "Слой"}, {"id": "background", "name": "Ранние"}], "catalog": cat,
        "allow": {"hosts": ["example.org"]}, "registry": {"node": "meta"}, "source": {"sha256": HASH},
        "nodes": [
            n("bot", "actor"),
            n("alpha", "repo", repo="owner/Alpha", role="продукт; рабочие подробности", group="core",
              stage="шаг 7 из 13, бэкенд готов", public_role="продукт", public_stage="ранняя стадия"),
            {**n("open", "repo", "public", repo="owner/Open"), "layer": "background"},
            n("secret", "repo", "undisclosed", repo="owner/Secret", role="клиент закрытой сети"),
            n("lib", "upstream", "public", role="внешняя библиотека секретного"),
            n("dev", "device", role="устройство автора"),
            n("ntfy", "service"),
            n("site", "service", "public", role="сайт на хостинге Beget", public_role="сайт",
              probes=[{"url": "https://example.org/"}]),
        ],
        "flows": [
            f("work.alpha", "bot", "alpha", what="ветки, коммиты; см. tools/x.toml", public_what="коммиты сессий"),
            f("work.secret", "bot", "secret"),
            f("build.lib", "lib", "secret", kind="build", tr="http"),
            f("clone.alpha", "alpha", "dev", kind="deploy", tr="git", trigger="клон на устройстве"),
            f("apk.open", "open", "dev", kind="deploy", tr="manual"),
            f("up.site", "dev", "site", kind="deploy", tr="rsync", trigger="ярлык на устройстве"),
            f("note.chan", "bot", "ntfy", kind="notify", tr="push"),
            f("work.open", "bot", "open"),
        ],
    }


def state() -> dict:
    days = [0] * 9 + [3]
    return {
        "schema": "dpvis-state/1", "as_of": "2026-10-06T17:00:00Z",
        "generated_by": {"tool": "DPvisualisation"}, "window": {"start": "2026-09-27", "days": 10},
        "rules": {"rate_days": 7}, "registry": {"checked": True}, "options": {"online": False},
        "diagnostics": [{"code": "CLONE_NOT_FOUND", "subject": "secret", "message": "нет клона owner/Secret"}],
        "nodes": {
            "bot": {"activity": {"state": "working", "last": "2026-10-06T16:00:00Z"}, "days": days},
            "alpha": {"activity": {"state": "working", "last": "2026-10-06T16:00:00Z",
                                   "reasons": [{"rule": "branch", "branch": "claude/x-1"}]},
                      "git": {"days": {"ai": days, "human": days, "merges": days}, "first_time": "2026-09-30T08:00:00Z",
                              "head": HASH, "head_time": "2026-10-06T16:00:00Z", "ref": "origin/main",
                              "branches": [{"name": "claude/x-1", "ahead": 2, "time": "2026-10-06T15:00:00Z"}],
                              "window": {"ai": 3, "human": 3, "merges": 3}, "history": "complete"}},
            "secret": {"activity": {"state": "unknown", "last": None}},
            "site": {"activity": {"state": "unknown", "last": None}},
        },
        "flows": {
            "work.alpha": {"state": "flowing", "days": days, "week": 3, "total": 3, "last": "2026-10-06T16:00:00Z", "signal": "git-commits"},
            "work.secret": {"state": "unknown"},
            "up.site": {"state": "unknown"},
        },
    }


class PublicBundle(unittest.TestCase):
    def setUp(self):
        self.doc = public.public_bundle(model(), state())
        self.text = json.dumps(self.doc, ensure_ascii=False)

    def test_closed_and_personal_nodes_are_gone(self):
        ids = {n["id"] for n in self.doc["model"]["nodes"]}
        self.assertEqual(ids, {"bot", "alpha", "site"}, "старые открытые проекты, закрытое и личное — вне выжимки")
        self.assertEqual(self.doc["model"]["hidden"], {"undisclosed": 1})
        self.assertEqual([l["id"] for l in self.doc["model"]["layers"]], ["x"])
        for word in ("Secret", "secret", "dev", "ntfy", "lib", "Open"):
            self.assertNotIn(f'"{word}"', self.text)
        self.assertNotIn("клиент закрытой сети", self.text)

    def test_only_whitelisted_fields(self):
        for key in ("refs", "contracts", "signal", "probes", "allow", "registry", "diagnostics", "options", "sha256", HASH, "claude/x"):
            self.assertNotIn(key, self.text)
        for key in ('"repo":', '"transport":', '"trigger":', '"branches":', '"merges":', '"history":'):
            self.assertNotIn(key, self.text, "подробности рабочей кухни не публикуются")
        kinds = {k["id"] for k in self.doc["model"]["catalog"]["node_kinds"]}
        self.assertNotIn("device", kinds, "каталог — только то, что есть в выжимке")

    def test_deploy_through_device_collapses(self):
        flows = {f["id"]: f for f in self.doc["model"]["flows"]}
        self.assertIn("deploy.alpha.site", flows)
        self.assertNotIn("deploy.open.site", flows, "APK на проверку — не выгрузка сайта")
        self.assertEqual(self.doc["state"]["flows"]["deploy.alpha.site"]["state"], "unknown")

    def test_state_has_numbers_and_dates_only(self):
        a = self.doc["state"]["nodes"]["alpha"]
        self.assertEqual(sorted(a["git"]), ["days", "first_date", "head_date", "window"])
        self.assertEqual(a["git"]["window"], {"ai": 3, "human": 3})
        self.assertEqual(a["git"]["head_date"], "2026-10-06")
        self.assertEqual(self.doc["state"]["nodes"]["bot"], {"activity": {"state": "working", "last": None}},
                         "дневной активности самого автора и участников в выжимке нет")
        self.assertEqual(a["activity"], {"state": "working", "last": "2026-10-06"})
        self.assertEqual(self.doc["state"]["flows"]["work.alpha"]["last"], "2026-10-06")
        self.assertNotIn("T16:00", json.dumps(self.doc["state"]["nodes"]))

    def test_texts_only_from_public_fields(self):
        nodes = {n["id"]: n for n in self.doc["model"]["nodes"]}
        self.assertEqual((nodes["alpha"]["role"], nodes["alpha"]["stage"]), ("продукт", "ранняя стадия"))
        self.assertEqual(nodes["site"]["role"], "сайт")
        self.assertNotIn("role", nodes["bot"], "нет публичной формулировки — нет текста")
        flows = {f["id"]: f for f in self.doc["model"]["flows"]}
        self.assertEqual(flows["work.alpha"]["what"], "коммиты сессий")
        self.assertNotIn("what", flows["deploy.alpha.site"])
        for word in ("рабочие подробности", "бэкенд", "tools/x.toml", "что-то", "public_"):
            self.assertNotIn(word, self.text, "рабочие тексты модели в выжимку не попадают")

    def test_check_catches_each_leak(self):
        cases = {
            "имя": ("role", "это DPpass"),
            "детали": ("role", "клиент VPN"),
            "устройство": ("role", "на телефоне"),
            "почта": ("role", "пишите a.b" + "@" + "mail.ru"),
            "адрес": ("role", "см. https://x.example"),
            "хеш": ("role", HASH),
            "ветка": ("role", "ветка ccr-0123abcd-xyz"),
        }
        for name, (field, value) in cases.items():
            doc = json.loads(json.dumps(self.doc))
            doc["model"]["nodes"][0][field] = value
            with self.assertRaises(public.PublicError, msg=name):
                public.check(doc)
        doc = json.loads(json.dumps(self.doc))
        doc["state"]["nodes"]["alpha"]["activity"]["last"] = "2026-10-06T16:00:00Z"
        with self.assertRaises(public.PublicError):
            public.check(doc)
        public.check(self.doc)  # чистое проходит


class Build(unittest.TestCase):
    def test_bundle_is_one_script_without_modules(self):
        js = build.bundle_js()
        self.assertNotRegex(js, r"(?m)^\s*(import|export)\s")
        node = shutil.which("node")
        if node:
            with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False, encoding="utf-8") as fh:
                fh.write(js)
            r = subprocess.run([node, "--check", fh.name], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stderr)

    def test_duplicate_top_level_name_refused(self):
        tmp = Path(tempfile.mkdtemp())
        shutil.copytree(build.VIEWER, tmp / "viewer")
        p = tmp / "viewer" / "src" / "tables.js"
        p.write_text(p.read_text(encoding="utf-8") + "\nfunction fmtDate() {}\n", encoding="utf-8")
        with self.assertRaises(build.BuildError):
            build.bundle_js(tmp / "viewer")

    def test_site_kit_and_single_file(self):
        tmp = Path(tempfile.mkdtemp())
        m = tmp / "model.json"
        s = tmp / "state.json"
        mm = model()
        mm["title"] = "Тест </script>"
        m.write_text(json.dumps(mm, ensure_ascii=False), encoding="utf-8")
        s.write_text(json.dumps(state(), ensure_ascii=False), encoding="utf-8")
        sums = build.build_site(m, s, tmp / "site")
        self.assertEqual(set(sums), {"dpvis.js", "dpvis.css", "dpvis-data.json"})
        data = json.loads((tmp / "site" / "dpvis-data.json").read_text(encoding="utf-8"))
        self.assertEqual(data["schema"], public.PUBLIC_SCHEMA)
        manifest = json.loads((tmp / "site" / "MANIFEST.json").read_text(encoding="utf-8"))
        self.assertEqual(manifest["files"], sums)
        out = build.build_bundle(m, s, tmp / "one.html", public_only=True)
        html = out.read_text(encoding="utf-8")
        payload = html.split('id="dpvis-data">', 1)[1].split("</script>", 1)[0]
        self.assertIn("<\\/script>", payload, "«</» внутри данных экранирован")
        self.assertNotIn("Secret", html)


if __name__ == "__main__":
    unittest.main()
