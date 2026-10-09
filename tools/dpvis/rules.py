"""Все пороги и окна сборщика — в одном месте (D-04).

Изменился смысл правила — новый RULES_VERSION: он пишется в срез, и
читатель видит, по каким правилам посчитаны числа.
"""

import re
from datetime import timedelta, timezone

RULES_VERSION = 1

# Окно истории: дней до времени среза включительно.
WINDOW_DAYS = 30

# Часовой пояс дней — московское время автора
# (SocialMediaImage:data/progress/scope.yaml, timezone).
TZ = timezone(timedelta(hours=3))
TZ_NAME = "+03:00"

# «Кто работает» — признаки DPmeta:M-08.
MAIN_ACTIVE_HOURS = 6       # коммит основной ветки — «последние часы»
BRANCH_ACTIVE_HOURS = 24    # ветка с невлитой работой — «меньше суток»

# Участник (автор, сессии Claude) «работает», если его последнее событие
# моложе этого порога — как основная ветка репозитория.
ACTOR_ACTIVE_HOURS = 6

# Поток «идёт», если его последнее событие моложе этого порога —
# как active_hours набора progress/v1 (SocialMediaImage:tools/cs/progress).
FLOW_RECENT_HOURS = 72

# Интенсивность связи на схеме (толщина линии, D-16) — события за последние дни.
RATE_DAYS = 7

# Коммит ИИ: автор с таким именем или строка соавторства (F-12).
AI_AUTHORS = ("Claude",)
AI_TRAILER = re.compile(r"^co-authored-by:\s*claude\b", re.IGNORECASE | re.MULTILINE)

# Время команд git (к сайту и сервисам сборщик не обращается — D-19).
GIT_TIMEOUT_S = 60
CHERRY_TIMEOUT_S = 20
FETCH_TIMEOUT_S = 120
