"""События проектов → черновики кампаний (PLATFORM_VISION §36–38; SYSTEM_DESIGN.md, §3).

Источник события (опрос GitHub, ручной ввод) даёт нормализованное
событие; обработчик создаёт **черновик** кампании и объект в IDEA — без
публикации (её путь — через одобрение, Д12). Повтор того же события
(та же пара источник + id) второго черновика не создаёт (§56: duplicate
webhook). Сырые данные источника в контент не идут (§37).
"""
from __future__ import annotations

from dataclasses import dataclass, field

import yaml

from .repo import Repo

KINDS = {"release", "milestone", "feature", "video", "article", "project_state", "performance"}


@dataclass
class Event:
    kind: str
    project: str
    source: str           # «github:dpsmpx/<repo>», «manual»
    source_id: str        # id релиза, тега, вехи
    title: str
    data: dict = field(default_factory=dict)

    @property
    def key(self) -> str:
        return f"{self.source}#{self.source_id}"


def seen(repo: Repo, ev: Event) -> str | None:
    for cid, c in repo.campaigns().items():
        if (c.get("source_event") or {}).get("key") == ev.key:
            return cid
    return None


def next_campaign_id(repo: Repo) -> str:
    nums = [int(c.split("-")[1]) for c in repo.campaigns()]
    return f"CMP-{max(nums, default=0) + 1:04d}"


def draft_campaign(repo: Repo, ev: Event, apply: bool = False) -> tuple[str, dict, bool]:
    """(id, черновик, создан ли). Без apply — только показать."""
    if ev.kind not in KINDS:
        raise ValueError(f"вид события {ev.kind}: {sorted(KINDS)}")
    if ev.project not in repo.projects():
        raise ValueError(f"проект {ev.project} не описан в data/projects/")
    old = seen(repo, ev)
    if old:
        return old, repo.campaigns()[old], False
    from .. import newitem
    cid = next_campaign_id(repo)
    draft = {"schema": "campaign/v1", "id": cid, "title": f"{ev.project}: {ev.title}",
             "objective": f"Сообщить о событии «{ev.title}» ({ev.kind}) проекта {ev.project} — цель уточняет автор",
             "items": [], "launch": None, "status": "draft", "schedule": [],
             "source_event": {"key": ev.key, "kind": ev.kind, "project": ev.project},
             "notes": "черновик из события; объект и варианты — по конвейеру (CONTENT_PIPELINE.md)"}
    if apply:
        iid = newitem.create("news", "RELEASE" if ev.kind == "release" else "MILESTONE",
                             f"{ev.project}-{ev.kind}-{ev.source_id}".lower().replace(".", "-")[:60],
                             f"Сообщить о событии «{ev.title}» проекта {ev.project}",
                             items_dir=repo.path("content", "items"))
        draft["items"] = [iid]
        p = repo.path("data", "campaigns", f"{cid}.yaml")
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text("# Черновик кампании из события (core/events.py).\n"
                     + yaml.safe_dump(draft, allow_unicode=True, sort_keys=False), encoding="utf-8")
        repo.reset()
    return cid, draft, apply
