"""Водяной знак сайта, реестр выпусков и метки времени (Д41, П2-20). Сеть и rsync — подменены."""
from __future__ import annotations

import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import tempfile
import unittest
import urllib.error
from pathlib import Path
from unittest import mock

import yaml

from tools.cs.mark import key as mkey
from tools.cs.mark import release, stamp, svg, verify
from tests.test_deploy import AUTHOR, FakeRsync, site_repo

try:
    import numpy
    from PIL import Image, ImageDraw
    HAVE_IMAGING = bool(numpy.__version__)
except ImportError:  # на телефоне — pkg install python-numpy python-pillow (Д41)
    HAVE_IMAGING = False

KEY = bytes(range(32))
OTHER = bytes(range(1, 33))


def figure(n: int = 60) -> str:
    """Фигура, похожая на фигуры сайта: линии, точки, подписи; координаты с одним знаком и целые."""
    parts = [f'<line x1="{i * 10}" y1="{200 - i * 2.5}" x2="{i * 10 + 8}" y2="{200 - i * 3.5}" class="g"/>' for i in range(n)]
    parts += [f'<circle cx="{i * 12.5}" cy="{40 + i % 7}" r="3"/>' for i in range(n // 2)]
    parts += ['<path d="M8 300 L200 10.5 L20 20"/>', '<path d="M0 0 A5 5 0 0 1 9 9"/>',
              '<text x="12" y="290">2026</text>']
    return f'<svg class="chart" viewBox="0 0 640 300" width="640" height="300">{"".join(parts)}</svg>'


class KeyTest(unittest.TestCase):
    def test_init_load_and_no_overwrite(self):
        p = Path(tempfile.mkdtemp(prefix="cs-key-")) / "dpsmpx" / "mark.key"
        mkey.init(p)
        self.assertEqual(p.stat().st_mode & 0o777, 0o600)
        k = mkey.load(p)
        self.assertEqual(len(k), 32)
        with self.assertRaises(FileExistsError):
            mkey.init(p)
        self.assertEqual(mkey.load(p), k, "ключ не перезаписан")
        self.assertIsNone(mkey.load(p.with_name("нет.key")))
        p.write_text("abcd\n", encoding="ascii")
        with self.assertRaises(ValueError):
            mkey.load(p)
        self.assertNotIn(KEY.hex(), mkey.fingerprint(KEY))


class SvgMark(unittest.TestCase):
    def test_embed_is_repeatable_small_and_keeps_arcs(self):
        src = figure()
        out = svg.embed(src, KEY)
        self.assertEqual(out, svg.embed(src, KEY), "тот же вход и ключ — те же байты")
        self.assertNotEqual(out, svg.embed(src, OTHER))
        self.assertEqual(svg.skeleton(out), svg.skeleton(src))
        a, b = [float(x) for x in re.findall(r'cx="([^"]+)"', src)], [float(x) for x in re.findall(r'cx="([^"]+)"', out)]
        self.assertTrue(all(0 < abs(x - y) <= 0.0300001 for x, y in zip(a, b)), "сдвиг — не больше 0,03 px")
        self.assertIn('d="M0 0 A5 5 0 0 1 9 9"', out, "дуга не тронута")
        self.assertIn('viewBox="0 0 640 300" width="640" height="300"', out, "размеры не тронуты")
        self.assertIn('r="3"', out)
        self.assertIn(">2026</text>", out, "текст не тронут")

    def test_read_without_original(self):
        src = figure()
        found = svg.read(svg.embed(src, KEY), KEY)
        self.assertTrue(found.found)
        self.assertEqual(found.agree, found.moved)
        self.assertFalse(svg.read(svg.embed(src, OTHER), KEY).found, "чужой ключ — не находка")
        self.assertEqual(svg.read(src, KEY).moved, 0, "без метки сдвигов нет")
        tiny = svg.read(svg.embed('<svg><circle cx="5" cy="5" r="1"/></svg>', KEY), KEY)
        self.assertFalse(tiny.found, "две координаты — слишком мало для вывода")
        self.assertGreater(tiny.p, 0.1)

    def test_two_decimal_coordinates(self):
        src = '<svg>' + "".join(f'<circle cx="{i}.25" cy="{i}.75"/>' for i in range(40)) + '</svg>'
        out = svg.embed(src, KEY)
        self.assertRegex(out, r'cx="0\.(2503|2497)"', "0.25 → ±0.0003: меньше шага исходной записи")
        self.assertTrue(svg.read(out, KEY).found)

    def test_inline_figures(self):
        page = f"<!doctype html><html><body><p>x=1</p>{figure()}<p>и</p>{figure(30)}</body></html>"
        out, n = svg.embed_inline(page, KEY)
        self.assertEqual(n, 2)
        self.assertIn("<p>x=1</p>", out, "текст страницы не тронут")
        self.assertEqual([m.found for m in svg.read_inline(out, KEY)], [True, True])


@unittest.skipUnless(HAVE_IMAGING, "нет numpy или Pillow")
class ImageMark(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        img = Image.new("RGB", (384, 256), (32, 34, 38))
        d = ImageDraw.Draw(img)
        for i in range(0, 384, 24):
            d.line([(i, 0), (i, 255)], fill=(70, 74, 80))
        for i in range(12):
            d.rectangle([20 + i * 28, 60 + (i % 3) * 30, 40 + i * 28, 200], outline=(220, 140, 60), width=2)
            d.text((22 + i * 28, 210), str(i), fill=(230, 230, 230))
        buf = io.BytesIO()
        img.save(buf, "PNG")
        cls.src = buf.getvalue()
        from tools.cs.mark import image
        cls.image = image
        cls.marked = image.embed(cls.src, KEY)

    def open(self, data):
        return Image.open(io.BytesIO(data))

    def test_repeatable_and_invisible(self):
        self.assertEqual(self.image.embed(self.src, KEY), self.marked)
        self.assertGreater(self.image.psnr(self.src, self.marked), 40)

    def test_found_after_jpeg_and_downscale(self):
        buf = io.BytesIO()
        self.open(self.marked).convert("RGB").save(buf, "JPEG", quality=85)
        self.assertTrue(self.image.match(self.open(buf.getvalue()), self.src, KEY).found, "JPEG 85")
        half = self.open(self.marked).resize((192, 128), Image.LANCZOS)
        self.assertTrue(self.image.match(half, self.src, KEY).found, "уменьшение вдвое")

    def test_found_after_downscale_to_third(self):
        """Уменьшение до трети + JPEG 85: исходник проходит то же уменьшение, что и копия, — его
        детали не глушат метку (до 31.10.2026 — p ≈ 1e-4, не находка); без метки — не находка."""
        third = (128, 85)
        for data, found in ((self.marked, True), (self.src, False)):
            buf = io.BytesIO()
            self.open(data).convert("RGB").resize(third, Image.LANCZOS).save(buf, "JPEG", quality=85)
            mt = self.image.match(self.open(buf.getvalue()), self.src, KEY)
            self.assertEqual(mt.found, found, f"p={mt.p:.1e}")
            self.assertFalse(mt.screen)

    def test_found_when_sides_were_resized_apart(self):
        """Стороны уменьшены порознь (129×87 из 384×256, пропорция — на 1 % другая): общий масштаб
        по ширине выводит высоту за исходник, по высоте — сдвигает край; прямо к размеру исходника —
        находка (до 31.10.2026 — p ≈ 0,08). Без метки — не находка."""
        for data, found in ((self.marked, True), (self.src, False)):
            buf = io.BytesIO()
            self.open(data).convert("RGB").resize((129, 87), Image.LANCZOS).save(buf, "JPEG", quality=85)
            mt = self.image.match(self.open(buf.getvalue()), self.src, KEY)
            self.assertEqual(mt.found, found, f"p={mt.p:.1e}")

    def test_copy_place_sum_depends_only_on_its_own_signs(self):
        """Копия, как и снимок: сумма места зависит только от его знаков — без метки знаки мест —
        независимые монетки, вероятность точная. Размытая метка (прежняя статистика копии) меняла
        суммы соседних мест. Перевернуть знаки одного места — меняется сумма только его."""
        import numpy as np
        im = self.image
        buf = io.BytesIO()
        self.open(self.src).convert("RGB").resize((128, 85), Image.LANCZOS).save(buf, "JPEG", quality=85)
        copy = self.open(buf.getvalue()).convert("RGB")
        y0 = im._luma(im._rgb(self.open(self.src))[0])
        H, W = y0.shape
        sy = im._resample(im._luma(im._rgb(copy)[0]), (W, H))
        sg = im.signs(KEY, self.src)
        c1, has = im.copy_sums(sy, y0, im.mark_delta(y0, sg), 0, 0, copy.size)
        flip = sg.copy()
        flip[5, 7] *= -1
        c2, _ = im.copy_sums(sy, y0, im.mark_delta(y0, flip), 0, 0, copy.size)
        changed = np.flatnonzero(~np.isclose(c1, c2, rtol=0, atol=1e-9))
        self.assertEqual(changed.tolist(), [5 * im.TILE[1] + 7])
        self.assertTrue(has.all())

    def test_not_found_without_mark_or_with_other_key(self):
        self.assertFalse(self.image.match(self.open(self.src), self.src, KEY).found)
        self.assertFalse(self.image.match(self.open(self.image.embed(self.src, OTHER)), self.src, KEY).found)

    def screen(self, data, width, at, size=(900, 700), quality=85):
        """Снимок экрана: светлая страница со строками текста, картинка шириной width в точке at, JPEG."""
        page = Image.new("RGB", size, (248, 248, 246))
        d = ImageDraw.Draw(page)
        for y in range(12, size[1], 22):
            for x in range(12, size[0] - 40, 64):
                d.text((x, y), "текст", fill=(40, 40, 40))
        pic = self.open(data).convert("RGB")
        page.paste(pic.resize((width, round(width * pic.height / pic.width)), Image.BILINEAR), at)
        buf = io.BytesIO()
        page.save(buf, "JPEG", quality=quality)
        return self.open(buf.getvalue())

    def test_found_in_screenshot_at_unknown_scale_and_place(self):
        for width, at in ((288, (301, 77)), (576, (150, 260))):  # уменьшена до 0,75 и увеличена в 1,5 раза
            shot = self.screen(self.marked, width, at, size=(900, 700) if width < 400 else (1000, 800))
            mt = self.image.match(shot, self.src, KEY)
            self.assertTrue(mt.found and mt.screen, f"ширина {width}: p={mt.p:.1e}")
            self.assertEqual(mt.shift, (at[1], at[0]))
            self.assertAlmostEqual(1 / mt.scale, width / 384, places=2)

    def test_screenshot_without_mark_other_key_or_picture_is_not_found(self):
        for data in (self.src, self.image.embed(self.src, OTHER)):
            self.assertFalse(self.image.match(self.screen(data, 288, (301, 77)), self.src, KEY).found)
        page = self.screen(self.marked, 288, (301, 77))
        page.paste((248, 248, 246), (290, 70, 600, 280))  # картинки в кадре нет
        mt = self.image.match_screen(page, self.src, KEY)
        self.assertFalse(mt.found)
        self.assertEqual(mt.cells, 0)

    def test_screenshot_place_sum_depends_only_on_its_own_signs(self):
        """Без метки знаки мест плитки — независимые монетки, только если сумма места зависит лишь от
        его знаков. Размытая метка (как было до проверки 26.10.2026) заходила на соседние места: при
        уменьшении до 0,2 разброс числа совпадений — в 1,5 раза шире биномиального, вероятность
        ложной находки — занижена. Перевернуть знаки одного места — меняется сумма только его."""
        import numpy as np
        im = self.image
        shot = self.screen(self.src, 160, (301, 77))  # уменьшение до 0,42, без метки
        y0 = im._luma(im._rgb(self.open(self.src))[0])
        ys = im._luma(im._rgb(shot.convert("RGB"))[0])
        pl = im.locate(ys, y0)
        self.assertIsNotNone(pl)
        sg = im.signs(KEY, self.src)
        c1, has = im.screen_sums(ys, y0, im.mark_delta(y0, sg), pl)
        flip = sg.copy()
        flip[5, 7] *= -1
        c2, _ = im.screen_sums(ys, y0, im.mark_delta(y0, flip), pl)
        changed = np.flatnonzero(~np.isclose(c1, c2, rtol=0, atol=1e-9))
        self.assertEqual(changed.tolist(), [5 * im.TILE[1] + 7])
        self.assertTrue(has.all())

    def test_long_page_screenshot_is_searched(self):
        """Снимок длинной страницы: ни один масштаб копии целиком не подходит — снимок всё равно ищется."""
        shot = self.screen(self.marked, 288, (6, 2400), size=(300, 4000))
        mt = self.image.match(shot, self.src, KEY)
        self.assertTrue(mt.found and mt.screen, f"p={mt.p:.1e}")

    def test_verify_finds_release_by_screenshot(self):
        root = Path(tempfile.mkdtemp(prefix="cs-verify-"))
        (root / "content").mkdir()
        (root / "content" / "pic.png").write_bytes(self.src)
        rel = root / release.RELEASES / "2026-10-26T000000Z"
        rel.mkdir(parents=True)
        entry = {"path": "media/pic.png", "sha256": hashlib.sha256(self.marked).hexdigest(),
                 "source_sha256": hashlib.sha256(self.src).hexdigest(), "kind": "image",
                 "method": self.image.METHOD, "dhash": self.image.dhash(self.open(self.src))}
        m = release.manifest([entry], KEY, "https://dpsmpx.com", None, rel.name)
        (rel / "manifest.json").write_bytes(release.manifest_bytes(m))
        buf = io.BytesIO()
        self.screen(self.marked, 288, (301, 77)).save(buf, "PNG")
        res = verify.check(root, buf.getvalue(), "shot.png", KEY)
        self.assertTrue(res.found, "\n".join(res.lines))
        self.assertIn(rel.name, res.releases)
        self.assertTrue(any("снимок экрана" in line for line in res.lines), "\n".join(res.lines))


def der_resp(status: int) -> bytes:
    """Ответ службы RFC 3161 без токена: SEQUENCE { SEQUENCE { INTEGER status } }."""
    inner = b"\x30\x03\x02\x01" + bytes([status])
    return b"\x30" + bytes([len(inner)]) + inner


class _Resp(io.BytesIO):
    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False


class Stamps(unittest.TestCase):
    def manifest(self) -> Path:
        p = Path(tempfile.mkdtemp(prefix="cs-rel-")) / "manifest.json"
        p.write_bytes(b'{"schema": "site-release/v1"}\n')
        return p

    def test_request_is_der_with_digest_and_cert_request(self):
        d = hashlib.sha256(b"x").digest()
        q = stamp.tsq(d, nonce=12345)
        self.assertEqual(q[0], 0x30)
        self.assertIn(d, q)
        self.assertIn(stamp.SHA256_OID, q)
        self.assertTrue(q.endswith(b"\x01\x01\xff"), "certReq TRUE")
        if shutil.which("openssl"):
            f = Path(tempfile.mkdtemp()) / "q.tsq"
            f.write_bytes(q)
            out = subprocess.run(["openssl", "ts", "-query", "-in", str(f), "-text"], capture_output=True, text=True).stdout
            self.assertIn("Hash Algorithm: sha256", out)
            self.assertIn("Certificate required: yes", out)

    def test_status(self):
        self.assertEqual(stamp.tsr_status(der_resp(0)), 0)
        self.assertEqual(stamp.tsr_status(der_resp(2)), 2)
        self.assertIsNone(stamp.tsr_status(b"<html>"))
        self.assertIsNone(stamp.tsr_status(b"\x30\x05\x30\x03\x02\x01"), "обрезанный ответ — не метка")

    def test_broken_response_is_pending(self):
        import http.client

        class Broken(_Resp):
            def read(self, *a):
                raise http.client.IncompleteRead(b"")

        mp = self.manifest()
        self.assertEqual(stamp.rfc3161(mp, post=lambda r, timeout=None: Broken()).state, "pending")
        self.assertFalse(mp.with_name("manifest.json.tsr").exists())

    def test_network_down_is_pending_not_failure(self):
        mp = self.manifest()

        def down(req, timeout=None):
            raise urllib.error.URLError("нет сети")

        with mock.patch("tools.cs.mark.stamp.shutil.which", return_value=None):
            res = stamp.stamp_all(mp, post=down)
        self.assertEqual([(s.kind, s.state) for s in res], [("rfc3161", "pending"), ("ots", "limited")])
        self.assertFalse(mp.with_name("manifest.json.tsr").exists())

    def test_granted_is_saved_and_refusal_is_not(self):
        mp = self.manifest()
        seen = []

        def tsa(req, timeout=None):
            seen.append(req)
            return _Resp(der_resp(0))

        s = stamp.rfc3161(mp, post=tsa)
        self.assertEqual(s.state, "done")
        self.assertEqual(seen[0].headers["Content-type"], "application/timestamp-query")
        self.assertIn(hashlib.sha256(mp.read_bytes()).digest(), seen[0].data)
        self.assertEqual(stamp.rfc3161(mp, post=None).note, "уже есть")
        mp2 = self.manifest()
        self.assertEqual(stamp.rfc3161(mp2, post=lambda r, timeout=None: _Resp(der_resp(2))).state, "failed")
        self.assertFalse(mp2.with_name("manifest.json.tsr").exists())

    @unittest.skipUnless(shutil.which("openssl"), "нет openssl")
    def test_verify_with_local_tsa(self):
        """Своя служба RFC 3161 на openssl: подпись сходится, подмена манифеста ловится."""
        d = Path(tempfile.mkdtemp(prefix="cs-tsa-"))
        (d / "tsa.cnf").write_text(
            "[ tsa ]\ndefault_tsa = t\n[ t ]\ndir = .\nserial = ./serial\ncrypto_device = builtin\n"
            "signer_cert = ./tsa.crt\ncerts = ./ca.crt\nsigner_key = ./tsa.key\nsigner_digest = sha256\n"
            "default_policy = 1.2.3.4.1\nother_policies = 1.2.3.4.2\ndigests = sha256\naccuracy = secs:1\n"
            "ordering = yes\ntsa_name = no\ness_cert_id_chain = no\ness_cert_id_alg = sha256\n"
            "[ v3_tsa ]\nbasicConstraints=CA:FALSE\nkeyUsage=nonRepudiation,digitalSignature\n"
            "extendedKeyUsage=critical,timeStamping\n", encoding="ascii")
        (d / "serial").write_text("01\n")

        def ssl(*argv):
            subprocess.run(["openssl", *argv], cwd=d, capture_output=True, check=True)

        ssl("req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", "ca.key", "-out", "ca.crt", "-subj", "/CN=ca",
            "-days", "2", "-addext", "basicConstraints=critical,CA:TRUE", "-addext", "keyUsage=keyCertSign,cRLSign")
        ssl("req", "-newkey", "rsa:2048", "-nodes", "-keyout", "tsa.key", "-out", "tsa.csr", "-subj", "/CN=tsa")
        ssl("x509", "-req", "-in", "tsa.csr", "-CA", "ca.crt", "-CAkey", "ca.key", "-CAcreateserial", "-out", "tsa.crt",
            "-days", "2", "-extfile", "tsa.cnf", "-extensions", "v3_tsa")
        mp = d / "manifest.json"
        mp.write_bytes(b'{"id": "x"}\n')

        def tsa(req, timeout=None):
            (d / "q.tsq").write_bytes(req.data)
            ssl("ts", "-reply", "-config", "tsa.cnf", "-queryfile", "q.tsq", "-out", "r.tsr")
            return _Resp((d / "r.tsr").read_bytes())

        self.assertEqual(stamp.rfc3161(mp, post=tsa).state, "done")
        self.assertEqual(stamp.verify_rfc3161(mp, d / "ca.crt", d / "tsa.crt")[0], True)
        self.assertIsNone(stamp.verify_rfc3161(mp)[0], "без сертификата службы — «не проверено», а не «верно»")
        mp.write_bytes(b'{"id": "y"}\n')
        self.assertEqual(stamp.verify_rfc3161(mp, d / "ca.crt", d / "tsa.crt")[0], False, "подмена манифеста")


def tree() -> Path:
    root = Path(tempfile.mkdtemp(prefix="cs-build-")) / "production"
    (root / "path").mkdir(parents=True)
    (root / "path" / "index.html").write_text(f"<!doctype html><html><body>{figure()}</body></html>", encoding="utf-8")
    (root / "favicon.svg").write_text(figure(10), encoding="utf-8")
    (root / "style.css").write_text("body{margin:0}", encoding="utf-8")
    (root / "index.html").write_text("<!doctype html><html><body><p>без фигур</p></body></html>", encoding="utf-8")
    return root


class Release(unittest.TestCase):
    def test_mark_build_records_and_is_repeatable(self):
        a, b = tree(), tree()
        fa, fb = release.mark_build(a, KEY), release.mark_build(b, KEY)
        self.assertEqual(fa, fb, "повторяемо")
        by = {f["path"]: f for f in fa}
        self.assertEqual(by["path/index.html"]["kind"], "html-svg")
        self.assertEqual(by["favicon.svg"]["kind"], "svg")
        self.assertNotIn("method", by["style.css"])
        self.assertNotIn("source_sha256", by["index.html"], "страница без фигур не меняется")
        for f in fa:
            self.assertEqual(release.sha256((a / f["path"]).read_bytes()), f["sha256"])
        m = release.manifest(fa, KEY, "https://dpsmpx.com", "abc", "2026-10-17T120000Z")
        self.assertEqual(m["key_id"], mkey.fingerprint(KEY))
        self.assertNotIn(KEY.hex(), release.manifest_bytes(m).decode())
        repo_root = Path(tempfile.mkdtemp(prefix="cs-repo-"))
        p = release.write_manifest(repo_root, m)
        self.assertEqual(release.load_all(repo_root), [(p, m)])
        m2 = release.manifest(fa, KEY, "https://dpsmpx.com", "abc", "2026-10-17T120000Z")
        p2 = release.write_manifest(repo_root, m2)
        self.assertEqual(m2["id"], "2026-10-17T120000Z-2", "выгрузка в ту же секунду — свой каталог, а не падение")
        self.assertEqual(p2.parent.name, m2["id"])


def marked_repo():
    repo = site_repo()
    p = repo.path("data/website/site.yaml")
    cfg = yaml.safe_load(p.read_text(encoding="utf-8"))
    cfg["environments"]["production"]["deploy"]["mark"] = {"required": True, "tsa": "https://tsa.example/tsr"}
    p.write_text(yaml.safe_dump(cfg, allow_unicode=True), encoding="utf-8")
    repo.reset()
    return repo


class DeployWithMark(unittest.TestCase):
    """Д41: метку ставит выгрузка ключом автора; без ключа — отказ; после — манифест и метки времени."""

    def setUp(self):
        self.keyfile = Path(tempfile.mkdtemp(prefix="cs-key-")) / "mark.key"
        self.keyfile.write_text(KEY.hex() + "\n", encoding="ascii")

    def deploy(self, repo, out, keyfile, dry_run=False):
        from tools.cs.site import deploy
        env = {**AUTHOR, mkey.KEY_ENV: str(keyfile)}

        def down(req, timeout=None):
            raise urllib.error.URLError("нет сети")

        with mock.patch.dict(os.environ, env), mock.patch("tools.cs.mark.stamp.shutil.which", return_value=None):
            return deploy.run(repo, out, dry_run=dry_run, runner=FakeRsync(), post=down)

    def test_without_key_refuses(self):
        from tools.cs.site import deploy
        repo = marked_repo()
        res = self.deploy(repo, tree(), self.keyfile.with_name("нет.key"))
        self.assertFalse(res.ok)
        self.assertIn("mark init", res.problems[0])
        with mock.patch.dict(os.environ, {**AUTHOR, mkey.KEY_ENV: str(self.keyfile.with_name("нет.key"))}):
            self.assertTrue(any("mark init" in p for p in deploy.guard(repo)), "ключ проверяет и preflight публикации")
            self.assertFalse([p for p in deploy.guard(site_repo()) if "ключ" in p], "без deploy.mark ключ не нужен")

    def test_release_record_failure_does_not_fail_deploy(self):
        from tools.cs.site import deploy
        repo, out = marked_repo(), tree()
        with mock.patch.object(deploy, "record_release", side_effect=OSError("диск")):
            res = self.deploy(repo, out, self.keyfile)
        self.assertTrue(res.ok, "rsync уже прошёл — выгрузка не падает")
        self.assertIsNone(res.release)
        self.assertTrue(any("манифест выпуска не записан" in p for p in res.problems))

    def test_manifest_names_object_and_version(self):
        from types import SimpleNamespace as NS
        from tools.cs.site import deploy
        repo, out = marked_repo(), tree()
        entry = NS(item=NS(id="CI-0101"), variant=NS(id="website.ru", m=lambda k: 3 if k == "version" else None),
                   path="/path/", image=None)
        objs = release.objects([entry])
        self.assertEqual(objs, {"path/index.html": {"object": "CI-0101", "variant": "website.ru", "version": 3}})
        from tools.cs.site import deploy as d

        def down(req, timeout=None):
            raise urllib.error.URLError("нет сети")

        with mock.patch.dict(os.environ, {**AUTHOR, mkey.KEY_ENV: str(self.keyfile)}), \
                mock.patch("tools.cs.mark.stamp.shutil.which", return_value=None):
            res = d.run(repo, out, runner=FakeRsync(), post=down, objects=objs)
        man = json.loads(res.release.read_text(encoding="utf-8"))
        rec = next(f for f in man["files"] if f["path"] == "path/index.html")
        self.assertEqual((rec["object"], rec["variant"], rec["version"]), ("CI-0101", "website.ru", 3))
        self.assertNotIn("object", next(f for f in man["files"] if f["path"] == "style.css"))
        self.assertTrue(deploy)

    def test_marks_once_writes_manifest_and_survives_no_network(self):
        repo, out = marked_repo(), tree()
        before = (out / "path" / "index.html").read_bytes()
        dry = self.deploy(repo, out, self.keyfile, dry_run=True)
        self.assertTrue(dry.ok)
        self.assertIsNone(dry.release, "пробный прогон — без манифеста")
        marked = (out / "path" / "index.html").read_bytes()
        self.assertNotEqual(marked, before)
        res = self.deploy(repo, out, self.keyfile)
        self.assertTrue(res.ok, res.problems)
        self.assertEqual((out / "path" / "index.html").read_bytes(), marked, "метка не ставится второй раз")
        self.assertEqual(res.marked, 2)
        man = json.loads(res.release.read_text(encoding="utf-8"))
        self.assertEqual(man["schema"], "site-release/v1")
        self.assertTrue(str(res.release).startswith(str(repo.path("data/provenance/site-releases"))))
        self.assertEqual([(s.kind, s.state) for s in res.stamps], [("rfc3161", "pending"), ("ots", "limited")])
        self.assertFalse(list(out.rglob("*.mark.json")), "служебные записи метки на хостинг не уходят")

        # mark verify: точная копия, метка в странице без исходника, чужой файл
        v = verify.check(repo.root, marked, "index.html", KEY)
        self.assertTrue(v.found)
        self.assertIn(man["id"], v.releases)
        self.assertTrue(any("точная копия" in ln for ln in v.lines))
        copy = marked.replace(b"<body>", b"<body><p>\xd1\x87\xd1\x83\xd0\xb6\xd0\xb0\xd1\x8f</p>")
        v = verify.check(repo.root, copy, "copy.html", KEY)
        self.assertTrue(v.found and v.p < 1e-9, v.lines)
        v = verify.check(repo.root, b"<!doctype html><html><body><p>x</p></body></html>", "other.html", KEY)
        self.assertFalse(v.found)
        self.assertTrue(any("не найдено" in ln for ln in v.lines))


class Cli(unittest.TestCase):
    def test_mark_init_and_verify(self):
        from contextlib import redirect_stdout
        from tools.cs.__main__ import main
        kp = Path(tempfile.mkdtemp(prefix="cs-key-")) / "mark.key"
        buf = io.StringIO()
        with mock.patch.dict(os.environ, {mkey.KEY_ENV: str(kp)}), redirect_stdout(buf):
            self.assertEqual(main(["mark", "init"]), 0)
            self.assertEqual(main(["mark", "init"]), 1, "второй раз ключ не создаётся")
        self.assertIn("отпечаток", buf.getvalue())
        self.assertNotIn(kp.read_text().strip(), buf.getvalue(), "ключ не печатается")
        f = Path(tempfile.mkdtemp()) / "x.svg"
        f.write_text(figure(), encoding="utf-8")
        buf = io.StringIO()
        with mock.patch.dict(os.environ, {mkey.KEY_ENV: str(kp)}), redirect_stdout(buf):
            self.assertEqual(main(["mark", "verify", str(f)]), 1)
        self.assertIn("не найдено", buf.getvalue())


if __name__ == "__main__":
    unittest.main()
