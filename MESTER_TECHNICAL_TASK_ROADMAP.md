DPmetaApp Ecosystem Completion

Действующая редакция от 11 ноября 2026 — D-86…D-90 и
`docs/direction/PROJECT_ROLES.md`. Цель — мобильная студия разработки
с ИИ; завершение всех проектов экосистемы не является входным условием.
Разделы о включении контента, обучения, профиля, монетизации, DPlang IDE
и тяжёлой визуализации сохраняются как история прежнего замысла и
не задают текущий состав. План реализации — `ROADMAP/ROADMAP.md`.

Master ТЗ-Roadmap для последовательного завершения экосистемы и последующего построения мета-приложения

0. Назначение документа

Этот документ определяет единый долгосрочный протокол развития всей экосистемы dpsmpx.

Главная задача текущего этапа:

«Не строить DPmetaApp поверх набора незавершённых проектов, а сначала последовательно довести каждый существующий проект до полного, внутренне согласованного и проверенного состояния, сохранить их как законченные самостоятельные системы, установить межпроектные контракты и только после этого построить DPmetaApp как нативный мета-уровень над готовыми системами.»

Работа выполняется одним глобальным AI-агентом с доступом ко всем относящимся к экосистеме репозиториям.

Разные Claude-сессии, аккаунты и продолжения работы не являются разными независимыми разработчиками. Они являются последовательными экземплярами одного и того же долгоживущего инженерного процесса.

Главное требование:

«При смене сессии агент не должен заново решать, чем заниматься. Он обязан восстановить состояние из формализованных артефактов экосистемы и продолжить работу с ближайшей незавершённой точки глобального плана.»

---

1. Основные принципы

1.1. Сначала закончить исходную систему, потом интегрировать

Незавершённый проект не должен маскироваться внутри DPmetaApp под готовый модуль.

Правильная последовательность:

исследование проекта
        ↓
фиксация его назначения и границ
        ↓
завершение архитектуры и функциональности
        ↓
исправление технического долга
        ↓
тестирование
        ↓
документация
        ↓
межпроектная проверка
        ↓
PROJECT COMPLETE
        ↓
подготовка Integration Contract
        ↓
позднейшая нативная интеграция в DPmetaApp

DPmetaApp не должен использоваться как способ скрыть недоделанность исходной системы.

---

1.2. «Полностью завершён» не означает «в нём больше никогда ничего не будет»

Перед активной разработкой каждого проекта необходимо зафиксировать Scope Freeze.

Агент обязан разделить:

CURRENT COMMITTED SCOPE
      │
      ├── обязательная текущая функциональность
      ├── обязательные архитектурные свойства
      ├── тесты
      └── документация

FUTURE SCOPE
      │
      ├── следующие версии
      ├── идеи
      ├── экспериментальные возможности
      └── коммерческие/масштабные расширения

Проект считается полностью завершённым, когда выполнен весь Committed Scope.

Будущие возможности не должны искусственно превращать проект в бесконечно незавершаемый.

---

1.3. Каждый проект остаётся самостоятельной системой

До интеграции в DPmetaApp каждый репозиторий сохраняет:

- собственную архитектуру;
- собственные источники истины;
- собственные решения;
- собственные тесты;
- собственную документацию;
- собственные критерии завершения.

DPmeta является координационным мета-слоем и не должен незаметно становиться источником истины для деталей чужой реализации.

---

1.4. Cross-repository consistency обязательна

После значимого изменения необходимо учитывать его влияние на:

- DPmeta;
- DP-Verify;
- другие зависимые проекты;
- договорённости между проектами;
- документацию;
- будущую интеграцию;
- безопасность;
- происхождение данных и решений.

Нельзя завершить проект формально, если его окончательное состояние противоречит другой части экосистемы.

---

1.5. AI предлагает, формальные системы проверяют

AI не должен самостоятельно превращать:

- гипотезу в факт;
- предположение в архитектурное решение;
- генерацию в validated data;
- рекомендацию в необратимое действие.

Где возможно, использовать:

AI
 ↓
proposal / inference / hypothesis
 ↓
deterministic verification
 ↓
approval
 ↓
action

---

2. Глобальная роль единого агента

Агент работает не как обычный coding assistant, а как:

«Global Ecosystem Engineering Agent»

Он отвечает одновременно за:

- техническое завершение текущего проекта;
- сохранение архитектурной целостности всей системы;
- учёт связей между репозиториями;
- последовательность дальнейшей работы;
- актуальность документации;
- накопление проверенного состояния;
- подготовку следующей AI-сессии.

Он не должен ждать от пользователя ответа на вопрос:

««Что делать дальше?»»

Ответ на него должен определяться состоянием репозиториев и настоящим документом.

---

3. Главный источник состояния

Рабочий контекст не должен храниться только в окне Claude.

После смены сессии следующий агент должен иметь возможность восстановить состояние по репозиториям.

Минимально необходимо поддерживать:

DPmeta
 ├── ecosystem map
 ├── project status
 ├── completion matrix
 ├── cross-project findings
 ├── decisions
 ├── risks
 ├── dependencies
 ├── integration status
 └── current global handoff

В каждом проекте должны использоваться его собственные:

TODO.md
ROADMAP
DECISIONS
CHANGELOG
architecture docs
contracts
audits
tests

Названия конкретных файлов следует сохранять в существующем проекте, если они уже являются его принятым форматом. Новые документы создавать только там, где реально отсутствует необходимый механизм фиксации состояния.

---

4. Глобальный Completion Matrix

Необходимо создать и поддерживать единую таблицу состояния:

PROJECT
CURRENT STATE
COMMITTED SCOPE
BLOCKERS
TECH DEBT
CROSS-REPO RISKS
INTEGRATION READINESS
COMPLETION %
NEXT REQUIRED ACTION

Процент сам по себе не является критерием завершения.

Основные статусы:

DISCOVERING
AUDITING
PLANNING
IN_PROGRESS
BLOCKED
VERIFYING
CROSS_REPO_REVIEW
COMPLETE
READY_FOR_INTEGRATION
INTEGRATED

Статус "COMPLETE" нельзя назначать только потому, что основные функции работают.

---

5. Definition of Done проекта

Проект разрешено объявить "COMPLETE" только после выполнения всех применимых условий:

Scope

Весь текущий обязательный функциональный scope реализован.

Architecture

Основная архитектура определена и не содержит известных фундаментальных противоречий.

Implementation

Нет известных критических незавершённых частей.

TODO

"TODO.md" содержит только:

- реальные хвосты;
- осознанно отложенные задачи;
- будущий scope;
- технический долг, который не мешает объявлению текущего scope завершённым.

Незакрытые обязательные задачи не должны оставаться незаметно внутри TODO.

Tests

Есть соответствующий набор:

- unit tests;
- integration tests;
- regression tests;
- deterministic checks;
- device/host tests, если применимо.

Documentation

Актуальны:

- README;
- architecture;
- decisions;
- contracts;
- operation/build instructions;
- known limitations;
- security notes.

Security

Есть соответствующая проверка безопасности и происхождения зависимостей.

Cross-repo

Изменения согласованы с зависимыми проектами.

Reproducibility

Проект можно воспроизвести и проверить независимо от памяти текущего AI-сеанса.

Handoff

Создано состояние, позволяющее другой Claude-сессии продолжить экосистемный процесс без потери контекста.

---

6. Протокол работы над каждым проектом

Каждый репозиторий проходить одинаковый жизненный цикл.

Phase P0 — Discovery

Агент сначала изучает проект.

Не начинать с массового написания кода.

Исследовать:

- README;
- CLAUDE.md;
- TODO;
- ROADMAP;
- DECISIONS;
- CHANGELOG;
- architecture;
- tests;
- scripts;
- CI;
- открытые PR;
- последние коммиты;
- состояние веток;
- существующие аудит-результаты.

При необходимости после документационного анализа переходить к коду.

Цель:

«понять, что проект уже умеет, что реально является обязательным scope и где находятся незакрытые части.»

---

Phase P1 — Scope Reconstruction

Установить:

что проект должен делать;
что уже делает;
что обещано документацией;
что фактически реализовано;
что противоречит друг другу;
что является техническим долгом;
что является будущей идеей.

Особенно тщательно искать:

- устаревшие заявления;
- выполненные, но всё ещё записанные как TODO задачи;
- частично реализованные функции;
- фиктивно законченные функции;
- документы, расходящиеся с кодом;
- скрытые assumptions.

---

Phase P2 — Completion Plan

Сформировать конкретный план закрытия проекта.

Не переписывать всё автоматически.

Для каждой крупной задачи определить:

WHY
CURRENT STATE
TARGET STATE
DEPENDENCIES
FILES / DOMAINS
TEST STRATEGY
DOCUMENTATION IMPACT
CROSS-REPO IMPACT
DONE CRITERIA

---

Phase P3 — Implementation

Последовательно реализовывать план.

Правила:

- не оставлять намеренно «заглушки на потом», если они относятся к текущему scope;
- не делать работу исключительно ради количества кода;
- не создавать лишнюю инфраструктуру без необходимости;
- не ломать существующие хорошие архитектурные решения;
- предпочтительно улучшать существующую систему, а не заменять её без доказанной причины.

---

Phase P4 — Verification

После существенных изменений запускать соответствующие:

- tests;
- static checks;
- build;
- lint;
- hostcheck;
- DP-Verify;
- device tests;
- performance tests;
- security checks.

Не считать задачу выполненной по принципу:

««код написан».»

Правильная единица готовности:

«implementation + verification + documentation»

---

Phase P5 — Cross-Repo Validation

Проверить:

Does this change affect another repo?
Does it violate a contract?
Does DPmeta need update?
Does DP-Verify need adaptation?
Does it change future integration?
Does it invalidate documentation elsewhere?
Does it create duplicated responsibility?

При необходимости обновить несколько репозиториев в согласованном порядке.

---

Phase P6 — Final Audit

Перед "COMPLETE" провести финальный аудит:

requirements
architecture
implementation
tests
security
docs
cross-repo consistency
future integration readiness

---

Phase P7 — Completion Record

После завершения зафиксировать:

- финальный scope;
- что реализовано;
- что сознательно отложено;
- тестовое состояние;
- важные решения;
- known limitations;
- integration contract;
- commit/revision;
- статус в DPmeta.

---

7. Порядок завершения экосистемы

Порядок является dependency-aware и может изменяться только самим глобальным агентом на основании фактического состояния.

Однако базовой последовательностью считать следующую.

---

WAVE 0 — Global Ecosystem Audit

До глубокой разработки отдельных систем:

1. Найти все существующие репозитории.
2. Определить назначение каждого.
3. Определить состояние.
4. Найти дублирование.
5. Найти зависимости.
6. Найти архитектурные конфликты.
7. Найти документы, противоречащие друг другу.
8. Определить текущий committed scope.
9. Создать/обновить Completion Matrix.
10. Создать глобальный execution state.

Ни один проект не следует считать готовым только на основании README.

---

WAVE 1 — DPmeta

DPmeta является системой координации, поэтому должна быть приведена к стабильному состоянию в первую очередь.

Довести:

- repository registry;
- project map;
- boundaries;
- contracts;
- risks;
- decisions;
- consistency checks;
- activity;
- cross-reference analysis;
- synchronization workflow;
- ecosystem state;
- handoff mechanism.

Особое внимание:

«DPmeta должна быть достаточно надёжной, чтобы следующие Claude-сессии могли использовать её как карту текущего состояния экосистемы.»

---

WAVE 2 — DP-Verify

Довести DP-Verify до законченного состояния текущего scope.

Особенно:

- verification stages;
- profiles;
- mandatory checks;
- baseline;
- scope;
- report;
- explain;
- doctor;
- plan;
- project profile;
- consumer integration;
- reproducibility.

После завершения использовать его как стандартный verification layer для следующих проектов, где это применимо.

---

WAVE 3 — DPpass

DPpass является зрелой технической системой и должен быть закрыт до полноценного состояния проекта без искусственного стремления превратить его в публичный продукт.

Учитывать:

- Android VPN/TUN;
- IR/configuration;
- Xray backend;
- routing;
- diagnostics;
- encrypted storage;
- adaptive stability;
- domestic split;
- deterministic testing;
- security;
- host/device validation;
- DP-Verify integration.

Для DPpass:

«personal-ready и engineering-complete важнее публичного release readiness.»

Не добавлять маркетинговую или релизную инфраструктуру только ради формального статуса продукта.

---

WAVE 4 — DPlang

Довести DPlang до полноценного заявленного v1.0 scope.

Порядок:

lexer
 ↓
parser
 ↓
modules
 ↓
types
 ↓
Core IR
 ↓
reference interpreter
 ↓
C backend
 ↓
runtime/toolchain
 ↓
tests
 ↓
diagnostics
 ↓
documentation
 ↓
stability

Закрыть известные проблемы, включая рекурсивные случаи в type comparator и юридические/licensing решения в рамках текущего scope.

Основной принцип:

«DPlang сначала должен стать самостоятельным языком и toolchain, затем может использоваться как фундамент будущей миграции других систем.»

Не начинать большую миграцию VoxelRPG на недозрелый DPlang.

---

WAVE 5 — MAIN_OBSIDIAN_PC_works + Knowledge Layer

Привести систему личной базы знаний к определённой модели:

raw information
 ↓
candidate fact
 ↓
evidence
 ↓
validated fact
 ↓
relations
 ↓
knowledge graph
 ↓
retrieval

Должны быть определены:

- source provenance;
- fact validation;
- contradictions;
- confidence;
- relationships;
- search;
- semantic retrieval;
- project links;
- decision links;
- historical state.

Не смешивать три существующие ветви/концепции проекта в одну неформальную структуру. Каждая ветка должна сохранить своё назначение, а итоговая система — явно определить механизм их совместной работы.

---

WAVE 6 — DP_TextFile_Sorter

Довести до роли:

«внутреннего интеллектуального организатора локального хранилища.»

Не превращать его в самостоятельный пользовательский продукт, если это больше не соответствует архитектурной цели.

Завершить:

- scanner;
- parser;
- structure analyzer;
- classifier stack;
- decision layer;
- file manager;
- dry-run;
- safety;
- reports;
- rollback/recovery;
- embeddings;
- semantic classification;
- hybrid arbitration.

Особенно важно:

«классификация не должна уничтожать или незаметно перемещать данные.»

Все потенциально опасные операции должны быть reversible/approval-gated.

---

WAVE 7 — DP_Repeated_Tasks

Довести систему до полной версии текущего scope:

- task tree;
- subtasks;
- completion timestamps;
- history;
- statistics;
- kanban;
- atomic save;
- backups;
- corruption rejection;
- migration.

Затем определить её роль как внутреннего task/activity subsystem экосистемы.

---

WAVE 8 — DPlearn

Довести полный цикл:

goal
 ↓
diagnosis
 ↓
learning
 ↓
practice
 ↓
independent check
 ↓
error analysis
 ↓
recheck
 ↓
delayed verification

Главный инвариант:

«mastery = evidence, not checkbox.»

Сформировать чёткие протоколы оценки, накопления результатов, ошибок и повторного обучения.

---

WAVE 9 — DPprofile

Довести evidence-traced history system.

Завершить:

- sources;
- atomic facts;
- timeline;
- insights;
- methods;
- project events;
- evidence links;
- provenance;
- book-oriented structure.

Не превращать его преждевременно в литературную книгу.

Система должна оставаться структурированным источником фактов и истории, из которого будущие публикации могут быть построены после выполнения соответствующих внешних milestones.

---

WAVE 10 — DP_game_market_analytic_system

Довести market intelligence system до полностью работоспособного current scope.

Сохранить обязательные инварианты:

- determinism;
- order independence;
- point-in-time correctness;
- append-only observation ledger;
- SHA/provenance;
- evidence-first;
- unknown ≠ zero;
- status separation;
- causality boundary;
- versioned rules/schema;
- explicit conflicts;
- explainability;
- synthetic isolation.

Разделять:

observed fact
derived metric
hypothesis
forecast
recommendation

LLM не должен становиться доказательством.

---

WAVE 11 — DPmonetization

Опираясь на законченные технические и аналитические системы, привести readiness model к полноценной рабочей системе.

Должны быть связаны:

technical readiness
engineering readiness
product readiness
user readiness
market readiness
monetization readiness
operational readiness
research readiness

Принцип:

«stage определяется проверенными критериями, а не процентом выполненных feature.»

DPmonetization не должен самостоятельно придумывать факты о состоянии продукта.

---

WAVE 12 — SocialMediaImage

Довести контентную и publishing platform до завершённого состояния.

Сохранить pipeline:

Author / Projects
        ↓
Research / Facts
        ↓
Canonical Content
        ↓
Adaptation
        ↓
Validation
        ↓
Approval
        ↓
Website
        ↓
Platform adapters
        ↓
Publication journal
        ↓
Analytics
        ↓
Strategy

Особое внимание:

- canonical-first;
- evidence validation;
- publication journal;
- idempotence;
- safe mode;
- approval by hash;
- staging;
- site generation;
- deployment safety;
- CSP;
- accessibility;
- mobile;
- RSS/Atom/JSON;
- sitemap;
- browser validation;
- sensitivity scanning.

Окончательный сайт должен оставаться отделённым от закрытых внутренних сведений.

---

WAVE 13 — VoxelRPG / Engine

Это крупнейший и наиболее сложный блок.

Прежде чем считать его завершённым:

1. Провести полный scope audit.
2. Отделить реально обязательные игровые системы от будущих идей.
3. Довести текущую архитектуру до внутренней устойчивости.
4. Закрыть gameplay/engine TODO.
5. Закрыть технический долг.
6. Завершить performance/security/provenance audits.
7. Провести stress testing.
8. Проверить save/load, world generation, entities, NPC, mobs, rendering, UI, inventory, tools, durability, world liveliness и остальные обязательные подсистемы.
9. Проверить device compatibility.
10. Зафиксировать Engine ↔ VoxelRPG boundaries.

Разделение Engine и VoxelRPG не форсировать искусственно.

Сначала:

«ясные boundaries и стабильная архитектура.»

Затем, при наличии реальной необходимости:

«физическое разделение репозиториев.»

DPlang рассматривается как потенциальный будущий foundation, а не как причина prematurely переписывать рабочий VoxelRPG.

---

WAVE 14 — DPvisualisation

После стабилизации data-producing систем определить и завершить общую visualization layer.

Она должна уметь визуализировать:

- project progress;
- repository health;
- verification;
- activity;
- learning;
- market metrics;
- monetization readiness;
- profile timeline;
- ecosystem relations;
- performance data.

Визуализация не должна сама становиться источником истины.

---

WAVE 15 — DPlang IDE

После стабилизации DPlang создать полноценную IDE.

Базовый scope:

editor
 ↓
syntax highlighting
 ↓
parser diagnostics
 ↓
compiler
 ↓
run
 ↓
debug/diagnostics
 ↓
project/workspace management
 ↓
documentation
 ↓
tests

AI-ассистент IDE — дополнительный слой над формальными compiler diagnostics.

Не разрешать AI исправлять код таким образом, чтобы он обходил формальную семантику языка.

---

WAVE 16 — Global Completion Audit

После прохождения всех проектов агент должен остановить обычную feature development loop и выполнить Ecosystem Completion Audit.

Проверить:

Every repository
Every committed requirement
Every dependency
Every cross-repo contract
Every major decision
Every security boundary
Every verification layer
Every build path
Every documentation dependency
Every planned migration

Результатом должно стать:

ECOSYSTEM BASELINE

То есть фиксированная точка, от которой начинается строительство мета-приложения.

---

8. Migration Contract

После завершения каждого проекта подготовить Integration Contract.

Минимальная структура:

Project:
Purpose:
Canonical Source:
Current Version:
Stable Revision:
Public/Internal Boundary:
Core Concepts:
Input:
Output:
Persistent State:
Commands:
Events:
Errors:
Permissions:
Security:
Dependencies:
Data Ownership:
Verification:
Known Limitations:
DPmeta Integration:
DPmetaApp Integration:
Migration Notes:
Semantic Parity Requirements:

Это не означает переносить код немедленно.

Это означает:

«зафиксировать, что именно позже должно попасть в DPmetaApp и в каком смысле.»

---

9. Правило Semantic Parity при переносе

Когда начнётся DPmetaApp:

Original Project
       ↓
Integration Adapter / Artifact
       ↓
Native DPmetaApp Domain

Нельзя просто переписать поведение «примерно так же».

Для критических систем необходимо сравнивать:

input
 ↓
original semantics
 ↓
expected result

vs

input
 ↓
native implementation
 ↓
actual result

И только после прохождения semantic parity считать перенос успешным.

---

10. DPmetaApp строится только после Ecosystem Baseline

DPmetaApp не должна быть:

- launcher;
- WebView;
- папкой с кнопками;
- оболочкой вокруг отдельных APK;
- набором несвязанных mini-apps;
- UI над недоделанными CLI;
- интерфейсом к проектам без общей модели.

Она должна стать:

«единым нативным runtime управления всей экосистемой.»

---

11. Архитектура DPmetaApp после завершения исходных систем

Базовое ядро:

DPmetaApp
│
├── Core
├── Storage
├── Event Bus
├── Provenance
├── Security
├── Permissions / Approval
├── Search
├── Ecosystem Model
│
├── Development
├── AI
├── Knowledge
├── Verification
├── Content
├── Website
├── Learning
├── Market
├── Monetization
├── Profile
├── Tasks
├── Network / DPpass
├── DPlang
├── DPlang IDE
└── Visualisation

---

12. Unified Data Model

После завершения исходных систем определить единые сущности только там, где это действительно оправдано.

Например:

Project
Repository
Worktree
Branch
Commit
PullRequest
Issue
Task
Decision
Audit
Check
Evidence
Fact
Hypothesis
Metric
Artifact
Document
Note
KnowledgeNode

LearningItem
LearningSession
Prompt
AIAccount
AIExecution
AIJob

Publication
ContentItem
ContentVariant
Campaign
Deployment

Dataset
MarketObservation
MarketFact
MarketMetric
MarketHypothesis

MonetizationTrack
RevenueRecord

RepeatedTask

NetworkProfile
VPNProfile

StorageObject
Event
Backup

Нельзя унифицировать сущности только ради красивой архитектуры.

Единая модель оправдана там, где она создаёт реальную межсистемную ценность.

---

13. Unified Event Model

Мета-приложение должно видеть не только текущее состояние, но и изменение состояния.

Примеры:

ProjectUpdated
RepositorySynced
BranchChanged
CommitObserved
PullRequestChanged

TaskCreated
TaskCompleted

DecisionRecorded

AuditStarted
AuditCompleted

CheckStarted
CheckCompleted

AIJobStarted
AIJobCompleted

PublicationPrepared
PublicationApproved
PublicationPublished

DeploymentPrepared
DeploymentApproved
DeploymentCompleted

LearningSessionStarted
LearningAssessmentCompleted

MarketObservationAdded
MarketAnalysisUpdated

BackupCreated
RestoreCompleted

События должны иметь:

- stable ID;
- timestamp;
- source;
- actor;
- project;
- operation ID;
- provenance;
- payload;
- severity;
- correlation ID.

---

14. Local-first Storage

DPmetaApp должна иметь локальное хранилище.

Минимальная архитектура:

DPStore
 ├── structured database
 ├── encrypted object store
 ├── append-only event/audit log
 ├── indexes
 ├── provenance records
 └── backup/restore

Основные требования:

- atomic transactions;
- consistency;
- migration;
- integrity verification;
- backup;
- restore;
- corruption detection;
- deterministic recovery where possible.

---

15. AI в DPmetaApp

Локальный AI является компонентом после формирования законченной экосистемы, а не заменой её инженерных механизмов.

Он должен работать поверх:

verified data
events
knowledge
projects
documents
metrics
contracts
tasks

Основные уровни:

Local AI
   ├── classification
   ├── semantic search
   ├── summarization
   ├── context construction
   ├── routing
   ├── hypothesis generation
   └── lightweight reasoning

External AI / Claude
   ├── complex reasoning
   ├── code development
   ├── large-scale research
   └── long-running engineering tasks

Локальный AI не должен заменять deterministic verification.

---

16. Единый Claude Workflow

DPmetaApp должна в дальнейшем уметь собирать для Claude:

project
branch
PR
current TODO
relevant architecture
decisions
recent changes
tests
risks
contracts
cross-project dependencies
handoff

Это называется:

«Context Bundle.»

Пользователь не должен вручную собирать этот контекст.

---

17. Multi-Claude Model

Несколько Claude-аккаунтов рассматриваются как несколько execution slots одного глобального агента.

Обязательно разделять:

AI identity
Execution target
Account
Session
Project
Job
Context
Result

Каждая новая сессия обязана читать состояние проекта и глобальный handoff.

Запрещено полагаться на:

- память предыдущего окна;
- незафиксированные сообщения;
- устные указания;
- «я помню, где остановился».

---

18. Приоритет задач

Агент выбирает следующую работу по такому приоритету:

1. Integrity / security / data-loss risks
2. Cross-repo blockers
3. Mandatory completion requirements
4. Broken tests/builds
5. Architectural blockers
6. Critical technical debt
7. Current feature completion
8. Documentation inconsistencies
9. Quality improvements
10. Future enhancements

Но порядок проектов не должен хаотично переключаться без серьёзной причины.

В нормальном режиме:

current project
    ↓
finish
    ↓
verify
    ↓
close
    ↓
next project

---

19. Правило минимизации контекстного переключения

Агент не должен менять проект после каждой небольшой задачи.

Предпочтительный режим:

select project
 ↓
deep work
 ↓
verification
 ↓
cleanup
 ↓
documentation
 ↓
completion
 ↓
next project

Исключение:

если текущий проект реально заблокирован зависимостью другого проекта.

Тогда необходимо:

1. формально зафиксировать blocker;
2. определить минимальную разблокирующую работу;
3. временно перейти в зависимый проект;
4. выполнить её;
5. вернуться к исходному проекту.

---

20. Работа при завершении лимита Claude

Это критически важный механизм.

При приближении к пределу контекста/сессии агент обязан оставить handoff.

Handoff должен содержать:

GLOBAL STATE
CURRENT PROJECT
CURRENT PHASE
CURRENT TASK
WHAT WAS COMPLETED
WHAT WAS VERIFIED
WHAT FAILED
OPEN RISKS
OPEN QUESTIONS
FILES MODIFIED
COMMITS / BRANCH
CROSS-REPO IMPACT
EXACT NEXT ACTION

Особенно важен последний пункт:

«EXACT NEXT ACTION»

Не:

««продолжить работу над DPpass».»

А:

««Запустить X, исправить Y, затем проверить Z и обновить файл N».»

Следующая сессия должна иметь возможность начать с этого пункта.

---

21. Правило вопросов

Агент не должен останавливаться для обычных уточняющих вопросов.

Если возникает неопределённость:

question
 ↓
analyse repository evidence
 ↓
infer safest valid interpretation
 ↓
continue

Если решение действительно невозможно определить:

record question in TODO / OPEN QUESTIONS
record impact
record blocked state
continue with independent work

Пользователь не должен быть необходимым синхронным диспетчером всей разработки.

---

22. Правило фиксации решений

Каждое значимое архитектурное изменение должно попадать в "DECISIONS" соответствующего проекта.

Если решение затрагивает несколько проектов:

local decision
+
DPmeta cross-project record

Не допускать ситуации:

«агент помнит архитектурное решение, но репозиторий — нет.»

---

23. Работа с техническим долгом

Технический долг разделять на:

BLOCKING
HIGH
MEDIUM
LOW
FUTURE

Перед "COMPLETE" обязательно закрыть:

- blocking;
- критический high, который нарушает архитектуру/безопасность/надёжность;
- обязательный scope-related debt.

Неблокирующий исторический долг можно оставить как явно классифицированный future work.

---

24. Работа с уже хорошими проектами

Если проект уже практически готов, агент не должен искусственно переписывать его.

Алгоритм:

audit
 ↓
verify
 ↓
identify actual gaps
 ↓
fix only real gaps
 ↓
complete

Например, хорошо работающий DPpass не должен подвергаться полной архитектурной реконструкции просто потому, что позднее он будет частью DPmetaApp.

Его зрелые решения являются source material для интеграции, а не поводом уничтожать проверенную архитектуру.

---

25. Не переписывать проекты ради мета-приложения

До начала DPmetaApp запрещено:

- массово переносить код;
- превращать исходные проекты в библиотеки только ради будущего API;
- преждевременно вводить общий framework;
- делать искусственный common abstraction layer;
- дублировать будущий DPStore;
- подгонять все существующие архитектуры под одну структуру.

Сначала:

«завершённые самостоятельные системы.»

Потом:

«осмысленная интеграция.»

---

26. Защита от «впихивания незавершённости»

Если проект имеет:

50% functionality
30% docs
20% tests

он НЕ считается готовым модулем.

Агент обязан написать:

PROJECT STATUS: IN_PROGRESS

и продолжить завершение исходного проекта.

DPmetaApp не может использовать наличие соответствующего каталога/класса внутри себя как доказательство завершённости домена.

---

27. Защита от бесконечной разработки

Перед каждым проектом:

SCOPE FREEZE

После него новые идеи автоматически относятся к:

future scope

если они не необходимы для достижения заявленной текущей цели.

Таким образом:

"complete current system"
≠
"implement every idea that will ever occur"

---

28. Git / branches / commits

Использовать существующую модель разработки проекта.

По возможности:

task
 ↓
branch
 ↓
implementation
 ↓
tests
 ↓
audit
 ↓
commit
 ↓
PR
 ↓
merge

Не смешивать несвязанные изменения.

Для больших завершительных этапов сохранять понятные checkpoints.

После каждого значимого milestone должно существовать состояние, из которого можно безопасно продолжить работу.

---

29. Документация как часть разработки

Документацию нельзя переносить на самый конец.

После изменения:

code
 ↓
tests
 ↓
docs
 ↓
decision/changelog

Документы должны отражать фактическое состояние, а не желаемое.

Особенно:

- README не должен объявлять готовым то, что не готово;
- TODO не должен содержать уже выполненное;
- DECISIONS не должен расходиться с архитектурой;
- CHANGELOG должен отражать существенные завершённые изменения.

---

30. Security / Provenance

Для всей экосистемы сохранить единый подход:

origin
 ↓
source
 ↓
transformation
 ↓
validation
 ↓
approval
 ↓
output

Особенно строго для:

- credentials;
- AI integrations;
- publishing;
- website deployment;
- VPN/network;
- financial information;
- destructive filesystem operations;
- backups;
- external APIs.

Закрытые проекты не должны случайно утекать в публичный сайт, content pipeline или AI context.

---

31. Внешние интеграции

Интеграции должны быть отделены от доменной логики.

Например:

domain
  ↓
connector interface
  ↓
GitHub
Beget
Telegram
VK
YouTube
Discord
...

Так доменные системы остаются тестируемыми и не зависят от конкретного внешнего сервиса.

---

32. Главный принцип для DPmetaApp

После завершения всей исходной экосистемы DPmetaApp не должна уничтожать автономность проектов.

Правильная модель:

Completed Systems
       │
       ├──────────────┐
       │              │
       ▼              ▼
Independent     DPmetaApp Native Domain
Source          Integration
       │              │
       └─────┬────────┘
             ▼
       semantic parity
             ▼
        unified runtime

То есть исходные репозитории остаются исторически и инженерно ценными источниками.

---

33. Финальная архитектурная цель

Итоговая система должна выглядеть концептуально так:

                           DPmetaApp
                               │
                 ┌─────────────┴─────────────┐
                 │                           │
          Unified Ecosystem Core        Local AI Core
                 │                           │
       ┌─────────┼──────────┐          ┌─────┼─────┐
       │         │          │          │     │     │
     State    Events    Provenance   Search Reasoning Routing
       │         │          │
       └─────────┼──────────┘
                 │
       ┌─────────┼───────────────────────────────┐
       │         │        │        │              │
     Dev      Knowledge  AI     Analytics      Content
       │         │        │        │              │
       ▼         ▼        ▼        ▼              ▼
    GitHub   Obsidian  Claude   Market       SocialMediaImage
    DPmeta   DPprofile local    Monetization Website
    Verify   DPlearn
    DPlang   Sorter
    Voxel
    DPpass
       │
       └─────────────────────────────────────────┐
                                                 ▼
                                           Visualisation

---

34. Пользовательский уровень

После завершения интеграции пользователь должен воспринимать систему не как 10–15 программ, а как одну среду.

Например:

"Покажи состояние моего VoxelRPG."

или:

"Найди все незакрытые проблемы, связанные с DPlang."

или:

"Подготовь контекст для Claude по текущему blocker."

или:

"Почему эта задача всё ещё не закрыта?"

или:

"Проведи verification."

или:

"Покажи прогресс экосистемы за последние 30 дней."

или:

"Проверь готовность VoxelRPG к следующему milestone."

или:

"Собери публикацию из уже подтверждённых фактов."

или:

"Запусти DPlang project и покажи diagnostics."

И все эти действия должны использовать одну общую модель состояния.

---

35. Главный execution loop

Вся работа агента должна подчиняться следующему циклу:

READ GLOBAL STATE
        ↓
SELECT CURRENT PROJECT
        ↓
READ PROJECT STATE
        ↓
UNDERSTAND CURRENT GAP
        ↓
PLAN
        ↓
IMPLEMENT
        ↓
TEST
        ↓
AUDIT
        ↓
DOCUMENT
        ↓
CROSS-REPO CHECK
        ↓
COMMIT / CHECKPOINT
        ↓
IS PROJECT COMPLETE?
      /       \
    NO         YES
    │           │
    ▼           ▼
CONTINUE    WRITE COMPLETION
                ↓
          SELECT NEXT PROJECT

---

36. Если текущая задача завершена раньше лимита

Не прекращать работу только потому, что одна задача закончена.

Агент обязан:

1. проверить следующие обязательные задачи текущего проекта;
2. закрыть мелкие хвосты;
3. обновить документацию;
4. запускать дополнительные проверки;
5. если проект завершён — перейти к следующему проекту.

---

37. Если лимит заканчивается во время задачи

Не начинать большой новый этап.

Сначала:

save state
run minimal verification
write handoff
checkpoint

После этого новая сессия продолжает текущую задачу.

---

38. Если обнаружен архитектурный конфликт между проектами

Не выбирать молча одну сторону.

Алгоритм:

detect conflict
 ↓
identify source-of-truth in both repos
 ↓
compare decisions
 ↓
determine dependency direction
 ↓
choose resolution
 ↓
record decision
 ↓
modify affected projects
 ↓
verify consistency

Если выбор невозможно сделать по существующей информации:

record OPEN QUESTION
record temporary safe state
continue independent work

---

39. Критерий окончания всего этапа

Первый большой этап завершён только тогда, когда:

all current projects
        +
all committed scopes
        +
cross-project consistency
        +
verification
        +
security
        +
documentation
        +
integration contracts

сформировали единый стабильный Ecosystem Baseline.

---

40. После Ecosystem Baseline — отдельная фаза DPmetaApp

Только после этого разрешается полноценная реализация:

V0 — Platform Core
V1 — Ecosystem Model
V2 — Storage / Events / Provenance
V3 — Native Domain Integrations
V4 — Verification
V5 — Knowledge
V6 — AI / Claude orchestration
V7 — DPpass
V8 — Content / Website
V9 — Learning / Tasks / Profile
V10 — Market / Monetization / Visualisation
V11 — DPlang / IDE
V12 — Full Cross-System Automation
V13 — Security / Performance / Recovery Audit
V14 — Final Ecosystem Runtime

Эти версии DPmetaApp являются версиями мета-приложения, а не версиями исходных проектов.

---

41. Native integration rules

Каждый нативно интегрируемый subsystem должен:

- иметь понятный owner domain;
- иметь typed contracts;
- сохранять provenance;
- иметь deterministic core там, где это возможно;
- иметь единый diagnostics model;
- использовать DPStore;
- использовать unified event model;
- иметь tests;
- иметь approval boundary;
- иметь migration/parity tests;
- быть связан с исходным Integration Contract.

---

42. DPmetaApp не должна поглощать всё без остатка

Некоторые компоненты могут оставаться внешними:

Claude service
GitHub
Beget
Telegram
VK
YouTube
Discord

DPmetaApp управляет ими через коннекторы.

Но пользователь должен получать единый интерфейс управления.

---

43. Конечная цель AI-агента

В финальном состоянии локальный AI и внешний Claude должны выполнять разные роли.

Local AI

observe
classify
retrieve
summarize
connect
route
predict
recommend
prepare context

Claude

reason deeply
research
write code
refactor
debug
design
execute complex engineering tasks

Deterministic systems

compile
test
verify
calculate
validate
persist
hash
measure
enforce

User

final authority

---

44. Неприкосновенное правило безопасности

Ни одна AI-модель не должна получать неограниченную возможность самостоятельно выполнить потенциально опасное действие только потому, что она «считает» его правильным.

Операции классифицировать как:

READ
SAFE_WRITE
SENSITIVE_WRITE
DESTRUCTIVE
IRREVERSIBLE

Для опасных операций обязательны:

proposal
 ↓
verification
 ↓
approval
 ↓
execution
 ↓
result

---

45. Как агент должен относиться к уже реализованным частям

Всегда:

existing working system
        ↓
understand
        ↓
verify
        ↓
preserve unless justified

Не:

existing system
        ↓
rewrite because "cleaner"

Главный приоритет:

«сохранять накопленную инженерную ценность.»

---

46. Основной критерий эффективности

Успех не измеряется:

- количеством написанного кода;
- количеством коммитов;
- количеством созданных файлов;
- количеством AI-сессий;
- количеством функций внутри APK.

Главная метрика:

«насколько быстро и безопасно экосистема переходит из фактического состояния в согласованное, проверенное и воспроизводимое состояние.»

---

47. Инструкция агенту: режим непрерывной работы

После прочтения этого документа агент должен немедленно:

1. Получить полную карту экосистемы.
2. Проверить актуальное состояние DPmeta.
3. Проверить Completion Matrix.
4. Найти текущий активный проект.
5. Прочитать его состояние.
6. Проверить последний handoff.
7. Определить ближайшую незавершённую обязательную работу.
8. Составить внутренний план.
9. Начать выполнение.
10. Не останавливаться после одной маленькой задачи.
11. Переходить дальше внутри текущего проекта до естественной контрольной точки.
12. При необходимости менять связанные репозитории.
13. Проверять результат.
14. Обновлять документацию.
15. Фиксировать решения.
16. Обновлять глобальное состояние.
17. При завершении проекта переходить к следующему.
18. При достижении лимита оставлять точный handoff.
19. После продолжения читать состояние заново и продолжать выполнение.
20. Не спрашивать пользователя, какой проект брать следующим, если это можно определить из текущего состояния и roadmap.

---

48. Запрещённые режимы работы

Агент не должен:

- бросать текущий проект ради случайно возникшей идеи;
- интегрировать незавершённый проект в DPmetaApp как будто он готов;
- дублировать один и тот же источник истины;
- переписывать стабильный код без причины;
- считать README достаточным доказательством готовности;
- считать отсутствие ошибок компиляции завершением проекта;
- оставлять неизвестное состояние без фиксации;
- полагаться на собственную память между сессиями;
- превращать TODO в бесконечный список пожеланий;
- создавать AI-generated facts без evidence;
- принимать гипотезы за решения;
- внедрять необратимые действия без соответствующего контроля;
- изменять публичные материалы на основании неподтверждённого закрытого контекста;
- начинать массовую реализацию DPmetaApp до достижения Ecosystem Baseline.

---

49. Финальный результат

В результате этого roadmap должны существовать две чёткие фазы.

Фаза A — Complete Ecosystem

Все самостоятельные проекты закончены:

DPmeta
DP-Verify
DPpass
DPlang
MAIN_OBSIDIAN_PC_works
DP_TextFile_Sorter
DP_Repeated_Tasks
DPlearn
DPprofile
DP_game_market_analytic_system
DPmonetization
SocialMediaImage
VoxelRPG / Engine
DPvisualisation
DPlang IDE

Для каждого существует:

stable implementation
tests
documentation
decisions
verified state
known limitations
Integration Contract

И между ними существует согласованная система связей.

Фаза B — Meta Application

После этого:

Completed Ecosystem
        ↓
Integration Contracts
        ↓
DPmetaApp
        ↓
Unified storage
Unified events
Unified permissions
Unified search
Unified AI
Unified verification
Unified automation
Unified UI
        ↓
ONE MOBILE ECOSYSTEM RUNTIME

---

50. Конечный принцип

Основной принцип всей разработки:

«Сначала построить качественные самостоятельные системы. Затем заставить их корректно понимать друг друга. И только после этого построить единое приложение, которое объединяет их не на уровне оболочки, а на уровне общей модели состояния, данных, событий, знаний, инструментов и процессов.»

Именно поэтому текущий этап не должен рассматриваться как:

««создать APK и постепенно наполнить его существующими проектами».»

Правильная модель:

««сначала завершить экосистему, затем превратить её в единый runtime».»

Вся последующая работа Claude должна рассматриваться как непрерывное выполнение этого документа до достижения "ECOSYSTEM BASELINE", после чего начинается отдельная фаза нативного строительства DPmetaApp.