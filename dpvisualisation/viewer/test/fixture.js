// Синтетические модель и срез для тестов просмотрщика (не данные экосистемы).

export function model() {
  return {
    schema: "dpvis-model/1",
    title: "Тест",
    as_of: "2026-10-01",
    layers: [
      { id: "actors", name: "Участники" },
      { id: "product", name: "Продукты" },
      { id: "infra", name: "Инфраструктура" },
    ],
    catalog: {
      drivers: [{ id: "human", name: "решает автор" }, { id: "ai", name: "ИИ" }, { id: "check", name: "проверка" }, { id: "data", name: "данные" }],
      flow_kinds: [{ id: "work", name: "работа", driver: "ai" }, { id: "decision", name: "решение", driver: "human" }, { id: "host", name: "где живёт", driver: "data" }],
      node_kinds: [{ id: "repo", name: "репозиторий" }, { id: "actor", name: "участник" }, { id: "service", name: "сервис" }],
      statuses: [{ id: "active", name: "работает" }, { id: "planned", name: "план" }],
      transports: [{ id: "git", name: "git" }],
      visibility: [{ id: "public", name: "открытый" }, { id: "private", name: "закрытый" }, { id: "undisclosed", name: "закрытый, имя не публикуется" }],
      groups: [{ id: "core", name: "ядро" }],
    },
    nodes: [
      { id: "author", kind: "actor", layer: "actors", name: "Автор", status: "active", visibility: "private" },
      { id: "bot", kind: "actor", layer: "actors", name: "Сессии", status: "active", visibility: "private" },
      { id: "alpha", kind: "repo", layer: "product", name: "Alpha", status: "active", visibility: "private", group: "core" },
      { id: "beta", kind: "repo", layer: "product", name: "Beta", status: "active", visibility: "undisclosed", group: "core" },
      { id: "srv", kind: "service", layer: "infra", name: "Сервис", status: "planned", visibility: "public" },
    ],
    flows: [
      { id: "work.alpha", from: "bot", to: "alpha", kind: "work", driver: "ai", status: "active", transport: "git", what: "коммиты" },
      { id: "work.beta", from: "bot", to: "beta", kind: "work", driver: "ai", status: "active", transport: "git", what: "коммиты" },
      { id: "decide.alpha", from: "author", to: "alpha", kind: "decision", driver: "human", status: "active", transport: "git", what: "решения" },
      { id: "host.srv", from: "alpha", to: "srv", kind: "host", driver: "data", status: "planned", transport: "git", what: "план" },
      { id: "dangling", from: "alpha", to: "nope", kind: "host", driver: "data", status: "active", transport: "git", what: "битая ссылка" },
    ],
  };
}

const d = (arr) => [...new Array(10 - arr.length).fill(0), ...arr];

export function state() {
  return {
    schema: "dpvis-state/1",
    as_of: "2026-10-06T12:00:00Z",
    window: { start: "2026-09-27", end: "2026-10-06", days: 10, timezone: "+03:00" },
    rules: { rate_days: 7 },
    nodes: {
      author: { activity: { state: "working", last: "2026-10-06T10:00:00Z" }, days: d([1, 0, 2]) },
      bot: { activity: { state: "working", last: "2026-10-06T11:00:00Z" }, days: d([5, 4, 3]) },
      alpha: {
        activity: { state: "working", last: "2026-10-06T11:00:00Z" },
        git: {
          days: { ai: d([5, 4, 3]), human: d([1, 0, 2]), merges: d([1, 1, 1]) },
          first_time: "2026-09-30T08:00:00Z",
          head_time: "2026-10-06T11:00:00Z",
          branches: [{ name: "claude/x", ahead: 2, time: "2026-10-06T09:00:00Z" }],
          window: { ai: 12, human: 3, merges: 3 },
          history: "complete",
        },
      },
      beta: { activity: { state: "unknown", last: null } },
      srv: { activity: { state: "planned", last: null } },
    },
    flows: {
      "work.alpha": { state: "flowing", days: d([5, 4, 3]), week: 12, total: 12, last: "2026-10-06T11:00:00Z" },
      "work.beta": { state: "unknown" },
      "decide.alpha": { state: "quiet", days: d([0, 0, 0]), week: 0, total: 0, last: null },
      "host.srv": { state: "planned" },
    },
    diagnostics: [{ code: "CLONE_NOT_FOUND", subject: "beta", message: "нет клона" }],
  };
}
