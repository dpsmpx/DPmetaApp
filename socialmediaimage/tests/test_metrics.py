import unittest

from tools.cs.metrics import normalize, parse_value

CSV = """Content,Video title,Views,Watch time (hours),Average view duration,Impressions,Impressions click-through rate (%),Unknown column
Total,,120,3.5,0:01:45,2000,4.5,x
abc,Video,120,3.5,0:01:45,2000,4.5,y
"""


class MetricsTest(unittest.TestCase):
    def test_total_row(self):
        values, unmapped = normalize(CSV)
        self.assertEqual(values["views"], 120)
        self.assertEqual(values["avg_view_duration_s"], 105)
        self.assertEqual(values["ctr"], 4.5)
        self.assertIn("Unknown column", unmapped)

    def test_parse_values(self):
        self.assertEqual(parse_value("1 234"), 1234)
        self.assertEqual(parse_value("12,5%"), 12.5)
        self.assertEqual(parse_value("1,234"), 1234)
        self.assertIsNone(parse_value(""))

    def test_many_rows_without_total(self):
        with self.assertRaises(ValueError):
            normalize("Views\n1\n2\n")


if __name__ == "__main__":
    unittest.main()
