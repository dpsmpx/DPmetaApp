"""Живой режим (D-12, F-43…F-46): локальный сервер и пересборка среза по расписанию.

`python3 -m tools.dpvis serve --root <клоны> [--every 15] [--fetch] [--online]`

- Сервер слушает `127.0.0.1` (другой адрес — только явным `--host` и с
  предупреждением в журнале, F-46) и отдаёт только файлы `viewer/` и
  `data/`: без записи, без списка каталогов, только GET и HEAD (T-03).
  `/` ведёт на `/viewer/`; просмотрщик оттуда читает `../data/` и опрашивает
  срез (F-45).
- Фоновый цикл раз в N минут собирает срез (`collect`) во временный файл и
  заменяет прежний одной операцией; неудача сбора пишется в журнал, а
  прежний срез остаётся (F-44).
- Своих облачных серверов и push-каналов нет: это процесс на устройстве
  автора (компьютер или Termux), `git fetch` — его учётными данными.
"""

from __future__ import annotations

import ipaddress
import sys
import threading
import time
from datetime import datetime, timezone
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlsplit

from . import collect, model as modelmod

REPO = Path(__file__).resolve().parents[2]
SERVED = ("viewer", "data")
STATE_URL = "/data/state/latest.json"
TYPES = {
    ".html": "text/html; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".json": "application/json; charset=utf-8",
    ".svg": "image/svg+xml",
    ".png": "image/png",
}
HEADERS = {
    "X-Content-Type-Options": "nosniff",
    "Referrer-Policy": "no-referrer",
    "Content-Security-Policy": "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; "
                               "connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'none'; "
                               "frame-ancestors 'self'",
}


def log(text: str, stream=None) -> None:
    stamp = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%SZ")
    print(f"[{stamp}] {text}", file=stream or sys.stderr, flush=True)


def host_warning(host: str) -> str | None:
    """Предупреждение, если сервер слышен не только с этого устройства (F-46)."""
    try:
        if host == "localhost" or ipaddress.ip_address(host).is_loopback:
            return None
    except ValueError:
        pass
    return (f"! сервер слушает {host}: просмотрщик и срез с устройством закрытой экосистемы "
            "увидят и другие устройства сети (D-08) — нужен ли этот адрес?")


# ----------------------------------------------------------------- сбор

class Rebuilder:
    """Собирает срез и заменяет файл одной операцией; прежний срез не портится."""

    def __init__(self, load_model, root: Path | None, out: Path, *, days: int, online: bool,
                 fetch: bool, overrides: dict | None = None, now=None, stream=None):
        self.load_model = load_model        # () -> собранная модель dpvis-model/1
        self.root = root
        self.out = out
        self.days = days
        self.online = online
        self.fetch = fetch
        self.overrides = overrides or {}
        self.now = now or (lambda: datetime.now(timezone.utc).replace(microsecond=0))
        self.stream = stream
        self.last_ok: str | None = None
        self.last_error: str | None = None

    def once(self) -> bool:
        started = time.monotonic()
        try:
            model = self.load_model()
            state = collect.collect(model, self.root, self.now(), days=self.days, online=self.online,
                                    fetch=self.fetch, overrides=self.overrides)
            self.out.parent.mkdir(parents=True, exist_ok=True)
            tmp = self.out.with_name(self.out.name + ".tmp")
            tmp.write_text(modelmod.dumps(state), encoding="utf-8")
            tmp.replace(self.out)
        except Exception as exc:  # журнал вместо падения: прежний срез остаётся (F-44)
            self.last_error = f"{type(exc).__name__}: {exc}"
            log(f"✗ срез не собран, остаётся прежний: {self.last_error}", self.stream)
            return False
        self.last_ok = state["as_of"]
        self.last_error = None
        codes: dict[str, int] = {}
        for d in state["diagnostics"]:
            codes[d["code"]] = codes.get(d["code"], 0) + 1
        diag = ", ".join(f"{k} {v}" for k, v in sorted(codes.items())) or "нет"
        log(f"✓ срез на {state['as_of']} за {time.monotonic() - started:.1f} с; диагностика: {diag}", self.stream)
        return True


def loop(rebuilder: Rebuilder, every_min: float, stop: threading.Event) -> None:
    """Сбор сразу и затем раз в every_min минут; 0 — только при старте."""
    while not stop.is_set():
        rebuilder.once()
        if every_min <= 0:
            return
        stop.wait(every_min * 60)


# ---------------------------------------------------------------- сервер

def resolve(base: Path, url_path: str, state_path: Path) -> Path | None:
    """Файл для адреса или None (404): только `viewer/` и `data/`, без выхода наверх."""
    path = unquote(urlsplit(url_path).path)
    if path == STATE_URL:
        return state_path if state_path.is_file() else None
    parts = [p for p in path.split("/") if p]
    if not parts or parts[0] not in SERVED or any(p in ("..", ".") or p.startswith(".") for p in parts):
        return None
    target = base.joinpath(*parts)
    try:
        target.resolve().relative_to((base / parts[0]).resolve())
    except ValueError:
        return None
    if target.is_dir():
        target = target / "index.html"   # списка каталогов нет: только его index.html
    return target if target.is_file() and target.suffix in TYPES else None


def make_handler(base: Path, state_path: Path, stream=None):
    class Handler(BaseHTTPRequestHandler):
        server_version = "DPvisualisation"
        sys_version = ""

        def _send(self, code: int, body: bytes = b"", ctype: str | None = None, extra: dict | None = None):
            self.send_response(code)
            for k, v in {**HEADERS, **(extra or {})}.items():
                self.send_header(k, v)
            if ctype:
                self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(body)

        def do_GET(self):
            path = urlsplit(self.path).path
            if path in ("/", ""):
                return self._send(HTTPStatus.FOUND, extra={"Location": "/viewer/"})
            if path == "/favicon.ico":   # значка нет — пустой ответ вместо 404 в журнале браузера
                return self._send(HTTPStatus.NO_CONTENT)
            if path == "/viewer":
                return self._send(HTTPStatus.MOVED_PERMANENTLY, extra={"Location": "/viewer/"})
            target = resolve(base, self.path, state_path)
            if target is None:
                return self._send(HTTPStatus.NOT_FOUND, "не найдено\n".encode(), "text/plain; charset=utf-8")
            body = target.read_bytes()
            cache = {"Cache-Control": "no-store"} if path.startswith("/data/") else {"Cache-Control": "no-cache"}
            self._send(HTTPStatus.OK, body, TYPES[target.suffix], cache)

        def do_HEAD(self):
            self.do_GET()

        def _not_allowed(self):
            self._send(HTTPStatus.METHOD_NOT_ALLOWED, b"", extra={"Allow": "GET, HEAD"})

        do_POST = do_PUT = do_DELETE = do_PATCH = do_OPTIONS = _not_allowed

        def log_message(self, fmt, *args):  # запросы — без адресов клиентов и без шума
            if args and str(args[1]).startswith(("4", "5")):
                log(f"{self.command} {self.path} → {args[1]}", stream)

    return Handler


def make_server(host: str, port: int, base: Path = REPO, state_path: Path | None = None,
                stream=None) -> ThreadingHTTPServer:
    warn = host_warning(host)
    if warn:
        log(warn, stream)
    server = ThreadingHTTPServer((host, port), make_handler(base, state_path or base / "data/state/latest.json", stream))
    server.daemon_threads = True
    return server


def run(args) -> int:
    """Команда `serve` (__main__.py)."""
    root = args.root.expanduser().resolve() if args.root else None
    if not args.no_collect and (root is None or not root.is_dir()) and not args.clone:
        print("✗ нужен --root с клонами (или --no-collect — только отдавать файлы)", file=sys.stderr)
        return 2
    out = Path(args.state_out).resolve()

    def load_model():
        src, raw = modelmod.load_source(args.model_source)
        return modelmod.compile_model(src, raw)

    overrides = {}
    for item in args.clone:
        key, _, path = item.partition("=")
        overrides[key] = Path(path).expanduser().resolve()
    stop = threading.Event()
    worker = None
    if not args.no_collect:
        rb = Rebuilder(load_model, root, out, days=args.days, online=args.online, fetch=args.fetch,
                       overrides=overrides)
        worker = threading.Thread(target=loop, args=(rb, args.every, stop), daemon=True)
    try:
        server = make_server(args.host, args.port, REPO, out)
    except OSError as exc:
        print(f"✗ сервер не запустился на {args.host}:{args.port}: {exc}", file=sys.stderr)
        return 3
    if worker:
        worker.start()
    host, port = server.server_address[:2]
    every = "только при старте" if args.every <= 0 else f"раз в {args.every:g} мин"
    log(f"живой режим: http://{host}:{port}/viewer/ — срез {'не пересобирается' if args.no_collect else every}; Ctrl+C — стоп")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        stop.set()
        server.server_close()
    return 0
