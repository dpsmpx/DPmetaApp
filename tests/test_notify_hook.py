"""Хук мгновенного канала (D-152; F-56): события, поля и подпись — как у приложения."""
import io
import json
import os
import sys
import unittest
from unittest import mock

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "tools", "notify"))
import dpmeta_notify as n  # noqa: E402

KEY = "test-key-0123456789abcdef"
EVENT_ID = "0123456789abcdef0123456789abcdef"
# Тот же вектор — в PushEventTest (Kotlin): подпись скрипта проверяет приложение.
VECTOR = "20489adb752e711c3775ad3cda62bae21ed9c8857ee4a8031cc057fc56944e87"


class NotifyHookTest(unittest.TestCase):
    def build(self, hook, project="DPlang", branch="main"):
        return n.build(hook, project, branch, 1763200000, KEY, EVENT_ID)

    def test_stop_is_signed_like_the_app(self):
        e = self.build({"hook_event_name": "Stop", "session_id": "session_01ABCdef234", "transcript_path": "/x"})
        self.assertEqual(VECTOR, e["sig"])
        self.assertEqual({"v", "type", "project", "branch", "at", "session", "reason", "id", "sig"}, set(e))
        self.assertEqual("claude.turn.done", e["type"])

    def test_notification_kinds_and_skips(self):
        self.assertEqual(("claude.needs_input", "permission"), n.kind({"hook_event_name": "Notification", "notification_type": "permission_prompt"}))
        self.assertEqual(("claude.needs_input", "idle"), n.kind({"hook_event_name": "Notification", "message": "Claude is waiting for your input"}))
        self.assertIsNone(n.kind({"hook_event_name": "Notification", "message": "auth success"}))
        self.assertIsNone(n.kind({"hook_event_name": "PreToolUse"}))
        self.assertEqual("claude.turn.failed", n.kind({"hook_event_name": "StopFailure", "reason": "rate_limit"})[0])

    def test_fields_are_cleaned_not_text(self):
        e = self.build({"hook_event_name": "Stop", "session_id": "s; rm -rf /\n", "last_assistant_message": "секрет"},
                       project="my proj/../x", branch="feat\nx")
        self.assertEqual("myproj..x", e["project"])
        self.assertEqual("srm-rf", e["session"])
        self.assertNotIn("\n", e["branch"])
        self.assertNotIn("секрет", json.dumps(e, ensure_ascii=False), "текст ответа в событие не попадает")

    def test_never_breaks_claude(self):
        with mock.patch.dict(os.environ, {"DPMETA_PUSH_URL": "", "DPMETA_PUSH_KEY": ""}, clear=False):
            self.assertEqual(0, n.main())
        with mock.patch.dict(os.environ, {"DPMETA_PUSH_URL": "https://ntfy.invalid/t", "DPMETA_PUSH_KEY": KEY}), \
                mock.patch("sys.stdin", io.StringIO("не json")):
            self.assertEqual(0, n.main())
        with mock.patch.dict(os.environ, {"DPMETA_PUSH_URL": "https://ntfy.invalid/t", "DPMETA_PUSH_KEY": KEY}), \
                mock.patch("sys.stdin", io.StringIO(json.dumps({"hook_event_name": "Stop", "session_id": "s", "cwd": "/"}))), \
                mock.patch("urllib.request.urlopen", side_effect=OSError("нет сети")):
            self.assertEqual(0, n.main())


if __name__ == "__main__":
    unittest.main()
