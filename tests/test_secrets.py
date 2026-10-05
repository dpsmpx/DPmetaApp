import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from tools.cs import secrets
from tools.cs.secrets import private_lines, private_words, scan_text

# Образцы собираются из частей, чтобы проверка secrets не находила их в этом файле.
GH = "gh" + "p_" + "A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8"
AWS = "AK" + "IA" + "ABCDEFGHIJKLMNOP"
KEY = "-----BEGIN " + "RSA PRIVATE KEY-----"
SIGNED = "https://cdn.example.org/a.jpeg?x-" + "signature=" + "Vn2vjyeCL9U99"


class SecretsTest(unittest.TestCase):
    def kinds(self, text):
        return {name for level, name, _ in scan_text(text) if level == "error"}

    def test_detects(self):
        self.assertIn("токен GitHub", self.kinds(f"token: {GH}"))
        self.assertIn("ключ AWS", self.kinds(AWS))
        self.assertIn("приватный ключ", self.kinds(KEY))
        self.assertIn("подписанный адрес или сессия", self.kinds(SIGNED))

    def test_plain_text_is_clean(self):
        text = "Секреты — только локально, вне Git. password: <локально>\nhttps://www.tiktok.com/@dpsmpx"
        self.assertEqual(self.kinds(text), set())

    def test_reports_line(self):
        hits = scan_text("a\nb\n" + AWS)
        self.assertEqual(hits[0][2], 3)


class PrivateWordsTest(unittest.TestCase):
    """Д40: список личных слов — вне Git; слова — заглушки, не имена."""

    def write(self, text: str) -> Path:
        p = Path(tempfile.mkdtemp(prefix="cs-words-")) / "private-words.txt"
        p.write_text(text, encoding="utf-8")
        return p

    def test_parse(self):
        p = self.write("# комментарий\nQwzk  # хвост\nab\n\nЁжзк\n")
        self.assertEqual(private_words(p), ["qwzk", "ежзк"])
        self.assertIsNone(private_words(p.with_name("нет.txt")))

    def test_lines_without_words(self):
        self.assertEqual(private_lines("a\nQWZKов b\nc\nежзк", ["qwzk", "ежзк"]), [2, 4])

    def test_check_limited_without_list_and_clean_with_list(self):
        missing = Path(tempfile.mkdtemp(prefix="cs-words-")) / "нет.txt"
        with mock.patch.dict(os.environ, {"DPSMPX_PRIVATE_WORDS": str(missing)}):
            r = secrets.check()
        self.assertTrue(any("LIMITED" in n for n in r.notes), r.notes)
        word = "qq" + "wv" + "zk"  # из частей — чтобы этого слова не было в самом файле
        with mock.patch.dict(os.environ, {"DPSMPX_PRIVATE_WORDS": str(self.write(word + "\n"))}):
            r = secrets.check()
        self.assertFalse([e for e in r.errors if "личных слов" in e], r.errors)
        self.assertTrue(any("слов 1" in n for n in r.notes), r.notes)


if __name__ == "__main__":
    unittest.main()
