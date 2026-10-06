// Данные просмотрщика: модель (dpvis-model/1) + срез (dpvis-state/1) или публичная
// сборка (dpvis-public/1, D-15) → одно представление для экрана. Чистые функции — тесты node.

import { sum, windowDays } from "./format.js";

export const MODEL_SCHEMA = "dpvis-model/1";
export const STATE_SCHEMA = "dpvis-state/1";
export const PUBLIC_SCHEMA = "dpvis-public/1";

export class DataError extends Error {}

// Разобрать загруженный документ (F-37, F-38): что это — модель, срез или публичная сборка.
export function classify(doc) {
  if (!doc || typeof doc !== "object" || Array.isArray(doc)) {
    throw new DataError("файл не похож на данные DPvisualisation: ждали объект JSON");
  }
  if (doc.schema === PUBLIC_SCHEMA) {
    if (!doc.model || !Array.isArray(doc.model.nodes)) throw new DataError("в публичной сборке нет модели");
    return { model: doc.model, state: doc.state || null, public: true };
  }
  if (doc.schema === MODEL_SCHEMA) {
    if (!Array.isArray(doc.nodes) || !Array.isArray(doc.flows)) throw new DataError("в модели нет узлов или потоков");
    return { model: doc };
  }
  if (doc.schema === STATE_SCHEMA) {
    if (!doc.nodes || !doc.flows) throw new DataError("в срезе нет узлов или потоков");
    return { state: doc };
  }
  throw new DataError(`неподдерживаемый формат: schema = ${JSON.stringify(doc.schema ?? null)}; ждали dpvis-model/1, dpvis-state/1 или dpvis-public/1`);
}

function index(list) {
  const out = new Map();
  for (const x of list || []) out.set(x.id, x);
  return out;
}

function zeros(n) {
  return new Array(n).fill(0);
}

function fit(arr, n) {
  if (!Array.isArray(arr)) return zeros(n);
  if (arr.length === n) return arr.slice();
  if (arr.length > n) return arr.slice(arr.length - n);
  return zeros(n - arr.length).concat(arr);
}

function lastN(arr, k) {
  return sum(arr.slice(Math.max(0, arr.length - k)));
}

// ISO момента или даты → индекс дня окна; до окна — -1, после — days.
export function dayIndex(iso, days) {
  if (!iso) return -1;
  const d = iso.slice(0, 10);
  const i = days.indexOf(d);
  if (i >= 0) return i;
  return d < days[0] ? -1 : days.length;
}

// Ветки с невлитой работой: в полной модели — список, в публичной — число (D-15).
function branchInfo(git) {
  if (!git) return { count: 0, list: [], known: false };
  if (Array.isArray(git.branches)) return { count: git.branches.length, list: git.branches, known: true };
  if (typeof git.branches === "number") return { count: git.branches, list: [], known: true };
  return { count: 0, list: [], known: false }; // публичная выжимка — веток нет (D-15)
}

export function normalize(model, state = null, opts = {}) {
  if (!model) throw new DataError("нет модели");
  const nDays = state?.window?.days || 30;
  const days = state?.window?.start ? windowDays(state.window.start, nDays) : windowDays(endMinus(nDays), nDays);
  const rateDays = state?.rules?.rate_days || 7;
  const cat = model.catalog || {};
  const layers = (model.layers || []).map((l, i) => ({ ...l, index: i }));
  const layerIndex = new Map(layers.map((l) => [l.id, l.index]));

  const nodes = (model.nodes || []).map((n) => {
    const st = state?.nodes?.[n.id] || null;
    const git = st?.git || null;
    const node = {
      ...n,
      layerIndex: layerIndex.has(n.layer) ? layerIndex.get(n.layer) : layers.length,
      activity: st?.activity?.state || (state ? "unknown" : "nodata"),
      last: st?.activity?.last || null,
      reasons: st?.activity?.reasons || [],
      git: null,
      daysAi: zeros(nDays),
      daysHuman: zeros(nDays),
      daysMerges: zeros(nDays),
      firstIndex: -1,
      branches: { count: 0, list: [], known: false },
      week: 0,
      windowTotal: 0,
    };
    if (git) {
      node.git = git;
      node.daysAi = fit(git.days?.ai, nDays);
      node.daysHuman = fit(git.days?.human, nDays);
      node.daysMerges = fit(git.days?.merges, nDays);
      node.firstIndex = dayIndex(git.first_time || git.first_date, days);
      node.branches = branchInfo(git);
      node.windowTotal = (git.window?.ai || 0) + (git.window?.human || 0);
    } else if (Array.isArray(st?.days)) {
      // участник: события по дням
      node.daysHuman = n.id === "author" ? fit(st.days, nDays) : zeros(nDays);
      node.daysAi = n.id === "author" ? zeros(nDays) : fit(st.days, nDays);
      node.windowTotal = sum(st.days);
    }
    node.daysTotal = node.daysAi.map((v, i) => v + node.daysHuman[i]);
    node.week = lastN(node.daysTotal, rateDays);
    return node;
  });
  const nodeById = index(nodes);

  const flows = [];
  for (const f of model.flows || []) {
    if (!nodeById.has(f.from) || !nodeById.has(f.to)) continue;
    const st = state?.flows?.[f.id] || null;
    const fdays = fit(st?.days, nDays);
    flows.push({
      ...f,
      state: st?.state || (state ? "unknown" : f.status === "active" ? "nodata" : f.status),
      days: fdays,
      week: st?.week ?? lastN(fdays, rateDays),
      total: st?.total ?? sum(fdays),
      lastEvent: st?.last || null,
    });
  }
  const flowById = index(flows);
  const outgoing = new Map(nodes.map((n) => [n.id, []]));
  const incoming = new Map(nodes.map((n) => [n.id, []]));
  for (const f of flows) {
    outgoing.get(f.from).push(f);
    incoming.get(f.to).push(f);
  }

  return {
    title: model.title || "Экосистема",
    modelAsOf: model.as_of || null,
    asOf: state?.as_of || null,
    hasState: !!state,
    public: !!opts.public,
    days,
    rateDays,
    rules: state?.rules || null,
    layers,
    catalog: {
      nodeKinds: index(cat.node_kinds),
      flowKinds: index(cat.flow_kinds),
      drivers: index(cat.drivers),
      statuses: index(cat.statuses),
      transports: index(cat.transports),
      visibility: index(cat.visibility),
      groups: index(cat.groups),
    },
    catalogLists: cat,
    nodes,
    flows,
    nodeById,
    flowById,
    outgoing,
    incoming,
    hasBranches: nodes.some((n) => n.branches.known),
    diagnostics: state?.diagnostics || null,
    registry: state?.registry || null,
    hidden: model.hidden || null,
  };
}

function endMinus(n) {
  const d = new Date(Date.now() - (n - 1) * 86400000);
  return d.toISOString().slice(0, 10);
}

// Состояние потока в выбранный день шкалы времени (F-28): событие в этот день — «идёт».
export function flowStateAt(flow, dayIdx) {
  if (dayIdx === null || dayIdx === undefined) return flow.state;
  if (["planned", "blocked", "off"].includes(flow.state)) return flow.state;
  if (flow.state === "unobserved" || flow.state === "unknown" || flow.state === "nodata") return flow.state;
  return flow.days[dayIdx] > 0 ? "flowing" : "quiet";
}

export function flowRateAt(flow, dayIdx, rateDays = 7) {
  if (dayIdx === null || dayIdx === undefined) return flow.week;
  return flow.days[dayIdx] * rateDays; // день в масштабе недели — та же шкала частиц
}

export function nodeActivityAt(node, dayIdx) {
  if (dayIdx === null || dayIdx === undefined) return node.activity;
  if (node.kind === "repo" || node.kind === "actor") {
    if (!node.git && node.kind === "repo") return node.activity;
    return node.daysTotal[dayIdx] > 0 ? "working" : "idle";
  }
  return node.activity;
}

// Узел существует в выбранный день: у репозитория первый коммит не позже дня (F-28).
export function nodeExistsAt(node, dayIdx) {
  if (dayIdx === null || dayIdx === undefined) return true;
  if (node.kind !== "repo" || !node.git) return true;
  return node.firstIndex <= dayIdx;
}
