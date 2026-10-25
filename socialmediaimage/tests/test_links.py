import unittest

from tools.cs.common import ROOT
from tools.cs.links import _expand, _top_dirs, candidates

TOP = {"docs", "data", "tools"}


class LinksTest(unittest.TestCase):
    def test_braces(self):
        self.assertEqual(_expand("docs/{A,B}.md"), ["docs/A.md", "docs/B.md"])

    def test_candidates(self):
        self.assertTrue(candidates("docs/PROCESS.md", TOP))
        self.assertTrue(candidates("DECISIONS.md", TOP))
        self.assertFalse(candidates("https://example.org/a.md", TOP))
        self.assertFalse(candidates("python3", TOP))

    def test_ignored_dirs_are_not_paths(self):
        # build/ в .gitignore: в repo_files его нет, есть ли он на диске — итог один
        top = _top_dirs([ROOT / "docs" / "PROCESS.md", ROOT / "README.md"])
        self.assertIn("docs", top)
        self.assertNotIn("build", top)
        self.assertFalse(candidates("build/site/staging", top))


if __name__ == "__main__":
    unittest.main()
