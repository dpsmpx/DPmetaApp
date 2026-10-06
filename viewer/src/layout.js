// Раскладка графа (F-21): узлы по слоям — колонки слева направо; порядок в колонке —
// по среднему положению соседей (меньше пересечений); у каждого потока — своё место
// выхода и входа на узле; потоки — кубические кривые. Раскладка не зависит от фильтров:
// при фильтрации узлы не прыгают.

export const GEOM = { colW: 178, nodeW: 148, nodeH: 42, gapY: 24, headerH: 40, pad: 18 };

const GROUP_ORDER = { core: 0, new: 1, periphery: 2 };

function degree(view, id) {
  return (view.outgoing.get(id) || []).length + (view.incoming.get(id) || []).length;
}

export function layout(view, g = GEOM) {
  const cols = view.layers
    .map((l) => ({ layer: l, nodes: view.nodes.filter((n) => n.layer === l.id) }))
    .filter((c) => c.nodes.length > 0);
  const stray = view.nodes.filter((n) => !view.layers.some((l) => l.id === n.layer));
  if (stray.length) cols.push({ layer: { id: "other", name: "Прочее" }, nodes: stray });

  // начальный порядок: группа, затем связность, затем имя
  for (const c of cols) {
    c.nodes.sort(
      (a, b) =>
        (GROUP_ORDER[a.group] ?? 3) - (GROUP_ORDER[b.group] ?? 3) ||
        degree(view, b.id) - degree(view, a.id) ||
        a.name.localeCompare(b.name, "ru"),
    );
  }
  const colOf = new Map();
  cols.forEach((c, i) => c.nodes.forEach((n) => colOf.set(n.id, i)));
  const maxRows = Math.max(...cols.map((c) => c.nodes.length));
  const fullH = maxRows * (g.nodeH + g.gapY) - g.gapY;

  const yOf = new Map();
  const place = () => {
    for (const c of cols) {
      const h = c.nodes.length * (g.nodeH + g.gapY) - g.gapY;
      const top = g.pad + g.headerH + (fullH - h) / 2;
      c.nodes.forEach((n, i) => yOf.set(n.id, top + i * (g.nodeH + g.gapY)));
    }
  };
  place();
  // барицентр: несколько проходов слева направо и обратно
  for (let pass = 0; pass < 6; pass++) {
    for (const c of pass % 2 ? [...cols].reverse() : cols) {
      const bc = new Map();
      for (const n of c.nodes) {
        const nb = [...view.outgoing.get(n.id), ...view.incoming.get(n.id)]
          .map((f) => (f.from === n.id ? f.to : f.from))
          .filter((id) => colOf.get(id) !== colOf.get(n.id));
        bc.set(n.id, nb.length ? nb.reduce((s, id) => s + yOf.get(id), 0) / nb.length : yOf.get(n.id));
      }
      c.nodes.sort((a, b) => bc.get(a.id) - bc.get(b.id) || a.name.localeCompare(b.name, "ru"));
    }
    place();
  }

  const nodes = new Map();
  const columns = cols.map((c, i) => {
    const x = g.pad + i * g.colW;
    for (const n of c.nodes) nodes.set(n.id, { x, y: yOf.get(n.id), w: g.nodeW, h: g.nodeH, col: i });
    return { id: c.layer.id, name: c.layer.name, x, w: g.nodeW };
  });

  // стороны: вперёд — справа налево-внутрь, назад — слева, в одной колонке — петля справа
  const sides = new Map(); // flowId → {from: "r"|"l", to: "l"|"r"}
  const ports = new Map(); // `${nodeId}:${side}` → [{flowId, otherY, end}]
  const addPort = (nid, side, flowId, otherY, end) => {
    const k = `${nid}:${side}`;
    if (!ports.has(k)) ports.set(k, []);
    ports.get(k).push({ flowId, otherY, end });
  };
  for (const f of view.flows) {
    const a = nodes.get(f.from);
    const b = nodes.get(f.to);
    if (!a || !b) continue;
    let s;
    if (b.col > a.col) s = { from: "r", to: "l" };
    else if (b.col < a.col) s = { from: "l", to: "r" };
    else s = { from: "r", to: "r" };
    sides.set(f.id, s);
    addPort(f.from, s.from, f.id, b.y, "from");
    addPort(f.to, s.to, f.id, a.y, "to");
  }
  const portY = new Map(); // `${flowId}:${end}` → y
  for (const [k, list] of ports) {
    const nid = k.slice(0, k.lastIndexOf(":"));
    const n = nodes.get(nid);
    list.sort((p, q) => p.otherY - q.otherY || (p.flowId < q.flowId ? -1 : 1));
    const step = (n.h - 8) / (list.length + 1);
    list.forEach((p, i) => portY.set(`${p.flowId}:${p.end}`, n.y + 4 + step * (i + 1)));
  }

  const flows = new Map();
  for (const f of view.flows) {
    const s = sides.get(f.id);
    if (!s) continue;
    const a = nodes.get(f.from);
    const b = nodes.get(f.to);
    const x1 = s.from === "r" ? a.x + a.w : a.x;
    const x2 = s.to === "r" ? b.x + b.w : b.x;
    const y1 = portY.get(`${f.id}:from`);
    const y2 = portY.get(`${f.id}:to`) - 0;
    let c1x, c2x;
    if (s.from === "r" && s.to === "l") {
      const dx = Math.max(36, (x2 - x1) * 0.45);
      c1x = x1 + dx;
      c2x = x2 - dx;
    } else if (s.from === "l" && s.to === "r") {
      const dx = Math.max(36, (x1 - x2) * 0.45);
      c1x = x1 - dx;
      c2x = x2 + dx;
    } else {
      const bulge = 28 + Math.min(60, Math.abs(y2 - y1) * 0.35);
      c1x = x1 + bulge;
      c2x = x2 + bulge;
    }
    const d = `M${r(x1)},${r(y1)} C${r(c1x)},${r(y1)} ${r(c2x)},${r(y2)} ${r(x2)},${r(y2)}`;
    flows.set(f.id, { d, x1, y1, x2, y2, c1x, c2x });
  }

  const width = g.pad * 2 + (cols.length - 1) * g.colW + g.nodeW + 40;
  const height = g.pad * 2 + g.headerH + fullH;
  return { width, height, nodes, flows, columns, geom: g };
}

function r(v) {
  return Math.round(v * 10) / 10;
}

// Точка кубической кривой при t ∈ [0, 1] — для частиц без обращения к DOM.
export function bezierPoint(fl, t) {
  const u = 1 - t;
  const x = u * u * u * fl.x1 + 3 * u * u * t * fl.c1x + 3 * u * t * t * fl.c2x + t * t * t * fl.x2;
  const y = u * u * u * fl.y1 + 3 * u * u * t * fl.y1 + 3 * u * t * t * fl.y2 + t * t * t * fl.y2;
  return { x, y };
}

// Таблица равномерных по длине параметров t — частицы идут с постоянной скоростью.
export function arcTable(fl, samples = 48) {
  const pts = [];
  let len = 0;
  let prev = bezierPoint(fl, 0);
  pts.push({ t: 0, s: 0 });
  for (let i = 1; i <= samples; i++) {
    const t = i / samples;
    const p = bezierPoint(fl, t);
    len += Math.hypot(p.x - prev.x, p.y - prev.y);
    pts.push({ t, s: len });
    prev = p;
  }
  return { pts, len };
}

export function pointAtFraction(fl, table, frac) {
  const target = frac * table.len;
  const pts = table.pts;
  let lo = 0;
  let hi = pts.length - 1;
  while (hi - lo > 1) {
    const mid = (lo + hi) >> 1;
    if (pts[mid].s < target) lo = mid;
    else hi = mid;
  }
  const a = pts[lo];
  const b = pts[hi];
  const k = b.s === a.s ? 0 : (target - a.s) / (b.s - a.s);
  return bezierPoint(fl, a.t + (b.t - a.t) * k);
}
