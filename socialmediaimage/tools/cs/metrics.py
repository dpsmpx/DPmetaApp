"""Метрики и эксперименты (Д14, Д15; docs/content/EXPERIMENTS_AND_ANALYTICS.md).

Импорт — выгрузка CSV из аналитики площадки, без скрейпинга и без токенов.
Колонка, которой нет в таблице соответствий, не угадывается: она
сохраняется в `unmapped` и перечисляется в выводе.
"""
from __future__ import annotations

import csv
import io
import re
from pathlib import Path

from .common import ROOT, Report, as_date, dump_yaml, load_items, load_yaml, rel, sha256_file, today

# Заголовок колонки (без регистра) → поле values. Названия — из выгрузок YouTube Studio (EN/RU)
# и статистики Telegram/VK; дополняется по мере появления настоящих выгрузок.
COLUMNS = {
    "impressions": "impressions", "показы": "impressions",
    "impressions click-through rate (%)": "ctr", "ctr": "ctr",
    "ctr для значков видео (%)": "ctr", "ctr для значков (%)": "ctr",
    "views": "views", "просмотры": "views",
    "watch time (hours)": "watch_time_hours", "время просмотра (часы)": "watch_time_hours",
    "average view duration": "avg_view_duration_s", "средняя продолжительность просмотра": "avg_view_duration_s",
    "subscribers": "subscribers_gained", "подписчики": "subscribers_gained",
    "likes": "reactions", "отметки «нравится»": "reactions", "реакции": "reactions", "reactions": "reactions",
    "comments added": "comments", "comments": "comments", "комментарии": "comments",
    "shares": "shares", "поделились": "shares", "репосты": "shares", "пересылки": "shares",
    "clicks": "clicks", "клики": "clicks", "переходы": "clicks",
}
TOTAL_ROW = re.compile(r"(?i)^(total|итого|всего)$")


def parse_value(raw: str) -> float | None:
    s = raw.strip().replace(" ", " ").rstrip("%").strip()
    if not s:
        return None
    if re.fullmatch(r"\d+:\d{2}(:\d{2})?", s):  # длительность 0:01:23 → секунды
        parts = [int(x) for x in s.split(":")]
        sec = 0
        for p in parts:
            sec = sec * 60 + p
        return float(sec)
    s = s.replace(" ", "")
    if re.fullmatch(r"-?\d{1,3}(,\d{3})+(\.\d+)?", s):  # 1,234 — разряды (как в claims.numbers)
        s = s.replace(",", "")
    else:  # 12,5 — десятичная запятая
        s = s.replace(",", ".")
    try:
        return float(s)
    except ValueError:
        return None


def normalize(text: str) -> tuple[dict, dict]:
    """(values, unmapped) из CSV: строка «Итого», иначе единственная строка данных."""
    rows = list(csv.reader(io.StringIO(text)))
    rows = [r for r in rows if any(c.strip() for c in r)]
    if len(rows) < 2:
        raise ValueError("в выгрузке нет строк данных")
    head, data = rows[0], rows[1:]
    total = [r for r in data if r and TOTAL_ROW.match(r[0].strip())]
    if total:
        row = total[0]
    elif len(data) == 1:
        row = data[0]
    else:
        raise ValueError(f"строк данных {len(data)} и нет строки «Итого» — выгрузите один объект или итог")
    values, unmapped = {}, {}
    for h, raw in zip(head, row):
        key = COLUMNS.get(h.strip().lower())
        val = parse_value(raw)
        if key and val is not None:
            values[key] = val
        elif h.strip() and raw.strip() and not TOTAL_ROW.match(raw.strip()):
            unmapped[h.strip()] = raw.strip()
    return values, unmapped


def import_csv(item_id: str, platform: str, file: Path, period_from: str, period_to: str,
               variant: str | None = None, url: str | None = None) -> Path:
    items = {i.id: i for i in load_items()}
    if item_id not in items:
        raise SystemExit(f"объект {item_id} не найден")
    values, unmapped = normalize(file.read_text(encoding="utf-8-sig"))
    out = ROOT / "data" / "metrics" / item_id / f"{platform}-{today().isoformat()}.yaml"
    doc = {
        "schema": "metrics/v1",
        "item": item_id,
        "variant": variant,
        "platform": platform,
        "publication_url": url,
        "period": {"from": period_from, "to": period_to},
        "source_file": file.name,  # только имя: локальный путь — личное
        "source_file_sha256": sha256_file(file),
        "values": values,
    }
    if unmapped:
        doc["unmapped"] = unmapped
    dump_yaml(doc, out)
    print(f"записано {rel(out)}: {', '.join(f'{k}={v:g}' for k, v in values.items()) or 'нет значений'}")
    if unmapped:
        print(f"  без соответствия (в unmapped): {', '.join(unmapped)}")
    return out


def check() -> Report:
    r = Report("metrics — метрики и эксперименты")
    items = {i.id: i for i in load_items()}
    n_m = n_e = 0
    for p in sorted((ROOT / "data" / "metrics").glob("*/*.yaml")):
        n_m += 1
        d = load_yaml(p)
        it = items.get(d.get("item"))
        if not it:
            r.error(f"{rel(p)}: объекта {d.get('item')} нет")
            continue
        if d.get("variant") and not it.variant(d["variant"]):
            r.error(f"{rel(p)}: варианта {d['variant']} нет в {it.id}")
        per = d.get("period") or {}
        if as_date(per.get("from")) and as_date(per.get("to")) and as_date(per["from"]) > as_date(per["to"]):
            r.error(f"{rel(p)}: период from > to")
        if rel(p) not in it.data.get("metrics", []):
            r.warn(f"{rel(p)}: не указан в metrics объекта {it.id}")
    for p in sorted((ROOT / "data" / "experiments").glob("*.yaml")):
        n_e += 1
        d = load_yaml(p)
        it = items.get(d.get("item"))
        if not it:
            r.error(f"{rel(p)}: объекта {d.get('item')} нет")
            continue
        for v in d.get("variants", []):
            if not it.variant(v):
                r.error(f"{rel(p)}: варианта {v} нет в {it.id}")
        if d.get("status") == "finished" and (d.get("result") is None or d.get("decision") is None):
            r.error(f"{rel(p)}: завершён без result и decision")
        if d.get("status") in ("running", "finished") and not d.get("started"):
            r.error(f"{rel(p)}: запущен без даты started (гипотеза и метрика фиксируются до запуска)")
        if d["id"] not in it.data.get("experiments", []):
            r.warn(f"{rel(p)}: не указан в experiments объекта {it.id}")
    r.note(f"метрик {n_m}, экспериментов {n_e}")
    return r
