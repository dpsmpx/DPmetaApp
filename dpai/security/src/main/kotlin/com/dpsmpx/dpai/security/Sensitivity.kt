package com.dpsmpx.dpai.security

/**
 * Уровень секретности источника (ТЗ, §65; `docs/SECURITY_MODEL.md`).
 * Порядок перечисления — от открытого к закрытому. `SECRET` в контекст по
 * умолчанию не попадает; включение — явное разрешение с записью.
 */
enum class Sensitivity { PUBLIC, INTERNAL, SENSITIVE, SECRET }

/**
 * Откуда пришло содержимое (ТЗ, §5.6, §64). Только `ORCHESTRATOR` —
 * доверенные указания; всё остальное — данные с пометкой происхождения,
 * а не инструкции (T3).
 */
enum class Origin { ORCHESTRATOR, USER, REPOSITORY, MODEL, EXTERNAL }
