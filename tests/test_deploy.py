"""Выгрузка сайта на Beget (Д35) и кто её запускает (Д36). rsync и сеть — подменены."""
from __future__ import annotations

import io
import os
import subprocess
import unittest
import urllib.error
from unittest import mock

import yaml

from tools.cs.adapters.website import WebsiteAdapter
from tools.cs.core.credentials import EnvCredentialStore
from tools.cs.core.engine import Publisher, Target
from tools.cs.core.ledger import Ledger
from tools.cs.site import deploy
from tools.cs.site.build import build
from tests.platform_fixture import ready_repo

TARGET = "u123@u123.beget.tech:dpsmpx.com/public_html/"
AUTHOR = {"CLAUDECODE": "", "CLAUDE_CODE_REMOTE": "", "CS_PROD_SITE_DEPLOY_TARGET": TARGET}
LOCAL = {**AUTHOR, "CLAUDECODE": "1"}
REMOTE = {**AUTHOR, "CLAUDECODE": "1", "CLAUDE_CODE_REMOTE": "true"}
RSYNC_OUT = ">f+++++++++ index.html\n>fc.T...... style.css\n*deleting   old.html\n"


def site_repo(live=True):
    """Готовая фикстура (CI-0101 одобрен) с выгрузкой rsync, как в data/website/site.yaml."""
    repo = ready_repo()
    p = repo.path("data/website/site.yaml")
    cfg = yaml.safe_load(p.read_text(encoding="utf-8"))
    cfg["hosting"] = "beget"
    cfg["environments"]["production"].update(live=live, deploy={
        "kind": "rsync", "target_ref": "CS_PROD_SITE_DEPLOY_TARGET", "guard_suffix": "public_html/",
        "protect": ["/.well-known/"]})
    p.write_text(yaml.safe_dump(cfg, allow_unicode=True), encoding="utf-8")
    repo.reset()
    return repo


class FakeRsync:
    def __init__(self, rc=0, out=RSYNC_OUT):
        self.calls, self.rc, self.out = [], rc, out

    def __call__(self, argv, **kw):
        self.calls.append(argv)
        return subprocess.CompletedProcess(argv, self.rc, self.out, "" if self.rc == 0 else "ssh: connect failed")


class FakeHTTP:
    """Хостинг: отдаёт собранные страницы из out (как после выгрузки)."""

    def __init__(self, out, base="https://dpsmpx.com"):
        self.out, self.base = out, base

    def __call__(self, req, timeout=None):
        path = req.full_url[len(self.base):]
        f = self.out / path.lstrip("/") / "index.html"
        if not f.is_file():
            raise urllib.error.HTTPError(req.full_url, 404, "Not Found", {}, io.BytesIO(b""))
        return _Resp(f.read_bytes())


class _Resp(io.BytesIO):
    status = 200

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False


class Rsync(unittest.TestCase):
    def test_argv(self):
        from pathlib import Path
        cfg = {"protect": ["/.well-known/"]}
        argv = deploy.rsync_argv(Path("/b/site"), "u@h:dpsmpx.com/public_html", cfg, dry_run=True)
        self.assertEqual(argv[0], "rsync")
        for flag in ("--delete-delay", "--delay-updates", "--checksum", "--dry-run", "--filter=P /.well-known/"):
            self.assertIn(flag, argv)
        self.assertIn("ssh -o BatchMode=yes", argv, "без ключа — отказ, а не вопрос пароля")
        self.assertEqual(argv[-2:], ["/b/site/", "u@h:dpsmpx.com/public_html/"], "содержимое папки, не сама папка")

    def test_target_must_end_with_public_html(self):
        cfg = {"guard_suffix": "public_html/"}
        self.assertEqual(deploy.target_problems(TARGET, cfg), [])
        self.assertEqual(deploy.target_problems("u@h:dpsmpx.com/public_html", cfg), [])
        self.assertIn("--delete не запускаю", deploy.target_problems("u@h:dpsmpx.com/", cfg)[0])
        self.assertIn("логин@сервер:путь", deploy.target_problems("/local/dir", cfg)[0])


class Guard(unittest.TestCase):
    def test_reasons(self):
        repo = site_repo(live=False)
        store = EnvCredentialStore({})
        with mock.patch.dict(os.environ, REMOTE):
            why = " ".join(deploy.guard(repo, store=store))
        self.assertIn("сайт готов", why)
        self.assertIn("облачной сессии", why)
        self.assertIn("CS_PROD_SITE_DEPLOY_TARGET", why)
        with mock.patch.dict(os.environ, REMOTE):
            dry = deploy.guard(repo, store=store, dry_run=True)
        self.assertEqual(len(dry), 1, "пробный прогон не выпуск: мешает только отсутствие адреса")

    def test_local_session_may_deploy_site_only(self):
        repo = site_repo()
        store = EnvCredentialStore({"CS_PROD_SITE_DEPLOY_TARGET": TARGET})
        with mock.patch.dict(os.environ, LOCAL):
            self.assertEqual(deploy.guard(repo, store=store), [])
            self.assertIsNone(deploy.session_may_deploy(repo, "website"))
            self.assertIn("Д26", deploy.session_may_deploy(repo, "telegram"))
        with mock.patch.dict(os.environ, AUTHOR):
            self.assertEqual(deploy.guard(repo, store=store), [])

    def test_run_masks_target_and_reports_changes(self):
        repo = site_repo()
        out = repo.path("build", "site", "production")
        build(repo, "production", out)
        fake = FakeRsync()
        store = EnvCredentialStore({"CS_PROD_SITE_DEPLOY_TARGET": TARGET})
        with mock.patch.dict(os.environ, AUTHOR):
            res = deploy.run(repo, out, store=store, runner=fake, dry_run=True)
        self.assertTrue(res.ok)
        self.assertIn("--dry-run", fake.calls[0])
        self.assertEqual(fake.calls[0][-1], TARGET)
        self.assertNotIn(TARGET, " ".join(res.command), "адрес не печатается")
        self.assertEqual(len(res.changes), 3)
        with mock.patch.dict(os.environ, AUTHOR):
            bad = deploy.run(repo, out, store=store, runner=FakeRsync(rc=255))
        self.assertFalse(bad.ok)
        self.assertIn("код 255", bad.problems[0])


class Verify(unittest.TestCase):
    def test_states(self):
        repo = site_repo()
        out = repo.path("build", "p")
        build(repo, "production", out)
        seen = deploy.verify("https://dpsmpx.com", ["/", "/nope/"], FakeHTTP(out))
        self.assertEqual(seen, {"/": True, "/nope/": False})

        def offline(req, timeout=None):
            raise urllib.error.URLError("no route")
        self.assertEqual(deploy.verify("https://dpsmpx.com", ["/"], offline), {"/": None})


class PublishToBeget(unittest.TestCase):
    """publish website.ru --to site-production --production: сборка → rsync → проверка по HTTP → журнал."""

    def publish(self, env, live=True):
        repo = site_repo(live)
        fake = FakeRsync()
        with mock.patch.dict(os.environ, env), \
                mock.patch.object(WebsiteAdapter, "runner", staticmethod(fake)), \
                mock.patch.object(WebsiteAdapter, "fetch", staticmethod(FakeHTTP(repo.path("build", "site", "production")))):
            o = Publisher(repo, "production", sleep=lambda s: None).run(Target("CI-0101", "website.ru", "site-production"))
        return repo, o, fake

    def test_author_and_local_session(self):
        for env in (AUTHOR, LOCAL):
            repo, o, fake = self.publish(env)
            self.assertEqual(o.status, "published", o.notes)
            self.assertEqual(o.remote_url, "https://dpsmpx.com/news/test-news/")
            self.assertEqual(len(fake.calls), 1)
            rec = next(iter(Ledger(repo, "production").publications().values())).last("PUBLISHED")
            self.assertTrue(rec["verified"], "страница найдена на хостинге по canonical")
            self.assertTrue((repo.path("build", "site", "production") / ".htaccess").is_file())

    def test_local_session_other_platforms_blocked(self):
        repo = site_repo()
        with mock.patch.dict(os.environ, LOCAL):
            pub = Publisher(repo, "production", sleep=lambda s: None)
            plan = pub.plan(Target("CI-0101", "telegram.ru", "tg-prod"))
            errs = [f.msg for f in pub.preflight(plan, remote=False) if f.level == "error"]
        self.assertTrue(any("Д26" in e for e in errs), "локальной сессии — только сайт (Д36)")

    def test_remote_session_and_not_live(self):
        _, o, fake = self.publish(REMOTE)
        self.assertEqual(o.status, "blocked")
        self.assertTrue(any("облачной сессии" in n for n in o.notes))
        self.assertEqual(fake.calls, [], "rsync не запускался")
        _, o, fake = self.publish(AUTHOR, live=False)
        self.assertEqual(o.status, "blocked")
        self.assertTrue(any("сайт готов" in n for n in o.notes))
        self.assertEqual(fake.calls, [])


class Cli(unittest.TestCase):
    """Сессии в production — только публикация на сайт; сверка и прочее — автор (Д24, Д26, Д36)."""

    def run_cli(self, env, *argv):
        from contextlib import redirect_stdout
        from tools.cs.__main__ import main
        buf = io.StringIO()
        with mock.patch.dict(os.environ, env), redirect_stdout(buf):
            rc = main(list(argv))
        return rc, buf.getvalue()

    def test_author_only_actions(self):
        rc, out = self.run_cli(LOCAL, "publish", "--reconcile", "PUB-x", "--exists", "yes", "--production")
        self.assertEqual(rc, 2)
        self.assertIn("действия автора", out)
        rc, out = self.run_cli(REMOTE, "publish", "CI-0001", "website.ru", "--to", "site-production", "--production")
        self.assertEqual(rc, 2, "облачной сессии production закрыт целиком")
        self.assertIn("session_modes", out)


class Htaccess(unittest.TestCase):
    def test_beget_gets_htaccess(self):
        repo = site_repo()
        out = repo.path("build", "h")
        build(repo, "production", out)
        h = (out / ".htaccess").read_text(encoding="utf-8")
        self.assertIn("ErrorDocument 404 /404.html", h)
        self.assertIn("Header always set Content-Security-Policy \"default-src 'self';", h)
        self.assertIn("<IfModule mod_headers.c>", h, "нет модуля — блок пропускается, а не ошибка 500")
        active = _directives(h)
        self.assertIn("Options -Indexes", active, "без списка файлов каталогов (инцидент 05.10.2026)")
        self.assertFalse([d for d in active if d.startswith("Options") and "+Indexes" in d])
        self.assertNotIn("Require all denied", active, "сайт должен отдаваться")
        self.assertNotIn("X-Robots-Tag", h, "production открыт")
        self.assertFalse((out / "_headers").exists())


def _directives(text: str) -> list[str]:
    """Строки .htaccess без комментариев и пустых."""
    return [s for s in (ln.strip() for ln in text.splitlines()) if s and not s.startswith("#")]


class RealConfig(unittest.TestCase):
    """Настоящие site.yaml и destinations.yaml: выпуск разрешён (Д34), запреты выгрузки на месте (Д35, Д36)."""

    def setUp(self):
        from tools.cs.core.repo import Repo
        self.repo = Repo()
        self.cfg = deploy.config(self.repo)
        self.store = EnvCredentialStore({"CS_PROD_SITE_DEPLOY_TARGET": TARGET})

    def test_production_is_deployable(self):
        self.assertIs(deploy.env_config(self.repo).get("live"), True, "Д34: выпуск разрешён")
        dest = self.repo.destinations()["site-production"]
        self.assertEqual(dest["status"], "active", "live и адресат согласованы — иначе site deploy и publish откажут")
        self.assertEqual(dest["target"]["base_url"], self.repo.site_base("production"))
        self.assertEqual(self.repo.site_base("production"), self.repo.site()["canonical_base"])
        for env in (AUTHOR, LOCAL):
            with mock.patch.dict(os.environ, env):
                self.assertEqual(deploy.guard(self.repo, store=self.store), [])

    def test_guards_stay(self):
        self.assertEqual(self.cfg["kind"], "rsync")
        self.assertEqual(self.cfg["target_ref"], "CS_PROD_SITE_DEPLOY_TARGET", "в Git — имя переменной, не адрес")
        self.assertEqual(self.cfg["guard_suffix"], "public_html/")
        self.assertIn("/.well-known/", self.cfg["protect"])
        self.assertEqual(self.repo.policy().get("local_session_production"), ["website"])
        with mock.patch.dict(os.environ, REMOTE):
            self.assertTrue(any("облачной сессии" in p for p in deploy.guard(self.repo, store=self.store)))
        with mock.patch.dict(os.environ, AUTHOR):
            self.assertTrue(any("CS_PROD_SITE_DEPLOY_TARGET" in p for p in deploy.guard(self.repo, store=EnvCredentialStore({}))))
            wrong = EnvCredentialStore({"CS_PROD_SITE_DEPLOY_TARGET": "u@h:dpsmpx.com/"})
            self.assertTrue(any("public_html" in p for p in deploy.guard(self.repo, store=wrong)))
        from pathlib import Path
        argv = deploy.rsync_argv(Path("/b/site"), TARGET, self.cfg, dry_run=True)
        for flag in ("--dry-run", "--filter=P /.well-known/", "ssh -o BatchMode=yes", "--delete-delay"):
            self.assertIn(flag, argv)

    def test_repo_root_is_not_servable(self):
        """Корень репозитория по ошибке в каталоге сайта (инцидент 05.10.2026) — Apache не отдаёт ничего."""
        active = _directives(self.repo.path(".htaccess").read_text(encoding="utf-8"))
        self.assertIn("Require all denied", active)
        self.assertIn("Deny from all", active, "Apache 2.2 без mod_authz_core")
        self.assertFalse([d for d in active if d.startswith(("Require all granted", "Allow from"))])

    def test_session_asks_before_deploy(self):
        """Д36: выгрузку из сессии Claude Code — только после «да»; любой порядок опций и ярлык."""
        import json
        perms = json.loads(self.repo.path(".claude/settings.json").read_text(encoding="utf-8"))["permissions"]
        for rule in ("Bash(python3 -m tools.cs publish *)", "Bash(python3 -m tools.cs site deploy *)",
                     "Bash(python -m tools.cs publish *)", "Bash(python -m tools.cs site deploy *)",
                     "Bash(*tools.cs site*deploy*)", "Bash(*tools.cs publish*)", "Bash(*dpsmpx-site-deploy*)",
                     "Bash(rsync *)", "Bash(ssh *)", "Bash(scp *)", "Bash(sftp *)"):
            self.assertIn(rule, perms["ask"])
        self.assertIn("Read(~/.ssh/**)", perms["deny"])

    def test_backup_keeps_every_tracked_file(self):
        """bundle — восстановление без GitHub (DISASTER_RECOVERY): в архиве и защиты (.htaccess, .claude/)."""
        from tools.cs.core.bundle import PARTS, tracked
        prefixes = [p for ps in PARTS.values() for p in ps]
        lost = [f for f in tracked(self.repo) if not any(f == p or f.startswith(p) for p in prefixes)]
        self.assertEqual(lost, [])

    def test_protection_changes_run_all_checks(self):
        from tools.cs.platform_cli import affected_checks
        for f in (".htaccess", ".claude/settings.json"):
            self.assertTrue(affected_checks([f])[1], f)


if __name__ == "__main__":
    unittest.main()
