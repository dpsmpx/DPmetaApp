"""Структурные ошибки публикации (Д24, PLATFORM_VISION §18).

Любой отказ площадки адаптер приводит к PublishError с кодом; движок
решает по коду, повторять ли, и пишет ошибку в журнал без сырого ответа
площадки (там могут быть токены и личные данные).
"""
from __future__ import annotations

# код → (повторяемая ли по умолчанию, что значит)
CODES: dict[str, tuple[bool, str]] = {
    "AUTH_EXPIRED": (False, "учётные данные недействительны или истекли — обновить (docs/operations/CREDENTIALS.md)"),
    "MISSING_CREDENTIALS": (False, "нет учётных данных адресата в окружении"),
    "PERMISSION_DENIED": (False, "у аккаунта нет права на действие"),
    "INVALID_DESTINATION": (False, "адресат не найден или не совпадает с записанными id"),
    "RATE_LIMITED": (True, "превышен лимит площадки — ждать retry_after"),
    "MEDIA_REJECTED": (False, "площадка отклонила медиа"),
    "INVALID_CONTENT": (False, "площадка отклонила текст"),
    "NOT_FOUND": (False, "запись на площадке не найдена"),
    "PLATFORM_UNAVAILABLE": (True, "площадка недоступна; запрос не дошёл или отклонён до обработки"),
    "OUTCOME_UNKNOWN": (False, "исход неизвестен (обрыв после отправки) — повтор только после проверки"),
    "MANUAL_REQUIRED": (False, "площадка без автоматической публикации — ручной путь (Д33)"),
    "UNSUPPORTED": (False, "операция не поддерживается площадкой"),
    "UNKNOWN_PLATFORM_ERROR": (False, "неизвестная ошибка площадки"),
}


class PublishError(Exception):
    def __init__(self, code: str, message: str = "", *, retryable: bool | None = None,
                 retry_after: float | None = None, meta: dict | None = None):
        if code not in CODES:
            code, message = "UNKNOWN_PLATFORM_ERROR", f"{code}: {message}"
        self.code = code
        self.message = message or CODES[code][1]
        self.retryable = CODES[code][0] if retryable is None else retryable
        self.retry_after = retry_after
        self.meta = meta or {}
        super().__init__(f"{code}: {self.message}")

    def as_record(self) -> dict:
        out = {"code": self.code, "message": self.message[:300], "retryable": self.retryable}
        if self.retry_after is not None:
            out["retry_after"] = self.retry_after
        return out
