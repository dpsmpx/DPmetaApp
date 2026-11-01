// Схема в SVG (F-21, F-23, F-26, F-33; D-16): узлы, связи — прямые со стрелкой, без анимаций.
// Интенсивность связи — толщина линии (события за неделю); масштаб — кнопками, сдвиг —
// перетаскиванием, только когда схема увеличена; иначе страница прокручивается как обычно.
// Ширина схемы — ширина контейнера: при её смене раскладка строится заново.
// Без встроенных стилей и обработчиков в разметке — работает под строгой CSP (D-05, D-15).

import { layout } from "./layout.js";
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

// Подпись узла по ширине: примерно 7,6 px на знак при 13,5 px полужирным.
export function short(name, width) {
  const max = Math.max(6, Math.floor((width - 30) / 7.6));
  return name.length > max ? name.slice(0, max - 1) + "…" : name;
}

// Толщина линии — интенсивность за неделю (вместо движения частиц, D-16).
export function strokeFor(state, rate) {
  if (state !== "flowing" && state !== "quiet") return 1.2;
  return Math.min(3.2, 1.2 + 0.5 * Math.log2(1 + rate));
}

export const ZOOM = { min: 1, max: 4, step: 1.4 };

export function createGraph(host, view, handlers) {
  const id = `dpv${++instance}`;
  let lay = null;
  let svg = null;
  let flowEls = new Map();
  let nodeEls = new Map();
  let current = null;
  let vb = null; // null — вся схема
  let moved = false;

  function draw(width, keepVb = false) {
    const prevVb = keepVb ? vb : null;
    lay = layout(view, width);
    const old = svg;
    svg = el("svg", {
      class: "dpvis-svg",
      "data-mode": lay.mode,
      viewBox: `0 0 ${lay.width} ${lay.height}`,
      width: lay.width,
      height: lay.height,
      role: "group",
      "aria-label": "Схема: проекты по слоям, связи — линии со стрелкой",
    });
    const defs = el("defs", {}, svg);
    for (const drv of ["human", "ai", "check", "data"]) {
      // стрелка — одного размера при любой толщине линии
      const m = el("marker", { id: `${id}-mk-${drv}`, viewBox: "0 0 10 10", refX: 9, refY: 5, markerWidth: 8, markerHeight: 8, orient: "auto-start-reverse", markerUnits: "userSpaceOnUse" }, defs);
      el("path", { d: "M0,1 L9,5 L0,9 z", class: `mk drv-${drv}` }, m);
    }
    const bg = el("rect", { class: "dpvis-bg", x: 0, y: 0, width: lay.width, height: lay.height }, svg);
    bg.addEventListener("click", () => {
      if (!moved) handlers.select(null);
    });
    const gFlows = el("g", { class: "flows" }, svg);
    const gHead = el("g", { class: "cols", "aria-hidden": "true" }, svg);
    for (const c of lay.headers) {
      const t = el("text", { x: c.x, y: c.y, class: "col-name", "text-anchor": c.anchor }, gHead);
      c.lines.forEach((line, i) => {
        el("tspan", { x: c.x, dy: i ? 14 : 0 }, t).textContent = line;
      });
    }
    const gNodes = el("g", { class: "nodes" }, svg);

    flowEls = new Map();
    for (const f of view.flows) {
      const geo = lay.flows.get(f.id);
      if (!geo) continue;
      const g = el("g", { class: "flow", "data-id": f.id }, gFlows);
      const path = el("path", {
        d: geo.d,
        class: `flow-line drv-${f.driver}`,
        "marker-end": `url(#${id}-mk-${f.driver})`,
        "marker-start": f.bidirectional ? `url(#${id}-mk-${f.driver})` : null,
      }, g);
      const hit = el("path", { d: geo.d, class: "flow-hit" }, g);
      const from = view.nodeById.get(f.from).name;
      const to = view.nodeById.get(f.to).name;
      el("title", {}, hit).textContent = `${from} → ${to}: ${view.catalog.flowKinds.get(f.kind)?.name || f.kind}`;
      hit.addEventListener("click", (e) => {
        e.stopPropagation();
        if (!moved) handlers.select(`f:${f.id}`);
      });
      hit.addEventListener("pointerenter", (e) => handlers.hover({ type: "flow", item: f }, e));
      hit.addEventListener("pointermove", (e) => handlers.hover({ type: "flow", item: f }, e));
      hit.addEventListener("pointerleave", () => handlers.hover(null));
      flowEls.set(f.id, { g, path });
    }

    nodeEls = new Map();
    for (const n of view.nodes) {
      const p = lay.nodes.get(n.id);
      if (!p) continue;
      const g = el("g", { class: `node kind-${n.kind}`, transform: `translate(${p.x},${p.y})`, tabindex: 0, role: "button", "data-id": n.id }, gNodes);
      el("rect", { class: "node-ring", x: -3, y: -3, width: p.w + 6, height: p.h + 6, rx: 9 }, g);
      el("rect", { class: "node-box", width: p.w, height: p.h, rx: 7 }, g);
      el("text", { class: "node-name", x: 9, y: 18 }, g).textContent = short(n.name, p.w);
      const sub = el("text", { class: "node-sub", x: 9, y: 34 }, g);
      const badge = el("g", { class: "badge", transform: `translate(${p.w - 2},2)` }, g);
      el("circle", { r: 9 }, badge);
      const bt = el("text", { "text-anchor": "middle", y: 4 }, badge);
      const q = el("text", { class: "unknown-mark", x: p.w - 13, y: 30, "text-anchor": "middle" }, g);
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
      nodeEls.set(n.id, { g, sub, badge, bt, title });
    }
    attachPan(svg);
    if (old) old.replaceWith(svg);
    else host.appendChild(svg);
    vb = prevVb && prevVb.w <= lay.width && prevVb.h <= lay.height ? clamp(prevVb) : null;
    applyVb();
    if (current) paint();
  }

  // Новые данные той же ширины: раскладка та же, масштаб и сдвиг сохраняются (F-45).
  function setView(v) {
    view = v;
    draw(lastW, true);
  }

  // ------------------------------------------------------------ состояние (F-23, F-26)
  function update(vis, focus, dimSet, day) {
    current = { vis, focus, dim: dimSet, day };
    paint();
  }

  function paint() {
    const { vis, focus, dim, day } = current;
    for (const n of view.nodes) {
      const e = nodeEls.get(n.id);
      if (!e) continue;
      const act = nodeActivityAt(n, day);
      const shown = vis.nodes.has(n.id);
      const faded = (focus && !focus.nodes.has(n.id)) || (dim && !dim.has(n.id));
      const sel = handlers.selected() === `n:${n.id}`;
      e.g.setAttribute("class", `node kind-${n.kind} act-${act} st-${n.status}${shown ? "" : " is-hidden"}${faded ? " is-dim" : ""}${sel ? " is-sel" : ""}`);
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
      const faded = (focus && !focus.flows.has(f.id)) || (dim && !(dim.has(f.from) && dim.has(f.to)));
      const inFocus = focus && focus.flows.has(f.id);
      e.g.setAttribute("class", `flow st-${st}${shown ? "" : " is-hidden"}${faded ? " is-dim" : ""}${inFocus ? " is-focus" : ""}${handlers.selected() === `f:${f.id}` ? " is-sel" : ""}`);
      e.path.setAttribute("stroke-width", strokeFor(st, rate).toFixed(2));
    }
  }

  // ------------------------------------------------------------ масштаб и сдвиг (F-33)
  function zoomLevel() {
    return vb ? lay.width / vb.w : 1;
  }

  function applyVb() {
    const v = vb || { x: 0, y: 0, w: lay.width, h: lay.height };
    svg.setAttribute("viewBox", `${v.x} ${v.y} ${v.w} ${v.h}`);
    host.classList.toggle("is-zoomed", !!vb);
    handlers.zoomChanged?.(zoomLevel());
  }

  function clamp(v) {
    const x = Math.min(Math.max(0, v.x), lay.width - v.w);
    const y = Math.min(Math.max(0, v.y), lay.height - v.h);
    return { ...v, x, y };
  }

  function zoomTo(k, cx, cy) {
    const z = Math.min(ZOOM.max, Math.max(ZOOM.min, k));
    if (z <= ZOOM.min + 1e-6) {
      vb = null;
    } else {
      const w = lay.width / z;
      const h = lay.height / z;
      vb = clamp({ x: cx - w / 2, y: cy - h / 2, w, h });
    }
    applyVb();
  }

  function center() {
    const v = vb || { x: 0, y: 0, w: lay.width, h: lay.height };
    return { x: v.x + v.w / 2, y: v.y + v.h / 2 };
  }

  function zoomBy(k) {
    const c = center();
    zoomTo(zoomLevel() * k, c.x, c.y);
  }

  function fit() {
    vb = null;
    applyVb();
  }

  function centerOn(nid) {
    const p = lay.nodes.get(nid);
    if (!p) return;
    if (vb) {
      vb = clamp({ ...vb, x: p.x + p.w / 2 - vb.w / 2, y: p.y + p.h / 2 - vb.h / 2 });
      applyVb();
    } else {
      nodeEls.get(nid)?.g.scrollIntoView?.({ block: "center", behavior: "auto" });
    }
  }

  function toSvg(clientX, clientY) {
    const m = svg.getScreenCTM();
    if (!m) return { x: 0, y: 0 };
    const pt = svg.createSVGPoint();
    pt.x = clientX;
    pt.y = clientY;
    return pt.matrixTransform(m.inverse());
  }

  function attachPan(s) {
    // колесо масштабирует только с Ctrl или ⌘ — иначе прокручивает страницу
    s.addEventListener("wheel", (e) => {
      if (!(e.ctrlKey || e.metaKey)) return;
      e.preventDefault();
      const pt = toSvg(e.clientX, e.clientY);
      zoomTo(zoomLevel() * Math.exp(-e.deltaY * 0.0015), pt.x, pt.y);
    }, { passive: false });
    let start = null;
    s.addEventListener("pointerdown", (e) => {
      moved = false;
      start = vb ? { x: e.clientX, y: e.clientY, vb: { ...vb }, id: e.pointerId } : null;
    });
    s.addEventListener("pointermove", (e) => {
      if (!start || e.pointerId !== start.id) return;
      const dx = e.clientX - start.x;
      const dy = e.clientY - start.y;
      if (!moved && Math.hypot(dx, dy) < 5) return;
      if (!moved) s.setPointerCapture?.(e.pointerId);
      moved = true;
      const m = s.getScreenCTM();
      const scale = m ? m.a : 1;
      vb = clamp({ ...start.vb, x: start.vb.x - dx / scale, y: start.vb.y - dy / scale });
      applyVb();
      handlers.hover(null);
    });
    const end = () => {
      start = null;
      setTimeout(() => (moved = false), 0);
    };
    s.addEventListener("pointerup", end);
    s.addEventListener("pointercancel", end);
  }

  // ------------------------------------------------------------ ширина
  let lastW = 0;
  let pending = 0;
  const widthNow = () => Math.floor(host.clientWidth || 0);
  const onResize = () => {
    if (pending) return;
    pending = setTimeout(() => {
      pending = 0;
      const w = widthNow();
      if (w && Math.abs(w - lastW) > 1) {
        lastW = w;
        draw(w);
      }
    }, 60);
  };
  lastW = widthNow() || 960;
  draw(lastW);
  const ro = "ResizeObserver" in window ? new ResizeObserver(onResize) : null;
  ro?.observe(host);
  if (!ro) window.addEventListener("resize", onResize);

  function destroy() {
    ro?.disconnect();
    window.removeEventListener("resize", onResize);
    clearTimeout(pending);
    svg.remove();
  }

  return {
    get svg() { return svg; },
    get layout() { return lay; },
    update, setView, fit, zoomBy, zoomLevel, centerOn, destroy,
    nodeEls: () => nodeEls,
  };
}
