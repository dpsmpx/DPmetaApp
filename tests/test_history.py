"""Переписывание истории Git (Д40): личные слова уходят из файлов, сообщений и тегов; без «переписать» GitHub не трогается."""
from __future__ import annotations

import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

from tools.cs import history

WORD = "qq" + "wv" + "zk"  # заглушка из частей, не имя (Д40)
WORDS = [WORD, "ежзк"]


def sh(*args, cwd=None):
    return subprocess.run(args, cwd=cwd, check=True, capture_output=True, text=True).stdout


def make_origin() -> Path:
    """Голый «origin»: слово в тексте, в двоичном файле, в сообщении коммита и в теге; две ветки."""
    root = Path(tempfile.mkdtemp(prefix="cs-hist-"))
    origin = root / "origin.git"
    sh("git", "init", "-q", "--bare", str(origin))
    w = root / "w"
    sh("git", "init", "-q", "-b", "main", str(w))
    sh("git", "config", "user.email", "t@example.org", cwd=w)
    sh("git", "config", "user.name", "t", cwd=w)
    (w / "a.md").write_text(f"Автор — Ёжзк {WORD.capitalize()}.\n", encoding="utf-8")
    (w / "b.png").write_bytes(b"\x89PNG\x00\x01" + WORD.encode() + b"\x00")
    sh("git", "add", ".", cwd=w)
    sh("git", "commit", "-qm", f"первый: {WORD}", cwd=w)
    sh("git", "branch", "side", cwd=w)
    (w / "a.md").write_text("чисто\n", encoding="utf-8")
    sh("git", "commit", "-qam", "второй", cwd=w)
    sh("git", "tag", "-a", "v1", "-m", "тег ЕЖЗК", cwd=w)
    sh("git", "push", "-q", str(origin), "main", "side", "v1", cwd=w)
    return origin


def reachable_hits(origin: Path) -> dict:
    """Совпадения в том, что видно из веток и тегов (после gc — как у нового клона)."""
    sh("git", "--git-dir", str(origin), "gc", "-q", "--prune=now")
    return history.scan_objects(origin, WORDS)


class Variants(unittest.TestCase):
    def test_case_and_yo(self):
        v = history.variants("ежзк")
        self.assertTrue({"ежзк", "Ежзк", "ЕЖЗК", "ёжзк", "Ёжзк", "ЁЖЗК"} <= set(v))
        self.assertEqual(history.variants("ab3"), ["AB3", "Ab3", "ab3"])

    def test_expressions_are_rules_of_filter_repo(self):
        self.assertIn(f"literal:{WORD}==>***\n", history.expressions([WORD]))


@unittest.skipUnless(history.filter_repo_available(), "нет git filter-repo (pip install git-filter-repo)")
class Rewrite(unittest.TestCase):
    def test_rewrite_and_push(self):
        origin = make_origin()
        self.assertTrue(history.scan_objects(origin, WORDS))
        work = origin.parent / "work"
        out: list[str] = []
        rc = history.rewrite(str(origin), work, WORDS, confirm=lambda _: "переписать", say=out.append)
        self.assertEqual(rc, 0, out)
        self.assertEqual(reachable_hits(origin), {})
        g = ["git", "--git-dir", str(origin)]
        self.assertEqual(sh(*g, "show", "main~1:a.md"), "Автор — *** ***.\n")
        self.assertEqual(sh(*g, "log", "--all", "--format=%s").split("\n")[1], "первый: ***")
        self.assertIn("тег ***", sh(*g, "tag", "-n"))
        png = subprocess.run([*g, "show", "side:b.png"], capture_output=True, check=True).stdout
        self.assertEqual(png, b"\x89PNG\x00\x01" + b"*" * len(WORD) + b"\x00", "двоичный файл — та же длина")
        self.assertTrue((work / "commit-map.txt").is_file())
        self.assertFalse((work / "expressions.txt").exists() or (work / "pairs.json").exists(),
                         "файлы со словами удалены")
        self.assertFalse([line for line in out if WORD in line.lower()], "слово в выводе не печатается")

    def test_no_confirm_leaves_origin(self):
        origin = make_origin()
        heads = sh("git", "--git-dir", str(origin), "for-each-ref")
        rc = history.rewrite(str(origin), origin.parent / "work", WORDS, confirm=lambda _: "нет", say=lambda *_: None)
        self.assertEqual(rc, 1)
        self.assertEqual(sh("git", "--git-dir", str(origin), "for-each-ref"), heads)

    def test_clean_history_needs_nothing(self):
        origin = make_origin()
        rc = history.rewrite(str(origin), origin.parent / "work", ["zzzqqq"], confirm=lambda _: "переписать",
                             say=lambda *_: None)
        self.assertEqual(rc, 0)
        self.assertFalse((origin.parent / "work" / "commit-map.txt").exists())


class Guards(unittest.TestCase):
    def test_ai_session_refused(self):
        with mock.patch.dict(os.environ, {"CLAUDECODE": "1"}):
            self.assertEqual(history.cmd_rewrite(SimpleNamespace(workdir=None, no_push=True)), 2)


if __name__ == "__main__":
    unittest.main()
