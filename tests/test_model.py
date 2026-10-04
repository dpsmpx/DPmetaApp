import unittest

from tools.cs.model import check_path, effective_state, reached


class ModelTest(unittest.TestCase):
    def test_main_line_is_valid(self):
        path = ["IDEA", "RESEARCHED", "BRIEFED", "GENERATED", "UNDER_REVIEW", "REVISED",
                "UNDER_REVIEW", "APPROVED", "EXPORTED", "PUBLISHED", "ARCHIVED"]
        self.assertEqual(check_path(path), [])

    def test_skip_to_approved_is_error(self):
        errs = check_path(["IDEA", "RESEARCHED", "BRIEFED", "GENERATED", "APPROVED"])
        self.assertTrue(any("GENERATED → APPROVED" in e for e in errs))

    def test_must_start_with_idea(self):
        self.assertTrue(check_path(["RESEARCHED"]))

    def test_reject_allowed_before_publication_only(self):
        self.assertEqual(check_path(["IDEA", "REJECTED"]), [])
        self.assertTrue(check_path(["IDEA", "RESEARCHED", "BRIEFED", "GENERATED", "UNDER_REVIEW",
                                    "APPROVED", "EXPORTED", "PUBLISHED", "REJECTED"]))

    def test_reached(self):
        self.assertTrue(reached("REVISED", "UNDER_REVIEW"))
        self.assertFalse(reached("UNDER_REVIEW", "APPROVED"))
        self.assertFalse(reached("REJECTED", "IDEA"))

    def test_effective_state_of_terminal(self):
        self.assertEqual(effective_state("REJECTED", ["IDEA", "RESEARCHED", "BRIEFED", "REJECTED"]), "BRIEFED")
        self.assertEqual(effective_state("ARCHIVED", ["IDEA", "PUBLISHED", "ARCHIVED"]), "PUBLISHED")
        self.assertEqual(effective_state("BRIEFED", []), "BRIEFED")


if __name__ == "__main__":
    unittest.main()
