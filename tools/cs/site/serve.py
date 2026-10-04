"""Локальный сервер staging и предпросмотра (только 127.0.0.1).

Сайт ссылается на свои файлы от корня (`/style.css`, `/fonts/…`), поэтому
открывается только через сервер, корень которого — корень сборки; из
`file://` страницы без стилей. Отсутствующий адрес отдаёт `/404.html` с
кодом 404 — как хостинг статики (Cloudflare Pages, Netlify).
"""
from __future__ import annotations

import http.server
import socketserver
from functools import partial
from pathlib import Path


class _Handler(http.server.SimpleHTTPRequestHandler):
    def send_error(self, code, message=None, explain=None):
        page = Path(self.directory) / "404.html"
        if code == 404 and page.is_file():
            body = page.read_bytes()
            self.send_response(404)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(body)
            return
        super().send_error(code, message, explain)

    def log_request(self, code="-", size="-"):  # тише: только ошибки
        if str(getattr(code, "value", code))[:1] in ("4", "5"):
            super().log_request(code, size)


class _Server(socketserver.TCPServer):
    allow_reuse_address = True


def server(root: Path, port: int = 8000) -> socketserver.TCPServer:
    """Сервер на 127.0.0.1:port (0 — свободный порт); корень — root."""
    return _Server(("127.0.0.1", port), partial(_Handler, directory=str(root)))


def serve(root: Path, port: int, label: str, path: str = "/") -> None:
    with server(root, port) as srv:
        print(f"{label}: http://localhost:{srv.server_address[1]}{path} (Ctrl+C — стоп)")
        try:
            srv.serve_forever()
        except KeyboardInterrupt:
            pass
