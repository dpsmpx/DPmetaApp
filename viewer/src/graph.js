// Граф в SVG (F-21…F-23, F-26, F-33): узлы, потоки, частицы, масштаб и сдвиг.
// Без встроенных стилей и обработчиков в разметке — работает под строгой CSP (D-05, D-15).

import { arcTable, pointAtFraction } from "./layout.js";
import { flowStateAt, flowRateAt, nodeActivityAt } from "./data.js";
import { STATE_NAMES } from "./filters.js";

const NS = "http://www.w3.org/2000/svg";
let instance = 0;

function el(tag, attrs = {}, parent = null) {
  const e = document.createElementNS(NS, tag);
  for (const [k, v] of Object.entries(attrs)) if (v !== null && v !== undefined) e.setAttribute(k, String(v));
  if (parent) parent.appendChild(e);
  return e;
}

// Подпись колонки: длинная — в две строки по ближайшему к середине пробелу.
export function splitHeader(name, max = 18) {
  if (name.length <= max || !name.includes(" ")) return [name];
  const mid = name.length / 2;
  let best = -1;
  for (let i = 0; i < name.length; i++) if (name[i] === " " && (best < 0 || Math.abs(i - mid) < Math.abs(best - mid))) best = i;
  return [name.slice(0, best), name.slice(best + 1)];
}

function short(name, max = 17) {
  return name.length > max ? name.slice(0, max - 1) + "…" : name;
}

// Частицы: число и скорость растут с событиями за неделю (F-22).
export function particlePlan(state, rate) {
  if (state !== "flowing" && state !== "quiet") return { n: 0, speed: 0 };
  if (state === "quiet" && rate <= 0) return { n: 1, speed: 14 };
  const n = Math.max(1, Math.min(6, 1 + Math.floor(Math.log2(1 + rate))));
  const speed = 26 + 10 * Math.log2(1 + rate);
  return { n, speed };
}

// Толщина линии при «меньше движения»: интенсивность без анимации (F-22).
export function strokeFor(state, rate) {
  if (state !== "flowing" && state !== "quiet") return 1.4;
  return Math.min(5, 1.4 + 0.7 * Math.log2(1 + rate));
}

export function createGraph(host, view, lay, handlers) {
  const id = `dpv${++instance}`;
  const reduced = () => handlers.reducedMotion();
  const svg = el("svg", {
    class: "dpvis-svg",
    viewBox: `0 0 ${lay.width} ${lay.height}`,
    preserveAspectRatio: "xMidYMid meet",
    role: "group",
    "aria-label": "Схема: узлы по слоям, потоки — кривые со стрелкой",
  });
  const defs = el("defs", {}, svg);
  for (const drv of ["human", "ai", "check", "data"]) {
    const m = el("marker", { id: `${id}-mk-${drv}`, viewBox: "0 0 10 10", refX: 9, refY: 5, markerWidth: 6, markerHeight: 6, orient: "auto-start-reverse" }, defs);
    el("path", { d: "M0,1 L9,5 L0,9 z", class: `mk drv-${drv}` }, m);
  }
  const bg = el("rect", { class: "dpvis-bg", x: -5000, y: -5000, width: 10000 + lay.width, height: 10000 + lay.height }, svg);
  const gCols = el("g", { class: "cols", "aria-hidden": "true" }, svg);
  for (const c of lay.columns) {
    const t = el("text", { x: c.x + c.w / 2, y: lay.geom.pad + 6, class: "col-name", "text-anchor": "middle" }, gCols);
    for (const [i, line] of splitHeader(c.name).entries()) {
      const s = el("tspan", { x: c.x + c.w / 2, dy: i ? 14 : 0 }, t);
      s.textContent = line;
    }
  }
  const gFlows = el("g", { class: "flows" }, svg);
  const gParticles = el("g", { class: "particles", "aria-hidden": "true" }, svg);
  const gNodes = el("g", { class: "nodes" }, svg);

  const flowEls = new Map();
  for (const f of view.flows) {
    const geo = lay.flows.get(f.id);
    if (!geo) continue;
    const g = el("g", { class: "flow", "data-id": f.id }, gFlows);
    const path = el("path", { d: geo.d, class: `flow-line drv-${f.driver}`, "marker-end": `url(#${id}-mk-${f.driver})` }, g);
    const hit = el("path", { d: geo.d, class: "flow-hit" }, g);
    const from = view.nodeById.get(f.from).name;
    const to = view.nodeById.get(f.to).name;
    const label = `${from} → ${to}: ${view.catalog.flowKinds.get(f.kind)?.name || f.kind}`;
    el("title", {}, hit).textContent = label;
    hit.addEventListener("click", (e) => {
      e.stopPropagation();
      if (!moved) handlers.select(`f:${f.id}`);
    });
    hit.addEventListener("pointerenter", (e) => handlers.hover({ type: "flow", item: f }, e));
    hit.addEventListener("pointermove", (e) => handlers.hover({ type: "flow", item: f }, e));
    hit.addEventListener("pointerleave", () => handlers.hover(null));
    flowEls.set(f.id, { g, path, hit, geo, table: arcTable(geo) });
  }

  const nodeEls = new Map();
  for (const n of view.nodes) {
    const p = lay.nodes.get(n.id);
    if (!p) continue;
    const g = el("g", { class: `node kind-${n.kind}`, transform: `translate(${p.x},${p.y})`, tabindex: 0, role: "button", "data-id": n.id }, gNodes);
    el("rect", { class: "node-ring", x: -4, y: -4, width: p.w + 8, height: p.h + 8, rx: 10 }, g);
    el("rect", { class: "node-box", width: p.w, height: p.h, rx: 7 }, g);
    const name = el("text", { class: "node-name", x: 9, y: 18 }, g);
    name.textContent = short(n.name);
    const sub = el("text", { class: "node-sub", x: 9, y: 33 }, g);
    const badge = el("g", { class: "badge", transform: `translate(${p.w - 2},2)` }, g);
    el("circle", { r: 9 }, badge);
    const bt = el("text", { "text-anchor": "middle", y: 4 }, badge);
    const q = el("text", { class: "unknown-mark", x: p.w - 13, y: 28, "text-anchor": "middle" }, g);
    q.textContent = "?";
    const title = el("title", {}, g);
    g.addEventListener("click", (e) => {
      e.stopPropagation();
      if (!moved) handlers.select(`n:${n.id}`);
    });
    g.addEventListener("keydown", (e) => {
      if (e.key === "Enter" || e.key === " ") {
        e.preventDefault();
        handlers.select(`n:${n.id}`);
      }
    });
    g.addEventListener("pointerenter", (e) => handlers.hover({ type: "node", item: n }, e));
    g.addEventListener("pointermove", (e) => handlers.hover({ type: "node", item: n }, e));
    g.addEventListener("pointerleave", () => handlers.hover(null));
    g.addEventListener("focus", () => handlers.focusNode?.(n));
    nodeEls.set(n.id, { g, sub, badge, bt, title, p });
  }
  host.appendChild(svg);

  // высота схемы — по пропорциям графа (широкий граф не тонет в высоком окне)
  const ratio = lay.height / lay.width;
  const NARROW = 700; // уже — телефон: читаемый масштаб и сдвиг вместо «вписать всё»
  let narrowView = false;
  const sizeSvg = () => {
    const w = host.clientWidth || lay.width;
    const vh = window.innerHeight || 800;
    const want = w < NARROW ? Math.max(380, vh * 0.62) : Math.max(340, Math.min(vh * 0.82, w * ratio * 1.05));
    svg.style.height = `${Math.round(want)}px`;
    if (w < NARROW && !narrowView && !userMoved) {
      const k = 0.78; // экранных точек на единицу схемы
      const vw = w / k;
      const vhh = want / k;
      vb = { x: 0, y: Math.max(0, (lay.height - vhh) / 2), w: vw, h: vhh };
      applyVb();
      narrowView = true;
    }
  };
  let userMoved = false;

  // ---------------------------------------------------------------- обновление
  let current = { vis: null, focus: null, dim: null, day: null };
  let particles = [];

  function update(vis, focus, dimSet, day) {
    current = { vis, focus, dim: dimSet, day };
    for (const n of view.nodes) {
      const e = nodeEls.get(n.id);
      if (!e) continue;
      const act = nodeActivityAt(n, day);
      const shown = vis.nodes.has(n.id);
      const faded = (focus && !focus.nodes.has(n.id)) || (dimSet && !dimSet.has(n.id));
      e.g.setAttribute("class", `node kind-${n.kind} act-${act} st-${n.status}${shown ? "" : " is-hidden"}${faded ? " is-dim" : ""}${focus && focus.nodes.has(n.id) && handlers.selected() === `n:${n.id}` ? " is-sel" : ""}`);
      const kindName = view.catalog.nodeKinds.get(n.kind)?.name || n.kind;
      const actName = STATE_NAMES[act] || act;
      e.sub.textContent = n.kind === "repo" || n.kind === "actor" || act === "up" || act === "down" ? actName : n.status === "active" ? kindName : view.catalog.statuses.get(n.status)?.name || n.status;
      const br = n.branches.count;
      e.badge.setAttribute("class", `badge${br > 0 && n.branches.known && day === null ? "" : " is-hidden"}`);
      e.bt.textContent = br > 9 ? "9+" : String(br);
      const parts = [n.name, kindName, actName];
      if (br > 0) parts.push(`веток с невлитой работой: ${br}`);
      e.g.setAttribute("aria-label", parts.join(", "));
      e.title.textContent = parts.join(" · ");
      e.g.setAttribute("tabindex", shown ? "0" : "-1");
    }
    for (const f of view.flows) {
      const e = flowEls.get(f.id);
      if (!e) continue;
      const st = flowStateAt(f, day);
      const rate = flowRateAt(f, day, view.rateDays);
      const shown = vis.flows.has(f.id);
      const faded = (focus && !focus.flows.has(f.id)) || (dimSet && !(dimSet.has(f.from) && dimSet.has(f.to)));
      e.g.setAttribute("class", `flow st-${st}${shown ? "" : " is-hidden"}${faded ? " is-dim" : ""}${handlers.selected() === `f:${f.id}` ? " is-sel" : ""}`);
      e.path.setAttribute("stroke-width", reduced() ? strokeFor(st, rate).toFixed(2) : st === "flowing" ? "1.8" : "1.4");
    }
    rebuildParticles();
  }

  function rebuildParticles() {
    gParticles.replaceChildren();
    particles = [];
    if (reduced() || !current.vis) return;
    for (const f of view.flows) {
      if (!current.vis.flows.has(f.id)) continue;
      const e = flowEls.get(f.id);
      const st = flowStateAt(f, current.day);
      const { n, speed } = particlePlan(st, flowRateAt(f, current.day, view.rateDays));
      const faded = (current.focus && !current.focus.flows.has(f.id)) || (current.dim && !(current.dim.has(f.from) && current.dim.has(f.to)));
      for (let i = 0; i < n; i++) {
        const c = el("circle", { r: st === "quiet" ? 2 : 2.6, class: `particle drv-${f.driver}${faded ? " is-dim" : ""}` }, gParticles);
        particles.push({ c, e, offset: i / n, speed });
      }
    }
  }

  let raf = 0;
  let last = 0;
  function frame(ts) {
    raf = requestAnimationFrame(frame);
    if (ts - last < 33) return; // ~30 кадров/с хватает и бережёт батарею телефона
    last = ts;
    const sec = ts / 1000;
    for (const p of particles) {
      const frac = (sec * p.speed / Math.max(1, p.e.table.len) + p.offset) % 1;
      const pt = pointAtFraction(p.e.geo, p.e.table, frac);
      p.c.setAttribute("cx", pt.x.toFixed(1));
      p.c.setAttribute("cy", pt.y.toFixed(1));
    }
  }
  function start() {
    if (!raf && !reduced()) raf = requestAnimationFrame(frame);
  }
  function stop() {
    if (raf) cancelAnimationFrame(raf);
    raf = 0;
  }
  const onVis = () => (document.hidden ? stop() : start());
  document.addEventListener("visibilitychange", onVis);
  let io = null;
  if ("IntersectionObserver" in window) {
    io = new IntersectionObserver((entries) => (entries[0].isIntersecting ? start() : stop()));
    io.observe(svg);
  } else start();

  // ---------------------------------------------------------------- масштаб и сдвиг (F-33)
  let vb = { x: 0, y: 0, w: lay.width, h: lay.height };
  const applyVb = () => svg.setAttribute("viewBox", `${vb.x} ${vb.y} ${vb.w} ${vb.h}`);
  sizeSvg();
  const ro = "ResizeObserver" in window ? new ResizeObserver(sizeSvg) : null;
  ro?.observe(host);
  function toSvg(clientX, clientY) {
    const m = svg.getScreenCTM();
    if (!m) return { x: 0, y: 0 };
    const pt = svg.createSVGPoint();
    pt.x = clientX;
    pt.y = clientY;
    return pt.matrixTransform(m.inverse());
  }
  function zoomAt(k, cx, cy) {
    const minW = lay.width / 8;
    const maxW = lay.width * 1.6;
    const w = Math.min(maxW, Math.max(minW, vb.w / k));
    const real = vb.w / w;
    vb = { x: cx - (cx - vb.x) / real, y: cy - (cy - vb.y) / real, w, h: vb.h / real };
    applyVb();
  }
  function fit() {
    userMoved = true;
    vb = { x: 0, y: 0, w: lay.width, h: lay.height };
    applyVb();
  }
  function zoomBy(k) {
    zoomAt(k, vb.x + vb.w / 2, vb.y + vb.h / 2);
  }
  function centerOn(nid) {
    const p = lay.nodes.get(nid);
    if (!p) return;
    vb = { ...vb, x: p.x + p.w / 2 - vb.w / 2, y: p.y + p.h / 2 - vb.h / 2 };
    applyVb();
  }
  svg.addEventListener("wheel", (e) => {
    e.preventDefault();
    const pt = toSvg(e.clientX, e.clientY);
    zoomAt(Math.exp(-e.deltaY * 0.0015), pt.x, pt.y);
  }, { passive: false });

  const pointers = new Map();
  let moved = false;
  let start0 = null;
  let pinch0 = null;
  svg.addEventListener("pointerdown", (e) => {
    pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
    moved = false;
    start0 = { x: e.clientX, y: e.clientY, vb: { ...vb } };
    if (pointers.size === 2) {
      const [a, b] = [...pointers.values()];
      pinch0 = { d: Math.hypot(a.x - b.x, a.y - b.y), vb: { ...vb }, mid: toSvg((a.x + b.x) / 2, (a.y + b.y) / 2) };
    }
  });
  svg.addEventListener("pointermove", (e) => {
    if (!pointers.has(e.pointerId)) return;
    pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (pointers.size === 2 && pinch0) {
      const [a, b] = [...pointers.values()];
      const d = Math.hypot(a.x - b.x, a.y - b.y);
      vb = { ...pinch0.vb };
      zoomAt(d / Math.max(1, pinch0.d), pinch0.mid.x, pinch0.mid.y);
      moved = true;
      return;
    }
    if (!start0) return;
    const dx = e.clientX - start0.x;
    const dy = e.clientY - start0.y;
    if (!moved && Math.hypot(dx, dy) < 5) return;
    if (!moved) svg.setPointerCapture?.(e.pointerId);
    moved = true;
    userMoved = true;
    const m = svg.getScreenCTM();
    const scale = m ? m.a : 1;
    vb = { ...start0.vb, x: start0.vb.x - dx / scale, y: start0.vb.y - dy / scale };
    applyVb();
    handlers.hover(null);
  });
  const end = (e) => {
    pointers.delete(e.pointerId);
    if (pointers.size < 2) pinch0 = null;
    if (pointers.size === 0) {
      start0 = null;
      setTimeout(() => (moved = false), 0);
    }
  };
  svg.addEventListener("pointerup", end);
  svg.addEventListener("pointercancel", end);
  bg.addEventListener("click", () => {
    if (!moved) handlers.select(null);
  });

  function destroy() {
    stop();
    io?.disconnect();
    ro?.disconnect();
    document.removeEventListener("visibilitychange", onVis);
    svg.remove();
  }

  return { svg, update, fit, zoomBy, centerOn, destroy, start, stop, nodeEls };
}
