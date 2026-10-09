"""Сверка модели с DPmeta (D-11, F-18): реестр репозиториев и заголовки контрактов.

Реестр (`DPmeta:tools/ecosystem.toml`, `schema = 1`) и контракты
(`DPmeta:docs/CONTRACTS.md`, заголовки `## C-NN — …`) читаются из основной
ветки клона DPmeta как данные; расхождения — диагностика среза, модель сама
не правится. Формат заголовков — тот же, что считает страница прогресса
SocialMediaImage (`DPmeta:C-10`).
"""

from __future__ import annotations

import re
import tomllib

from . import gitlog

CONTRACT_HEADING = re.compile(r"^#{2,3}\s+(C-\d+)\b", re.MULTILINE)


def check(model: dict, repos: dict, diags) -> dict:
    """Сверка по клону DPmeta: `repos` — наблюдения collect (`RepoData`), `diags` — его диагностика."""
    spec = model.get("registry")
    if not spec:
        return {"checked": False}
    source = spec["node"]
    data = repos.get(source)
    if data is None:
        diags.add("REGISTRY_NOT_FOUND", source, "нет клона репозитория с реестром — сверка пропущена")
        return {"checked": False, "node": source}
    out: dict = {"checked": True, "node": source, "ref": data.ref}
    model_slugs = {n["repo"].lower(): n["id"] for n in model["nodes"] if n["kind"] == "repo"}
    try:
        reg = tomllib.loads(gitlog.show(data.path, data.ref, spec["path"]))
    except gitlog.GitError:
        diags.add("REGISTRY_NOT_FOUND", source, f"в основной ветке нет {spec['path']}")
        reg = None
    except tomllib.TOMLDecodeError as exc:
        diags.add("REGISTRY_SCHEMA_UNSUPPORTED", source, f"{spec['path']} не читается: {exc}")
        reg = None
    if reg is not None:
        if reg.get("schema") != 1:
            diags.add("REGISTRY_SCHEMA_UNSUPPORTED", source,
                      f"{spec['path']}: schema {reg.get('schema')!r}, ожидалась 1")
        else:
            slugs = sorted({r["slug"] for r in reg.get("repo", []) if isinstance(r, dict) and r.get("slug")})
            missing = [s for s in slugs if s.lower() not in model_slugs]
            for slug in missing:
                diags.add("REGISTRY_DRIFT", slug, f"репозиторий есть в реестре {spec['path']}, а в модели нет узла")
            reg_lower = {s.lower() for s in slugs}
            out["registry_repos"] = len(slugs)
            out["missing_in_model"] = missing
            out["not_in_registry"] = sorted(n["repo"] for n in model["nodes"]
                                            if n["kind"] == "repo" and n["repo"].lower() not in reg_lower)
    try:
        text = gitlog.show(data.path, data.ref, spec["contracts"])
        known = sorted(set(CONTRACT_HEADING.findall(text)), key=lambda c: int(c.split("-")[1]))
    except gitlog.GitError:
        diags.add("REGISTRY_NOT_FOUND", source, f"в основной ветке нет {spec['contracts']}")
        known = None
    if known is not None:
        cited: dict[str, list[str]] = {}
        for flow in model["flows"]:
            for c in flow["contracts"]:
                cited.setdefault(c.split(":", 1)[1], []).append(flow["id"])
        for c in known:
            if c not in cited:
                diags.add("CONTRACT_UNCOVERED", f"DPmeta:{c}", "контракт DPmeta не покрыт ни одним потоком модели")
        for c, flows in sorted(cited.items()):
            if c not in known:
                diags.add("CONTRACT_NOT_FOUND", f"DPmeta:{c}",
                          f"на контракт ссылаются потоки {', '.join(sorted(flows))}, а в {spec['contracts']} его нет")
        out["contracts"] = known
        out["contracts_covered"] = sorted(c for c in known if c in cited)
    return out
