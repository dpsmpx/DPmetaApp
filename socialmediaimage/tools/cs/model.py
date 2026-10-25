"""Состояния объекта контента и допустимые переходы (docs/content/CONTENT_MODEL.md, §2)."""
from __future__ import annotations

STATES = [
    "IDEA", "RESEARCHED", "BRIEFED", "GENERATED", "UNDER_REVIEW", "REVISED",
    "APPROVED", "EXPORTED", "PUBLISHED", "ARCHIVED", "REJECTED",
]

TERMINAL = {"ARCHIVED", "REJECTED"}

TRANSITIONS: dict[str, set[str]] = {
    "IDEA": {"RESEARCHED"},
    "RESEARCHED": {"BRIEFED"},
    "BRIEFED": {"GENERATED", "RESEARCHED"},
    "GENERATED": {"UNDER_REVIEW"},
    "UNDER_REVIEW": {"REVISED", "APPROVED", "BRIEFED"},
    "REVISED": {"UNDER_REVIEW"},
    "APPROVED": {"EXPORTED", "UNDER_REVIEW"},
    "EXPORTED": {"PUBLISHED", "UNDER_REVIEW"},
    "PUBLISHED": {"ARCHIVED"},
    "ARCHIVED": set(),
    "REJECTED": set(),
}
# Отклонить можно на любом шаге до публикации.
for _s in STATES:
    if _s not in TERMINAL and _s != "PUBLISHED":
        TRANSITIONS[_s].add("REJECTED")

GATES = ["factual", "brand", "visual", "platform", "safety", "legal", "provenance"]
SCORE_AXES = ["factuality", "identity_fit", "visual_quality", "communication", "platform_fit", "strategic_value"]


def order(state: str) -> int:
    """Номер состояния на главной линии; REJECTED/ARCHIVED — в конце."""
    return STATES.index(state)


def reached(item_state: str, required: str) -> bool:
    """Объект прошёл состояние required (по главной линии; REVISED — между UNDER_REVIEW и APPROVED)."""
    if item_state in TERMINAL:
        return False
    main = ["IDEA", "RESEARCHED", "BRIEFED", "GENERATED", "UNDER_REVIEW", "APPROVED", "EXPORTED", "PUBLISHED"]
    state = "UNDER_REVIEW" if item_state == "REVISED" else item_state
    req = "UNDER_REVIEW" if required == "REVISED" else required
    return main.index(state) >= main.index(req)


def check_path(states: list[str]) -> list[str]:
    """Ошибки пути по истории: первое состояние — IDEA, каждый шаг — допустимый переход."""
    errors = []
    if not states:
        return ["история пуста"]
    if states[0] != "IDEA":
        errors.append(f"история начинается с {states[0]}, а не с IDEA")
    for a, b in zip(states, states[1:]):
        if a == b:
            continue  # несколько записей в одном состоянии — допустимо
        if b not in TRANSITIONS.get(a, set()):
            errors.append(f"недопустимый переход {a} → {b}")
    return errors


def effective_state(state: str, history_states: list[str]) -> str:
    """Состояние для требований: у ARCHIVED/REJECTED — последнее нетерминальное из истории.

    Отклонённый на шаге BRIEFED объект должен отвечать требованиям BRIEFED, а не
    ни одному (reached() для конечных состояний ложно).
    """
    if state not in TERMINAL:
        return state
    for s in reversed(history_states):
        if s not in TERMINAL:
            return s
    return "IDEA"
