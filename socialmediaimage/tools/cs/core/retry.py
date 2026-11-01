"""Повторы и ограничение частоты — у каждого адаптера свои (PLATFORM_VISION §17, §57).

Повторы ограничены, с экспоненциальной задержкой и дрожанием; задержку
площадки (retry_after) уважают. Часы и сон подставляются — тесты не
ждут по-настоящему.
"""
from __future__ import annotations

import random
import time
from dataclasses import dataclass, field
from typing import Callable


@dataclass
class RetryPolicy:
    max_attempts: int = 3          # попыток за один запуск
    max_total_attempts: int = 5    # попыток на ключ за всё время (журнал)
    base_delay_s: float = 2.0
    multiplier: float = 2.0
    max_delay_s: float = 300.0
    jitter: float = 0.2            # доля случайного разброса
    rng: random.Random = field(default_factory=lambda: random.Random(0), repr=False)

    @classmethod
    def from_policy(cls, d: dict | None) -> "RetryPolicy":
        d = d or {}
        keys = ("max_attempts", "max_total_attempts", "base_delay_s", "multiplier", "max_delay_s", "jitter")
        return cls(**{k: d[k] for k in keys if k in d})

    def delay(self, attempt: int, retry_after: float | None = None) -> float:
        """Задержка перед попыткой attempt+1 (attempt — номер неудачной, с 1)."""
        d = min(self.max_delay_s, self.base_delay_s * self.multiplier ** (attempt - 1))
        d *= 1 + self.jitter * (self.rng.random() * 2 - 1)
        if retry_after is not None:
            d = max(d, float(retry_after))
        return round(max(0.0, d), 3)


class RateLimiter:
    """Не чаще одного запроса за min_interval секунд (у Telegram — ~1 в секунду в чат)."""

    def __init__(self, min_interval: float, clock: Callable[[], float] = time.monotonic,
                 sleep: Callable[[float], None] = time.sleep):
        self.min_interval, self.clock, self.sleep = min_interval, clock, sleep
        self._last: float | None = None

    def wait(self) -> float:
        now = self.clock()
        waited = 0.0
        if self._last is not None:
            gap = self.min_interval - (now - self._last)
            if gap > 0:
                self.sleep(gap)
                waited = gap
        self._last = self.clock()
        return waited
