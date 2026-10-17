import unittest

from tools.cs import secrets
from tools.cs.secrets import scan_text

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


class CheckTest(unittest.TestCase):
    """Д40, 12.10.2026: списка личных слов нет — проверка не ищет его и не пишет LIMITED."""

    def test_no_private_words_list(self):
        r = secrets.check()
        self.assertFalse([n for n in r.notes if "LIMITED" in n or "личные слова" in n], r.notes)
        self.assertFalse(hasattr(secrets, "private_words"))


if __name__ == "__main__":
    unittest.main()
