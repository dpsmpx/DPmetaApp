import unittest

from tools.cs.links import _expand, candidates

TOP = {"docs", "data", "tools"}


class LinksTest(unittest.TestCase):
    def test_braces(self):
        self.assertEqual(_expand("docs/{A,B}.md"), ["docs/A.md", "docs/B.md"])

    def test_candidates(self):
        self.assertTrue(candidates("docs/PROCESS.md", TOP))
        self.assertTrue(candidates("DECISIONS.md", TOP))
        self.assertFalse(candidates("https://example.org/a.md", TOP))
        self.assertFalse(candidates("python3", TOP))


if __name__ == "__main__":
    unittest.main()
