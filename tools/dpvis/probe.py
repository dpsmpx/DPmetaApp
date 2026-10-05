"""HTTP-пробы сервисов — только к адресам из списка модели (D-03, D-14, F-17).

Только GET без учётных данных; переадресация на адрес вне списка не
выполняется. Тело ответа читается не дальше PROBE_MAX_BYTES и не
сохраняется.
"""

from __future__ import annotations

import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from urllib.parse import urlsplit

from . import VERSION, rules


class HostNotAllowed(Exception):
    pass


@dataclass
class ProbeResult:
    url: str
    status: int | None = None
    ms: int | None = None
    headers: dict[str, bool] = field(default_factory=dict)
    error: str | None = None
    code: str | None = None          # код диагностики, если проба неудачна

    def as_dict(self) -> dict:
        return {"url": self.url, "status": self.status, "ms": self.ms,
                "headers": dict(sorted(self.headers.items())), "error": self.error}


def _allowed(url: str, hosts: list[str]) -> bool:
    parts = urlsplit(url)
    return parts.scheme in ("http", "https") and parts.hostname in hosts


class _Redirects(urllib.request.HTTPRedirectHandler):
    def __init__(self, hosts: list[str]):
        self.hosts = hosts

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if not _allowed(newurl, self.hosts):
            raise HostNotAllowed(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def probe(url: str, expect: int, headers: list[str], hosts: list[str],
          opener_factory=None) -> ProbeResult:
    """Одна проба; ожидаемые отказы — в результате, а не исключением."""
    result = ProbeResult(url=url)
    if not _allowed(url, hosts):
        result.error = "адрес не в [allow] hosts"
        result.code = "HOST_NOT_ALLOWED"
        return result
    opener = (opener_factory or urllib.request.build_opener)(_Redirects(hosts))
    request = urllib.request.Request(url, method="GET",
                                     headers={"User-Agent": f"DPvisualisation/{VERSION}"})
    started = time.monotonic()
    try:
        with opener.open(request, timeout=rules.PROBE_TIMEOUT_S) as resp:
            resp.read(rules.PROBE_MAX_BYTES)
            result.status = resp.status
            got = {k.lower() for k in resp.headers.keys()}
    except HostNotAllowed as exc:
        result.error = f"переадресация на адрес вне списка: {exc}"
        result.code = "HOST_NOT_ALLOWED"
        return result
    except urllib.error.HTTPError as exc:
        result.status = exc.code
        got = {k.lower() for k in exc.headers.keys()} if exc.headers else set()
    except (urllib.error.URLError, OSError, ValueError) as exc:
        result.error = str(getattr(exc, "reason", exc))[:200]
        result.code = "PROBE_FAILED"
        return result
    result.ms = int(round((time.monotonic() - started) * 1000))
    result.headers = {h: h.lower() in got for h in headers}
    if result.status != expect:
        result.code = "PROBE_STATUS_UNEXPECTED"
        result.error = f"код {result.status}, ожидался {expect}"
    elif not all(result.headers.values()):
        result.code = "PROBE_HEADER_MISSING"
        missing = [h for h, ok in result.headers.items() if not ok]
        result.error = "нет заголовков: " + ", ".join(missing)
    return result
