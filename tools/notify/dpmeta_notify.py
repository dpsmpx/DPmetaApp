#!/usr/bin/env python3
"""Хук Claude Code для мгновенного канала DPmetaApp (D-23, D-152; ТЗ, F-56).

Читает событие хука со stdin (Stop, Notification, StopFailure, SessionEnd),
собирает событие канала из минимума полей — тип, ключ проекта, ветка, время,
id сессии, причина, id события — подписывает HMAC-SHA256 общим ключом и шлёт
в тему ntfy. Текст ответа Claude и сообщения не уходят никуда.

Окружение:
  DPMETA_PUSH_URL  адрес темы: https://ntfy.sh/<тема> (или свой сервер ntfy);
  DPMETA_PUSH_KEY  общий ключ из приложения («Мгновенный канал» → «Ключ для хуков»);
  DPMETA_PROJECT   ключ проекта (по умолчанию — имя корня git).

Хук никогда не мешает Claude: ошибки сети и настройки — молча, код выхода 0.
Только стандартная библиотека Python 3.8+.
"""
import hashlib
import hmac
import json
import os
import re
import secrets
import subprocess
import sys
import time
import urllib.request

VERSION_TAG = "dpmeta-push/1"


def clean(value, pattern, limit):
    """Оставить только допустимые знаки и обрезать: поля события — не текст."""
    return "".join(ch for ch in str(value or "") if re.fullmatch(pattern, ch))[:limit]


def kind(hook):
    """Тип и причина события канала по событию хука; не для канала — None."""
    name = hook.get("hook_event_name")
    if name == "Stop":
        return "claude.turn.done", "stop"
    if name == "Notification":
        t = str(hook.get("notification_type") or "")
        msg = str(hook.get("message") or "").lower()
        if t == "permission_prompt" or "permission" in msg:
            return "claude.needs_input", "permission"
        if t == "idle_prompt" or "waiting for your input" in msg:
            return "claude.needs_input", "idle"
        return None
    if name == "StopFailure":
        return "claude.turn.failed", clean(hook.get("reason") or "failure", r"[a-z_]", 40)
    if name == "SessionEnd":
        return "claude.session.ended", clean(hook.get("reason"), r"[a-z_]", 40)
    return None


def canonical(e):
    """Подписываемая строка — та же, что у приложения (PushEvent.canonical)."""
    return "\n".join([VERSION_TAG, e["type"], e["project"], e["branch"], str(e["at"]), e["session"], e["reason"], e["id"]])


def sign(e, key):
    return hmac.new(key.encode("utf-8"), canonical(e).encode("utf-8"), hashlib.sha256).hexdigest()


def build(hook, project, branch, now, key, event_id):
    """Событие канала с подписью; не для канала — None."""
    k = kind(hook)
    if k is None:
        return None
    e = {
        "v": 1,
        "type": k[0],
        "project": clean(project, r"[A-Za-z0-9._-]", 100),
        "branch": str(branch or "").replace("\n", " ")[:200],
        "at": int(now),
        "session": clean(hook.get("session_id"), r"[A-Za-z0-9_-]", 100),
        "reason": k[1],
        "id": event_id,
    }
    e["sig"] = sign(e, key)
    return e


def git(args, cwd):
    try:
        return subprocess.run(["git"] + args, cwd=cwd, capture_output=True, text=True, timeout=5).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return ""


def main():
    url = os.environ.get("DPMETA_PUSH_URL", "")
    key = os.environ.get("DPMETA_PUSH_KEY", "")
    if not url.startswith("https://") or not key:
        return 0
    try:
        hook = json.load(sys.stdin)
    except ValueError:
        return 0
    cwd = hook.get("cwd") or os.getcwd()
    project = os.environ.get("DPMETA_PROJECT") or os.path.basename(git(["rev-parse", "--show-toplevel"], cwd))
    branch = git(["rev-parse", "--abbrev-ref", "HEAD"], cwd)
    e = build(hook, project, branch, time.time(), key, secrets.token_hex(16))
    if e is None:
        return 0
    req = urllib.request.Request(url, data=json.dumps(e).encode("utf-8"), method="POST", headers={"Content-Type": "text/plain"})
    try:
        urllib.request.urlopen(req, timeout=10).close()
    except Exception:  # noqa: BLE001 — хук не мешает Claude ни при каком сбое сети
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
