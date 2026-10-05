"""Пробы: только адреса из списка, без запроса к чужим (F-17, D-03, T-04)."""

import email.message
import unittest
import urllib.error

from tests.fixture import base_source, model_from, utc
from tools.dpvis import collect, probe

HOSTS = ["site.example"]


class FakeResponse:
    def __init__(self, status, headers):
        self.status = status
        self.headers = email.message.Message()
        for k, v in headers.items():
            self.headers[k] = v

    def read(self, n=-1):
        return b"ok"

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


def opener_returning(status=200, headers=None, error=None, calls=None):
    class Opener:
        def __init__(self, *handlers):
            pass

        def open(self, request, timeout=None):
            if calls is not None:
                calls.append(request.full_url)
            if error is not None:
                raise error
            return FakeResponse(status, headers or {})

    return Opener


class ProbeTest(unittest.TestCase):
    def test_host_not_allowed_makes_no_request(self):
        calls = []
        res = probe.probe("http://evil.example/", 200, [], HOSTS, opener_returning(calls=calls))
        self.assertEqual(res.code, "HOST_NOT_ALLOWED")
        self.assertEqual(calls, [])

    def test_ok_with_headers(self):
        res = probe.probe("http://site.example/", 200, ["X-Frame-Options"], HOSTS,
                          opener_returning(200, {"X-Frame-Options": "DENY"}))
        self.assertIsNone(res.code)
        self.assertEqual(res.status, 200)
        self.assertEqual(res.headers, {"X-Frame-Options": True})
        self.assertIsInstance(res.ms, int)

    def test_header_missing(self):
        res = probe.probe("http://site.example/", 200, ["X-Frame-Options"], HOSTS, opener_returning(200, {}))
        self.assertEqual(res.code, "PROBE_HEADER_MISSING")

    def test_unexpected_status(self):
        err = urllib.error.HTTPError("http://site.example/", 503, "nope", email.message.Message(), None)
        res = probe.probe("http://site.example/", 200, [], HOSTS, opener_returning(error=err))
        self.assertEqual((res.code, res.status), ("PROBE_STATUS_UNEXPECTED", 503))

    def test_network_failure(self):
        res = probe.probe("http://site.example/", 200, [], HOSTS,
                          opener_returning(error=urllib.error.URLError("нет сети")))
        self.assertEqual(res.code, "PROBE_FAILED")
        self.assertIsNone(res.status)

    def test_redirect_to_foreign_host_refused(self):
        handler = probe._Redirects(HOSTS)
        with self.assertRaises(probe.HostNotAllowed):
            handler.redirect_request(None, None, 302, "Found", {}, "http://evil.example/x")

    def test_collect_online_marks_site_up_and_flow_flowing(self):
        model = model_from(base_source())
        state = collect.collect(model, None, utc("2026-10-05T12:00:00"), online=True,
                                opener_factory=opener_returning(200, {"X-Frame-Options": "DENY"}))
        self.assertEqual(state["nodes"]["site"]["activity"]["state"], "up")
        self.assertEqual(state["flows"]["probe.site"]["state"], "flowing")
        self.assertEqual(state["nodes"]["site"]["probe"]["results"][0]["status"], 200)

    def test_collect_online_site_down(self):
        model = model_from(base_source())
        state = collect.collect(model, None, utc("2026-10-05T12:00:00"), online=True,
                                opener_factory=opener_returning(error=urllib.error.URLError("x")))
        self.assertEqual(state["nodes"]["site"]["activity"]["state"], "down")
        self.assertEqual(state["flows"]["probe.site"]["state"], "down")
        self.assertIn("PROBE_FAILED", {d["code"] for d in state["diagnostics"]})


if __name__ == "__main__":
    unittest.main()
