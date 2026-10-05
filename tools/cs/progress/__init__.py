"""Прогресс экосистемы (Д38; docs/platforms/WEBSITE.md, «Страница прогресса»).

Цепочка: история git в клонах репозиториев → набор данных
`data/progress/datasets/<id>.json` (collect.py, в сессии с клонами) →
производные величины с формулами (dataset.py) → фигуры SVG и таблицы
(figures.py, charts.py) → страница сайта `/progress/` из объекта контента
с подстановками `{{progress:…}}` и `{{figure:…}}`.

Сборка сайта клонов не требует: она читает только набор данных, список
репозиториев (`data/progress/scope.yaml`) и вехи (`data/progress/milestones.yaml`).
"""
