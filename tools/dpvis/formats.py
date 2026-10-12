"""Проверка документов по схемам `schemas/*.schema.json` своим кодом (D-05, D-07).

Поддержано подмножество JSON Schema, которое используют наши схемы:
type, const, enum, pattern, minLength, minimum, maximum, minItems,
properties, required, additionalProperties, items, anyOf, $ref на
`#/$defs/…`. Неизвестное ключевое слово — ошибка схемы, а не молчание.

Сверх схемы — связность: узлы и потоки среза должны быть в модели, если
модель дана (validate_pair).
"""

from __future__ import annotations

import json
import re
from pathlib import Path

SCHEMAS = Path(__file__).resolve().parents[2] / "schemas"
SCHEMA_FILES = {
    "dpvis-model/1": "dpvis-model-1.schema.json",
    "dpvis-state/1": "dpvis-state-1.schema.json",
}

_KNOWN = {
    "$schema", "$id", "title", "description", "$defs", "type", "const", "enum",
    "pattern", "minLength", "minimum", "maximum", "minItems", "properties",
    "required", "additionalProperties", "items", "anyOf", "$ref",
}

_TYPES = {
    "object": dict, "array": list, "string": str, "boolean": bool, "null": type(None),
}


class SchemaError(Exception):
    pass


def load_schema(name: str) -> dict:
    return json.loads((SCHEMAS / SCHEMA_FILES[name]).read_text(encoding="utf-8"))


def _is_type(value, name: str) -> bool:
    if name == "integer":
        return isinstance(value, int) and not isinstance(value, bool)
    if name == "number":
        return isinstance(value, (int, float)) and not isinstance(value, bool)
    return isinstance(value, _TYPES[name])


def check(value, schema: dict, root: dict, path: str = "$") -> list[str]:
    """Ошибки значения относительно схемы; пустой список — подходит."""
    unknown = set(schema) - _KNOWN
    if unknown:
        raise SchemaError(f"{path}: схема использует неподдержанное {sorted(unknown)}")
    if "$ref" in schema:
        ref = schema["$ref"]
        if not ref.startswith("#/$defs/"):
            raise SchemaError(f"{path}: ссылка {ref} — только на #/$defs/")
        return check(value, root["$defs"][ref.removeprefix("#/$defs/")], root, path)
    errors: list[str] = []
    if "anyOf" in schema:
        if not any(not check(value, sub, root, path) for sub in schema["anyOf"]):
            errors.append(f"{path}: не подходит ни под один вариант")
        return errors
    if "const" in schema and value != schema["const"]:
        return [f"{path}: ожидалось {schema['const']!r}"]
    if "enum" in schema and value not in schema["enum"]:
        return [f"{path}: {value!r} не из {schema['enum']}"]
    if "type" in schema:
        types = schema["type"] if isinstance(schema["type"], list) else [schema["type"]]
        if not any(_is_type(value, t) for t in types):
            return [f"{path}: ожидался тип {'/'.join(types)}, а не {type(value).__name__}"]
    if isinstance(value, str):
        if "minLength" in schema and len(value) < schema["minLength"]:
            errors.append(f"{path}: строка короче {schema['minLength']}")
        if "pattern" in schema and not re.search(schema["pattern"], value):
            errors.append(f"{path}: {value!r} не по шаблону {schema['pattern']}")
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        if "minimum" in schema and value < schema["minimum"]:
            errors.append(f"{path}: меньше {schema['minimum']}")
        if "maximum" in schema and value > schema["maximum"]:
            errors.append(f"{path}: больше {schema['maximum']}")
    if isinstance(value, list):
        if "minItems" in schema and len(value) < schema["minItems"]:
            errors.append(f"{path}: элементов меньше {schema['minItems']}")
        if "items" in schema:
            for i, item in enumerate(value):
                errors += check(item, schema["items"], root, f"{path}[{i}]")
    if isinstance(value, dict):
        for key in schema.get("required", []):
            if key not in value:
                errors.append(f"{path}: нет поля {key!r}")
        props = schema.get("properties", {})
        extra = schema.get("additionalProperties", True)
        for key, item in value.items():
            if key in props:
                errors += check(item, props[key], root, f"{path}.{key}")
            elif extra is False:
                errors.append(f"{path}: лишнее поле {key!r}")
            elif isinstance(extra, dict):
                errors += check(item, extra, root, f"{path}.{key}")
    return errors


def validate_document(data) -> list[str]:
    """Проверяет модель или срез по схеме из поля schema."""
    if not isinstance(data, dict):
        return ["документ — не объект JSON"]
    name = data.get("schema")
    if name not in SCHEMA_FILES:
        return [f"неизвестная схема {name!r}: ожидалась одна из {', '.join(SCHEMA_FILES)}"]
    schema = load_schema(name)
    return check(data, schema, schema)


def validate_pair(model: dict, state: dict) -> list[str]:
    """Связность среза с моделью: каждый узел и поток среза есть в модели."""
    problems = []
    node_ids = {n["id"] for n in model.get("nodes", [])}
    flow_ids = {f["id"] for f in model.get("flows", [])}
    for nid in sorted(set(state.get("nodes", {})) - node_ids):
        problems.append(f"узла {nid!r} из среза нет в модели")
    for fid in sorted(set(state.get("flows", {})) - flow_ids):
        problems.append(f"потока {fid!r} из среза нет в модели")
    days = state.get("window", {}).get("days")
    for fid, flow in sorted(state.get("flows", {}).items()):
        if "days" in flow and len(flow["days"]) != days:
            problems.append(f"поток {fid}: дней {len(flow['days'])}, а в окне {days}")
    for nid, node in sorted(state.get("nodes", {}).items()):
        git = node.get("git")
        if git:
            for key, arr in list(git["days"].items()) + list(git["days_all"].items()):
                if len(arr) != days:
                    problems.append(f"узел {nid}: ряд {key} — {len(arr)} дней, а в окне {days}")
    return problems
