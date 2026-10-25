import struct
import tempfile
import unittest
import zlib
from pathlib import Path

from tools.cs.exports import file_checks, png_metadata_chunks


def png(path: Path, w: int, h: int, text: bool = False) -> None:
    def chunk(t, data):
        return struct.pack(">I", len(data)) + t + data + struct.pack(">I", zlib.crc32(t + data))
    raw = b"".join(b"\x00" + b"\x00\x00\x00" * w for _ in range(h))
    body = chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
    if text:
        body += chunk(b"tEXt", b"Comment\x00secret")
    body += chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + body)


FMTS = {"github_social": {"platform": "github", "width": 4, "height": 2, "file": "png",
                          "max_bytes": 1048576, "verified": True}}


class ExportsTest(unittest.TestCase):
    def test_size_and_metadata(self):
        with tempfile.TemporaryDirectory() as d:
            ok, bad = Path(d) / "ok.png", Path(d) / "bad.png"
            png(ok, 4, 2)
            png(bad, 5, 2, text=True)
            self.assertEqual([f for k, v in file_checks(ok, "github_social", FMTS).items() for f in v], [])
            res = file_checks(bad, "github_social", FMTS)
            self.assertTrue(res["platform"])
            self.assertTrue(res["safety"])
            self.assertEqual(png_metadata_chunks(bad), ["tEXt"])

    def test_unknown_format(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "x.png"
            png(p, 4, 2)
            self.assertTrue(file_checks(p, "nope", FMTS)["platform"])


if __name__ == "__main__":
    unittest.main()
