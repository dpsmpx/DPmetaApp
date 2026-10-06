// Фильтры, выбор и адрес вида (F-26, F-27, F-28): состояние ↔ строка «#…». Чистые функции.

import { nodeExistsAt, flowStateAt } from "./data.js";

export const FLOW_STATES = ["flowing", "quiet", "planned", "blocked", "off", "unknown", "unobserved"];

export const STATE_NAMES = {
  flowing: "идёт",
  quiet: "тихо",
  planned: "план",
  blocked: "заблокирован",
  off: "выключен",
  unknown: "неизвестно",
  unobserved: "не наблюдается",
  nodata: "нет данных",
  working: "работает",
  idle: "без работы",
  up: "отвечает",
  down: "не отвечает",
};

export function defaults() {
  return { sel: null, kinds: null, states: null, layers: null, q: "", a7: false, day: null, tab: "graph", show: false };
}

function setParam(list) {
  return list ? [...list].sort().join(",") : null;
}

export function encode(f) {
  const p = [];
  if (f.sel) p.push(["sel", f.sel]);
  if (f.kinds) p.push(["fk", setParam(f.kinds)]);
  if (f.states) p.push(["st", setParam(f.states)]);
  if (f.layers) p.push(["ly", setParam(f.layers)]);
  if (f.q) p.push(["q", f.q]);
  if (f.a7) p.push(["a7", "1"]);
  if (f.day !== null && f.day !== undefined) p.push(["day", String(f.day)]);
  if (f.tab && f.tab !== "graph") p.push(["tab", f.tab]);
  if (f.show) p.push(["show", "1"]);
  return p.length ? "#" + p.map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join("&") : "";
}

export function decode(hash, view = null) {
  const f = defaults();
  const s = (hash || "").replace(/^#/, "");
  if (!s) return f;
  for (const part of s.split("&")) {
    const i = part.indexOf("=");
    if (i < 0) continue;
    const k = part.slice(0, i);
    let v;
    try {
      v = decodeURIComponent(part.slice(i + 1));
    } catch {
      continue;
    }
    const list = () => new Set(v.split(",").filter(Boolean));
    if (k === "sel" && /^[nf]:[\w.\-]+$/.test(v)) f.sel = v;
    else if (k === "fk") f.kinds = list();
    else if (k === "st") f.states = list();
    else if (k === "ly") f.layers = list();
    else if (k === "q") f.q = v.slice(0, 80);
    else if (k === "a7") f.a7 = v === "1";
    else if (k === "day" && /^\d+$/.test(v)) f.day = Number(v);
    else if (k === "tab" && ["graph", "tables", "recon"].includes(v)) f.tab = v;
    else if (k === "show") f.show = v === "1";
  }
  if (view) {
    if (f.day !== null && f.day >= view.days.length) f.day = null;
    if (f.sel) {
      const [t, id] = [f.sel[0], f.sel.slice(2)];
      if ((t === "n" && !view.nodeById.has(id)) || (t === "f" && !view.flowById.has(id))) f.sel = null;
    }
  }
  return f;
}

export function matches(node, q) {
  if (!q) return true;
  const s = q.trim().toLowerCase();
  return node.name.toLowerCase().includes(s) || node.id.toLowerCase().includes(s);
}

// Что видно при фильтрах: {nodes: Set id, flows: Set id}. Поиск не скрывает, а приглушает (dimmed).
export function visible(view, f) {
  const nodes = new Set();
  for (const n of view.nodes) {
    if (f.layers && !f.layers.has(n.layer)) continue;
    if (!nodeExistsAt(n, f.day)) continue;
    nodes.add(n.id);
  }
  const flows = new Set();
  for (const fl of view.flows) {
    if (!nodes.has(fl.from) || !nodes.has(fl.to)) continue;
    if (f.kinds && !f.kinds.has(fl.kind)) continue;
    const st = flowStateAt(fl, f.day);
    if (f.states && !f.states.has(st)) continue;
    if (f.a7) {
      const active = f.day === null || f.day === undefined ? fl.week > 0 : fl.days[f.day] > 0;
      if (!active) continue;
    }
    flows.add(fl.id);
  }
  if (f.a7) {
    // «только с активностью»: узлы без работы и без видимых потоков — убрать
    for (const id of [...nodes]) {
      const n = view.nodeById.get(id);
      const own = f.day === null || f.day === undefined ? n.week > 0 : n.daysTotal[f.day] > 0;
      const linked = [...view.outgoing.get(id), ...view.incoming.get(id)].some((x) => flows.has(x.id));
      if (!own && !linked) nodes.delete(id);
    }
  }
  return { nodes, flows };
}

// Фокус выбора (F-26): выбранный и его соседи; для потока — он и его концы.
export function focusSet(view, sel) {
  if (!sel) return null;
  const id = sel.slice(2);
  const nodes = new Set();
  const flows = new Set();
  if (sel[0] === "n") {
    nodes.add(id);
    for (const f of [...(view.outgoing.get(id) || []), ...(view.incoming.get(id) || [])]) {
      flows.add(f.id);
      nodes.add(f.from);
      nodes.add(f.to);
    }
  } else {
    const f = view.flowById.get(id);
    if (f) {
      flows.add(f.id);
      nodes.add(f.from);
      nodes.add(f.to);
    }
  }
  return { nodes, flows };
}
