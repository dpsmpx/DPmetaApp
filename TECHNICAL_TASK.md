DPmetaApp — техническое задание

Единая нативная мобильная платформа управления экосистемой dpsmpx

Целевой репозиторий: "dpsmpx/DPmetaApp"
Тип продукта: личное Android-приложение высокого инженерного класса
Назначение: единая локальная точка управления, использования, анализа и координации всех систем экосистемы dpsmpx
Основной режим: personal / private / local-first
Целевой владелец: один автор
Источник архитектурных принципов: лучшие проверенные решения DPpass, DPmeta, DP-Verify, SocialMediaImage, DPlang, DPlearn, DPprofile, DP_game_market_analytic_system, DP_Repeated_Tasks и DP_TextFile_Sorter

---

1. Главная идея

DPmetaApp не должен быть лаунчером отдельных приложений, WebView-оболочкой над сайтами или набором несвязанных экранов.

Он должен стать единой нативной системой, внутри которой:

- знания хранятся в едином локальном хранилище;
- проекты представлены единой моделью;
- задачи, решения, проверки, метрики и события имеют единые идентификаторы и связи;
- различные доменные подсистемы напрямую обмениваются данными;
- внешние системы подключаются через строго определённые адаптеры;
- все существенные операции имеют проверяемый результат;
- ошибки не скрываются;
- вычисляемые состояния не подменяются ручными флагами;
- все изменения можно объяснить и восстановить;
- разрушительные операции требуют явного подтверждения;
- локальная база является рабочим центром системы, а облачные сервисы выступают внешними источниками или каналами поставки.

Главная архитектурная формула:

                    ┌─────────────────────────────┐
                    │          DPmetaApp           │
                    │                             │
                    │  unified local ecosystem    │
                    └──────────────┬──────────────┘
                                   │
            ┌──────────────────────┼──────────────────────┐
            │                      │                      │
       Domain Core            Data / Evidence         Automation
            │                      │                      │
   ┌────────┼────────┐      ┌──────┼────────┐      ┌──────┼────────┐
   │        │        │      │      │        │      │      │        │
 DPpass   Knowledge AI   GitHub  Facts  Metrics    Claude  Site   Tasks
   │        │        │      │      │        │      │      │        │
   └────────┴────────┘      └──────┴────────┘      └──────┴────────┘
                                   │
                         typed events / contracts

Принцип: одна платформа, много доменов, один orchestrator, чёткие границы.

---

2. Объём экосистемы

На текущем этапе исходный список содержит больше девяти отдельных репозиториев/систем. Поэтому число компонентов не должно быть жёстко зашито в архитектуру.

Приложение должно использовать динамический реестр подсистем.

Минимальный исходный реестр:

Система| Роль в DPmetaApp
DPpass| сетевой клиент и сетевой control layer
SocialMediaImage| контентная платформа, публикации, сайт
MAIN_OBSIDIAN_PC_works| личная база знаний и документы
DPmeta| мета-слой экосистемы, связи и контракты
DPmetaApp| сама мобильная платформа
DPmonetization| экономика и readiness gates
DPlang| язык, compiler toolchain
DPlang IDE| IDE для DPlang внутри приложения
DP_game_market_analytic_system| аналитика игрового рынка
DPprofile| проверяемая история развития автора и проектов
DPlearn| персональная система обучения
DP-Verify| единый verification framework
DPvisualisation| визуализация системных данных
DP_Repeated_Tasks| повторяющиеся задачи и история выполнения
DP_TextFile_Sorter| семантическая организация внутреннего хранилища

Новая система должна добавляться через registry, а не через переписывание центрального UI.

---

3. Что означает «нативно интегрировать»

Нельзя считать интеграцию выполненной, если DPmetaApp просто:

- открывает GitHub в браузере;
- открывает dpsmpx.com в WebView;
- запускает отдельный терминал;
- показывает iframe;
- вызывает существующий CLI исключительно как внешний black box без интеграционного контракта;
- хранит набор ссылок на репозитории.

Нативной считается интеграция, при которой логика системы либо:

1. перенесена в собственный модуль DPmetaApp;
2. подключена как локальная библиотека;
3. переписана на нативный Kotlin/Java/C++ слой с сохранением проверенной семантики;
4. либо доступна через строго определённый локальный runtime/API.

Внешний сервис допустим только там, где он по определению является внешней инфраструктурой:

- GitHub;
- Beget/site hosting;
- Anthropic;
- Telegram/VK/YouTube/Discord/TikTok;
- иные удалённые сервисы.

Даже в этом случае основная модель данных DPmetaApp должна оставаться локальной и контролируемой.

---

4. Архитектурный принцип

DPmetaApp должен быть modular monolith, а не microservice-system.

Причины:

- один пользователь;
- один Android-процесс как основной режим;
- минимизация IPC;
- отсутствие необходимости в сетевом service mesh;
- общая локальная база;
- общие транзакции;
- общие security boundaries;
- более высокая наблюдаемость;
- меньше failure modes.

Архитектура должна быть модульной настолько, чтобы любой домен можно было изолировать и тестировать отдельно.

---

5. Основные архитектурные слои

5.1. "core"

Общее фундаментальное ядро:

core/
    ids/
    result/
    diagnostics/
    clock/
    hashing/
    serialization/
    versioning/
    contracts/
    events/
    capabilities/
    provenance/
    transaction/

Принципы:

- "Clock", а не прямое чтение времени;
- детерминированные алгоритмы;
- стабильные идентификаторы;
- машиночитаемые ошибки;
- отсутствие скрытых глобальных состояний;
- версионирование схем;
- SHA-256 для content identity;
- единая модель "Outcome";
- единый механизм причин ошибок.

---

6. Единая модель результата

Из DPpass необходимо перенести принцип:

success
warning
failure

но расширить его:

OperationResult<T>
 ├── status
 ├── value
 ├── diagnostics[]
 ├── causes[]
 ├── warnings[]
 ├── operation_id
 ├── timestamp
 ├── source
 └── provenance

Ошибка должна сообщать:

- что произошло;
- на каком этапе;
- почему;
- из-за какого объекта;
- что было предпринято;
- обратима ли ошибка;
- можно ли безопасно повторить операцию.

Нельзя сводить сложные ошибки к:

"Something went wrong"

---

7. Единый реестр сущностей

Центральная модель должна содержать:

Project
Repository
Worktree
Branch
Commit
PullRequest
Issue
Decision
Task
Audit
Check
Evidence
Fact
Hypothesis
Metric
MetricSample
Artifact
Document
Note
KnowledgeNode
LearningItem
LearningSession
AIAccount
AIExecutionTarget
AISession
Prompt
Run
Publication
ContentItem
ContentVariant
Campaign
SiteDeployment
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

Все сущности должны иметь стабильный ID.

Нельзя использовать:

- индекс списка;
- позицию в UI;
- номер строки;
- случайно выбранный display name

в качестве долгоживущего identity.

---

8. Единый event model

Все важные действия создают события:

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
DocumentImported
DocumentMoved
DocumentClassified
ClaudeSessionStarted
ClaudeSessionCompleted
PublicationPrepared
PublicationApproved
PublicationPublished
SiteBuildCompleted
SiteDeployCompleted
LearningSessionCompleted
SkillEvidenceAdded
MarketDatasetUpdated
MetricCalculated
VPNConnected
VPNDisconnected
VPNDegraded
BackupCreated
BackupRestored

События используются для:

- истории;
- синхронизации;
- статистики;
- уведомлений;
- графиков;
- аналитики;
- восстановления;
- автоматизации.

---

9. Локальное хранилище

9.1. Общая модель

Использовать единое хранилище:

DPStore
├── structured database
├── encrypted object storage
├── append-only audit/event log
├── indexes
└── metadata/provenance

Решения:

- структурированные данные — локальная БД;
- большие файлы — object store;
- бинарные артефакты — по hash;
- секреты — отдельно через Android Keystore;
- критические операции — атомарные транзакции;
- резервная копия — отдельная команда;
- восстановление — проверяемая операция.

9.2. Принцип DP_TextFile_Sorter

Для внутреннего хранилища:

INBOX
  ↓
parse
  ↓
classify
  ↓
rank
  ↓
confidence
  ↓
explain
  ↓
preview
  ↓
approve
  ↓
atomic move/copy

Никогда не перемещать оригинал без:

1. предварительной проверки;
2. детерминированного решения;
3. записи операции;
4. возможности отката.

---

10. Внутреннее хранилище знаний

DP_TextFile_Sorter должен быть переработан из файловой утилиты в Knowledge Storage Organizer.

Поддержать:

- Markdown;
- YAML/frontmatter;
- ссылки;
- теги;
- проекты;
- решения;
- факты;
- источники;
- изображения;
- документы;
- вложения;
- семантические индексы.

Категоризация:

lexical
tf-idf
embedding
graph
hybrid
LLM arbitration

Главный режим:

existing structure → inferred semantic profiles

Программа не должна самовольно изобретать новые категории.

---

11. MAIN_OBSIDIAN_PC_works

Обеспечить:

- просмотр vault;
- поиск;
- дерево;
- backlinks;
- graph;
- tagging;
- semantic search;
- inbox;
- automatic classification;
- document lifecycle;
- duplicate detection;
- evidence linking;
- project linking;
- conversion to facts;
- links to commits;
- links to decisions;
- links to tasks;
- links to learning objects.

Объекты Obsidian не должны превращаться в безличный архив.

Они должны становиться частью общего knowledge graph.

---

12. DPmeta как runtime governance layer

DPmeta должен перестать быть только внешним метарепозиторием.

В приложении реализовать:

Ecosystem Registry
Contract Registry
Decision Registry
Cross-project Reference Graph
Consistency Graph
Risk Registry
Synchronization Queue
Repository Health
Architecture Dependencies

Для каждого проекта:

what it is
what it owns
what it consumes
what it provides
what it must not do
current state
dependencies
open risks
open decisions
verification state

---

13. Межпроектные контракты

Перенести из DPmeta:

Contract
  owner
  consumers
  payload
  version
  compatibility
  break conditions
  verification
  provenance

Любое межпроектное изменение должно отображаться как:

producer
    ↓
contract
    ↓
consumer

А не как неявная ссылка в документации.

---

14. DP-Verify

DP-Verify становится единым verification engine DPmetaApp.

Не просто запуск CLI, а нативная модель:

Check
CheckProfile
CheckRun
CheckResult
Finding
Baseline
Scope
VerificationReport

Уровни:

static
affected
full
release

Дополнительно:

doctor
plan
explain
baseline
scope
report

Каждая проверка должна иметь:

- ID;
- название;
- уровень;
- обязательность;
- capability;
- причины;
- ожидаемый выход;
- machine-readable result.

DPmetaApp должен уметь:

Project → Verify → Plan → Run → Explain → History → Compare

---

15. Интеграция DPpass

DPpass интегрируется как полноценный networking domain:

network/
    dppass/
    profiles/
    subscriptions/
    routing/
    health/
    adaptive/

Сохранить сильные решения DPpass:

- собственный IR;
- capability matrix;
- semantic validation;
- transactional activation;
- last-known-good;
- structured diagnostics;
- health checking;
- adaptive selection;
- encrypted storage;
- no telemetry by default;
- provenance;
- backend boundary.

DPpass должен стать не просто VPN-экраном, а сетевым сервисом DPmetaApp.

Пример:

DPmetaApp
   ↓
ConnectionController
   ↓
DPpassCore
   ↓
Xray
   ↓
Android VpnService

---

16. Единый dashboard

Главный экран приложения не должен показывать просто набор иконок.

Он должен показывать состояние всей экосистемы.

Минимально:

ECOSYSTEM STATUS

Projects             14
Healthy               X
Attention             X
Blocked               X

Development
  active sessions      X
  open PRs             X
  pending decisions    X
  TODO                 X

Verification
  last full pass       ...
  failing checks       ...

Knowledge
  notes                ...
  inbox                ...
  unsorted             ...

AI
  active targets       ...
  queued tasks         ...
  account usage        ...

Content
  drafts               ...
  approved             ...
  published            ...

Learning
  current objective    ...
  evidence             ...

Market
  research freshness   ...
  tracked projects     ...

Network
  VPN                  connected
  active profile       ...

Tasks
  overdue/attention    ...

Все значения должны быть вычисляемыми.

Нельзя поддерживать такие counters вручную.

---

17. DPvisualisation

DPvisualisation превращается в общий visualization engine.

Он должен принимать:

MetricSeries
StateGraph
DependencyGraph
Timeline
Heatmap
Distribution
Progress
Comparison
Forecast
Flow
NetworkGraph

и строить их одинаковым механизмом.

Каждый график должен быть связан с:

source
dataset
formula
version
timestamp

То есть:

Graph ≠ decoration

Graph = reproducible view of data

---

18. Унифицированная система метрик

Все домены должны публиковать метрики через один контракт:

MetricDefinition
MetricSample
MetricSeries
MetricSnapshot
MetricDerivation

Поля:

id
value
unit
timestamp
source
scope
method
version
confidence
coverage

Не допускается смешивать:

observed
reported
estimated
inferred
predicted

Это наследуется из DP_game_market_analytic_system.

---

19. DP_game_market_analytic_system

Перенести в нативный analytics domain:

sources
observations
facts
entities
metrics
segments
competitors
trends
anomalies
hypotheses
scenarios
forecasts
decisions

Обязательные инварианты:

- determinism;
- order independence;
- as-of correctness;
- append-only observations;
- evidence-first;
- unknown ≠ zero;
- explicit conflicts;
- causal boundary;
- versioned rules;
- strict schemas;
- LLM output != evidence;
- explainability.

Особенно важен принцип:

«результат должен быть воспроизводим из входных данных, правил, версии алгоритма и "as_of".»

---

20. DPprofile

DPprofile превращается в personal provenance/history domain.

Он должен автоматически строить:

timeline
projects
milestones
commits
PRs
decisions
audits
experiments
releases
methods

Любое утверждение должно иметь источник.

Например:

Claim
  ↓
Fact
  ↓
Repository
  ↓
Commit / File / Release

Нельзя считать содержимое самой DPmetaApp доказательством внешнего факта.

---

21. DPlearn

DPlearn интегрируется через:

learning/
    competencies/
    evidence/
    sessions/
    tasks/
    errors/
    reviews/
    spaced/
    metrics/

Сохранить главный принцип:

mastered = computed from evidence

а не:

mastered = user checkbox

Любое состояние обучения должно иметь доказательную базу.

Интеграции:

Project task
    ↓
Learning Item
    ↓
Practice
    ↓
Evidence
    ↓
Assessment
    ↓
Skill state

Разработка реальных проектов должна автоматически становиться источником учебных задач.

---

22. DP_Repeated_Tasks

Система повторных задач интегрируется в общий task domain.

Сохраняется философия:

- не заменять пользователя;
- не придумывать периодичность без основания;
- хранить историю;
- вычислять elapsed time;
- поддерживать дерево;
- поддерживать статистику.

Добавить связи:

RepeatedTask
   ↔ Project
   ↔ Repository
   ↔ Maintenance operation
   ↔ Backup
   ↔ Learning item

Например:

Backup ecosystem
last performed: 6 days 4 hours ago

может быть обычной repeated task, а не отдельной системой.

---

23. DPmonetization

Перенести readiness-gate модель.

Для каждого проекта:

track
stage
gates
criteria
evidence
status

Поддерживать:

technical
engineering
product
user
market
monetization
operational
research

Особенность:

stage ≠ number of implemented features

Стадия вычисляется через проверяемые критерии.

---

24. SocialMediaImage

SocialMediaImage должна стать Content & Publishing domain.

Сохранить pipeline:

Research
 ↓
Brief
 ↓
Canonical
 ↓
Adaptation
 ↓
Validation
 ↓
Approval
 ↓
Export
 ↓
Publication
 ↓
Analytics

Модели:

ContentItem
CanonicalVersion
PlatformVariant
Campaign
Publication
PublicationAttempt
Approval
PlatformCapability

Главное правило:

one canonical source
multiple derived representations

---

25. Контент должен быть связан с экосистемой

Например:

VoxelRPG commit
      ↓
DPprofile fact
      ↓
SocialMediaImage content item
      ↓
canonical
      ↓
Telegram variant
      ↓
website
      ↓
publication
      ↓
analytics

Так исчезает необходимость вручную переносить одну и ту же информацию между системами.

---

26. Полный контроль сайта

DPmetaApp должен управлять:

website source
build
preview
staging
content
assets
facts
publication metadata
deployment
health
deployment history

Для Beget предусмотреть connector:

WebsiteConnector
 ├── build
 ├── dryRun
 ├── upload
 ├── healthCheck
 ├── deploymentHistory
 └── rollback

Адрес сервера, ключи SSH и другие секреты не хранятся в коде или Git.

Destructive deployment:

build
 ↓
validate
 ↓
show diff
 ↓
dry-run
 ↓
explicit approval
 ↓
deploy
 ↓
verify

После deployment система обязана проверить production result.

---

27. Claude integration

27.1. Общая модель

Ввести:

AIAccount
AIProvider
AIExecutionTarget
AIWorkspace
AISession
Prompt
TaskAssignment
Execution
UsageRecord
ContextBundle
Handoff

27.2. Три режима

A. Consumer Claude account

Для личных Pro/Max-аккаунтов:

- отдельный account slot;
- имя;
- назначение;
- проект;
- предпочтительный тип работы;
- состояние;
- последний запуск;
- безопасный launch flow;
- ссылка на официальный Claude application/browser surface.

Приложение не должно считать cookie/session token своим нормальным API.

B. Anthropic API identity

Для автоматических операций:

API Account
    ↓
Anthropic API
    ↓
Task

API credentials:

- Keystore;
- encrypted storage;
- never logs;
- never Git;
- explicit scope;
- revoke support.

C. Remote Claude Code target

Поскольку реальный Claude Code должен работать на поддерживаемой development environment, DPmetaApp должен уметь управлять remote execution target:

DPmetaApp
   ↓ SSH / controlled transport
Development Host
   ↓
Claude Code
   ↓
Git Repository

Объект:

ClaudeCodeTarget
 hostname
 auth
 workspace
 repository mapping
 branch
 tool capabilities
 status

Приложение должно уметь:

- создать задачу;
- сформировать context bundle;
- передать prompt;
- получить статус;
- получить результат;
- увидеть commit/PR;
- принять handoff;
- остановить execution;
- записать статистику.

Автоматическое destructive action без approval запрещено.

---

28. Claude account manager

Интерфейс:

Claude

Accounts
 ├── Account A
 ├── Account B
 ├── Account C
 ├── ...
 └── Add account

Targets
 ├── Local API
 ├── Remote Claude Code / Host 1
 └── Remote Claude Code / Host 2

Tasks
 ├── queued
 ├── running
 ├── blocked
 ├── completed
 └── failed

Для каждого аккаунта:

purpose
plan
availability
last use
assigned projects
assigned sessions
usage
notes

---

29. Context Bundle

Одна из ключевых функций DPmetaApp.

Перед запуском AI-задачи система должна автоматически уметь собирать:

project identity
current branch
current PR
relevant decisions
TODO
architecture
recent changes
tests
known risks
handoff
related contracts
related docs

Получается:

Task
  ↓
Context Builder
  ↓
Context Bundle
  ↓
Claude

Это должно стать основным мостом между человеческой постановкой и AI sessions.

---

30. AI handoff

Когда сессия прекращена:

session
 ↓
state extraction
 ↓
handoff
 ↓
stored locally

Handoff должен содержать:

what was done
what was verified
what failed
what remains
decisions made
files changed
branch
commit
PR
next recommended action

Это наследует лучшие принципы Claude-сессий DPlang/DPpass/SocialMediaImage.

---

31. AI orchestration

Поддержать:

single task
parallel tasks
sequential pipeline
dependency graph
batch
research loop
verification loop

Пример:

DPmeta audit
     ↓
DPpass documentation
     ↓
DPprofile update
     ↓
SocialMediaImage fact refresh

Однако orchestration должен учитывать dependency graph.

Нельзя одновременно запускать два задания, которые небезопасно изменяют один и тот же ресурс.

---

32. Project control center

Для каждого репозитория отдельный экран:

Project
├── status
├── health
├── branches
├── PRs
├── issues
├── TODO
├── decisions
├── audits
├── checks
├── metrics
├── AI sessions
├── recent activity
├── risks
├── dependencies
└── contracts

Действия:

Open
Sync
Check
Audit
Create task
Run AI
View diff
View PR
Build
Test
Create handoff

---

33. GitHub integration

Использовать официальный GitHub API.

Поддержать:

- repositories;
- branches;
- commits;
- PRs;
- issues;
- reviews;
- labels;
- releases;
- workflow state where applicable;
- compare;
- changed files;
- commit history.

Но GitHub не должен становиться единственным local source of truth.

DPmetaApp должен кэшировать и нормализовать информацию локально.

---

34. Project synchronization

Для каждого проекта:

last_remote_sync
last_local_sync
remote_head
known_local_head
branch divergence
pending local changes
open PRs

Система должна различать:

known
observed
stale
unknown
unavailable

---

35. Неизвестное ≠ ошибка

Перенести в DPlearn и market analytics принцип:

UNKNOWN

не должен автоматически означать:

0
false
healthy
complete

Например:

device test = UNKNOWN

не означает:

device test = PASS

---

36. Единая система confidence

Для фактов, аналитики, автоматической классификации и AI:

Confidence
Evidence
Coverage
Source
Freshness

Разные эпистемические статусы должны сохраняться:

observed
reported
estimated
inferred
predicted
unverified
verified

---

37. DPmetaApp knowledge graph

Объекты должны быть связаны:

Project
  ↓
Repository
  ↓
Commit
  ↓
Decision
  ↓
Task
  ↓
AI Session
  ↓
Result
  ↓
Verification
  ↓
Fact
  ↓
Content
  ↓
Publication

И наоборот:

LearningItem
 ↕
Project
 ↕
Task
 ↕
Evidence

---

38. Главный search

Одна поисковая строка должна искать по всей системе:

projects
repos
commits
PRs
tasks
notes
facts
decisions
AI sessions
publications
metrics
market entities
learning items

Результат обязан показывать тип объекта и происхождение.

---

39. Unified activity timeline

Один timeline:

10:00 commit
10:03 test
10:04 PR opened
10:09 Claude session started
10:41 task completed
10:44 verification passed
11:00 publication prepared
11:05 website built
11:07 deployed

Сортировка — по event timestamp, а не по времени записи UI.

---

40. Security architecture

40.1. Keystore

Все секреты:

- API keys;
- SSH secrets;
- tokens;
- credentials;
- private configuration;
- account session material

хранить через Android Keystore + encrypted local storage.

40.2. Биометрия

Предусмотреть lock levels:

NONE
APP_UNLOCK
SENSITIVE_ACTION
SECRET_ACCESS
DEPLOYMENT

40.3. Screen security

Для:

- credentials;
- private project data;
- sensitive AI context;
- private financial information;
- keys

включать "FLAG_SECURE".

40.4. Logging

Логировать:

what
where
when
result
object ID

Не логировать:

token
cookie
password
API key
private key
session secret
full proxy credential

Clipboard export — только explicit action.

---

41. No telemetry by default

Для личной системы:

external telemetry = OFF
analytics to developer = NONE
crash upload = NONE
device identifiers = NONE

Локальная аналитика приложения допустима, если она используется непосредственно владельцем.

---

42. Local-first principle

Основной режим:

offline
 ↓
local data available
 ↓
local algorithms work
 ↓
network only when necessary

Примеры:

- просмотр проектов — offline;
- просмотр знаний — offline;
- DPlearn state — offline;
- tasks — offline;
- visualizations — offline;
- DPpass local state — offline;
- historical metrics — offline.

Сеть нужна только для:

- GitHub sync;
- Anthropic;
- website deployment;
- social publishing;
- external research;
- market source fetching;
- subscription update;
- remote execution.

---

43. Offline queue

Каждая внешняя операция может попасть в:

PendingOperation

с:

operation_id
target
payload_hash
created
dependencies
retry_policy
approval
state

Состояния:

QUEUED
READY
RUNNING
SUCCESS
FAILED
BLOCKED
CANCELLED

---

44. Approval engine

Любая потенциально опасная операция должна иметь policy:

READ
SAFE_WRITE
SENSITIVE_WRITE
DESTRUCTIVE
IRREVERSIBLE

Примеры:

read GitHub → READ
create branch → SAFE_WRITE
merge PR → SENSITIVE_WRITE
deploy site → DESTRUCTIVE
delete data → IRREVERSIBLE

Политика по умолчанию:

READ → automatic
SAFE_WRITE → configurable
SENSITIVE_WRITE → explicit approval
DESTRUCTIVE → explicit approval + summary
IRREVERSIBLE → explicit confirmation

---

45. Reproducibility

Любое вычисление высокого уровня должно хранить:

input hash
algorithm version
schema version
rules version
source revision
timestamp/as_of
result hash

Это особенно важно для:

- market analytics;
- profile facts;
- learning state;
- visualizations;
- verification;
- content generation;
- reports.

---

46. Versioning

Версионировать отдельно:

APP_VERSION
DATA_SCHEMA_VERSION
EVENT_SCHEMA_VERSION
PROJECT_CONTRACT_VERSION
ANALYTICS_RULES_VERSION
CONTENT_SCHEMA_VERSION
AI_CONTEXT_VERSION

Старые данные не должны молча исчезать при обновлении приложения.

---

47. Migration framework

Обязателен:

Migration
MigrationResult
MigrationVersion
RollbackMarker

Миграция должна быть:

- проверяемой;
- идемпотентной;
- атомарной;
- обратимой там, где возможно.

Перед миграцией создавать verified local snapshot.

---

48. DPlang

DPlang должен стать отдельным native domain:

dplang/
    compiler/
    parser/
    diagnostics/
    ir/
    runtime/
    toolchain/
    packages/

Существующий compiler остаётся отдельной архитектурной сущностью, но DPmetaApp предоставляет для него:

- editor;
- project explorer;
- diagnostics;
- build;
- test;
- formatter;
- navigation;
- compiler output;
- run;
- package management;
- benchmark;
- documentation.

---

49. DPlang IDE

IDE должна быть полноценной, а не text editor.

Минимум:

Editor
Syntax highlighting
Diagnostics
Autocomplete
Go to definition
Go to reference
Rename
Format
Fix
Build
Run
Test
Terminal
Compiler output
AST / IR inspector
Project tree
Search
Symbols
Problems panel

DPlang language server должен использовать тот же frontend/semantic model, что compiler.

Нельзя реализовывать второй независимый parser только для IDE.

---

50. Native compiler execution

Для Android:

Kotlin UI
   ↓
DPlang IDE service
   ↓
native compiler bridge
   ↓
C++ / NDK

JNI должен быть тонким boundary.

Не переносить compiler logic в UI.

---

51. DPvisualisation + analytics

Visualization engine должен принимать любой зарегистрированный "MetricSeries".

Например:

DPpass FPS / health
DPlang compile time
Projects activity
Claude sessions
AI productivity
Learning growth
Market trends
Website traffic
Content output
Task completion
Time since repeated task

---

52. Cross-system analytics

Особенно важна возможность задавать вопросы поперёк проектов:

Сколько AI-сессий ушло на DPpass?

Какие проекты развиваются быстрее?

Какие типы задач чаще приводят к дефектам?

Как изменялась скорость разработки?

Какие решения чаще пересматриваются?

Сколько задач находится в ожидании автора?

Какое количество работы выполняется Claude?

Как меняется результативность обучения?

Какие проекты имеют самый большой technical debt?

Как изменения одного проекта влияют на другие?

Ответ должен строиться из локальных данных, а не генерироваться моделью без evidence.

---

53. AI as analyst

Claude может анализировать внутренние данные, но его результат хранить как:

AIObservation

а не:

Fact

Если AI предполагает:

"Этот проект выглядит незавершённым"

это не становится фактом без evidence.

---

54. Research system

Общий research object:

ResearchQuestion
Source
Observation
Evidence
Hypothesis
Experiment
Result
Decision

Pipeline:

Question
 ↓
Sources
 ↓
Observations
 ↓
Analysis
 ↓
Evidence
 ↓
Conclusion
 ↓
Decision

Это объединяет подходы DPmeta, DPprofile, DPlearn и market analytics.

---

55. Экспериментальная система

Объект:

Experiment

с полями:

question
hypothesis
method
inputs
parameters
expected_result
actual_result
artifacts
conclusion
version

Любой эксперимент должен быть воспроизводим.

---

56. Dashboard builder

Пользователь должен иметь возможность собирать панели из:

metric
table
timeline
chart
graph
project card
status
progress
task tree
AI session list
market snapshot
network status

Но dashboard не должен хранить копию данных.

Он хранит specification:

query
filters
layout
visualization
refresh

---

57. Notification engine

Уведомления только о событиях, имеющих operational value:

VPN down
verification failed
PR changed
AI task finished
deployment failed
backup failed
critical task threshold reached
subscription expired
learning review due

Не спамить декоративными уведомлениями.

---

58. Background worker

Использовать Android background execution только для задач, которым это действительно нужно.

Все задачи должны иметь:

owner
cancel policy
retry policy
network constraint
battery constraint
priority

Никаких бесконтрольных бесконечных worker loops.

---

59. Project capabilities

Каждый проект должен объявлять capabilities:

READ_REPO
WRITE_REPO
RUN_TESTS
BUILD
DEPLOY
PUBLISH
READ_KNOWLEDGE
WRITE_KNOWLEDGE
RUN_AI
RUN_REMOTE_AI
MODIFY_CONFIG
EXPORT

Интерфейс показывает только реально доступные действия.

---

60. Connector architecture

Внешние системы подключаются через:

connectors/
    github/
    anthropic/
    beget/
    telegram/
    vk/
    youtube/
    discord/
    tiktok/
    ssh/
    http/

Каждый connector:

Connector
Capability
CredentialProvider
Request
Response
Mapper
HealthCheck
RateLimit
ErrorMapping

---

61. Не смешивать домен и транспорт

Например:

SocialMedia domain
       ↓
PublicationPort
       ↓
TelegramConnector

а не:

SocialMediaService.sendTelegramHttpRequest(...)

То же относится ко всем внешним системам.

---

62. Capability negotiation

Как у DPpass:

ConnectorCapabilities

должны описывать, что реально умеет конкретный сервис.

Например:

GitHub:
  read repositories
  read commits
  read/write PR
  read/write issue

При недоступной возможности выдавать явный результат:

UNSUPPORTED

а не делать partial operation молча.

---

63. Rate limits

Каждый connector должен иметь:

rate limit
backoff
retry
quota

Система не должна превращать несколько AI/task/network operations в uncontrolled request burst.

---

64. Unified health

Каждая интеграция имеет:

HealthStatus

Возможные состояния:

HEALTHY
DEGRADED
AUTH_REQUIRED
RATE_LIMITED
UNAVAILABLE
MISCONFIGURED
UNKNOWN

---

65. Account vault

Отдельный secure section:

Accounts
├── Claude
├── GitHub
├── Beget
├── Telegram
├── VK
├── YouTube
├── Discord
└── Other

Но реальные секреты никогда не отображаются полностью после сохранения.

---

66. Backup

DPmetaApp должен поддерживать:

Create Backup
Verify Backup
Restore Backup
List Backups
Delete Backup
Export Selected Data

Backup должен иметь:

schema version
content hash
encryption state
creation time
object count
verification result

---

67. Disaster recovery

Минимальный сценарий:

new device
 ↓
install DPmetaApp
 ↓
restore encrypted backup
 ↓
restore account references
 ↓
reconnect credentials
 ↓
verify database
 ↓
rebuild indexes
 ↓
verify ecosystem

---

68. Search / indexing

Индексировать:

full text
titles
tags
paths
IDs
project names
decision IDs
commit IDs
PR IDs
facts
links
embeddings

Индекс должен иметь rebuild operation.

---

69. Semantic indexing

Для Obsidian и внутреннего хранилища:

lexical index
semantic index
graph index
metadata index

При отсутствии semantic model система продолжает работать в lexical mode.

---

70. Import/export

Все major domains должны иметь:

export
import
validate
dry-run

Импорт:

parse
validate
preview
approve
transaction
commit

---

71. UI architecture

Рекомендуемая база:

Kotlin
Jetpack Compose
single Activity
Navigation
ViewModel/state holders
coroutines

При этом domain logic не должна зависеть от Compose.

---

72. Presentation model

Как в DPpass:

Domain state
    ↓
Presentation model
    ↓
Compose

UI не должен читать БД или connector APIs непосредственно.

---

73. Главная навигация

Предполагаемый каркас:

Home
Projects
Knowledge
AI
Development
Tasks
Analytics
Content
Learning
Network
Storage
Settings

Но окончательный arrangement разрешить UI phase определить после user-flow experiment.

---

74. Unified command palette

Из любой точки:

Search
Open Project
Run Verify
Run AI
Create Task
Open Note
Build Site
Deploy Site
Connect VPN
Start Learning
Run Analytics
Create Backup

Это должно стать основным productivity mechanism.

---

75. Deep linking внутри приложения

Каждый объект должен иметь URI:

dpm://project/...
dpm://repo/...
dpm://task/...
dpm://decision/...
dpm://fact/...
dpm://ai/session/...
dpm://document/...
dpm://metric/...

Это позволит:

- связывать сущности;
- создавать уведомления;
- строить references;
- передавать context между системами.

---

76. Unified task model

Все виды задач должны использовать общий слой:

Task
TaskType
TaskState
TaskPriority
TaskDependency
TaskEvidence
TaskExecution
TaskResult

Но domain-specific semantics остаются внутри домена.

---

77. Task orchestration

Система должна поддерживать DAG:

A
├── B
├── C
└── D
     ↓
     E

Например:

Audit docs
 ↓
Fix inconsistencies
 ↓
Run DP-Verify
 ↓
Update DPprofile
 ↓
Update SocialMedia facts

---

78. Dependency-aware scheduling

Нельзя запускать:

deploy website

если:

validation failed

Нельзя запускать:

AI continuation

если:

required handoff missing

Нельзя запускать:

merge

если:

required checks failed

---

79. Definition of Done

Любой internal operation считается завершённым только если:

implemented
tested
validated
documented
state updated
history written

---

80. Документация репозитория

Root:

README.md
CLAUDE.md
TODO.md
LICENSE

Основная документация:

docs/
    direction/
        TECHNICAL_TASK.md
        VISION.md
        GOAL.md
        DECISIONS.md
        OPEN_DECISIONS.md
        ROADMAP.md

    architecture/
        ARCHITECTURE.md
        MODULES.md
        DATA_MODEL.md
        EVENT_MODEL.md
        SECURITY.md
        CONNECTORS.md

    contracts/
        PROJECTS.md
        EXTERNAL_SERVICES.md
        AI.md
        STORAGE.md

    process/
        PROCESS.md
        TESTING.md
        RELEASE.md
        MIGRATIONS.md

    audits/
    research/
    reports/

---

81. Структура кода

Предлагаемая initial structure:

DPmetaApp/
├── app/
│
├── core/
│   ├── diagnostics/
│   ├── result/
│   ├── ids/
│   ├── clock/
│   ├── hashing/
│   ├── contracts/
│   ├── events/
│   ├── versioning/
│   └── transaction/
│
├── storage/
│   ├── database/
│   ├── blobs/
│   ├── encryption/
│   ├── migrations/
│   ├── backup/
│   └── indexes/
│
├── ecosystem/
│   ├── registry/
│   ├── projects/
│   ├── contracts/
│   ├── decisions/
│   ├── risks/
│   └── synchronization/
│
├── development/
│   ├── github/
│   ├── repositories/
│   ├── branches/
│   ├── pulls/
│   ├── issues/
│   ├── builds/
│   └── sessions/
│
├── ai/
│   ├── accounts/
│   ├── providers/
│   ├── anthropic/
│   ├── claude/
│   ├── claude_code/
│   ├── contexts/
│   ├── orchestration/
│   └── handoff/
│
├── knowledge/
│   ├── obsidian/
│   ├── markdown/
│   ├── search/
│   ├── semantic/
│   ├── graph/
│   └── sorter/
│
├── verification/
│   ├── checks/
│   ├── profiles/
│   ├── baseline/
│   ├── runs/
│   └── reports/
│
├── content/
│   ├── canonical/
│   ├── variants/
│   ├── campaigns/
│   ├── publishing/
│   └── analytics/
│
├── website/
│   ├── build/
│   ├── preview/
│   ├── deploy/
│   └── health/
│
├── learning/
│   ├── competencies/
│   ├── evidence/
│   ├── sessions/
│   ├── errors/
│   └── reviews/
│
├── market/
│   ├── sources/
│   ├── observations/
│   ├── facts/
│   ├── metrics/
│   ├── segments/
│   ├── hypotheses/
│   ├── forecasts/
│   └── decisions/
│
├── monetization/
│   ├── criteria/
│   ├── tracks/
│   ├── gates/
│   └── reports/
│
├── profile/
│   ├── facts/
│   ├── timeline/
│   ├── insights/
│   └── provenance/
│
├── tasks/
│   ├── generic/
│   ├── repeated/
│   ├── dependencies/
│   └── scheduler/
│
├── network/
│   └── dppass/
│
├── dplang/
│   ├── ide/
│   ├── compiler/
│   ├── language-server/
│   ├── runtime/
│   └── native/
│
├── visualization/
│   ├── charts/
│   ├── graphs/
│   ├── timelines/
│   ├── dashboards/
│   └── exporters/
│
├── connectors/
│   ├── github/
│   ├── anthropic/
│   ├── beget/
│   ├── telegram/
│   ├── vk/
│   ├── youtube/
│   ├── discord/
│   ├── tiktok/
│   └── ssh/
│
├── security/
│   ├── vault/
│   ├── keystore/
│   ├── approval/
│   ├── privacy/
│   └── audit/
│
├── ui/
│   ├── home/
│   ├── projects/
│   ├── knowledge/
│   ├── ai/
│   ├── development/
│   ├── tasks/
│   ├── analytics/
│   ├── content/
│   ├── learning/
│   ├── network/
│   └── settings/
│
└── tests/
    ├── unit/
    ├── integration/
    ├── contract/
    ├── regression/
    ├── property/
    ├── fuzz/
    ├── security/
    └── device/

---

82. Что брать из существующих репозиториев

Из DPpass

Брать:

- "Outcome";
- diagnostics;
- deterministic Clock;
- capability matrix;
- own IR;
- transaction activation;
- LKG;
- encrypted storage;
- health;
- adaptive control;
- strict semantic preservation;
- provenance;
- host/device separation.

Из SocialMediaImage

Брать:

- canonical model;
- derived variants;
- evidence-based content;
- approval gates;
- publication journal;
- website staging;
- safe production deployment;
- provenance;
- platform capability matrix.

Из DPmeta

Брать:

- contract model;
- project registry;
- ecosystem graph;
- cross-repository references;
- synchronization queue;
- architecture consistency.

Из DP-Verify

Брать:

- static/affected/full;
- mandatory checks;
- profiles;
- baseline;
- scope;
- report;
- explainability;
- self-tests.

Из DPlearn

Брать:

- evidence-first competence model;
- computed mastery;
- error loop;
- delayed verification;
- explicit uncertainty.

Из DPprofile

Брать:

- fact registry;
- source references;
- historical reconstruction;
- timeline;
- claims derived from facts.

Из DP_game_market_analytic_system

Брать:

- append-only observations;
- point-in-time correctness;
- unknown ≠ zero;
- conflicts;
- strict schema;
- deterministic analytics;
- versioned rules;
- explainability.

Из DPlang

Брать:

- strict compiler layering;
- deterministic output;
- explicit IR;
- versioning;
- reproducible builds;
- language-server principle;
- toolchain separation;
- native boundary.

Из DP_Repeated_Tasks

Брать:

- hierarchical tasks;
- execution history;
- elapsed time;
- robust local persistence;
- atomic writes.

Из DP_TextFile_Sorter

Брать:

- semantic classification;
- hierarchical category profiles;
- multiple independent signals;
- confidence/ambiguity;
- dry-run;
- safe file operations;
- hybrid classifiers.

---

83. Что НЕ делать

Запрещается:

- собирать весь проект в один giant service;
- делать прямые вызовы между UI и storage;
- делать cross-domain database writes без owner;
- дублировать одну бизнес-логику в двух доменах;
- скрывать ошибки;
- считать неизвестное успешным;
- писать AI-generated conclusions прямо как facts;
- сохранять secrets в plain text;
- использовать clipboard как скрытый transport;
- автоматически публиковать без approval policy;
- автоматически деплоить без validation;
- использовать scraping как основной integration mechanism;
- строить критические функции на undocumented private APIs;
- создавать второй parser/аналитический движок, если уже существует canonical implementation;
- копировать исходный код проекта в несколько модулей.

---

84. Originality / provenance

Каждая перенесённая subsystem implementation должна иметь запись:

source_project
source_revision
source_component
migration_type
semantic_equivalence
known_differences
tests

Типы:

PORT
ADAPT
REWRITE
WRAPPER
REFERENCE_ONLY

Это позволяет отличать:

перенос реализации

от:

архитектурное заимствование идеи

---

85. Testing architecture

Каждый домен:

unit
integration
contract
regression
property
fuzz

Система в целом:

full ecosystem test

Проверять:

- storage migrations;
- event consistency;
- connector mappings;
- permissions;
- secrets;
- approval engine;
- task dependencies;
- backup/restore;
- offline mode;
- crash recovery;
- concurrent actions;
- idempotence;
- deterministic output.

---

86. Contract tests

Каждая connector integration обязана иметь:

input fixture
expected normalized result
error fixtures
capability fixtures
rate-limit fixtures
auth fixtures

Нельзя тестировать только успешный happy path.

---

87. Property tests

Особенно:

classification
routing
task DAG
analytics
storage transactions
schema migration
sorting
normalization
hashing
event ordering

---

88. Fuzzing

Fuzz:

- Markdown;
- YAML;
- JSON;
- repository metadata;
- AI result envelopes;
- connector responses;
- config;
- import formats;
- task graphs;
- event streams.

Цель:

never crash
never silently corrupt
never silently accept invalid structure

---

89. Device testing

Обязательно тестировать:

- process death;
- low memory;
- network loss;
- airplane mode;
- background restrictions;
- Android reboot;
- permissions;
- biometric unlock;
- VPN lifecycle;
- storage migration;
- backup restore;
- long-running AI operation;
- large vault;
- large repository registry.

---

90. Performance budgets

Для каждого major domain создать бюджет:

startup
memory
CPU
I/O
network
database
index rebuild
search
rendering
AI orchestration overhead

Пороги должны приниматься по замерам.

---

91. Принцип «проверить → сделать или не трогать»

Перед любой задачей:

already exists?
  ↓
yes → extend
no  → implement

Нельзя создавать вторую реализацию существующей функции только потому, что новый модуль «удобнее».

---

92. Workflow разработки

Для любой задачи крупнее trivial:

1. Read DECISIONS
2. Read architecture
3. Check TODO
4. Define task contract
5. Implement smallest coherent part
6. Add tests
7. Run targeted checks
8. Run broader checks
9. Update docs
10. Commit
11. Push
12. Update PR

---

93. Branching

Основная модель:

main
  ↑
feature/*
fix/*
research/*

Каждая самостоятельная работа:

branch
→ PR
→ verification
→ merge

"main" должен оставаться зелёным.

---

94. DPmetaApp и DPmeta

Разделение:

DPmeta
  = governance / cross-repository knowledge

DPmetaApp
  = executable runtime of ecosystem

DPmeta не должен становиться runtime dependency приложения.

DPmetaApp должен использовать:

DPmeta concepts

через собственные versioned contracts.

---

95. Runtime registry

Подсистемы регистрируются:

SystemModule {
    id
    name
    version
    owner
    capabilities
    dataOwners
    dependencies
    healthCheck
    dashboardProvider
    taskProvider
    metricProvider
    searchProvider
}

Добавление нового проекта должно быть plugin-like.

---

96. System lifecycle

Каждый domain module должен иметь:

REGISTERED
INITIALIZING
READY
DEGRADED
BLOCKED
DISABLED
FAILED

---

97. Ecosystem health score

Не делать один «магический score».

Разделить:

technical
engineering
operational
data
security
AI
knowledge
product
market
personal

Каждый имеет отдельные evidence.

---

98. Unified reports

Поддержать:

Daily ecosystem report
Development report
Security report
AI report
Knowledge report
Learning report
Market report
Content report
Financial/monetization report
Network report

Все отчёты строятся из текущего local state.

---

99. Daily operating mode

Отдельный экран:

TODAY

Critical
Attention
Active work
AI sessions
Repeated tasks
Learning
Development
Content
Research

Задача интерфейса — уменьшить переключение между системами.

---

100. Personal command center

Главная цель:

не открыть десять инструментов,
а открыть один инструмент,
который уже знает состояние всех десяти.

---

101. Первоначальная версия

Не начинать с визуальной оболочки всех функций.

Порядок вертикальных срезов:

V0 — Platform Core

- storage;
- entity model;
- events;
- diagnostics;
- security;
- registry;
- command bus.

V1 — Ecosystem

- projects;
- repositories;
- DPmeta;
- GitHub;
- cross-links;
- TODO;
- decisions.

V2 — Verification

- DP-Verify;
- audits;
- reports;
- health.

V3 — Knowledge

- Obsidian;
- sorter;
- search;
- semantic index.

V4 — AI

- Claude accounts;
- API;
- remote Claude Code;
- context bundles;
- sessions;
- handoff;
- orchestration.

V5 — DPpass

- VPN;
- profiles;
- adaptive state;
- network health.

V6 — Content + Site

- SocialMediaImage;
- content;
- publication;
- website;
- deployment.

V7 — Learning + Tasks

- DPlearn;
- repeated tasks;
- unified task engine.

V8 — Analytics

- market;
- monetization;
- profile;
- metrics;
- visualization.

V9 — DPlang IDE

- editor;
- compiler;
- language server;
- debugger/toolchain.

V10 — Full ecosystem integration

- cross-domain event flows;
- unified dashboards;
- automation;
- ecosystem-wide queries;
- dependency-aware orchestration.

---

102. Definition of Done for the integrated product

DPmetaApp можно считать функционально завершённым только когда:

all registered systems have native domain modules
AND
all major source capabilities are represented
AND
cross-domain contracts work
AND
local persistence is transactional
AND
backup/restore is tested
AND
verification is integrated
AND
Claude orchestration works through supported interfaces
AND
website control works through supported deployment path
AND
DPlang IDE works with the actual compiler/toolchain
AND
DPpass works as network subsystem
AND
all major data types are searchable
AND
all major outputs are reproducible
AND
destructive actions are approval-gated
AND
full test suite is green
AND
device tests pass
AND
security audit passes

---

103. Главный критерий качества

DPmetaApp не должен быть просто большим приложением.

Его качество определяется тем, может ли он ответить на вопрос:

««Что сейчас происходит во всей моей экосистеме, почему это происходит, какие данные это доказывают, что зависит от чего, что можно сделать автоматически, а где обязательно требуется моё решение?»»

Если на этот вопрос система отвечает однозначно и проверяемо, архитектура достигла цели.

---

104. Итоговое архитектурное решение

DPmetaApp должен стать:

                    dpsmpx Ecosystem Runtime
                              │
        ┌─────────────────────┼────────────────────┐
        │                     │                    │
   Knowledge              Development             AI
        │                     │                    │
   Obsidian                 GitHub              Claude
   Sorter                   Projects            Code
   Facts                    Verify              Context
        │                     │                    │
        ├─────────────────────┼────────────────────┤
        │                     │                    │
    Analytics             Operations            Content
        │                     │                    │
      Market               Site               Publishing
      Metrics              Backup             Social
      Profile              Tasks
        │
        ├───────────────────────────────────────────
        │
    Personal Systems
        │
      DPlearn
      RepeatedTasks
      Monetization
      Visualization
        │
        ├───────────────────────────────────────────
        │
    Engineering Runtime
        │
      DPpass
      DPlang
      DPlang IDE
      DP-Verify
      DPmeta

Это не набор интеграций.

Это единая операционная среда экосистемы dpsmpx.

---

105. Final architectural rule

«Every subsystem must remain independently understandable, but no subsystem should be forced to remain isolated when a typed contract can make its knowledge, state or capability useful to the rest of the ecosystem.»

DPmetaApp должен объединить существующие системы на уровне:

data
contracts
events
capabilities
evidence
automation

а не просто на уровне UI.