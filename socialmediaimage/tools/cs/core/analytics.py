"""Нормализованная аналитика (Д29, PLATFORM_VISION §58–61).

Метрики площадок (импорт выгрузок по Д15 — data/metrics/, или будущие
адаптеры статистики) приводятся к событиям одного словаря, привязанным к
кампании, объекту, варианту, площадке и публикации. Метрика отвечает
«что произошло»; вывод «почему» — всегда INTERPRETATION и не меняет
стратегию сам (§59, §62).
"""
from __future__ import annotations

from dataclasses import asdict, dataclass

KINDS = ["Impression", "View", "Click", "Reaction", "Comment", "Share", "Subscriber", "Conversion", "Retention"]

# поле нормализованной выгрузки (schemas/metrics.schema.json, values) → вид события
FIELD_KIND = {
    "impressions": "Impression", "views": "View", "clicks": "Click", "reactions": "Reaction",
    "comments": "Comment", "shares": "Share", "subscribers_gained": "Subscriber",
    "retention_30s": "Retention", "ctr": None, "watch_time_hours": None, "avg_view_duration_s": None,
}


@dataclass(frozen=True)
class AnalyticsEvent:
    kind: str
    value: float
    platform: str
    item: str
    variant: str | None = None
    campaign: str | None = None
    publication_id: str | None = None
    period_from: str | None = None
    period_to: str | None = None
    source: str = "import"      # import | adapter | mock

    def as_dict(self) -> dict:
        return asdict(self)


def from_metrics(m: dict) -> list[AnalyticsEvent]:
    """Запись data/metrics/… → события. Поля без вида (CTR, время) остаются в исходной записи."""
    out = []
    per = m.get("period") or {}
    for k, v in (m.get("values") or {}).items():
        kind = FIELD_KIND.get(k)
        if kind and v is not None:
            out.append(AnalyticsEvent(kind, float(v), m.get("platform", ""), m.get("item", ""), m.get("variant"),
                                      m.get("campaign"), m.get("publication_id"), str(per.get("from") or ""),
                                      str(per.get("to") or "")))
    return out


def totals(events: list[AnalyticsEvent], by: str = "platform") -> dict:
    out: dict = {}
    for e in events:
        key = getattr(e, by)
        out.setdefault(key, {}).setdefault(e.kind, 0.0)
        out[key][e.kind] += e.value
    return out


def interpretation(text: str) -> dict:
    """Вывод из метрик — только как INTERPRETATION (Д29): в реестр фактов он не идёт без проверки."""
    return {"class": "INTERPRETATION", "text": text, "requires_author_decision": True}
