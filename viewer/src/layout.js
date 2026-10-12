// Раскладка схемы (D-16): от ширины контейнера, единица схемы — пиксель экрана, поэтому
// текст не мельчает и прокрутки вбок нет. Широко — слои колонками слева направо; узко —
// слои полосами сверху вниз, узлы в полосе — сеткой по ширине. Связь — прямой отрезок от
// края узла до края узла; несколько связей одной пары идут рядом, а не друг по другу.
// Раскладка не зависит от фильтров: при фильтрации узлы не прыгают.

export const GEOM = { minNodeW: 136, maxNodeW: 190, nodeH: 44, gapX: 16, gapY: 16, headerH: 28, pad: 12, bandGap: 14, minWidth: 240 };

const GROUP_ORDER = { control: 0, core: 1, sync: 2, support: 3, new: 4, periphery: 5 };

function degree(view, id) {
  return (view.outgoing.get(id) || []).length + (view.incoming.get(id) || []).length;
}

function groupsByLayer(view) {
  const cols = view.layers
    .map((l) => ({ layer: l, nodes: view.nodes.filter((n) => n.layer === l.id) }))
    .filter((c) => c.nodes.length > 0);
  const stray = view.nodes.filter((n) => !view.layers.some((l) => l.id === n.layer));
  if (stray.length) cols.push({ layer: { id: "other", name: "Прочее" }, nodes: stray });
  for (const c of cols) {
    c.nodes.sort(
      (a, b) =>
        (GROUP_ORDER[a.group] ?? 4) - (GROUP_ORDER[b.group] ?? 4) ||
        degree(view, b.id) - degree(view, a.id) ||
        a.name.localeCompare(b.name, "ru"),
    );
  }
  return cols;
}

// Подпись слоя в колонку: длинная — в две строки по пробелу у середины. Ширина
// знака — с запасом под прописные 12 px с разрядкой (style.css, .col-name).
export function splitHeader(name, width) {
  const max = Math.max(6, Math.floor((width - 10) / 8.4));
  if (name.length <= max || !name.includes(" ")) return [name];
  const mid = name.length / 2;
  let best = -1;
  for (let i = 0; i < name.length; i++) if (name[i] === " " && (best < 0 || Math.abs(i - mid) < Math.abs(best - mid))) best = i;
  return [name.slice(0, best), name.slice(best + 1)];
}

function neighbours(view, id) {
  return [...(view.outgoing.get(id) || []), ...(view.incoming.get(id) || [])].map((f) => (f.from === id ? f.to : f.from));
}

// Колонки: порядок в колонке — по среднему положению соседей в других колонках
// (меньше пересечений), несколько проходов туда и обратно.
function columns(view, cols, W, g) {
  const colW = (W - 2 * g.pad) / cols.length;
  const nodeW = Math.min(g.maxNodeW, Math.floor(colW - g.gapX));
  const colOf = new Map();
  cols.forEach((c, i) => c.nodes.forEach((n) => colOf.set(n.id, i)));
  const maxRows = Math.max(...cols.map((c) => c.nodes.length));
  const fullH = maxRows * (g.nodeH + g.gapY) - g.gapY;
  const lines = cols.map((c) => splitHeader(c.layer.name, colW));
  const top0 = g.pad + g.headerH + (lines.some((l) => l.length > 1) ? 14 : 0);
  const yOf = new Map();
  const place = () => {
    for (const c of cols) {
      const h = c.nodes.length * (g.nodeH + g.gapY) - g.gapY;
      const top = top0 + (fullH - h) / 2;
      c.nodes.forEach((n, i) => yOf.set(n.id, top + i * (g.nodeH + g.gapY)));
    }
  };
  place();
  for (let pass = 0; pass < 6; pass++) {
    for (const c of pass % 2 ? [...cols].reverse() : cols) {
      const bc = new Map();
      for (const n of c.nodes) {
        const nb = neighbours(view, n.id).filter((id) => colOf.has(id) && colOf.get(id) !== colOf.get(n.id));
        bc.set(n.id, nb.length ? nb.reduce((s, id) => s + yOf.get(id), 0) / nb.length : yOf.get(n.id));
      }
      c.nodes.sort((a, b) => bc.get(a.id) - bc.get(b.id) || a.name.localeCompare(b.name, "ru"));
    }
    place();
  }
  const nodes = new Map();
  const headers = cols.map((c, i) => {
    const cx = g.pad + i * colW + colW / 2;
    for (const n of c.nodes) nodes.set(n.id, { x: Math.round(cx - nodeW / 2), y: Math.round(yOf.get(n.id)), w: nodeW, h: g.nodeH, band: i });
    return { id: c.layer.id, name: c.layer.name, lines: lines[i], x: Math.round(cx), y: g.pad + 12, anchor: "middle", w: colW };
  });
  return { nodes, headers, height: top0 + fullH + g.pad, nodeW };
}

// Полосы: слой — заголовок и сетка узлов по ширине; число узлов в ряду — сколько влезает.
function bands(view, cols, W, g) {
  const inner = W - 2 * g.pad;
  const perRow = Math.max(1, Math.floor((inner + g.gapX) / (g.minNodeW + g.gapX)));
  const nodeW = Math.min(g.maxNodeW * 1.4, Math.floor((inner - (perRow - 1) * g.gapX) / perRow));
  const nodes = new Map();
  const headers = [];
  let y = g.pad;
  cols.forEach((c, i) => {
    headers.push({ id: c.layer.id, name: c.layer.name, lines: [c.layer.name], x: g.pad, y: y + 12, anchor: "start", w: inner });
    y += g.headerH;
    c.nodes.forEach((n, j) => {
      const row = Math.floor(j / perRow);
      const col = j % perRow;
      nodes.set(n.id, { x: g.pad + col * (nodeW + g.gapX), y: y + row * (g.nodeH + g.gapY), w: nodeW, h: g.nodeH, band: i });
    });
    const rows = Math.ceil(c.nodes.length / perRow);
    y += rows * (g.nodeH + g.gapY) - g.gapY + g.bandGap;
  });
  return { nodes, headers, height: y - g.bandGap + g.pad, nodeW };
}

// Точка на краю прямоугольника по направлению от его центра (с зазором под стрелку).
export function edgePoint(box, dx, dy, gap = 3) {
  const cx = box.x + box.w / 2;
  const cy = box.y + box.h / 2;
  if (dx === 0 && dy === 0) return { x: cx, y: cy };
  const tx = dx === 0 ? Infinity : (box.w / 2 + gap) / Math.abs(dx);
  const ty = dy === 0 ? Infinity : (box.h / 2 + gap) / Math.abs(dy);
  const t = Math.min(tx, ty);
  return { x: cx + dx * t, y: cy + dy * t };
}

function r(v) {
  return Math.round(v * 10) / 10;
}

export function layout(view, width, g = GEOM) {
  const W = Math.max(g.minWidth, Math.floor(width || 0));
  const cols = groupsByLayer(view);
  const colW = (W - 2 * g.pad) / Math.max(1, cols.length);
  const mode = cols.length && colW >= g.minNodeW + g.gapX ? "columns" : "bands";
  const placed = cols.length ? (mode === "columns" ? columns(view, cols, W, g) : bands(view, cols, W, g)) : { nodes: new Map(), headers: [], height: 2 * g.pad, nodeW: g.minNodeW };

  // пары узлов: связи одной пары (в любую сторону) — параллельно, с шагом 5 px
  const pairs = new Map();
  for (const f of view.flows) {
    if (!placed.nodes.has(f.from) || !placed.nodes.has(f.to)) continue;
    const key = f.from < f.to ? `${f.from}|${f.to}` : `${f.to}|${f.from}`;
    if (!pairs.has(key)) pairs.set(key, []);
    pairs.get(key).push(f);
  }
  const flows = new Map();
  for (const list of pairs.values()) {
    list.sort((a, b) => (a.id < b.id ? -1 : 1));
    list.forEach((f, i) => {
      const a = placed.nodes.get(f.from);
      const b = placed.nodes.get(f.to);
      const ax = a.x + a.w / 2;
      const ay = a.y + a.h / 2;
      const dx = b.x + b.w / 2 - ax;
      const dy = b.y + b.h / 2 - ay;
      const len = Math.hypot(dx, dy) || 1;
      // смещение поперёк линии — одно и то же для пары, как бы ни была направлена связь
      const sign = f.from < f.to ? 1 : -1;
      const off = (i - (list.length - 1) / 2) * 5 * sign;
      const ox = (-dy / len) * off;
      const oy = (dx / len) * off;
      const p1 = edgePoint(a, dx, dy);
      const p2 = edgePoint(b, -dx, -dy);
      const x1 = p1.x + ox;
      const y1 = p1.y + oy;
      const x2 = p2.x + ox;
      const y2 = p2.y + oy;
      flows.set(f.id, { d: `M${r(x1)},${r(y1)} L${r(x2)},${r(y2)}`, x1, y1, x2, y2 });
    });
  }
  return { width: W, height: Math.ceil(placed.height), mode, nodes: placed.nodes, flows, headers: placed.headers, nodeW: placed.nodeW, geom: g };
}
