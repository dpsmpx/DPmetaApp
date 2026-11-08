// Просмотрщик DPvisualisation: сборка экрана и связь частей (ТЗ §6.4, §9).
// Данные: data-src у корня → встроенный JSON (#dpvis-data) → ?model=&state= → ../data/.

import { h } from "./dom.js";
import { fmtAge, fmtDate, fmtDateTime, isStale } from "./format.js";
import { classify, normalize, DataError } from "./data.js";
import { decode, defaults, encode, focusSet, matches, visible, FLOW_STATES, STATE_NAMES } from "./filters.js";
import { createGraph, ZOOM } from "./graph.js";
import { flowPanel, nodePanel } from "./panel.js";
import { reconView, tablesView } from "./tables.js";

const THEME_KEY = "dpvis-theme";
const LIGHT_QUERY = "(prefers-color-scheme: light)";

/**
 * Тема схемы: выбор человека кнопкой (`stored`) — главный; иначе — тема
 * страницы-хозяина (`host`); `auto` — настройка системы (`prefersLight`),
 * так её задаёт сайт автора (SocialMediaImage:Д49). Неизвестное — тёмная.
 */
export function themeFor(stored, host, prefersLight) {
  const t = stored || host;
  if (t === "light" || t === "dark") return t;
  if (t === "auto") return prefersLight ? "light" : "dark";
  return "dark";
}

function prefersLight() {
  return typeof matchMedia === "function" && matchMedia(LIGHT_QUERY).matches;
}

function storeGet(k) {
  try {
    return localStorage.getItem(k);
  } catch {
    return null;
  }
}
function storeSet(k, v) {
  try {
    localStorage.setItem(k, v);
  } catch {
    /* приватное окно — тема просто не запомнится */
  }
}

function sameOrigin(url) {
  try {
    return new URL(url, location.href).origin === location.origin;
  } catch {
    return false;
  }
}

async function fetchJson(url) {
  const r = await fetch(url, { cache: "no-store" });
  if (!r.ok) throw new DataError(`${url}: ответ ${r.status}`);
  return r.json();
}

// Применить «показ» (F-32): имена закрытых-неназываемых и ветки скрыты.
export function showMode(model, state) {
  const m = { ...model, nodes: model.nodes.map((n) => (n.visibility === "undisclosed" ? { ...n, name: "закрытый проект", role: "закрытый проект без публичного имени", stage: null, refs: [], repo: null } : n)) };
  let s = state;
  if (state) {
    s = { ...state, nodes: {} };
    for (const [id, v] of Object.entries(state.nodes)) {
      s.nodes[id] = v.git ? { ...v, git: { ...v.git, branches: Array.isArray(v.git.branches) ? v.git.branches.length : v.git.branches } } : v;
    }
  }
  return { model: m, state: s };
}

export class Viewer {
  constructor(root) {
    this.root = root;
    this.site = root.dataset.embed === "site";
    this.data = { model: null, state: null, public: false };
    this.f = decode(location.hash);
    this.graph = null;
    this.view = null;
    this.vis = null;
    this.playTimer = 0;
    this.buildShell();
  }

  // ------------------------------------------------------------ каркас
  buildShell() {
    const r = this.root;
    r.classList.add("dpvis");
    const host = r.dataset.theme;
    r.dataset.theme = themeFor(storeGet(THEME_KEY), host, prefersLight());
    // «auto» без выбора человека — вслед за настройкой системы, без перезагрузки страницы.
    if (host === "auto" && typeof matchMedia === "function") {
      matchMedia(LIGHT_QUERY).addEventListener("change", () => {
        if (storeGet(THEME_KEY)) return;
        r.dataset.theme = themeFor(null, "auto", prefersLight());
        this.updateThemeBtn();
      });
    }
    r.replaceChildren();
    this.el = {};
    const e = this.el;
    e.asof = h("span", { class: "dv-asof" });
    e.stale = h("span", { class: "dv-stale", hidden: true, text: "устарел" });
    e.live = h("span", { class: "dv-live", hidden: true });
    e.theme = h("button", { type: "button", class: "dv-btn", on: { click: () => this.toggleTheme() } });
    e.show = h("button", { type: "button", class: "dv-btn", hidden: true, "aria-pressed": "false", on: { click: () => this.setF({ show: !this.f.show }, true) }, text: "Показ" });
    e.file = h("input", { type: "file", accept: ".json,application/json", class: "dv-file", on: { change: (ev) => this.openFile(ev) } });
    e.open = h("button", { type: "button", class: "dv-btn", hidden: this.site, on: { click: () => e.file.click() }, text: "Открыть…" });
    e.title = h("strong", { class: "dv-name" });
    r.appendChild(h("header", { class: "dv-head" },
      h("div", { class: "dv-title" }, e.title, e.asof, e.stale, e.live),
      h("div", { class: "dv-tools" }, e.theme, e.show, e.open, e.file)));

    e.tabs = {};
    const tabs = h("div", { class: "dv-tabs", role: "tablist", "aria-label": "Вид" });
    for (const [id, name] of [["graph", "Схема"], ["tables", "Таблицы"], ["recon", "Сверка"]]) {
      e.tabs[id] = h("button", { type: "button", role: "tab", class: "dv-tab-btn", id: `dv-tab-${id}`, on: { click: () => this.setF({ tab: id }) }, text: name });
      tabs.appendChild(e.tabs[id]);
    }
    r.appendChild(tabs);
    e.msg = h("div", { class: "dv-msg", role: "status", hidden: true });
    r.appendChild(e.msg);

    // схема
    e.filters = h("details", { class: "dv-filters" });
    e.graphBox = h("div", { class: "dv-graph" });
    e.zoomIn = h("button", { type: "button", class: "dv-btn", "aria-label": "Крупнее", on: { click: () => this.graph?.zoomBy(ZOOM.step) }, text: "+" });
    e.zoomOut = h("button", { type: "button", class: "dv-btn", "aria-label": "Мельче", on: { click: () => this.graph?.zoomBy(1 / ZOOM.step) }, text: "−" });
    e.zoomFit = h("button", { type: "button", class: "dv-btn", on: { click: () => this.graph?.fit() }, text: "Вся схема" });
    e.zoom = h("div", { class: "dv-zoom", role: "group", "aria-label": "Масштаб" }, e.zoomIn, e.zoomOut, e.zoomFit);
    e.panel = h("aside", { class: "dv-panel", "aria-live": "polite", "aria-label": "Подробности", hidden: true });
    e.hint = h("p", { class: "dv-hint", text: "Нажмите проект или связь — появятся подробности. «+» и «−» — масштаб (увеличенную схему можно двигать), «Вся схема» — вернуть. Клавиши: Tab, Enter, Esc, / — поиск, ← → — день." });
    e.timeline = h("div", { class: "dv-timeline" });
    e.legend = h("div", { class: "dv-legend", "aria-label": "Легенда" });
    e.tabGraph = h("section", { class: "dv-tab", role: "tabpanel", "aria-labelledby": "dv-tab-graph" },
      e.filters,
      (e.main = h("div", { class: "dv-main" }, h("div", { class: "dv-graph-wrap" }, e.zoom, e.graphBox), e.panel)),
      e.hint, e.timeline, e.legend);
    e.tabTables = h("section", { class: "dv-tab", role: "tabpanel", "aria-labelledby": "dv-tab-tables", hidden: true });
    e.tabRecon = h("section", { class: "dv-tab", role: "tabpanel", "aria-labelledby": "dv-tab-recon", hidden: true });
    r.append(e.tabGraph, e.tabTables, e.tabRecon);
    e.tip = h("div", { class: "dv-tip", role: "tooltip", hidden: true });
    r.appendChild(e.tip);
    this.updateThemeBtn();

    window.addEventListener("hashchange", () => {
      if (location.hash === encode(this.f)) return;
      this.f = decode(location.hash, this.view);
      this.render();
    });
    document.addEventListener("keydown", (ev) => this.onKey(ev));
    window.addEventListener("message", (ev) => {
      if (ev.source !== window.parent || window.parent === window) return;
      if (ev.data && ev.data.type === "dpvis:data") this.accept({ model: ev.data.model, state: ev.data.state || null });
    });
  }

  message(text, kind = "error") {
    const m = this.el.msg;
    m.textContent = text || "";
    m.dataset.kind = kind;
    m.hidden = !text;
  }

  // ------------------------------------------------------------ данные
  async load() {
    const r = this.root;
    const params = new URLSearchParams(location.search);
    try {
      if (r.dataset.src) {
        this.accept(classify(await fetchJson(r.dataset.src)), { stateUrl: null });
        return;
      }
      const inline = document.getElementById("dpvis-data");
      if (inline) {
        const doc = JSON.parse(inline.textContent);
        this.accept(doc.schema ? classify(doc) : { model: doc.model, state: doc.state || null });
        return;
      }
      const mUrl = params.get("model") || "../data/model.json";
      const sUrl = params.get("state") || "../data/state/latest.json";
      if (!sameOrigin(mUrl) || !sameOrigin(sUrl)) throw new DataError("адреса ?model= и ?state= — только с того же источника, что страница");
      const model = classify(await fetchJson(mUrl)).model;
      let state = null;
      try {
        state = classify(await fetchJson(sUrl)).state;
      } catch {
        state = null; // нет среза — одна модель с пометкой «нет данных» (F-31)
      }
      this.accept({ model, state }, { stateUrl: sUrl });
      const poll = Number(params.get("poll") ?? (location.protocol.startsWith("http") ? 60 : 0));
      if (poll > 0) this.startPolling(sUrl, poll);
    } catch (err) {
      this.message(`Данные не загрузились: ${err.message}. Можно открыть файл модели или среза кнопкой «Открыть…».`);
      this.el.open.hidden = false;
    }
  }

  startPolling(url, sec) {
    this.el.live.hidden = false;
    this.el.live.textContent = "⟳ живой";
    setInterval(async () => {
      try {
        const st = classify(await fetchJson(url)).state;
        if (st && st.as_of !== this.data.state?.as_of) {
          this.data.state = st;
          this.rebuild(true);
          this.el.live.textContent = `⟳ обновлено ${fmtDateTime(st.as_of)}`;
        }
      } catch {
        this.el.live.textContent = "⟳ нет связи с сервером";
      }
    }, sec * 1000);
  }

  accept(parts) {
    if (parts.model) this.data.model = parts.model;
    if ("state" in parts && parts.state !== undefined) this.data.state = parts.state;
    if (parts.public) this.data.public = true;
    if (!this.data.model) {
      this.message("Срез загружен, но модели нет — откройте файл модели.", "info");
      return;
    }
    this.message("");
    this.rebuild();
  }

  async openFile(ev) {
    const file = ev.target.files && ev.target.files[0];
    ev.target.value = "";
    if (!file) return;
    try {
      const parts = classify(JSON.parse(await file.text()));
      if (parts.state && !parts.model && !this.data.model) throw new DataError("сначала нужна модель");
      this.accept(parts);
    } catch (err) {
      this.message(`Файл «${file.name}» не принят: ${err instanceof SyntaxError ? "это не JSON" : err.message}. Прежние данные не тронуты.`);
    }
  }

  // ------------------------------------------------------------ построение
  rebuild(keepView = false) {
    const { model, state } = this.f.show ? showMode(this.data.model, this.data.state) : this.data;
    let view;
    try {
      view = normalize(model, state, { public: this.data.public });
    } catch (err) {
      this.message(`Данные не прочитаны: ${err.message}`);
      return;
    }
    this.view = view;
    this.f = decode(encode(this.f), view);
    if (keepView && this.graph) {
      this.graph.setView(view); // новый срез — тот же масштаб и сдвиг (F-45)
    } else {
      this.graph?.destroy();
      this.graph = createGraph(this.el.graphBox, view, {
        select: (sel) => this.select(sel),
        selected: () => this.f.sel,
        hover: (t, ev) => this.hover(t, ev),
        zoomChanged: (k) => this.syncZoom(k),
      });
    }
    this.el.title.textContent = view.title;
    this.el.show.hidden = !view.nodes.some((n) => n.visibility === "undisclosed") && !this.f.show;
    this.el.tabs.recon.hidden = !(view.diagnostics || view.registry);
    this.buildFilters();
    this.buildTimeline();
    this.buildLegend();
    this.render();
  }

  // ------------------------------------------------------------ состояние вида
  setF(patch, rebuild = false) {
    this.f = { ...this.f, ...patch };
    const hash = encode(this.f);
    if (hash !== location.hash && !(hash === "" && location.hash === "")) {
      history.replaceState(null, "", hash || location.pathname + location.search);
    }
    if (rebuild) this.rebuild(true);
    else this.render();
  }

  select(sel, fromTable = false) {
    this.setF({ sel, ...(fromTable ? { tab: "graph" } : {}) });
    if (sel && sel[0] === "n" && fromTable) this.graph?.centerOn(sel.slice(2));
  }

  render() {
    const v = this.view;
    if (!v) return;
    const f = this.f;
    // шапка: время среза всегда видно (F-31)
    if (v.asOf) {
      this.el.asof.textContent = `срез ${fmtDateTime(v.asOf)} · ${fmtAge(v.asOf)}`;
      this.el.stale.hidden = !isStale(v.asOf);
    } else {
      this.el.asof.textContent = `модель на ${fmtDate(v.modelAsOf)} · нет данных среза`;
      this.el.stale.hidden = true;
    }
    this.el.show.setAttribute("aria-pressed", String(!!f.show));
    for (const [id, b] of Object.entries(this.el.tabs)) b.setAttribute("aria-selected", String(f.tab === id));
    this.el.tabGraph.hidden = f.tab !== "graph";
    this.el.tabTables.hidden = f.tab !== "tables";
    this.el.tabRecon.hidden = f.tab !== "recon";

    this.vis = visible(v, f);
    const focus = focusSet(v, f.sel);
    const dim = f.q ? new Set(v.nodes.filter((n) => matches(n, f.q)).map((n) => n.id)) : null;
    this.graph.update(this.vis, focus, dim, f.day);
    this.renderPanel();
    this.syncFilters();
    this.syncTimeline();
    if (f.tab === "tables") this.el.tabTables.replaceChildren(tablesView(v, this.vis, (s, t) => this.select(s, t)));
    if (f.tab === "recon") this.el.tabRecon.replaceChildren(reconView(v));
  }

  renderPanel() {
    const v = this.view;
    const sel = this.f.sel;
    const p = this.el.panel;
    if (!sel) {
      p.classList.remove("has-sel");
      this.el.main.classList.remove("has-sel");
      p.hidden = true;
      p.replaceChildren();
      return;
    }
    const id = sel.slice(2);
    const item = sel[0] === "n" ? v.nodeById.get(id) : v.flowById.get(id);
    if (!item) return;
    const { title, body } = sel[0] === "n" ? nodePanel(v, item, (s) => this.select(s)) : flowPanel(v, item, (s) => this.select(s));
    const wasHidden = p.hidden;
    p.hidden = false;
    p.classList.add("has-sel");
    this.el.main.classList.add("has-sel");
    if (wasHidden && p.getBoundingClientRect().top > (window.innerHeight || 0)) p.scrollIntoView({ block: "nearest", behavior: "auto" });
    p.replaceChildren(
      h("div", { class: "dv-panel-head" },
        h("h2", { text: title }),
        h("button", { type: "button", class: "dv-btn dv-close", "aria-label": "Закрыть подробности", on: { click: () => this.select(null) }, text: "×" })),
      ...body.filter(Boolean));
  }

  hover(t, ev) {
    const tip = this.el.tip;
    // подсказка — только для мыши: на касание отвечает панель подробностей
    if (!t || !ev || (ev.pointerType && ev.pointerType !== "mouse")) {
      tip.hidden = true;
      return;
    }
    const v = this.view;
    let lines;
    if (t.type === "node") {
      const n = t.item;
      lines = [n.name, `${v.catalog.nodeKinds.get(n.kind)?.name || n.kind} · ${STATE_NAMES[n.activity] || n.activity}`];
      if (n.kind === "repo" || n.kind === "actor") lines.push(`за ${v.rateDays} дн.: ${n.week} коммит(ов)`);
      if (n.branches.count) lines.push(`веток с невлитой работой: ${n.branches.count}`);
    } else {
      const f = t.item;
      lines = [`${v.nodeById.get(f.from).name} → ${v.nodeById.get(f.to).name}`, `${v.catalog.flowKinds.get(f.kind)?.name || f.kind} · ${STATE_NAMES[f.state] || f.state}`];
      if (["flowing", "quiet"].includes(f.state)) lines.push(`за ${v.rateDays} дн.: ${f.week} событий`);
    }
    tip.replaceChildren(...lines.map((l, i) => h(i === 0 ? "strong" : "span", { text: l })));
    tip.hidden = false;
    const box = this.root.getBoundingClientRect();
    const x = ev.clientX - box.left + 14;
    const y = ev.clientY - box.top + 14;
    tip.style.left = `${Math.min(x, box.width - 230)}px`;
    tip.style.top = `${y}px`;
  }

  syncZoom(k) {
    const e = this.el;
    e.zoomOut.disabled = k <= ZOOM.min + 1e-6;
    e.zoomFit.disabled = k <= ZOOM.min + 1e-6;
    e.zoomIn.disabled = k >= ZOOM.max - 1e-6;
  }

  // ------------------------------------------------------------ фильтры (F-27)
  buildFilters() {
    const v = this.view;
    const e = this.el;
    e.filters.open = false; // проще экран (D-16): фильтры — по нажатию
    const kinds = [...new Set(v.flows.map((f) => f.kind))];
    const states = FLOW_STATES.filter((s) => v.flows.some((f) => f.state === s));
    const layers = v.layers.filter((l) => v.nodes.some((n) => n.layer === l.id));
    e.chips = { kinds: new Map(), states: new Map(), layers: new Map() };
    const group = (title, key, items) => {
      const box = h("div", { class: "dv-chipgroup", role: "group", "aria-label": title }, h("span", { class: "dv-chipttl", text: title }));
      for (const [id, name, cls] of items) {
        const b = h("button", { type: "button", class: "dv-chip", "aria-pressed": "true", on: { click: () => this.toggleChip(key, id, items.map((x) => x[0])) } },
          cls ? h("i", { class: `sw ${cls}`, "aria-hidden": "true" }) : null, name);
        e.chips[key].set(id, b);
        box.appendChild(b);
      }
      return box;
    };
    e.search = h("input", { type: "search", class: "dv-search", placeholder: "Поиск: имя или id", "aria-label": "Поиск узла", value: this.f.q, on: { input: (ev) => this.setF({ q: ev.target.value }) } });
    e.a7 = h("input", { type: "checkbox", id: "dv-a7", on: { change: (ev) => this.setF({ a7: ev.target.checked }) } });
    e.filters.replaceChildren(
      h("summary", { text: "Фильтры" }),
      h("div", { class: "dv-filter-row" }, e.search,
        h("label", { class: "dv-check", for: "dv-a7" }, e.a7, ` только с активностью за ${v.rateDays} дн.`),
        h("button", { type: "button", class: "dv-btn", on: { click: () => this.setF({ kinds: null, states: null, layers: null, q: "", a7: false }) }, text: "Сбросить" })),
      group("Потоки", "kinds", kinds.map((k) => {
        const fk = v.catalog.flowKinds.get(k);
        return [k, fk?.name || k, `drv-${fk?.driver || "data"}`];
      })),
      group("Состояния", "states", states.map((s) => [s, STATE_NAMES[s] || s, `st-${s}`])),
      group("Слои", "layers", layers.map((l) => [l.id, l.name, null])),
    );
  }

  toggleChip(key, id, all) {
    const cur = this.f[key] ? new Set(this.f[key]) : new Set(all);
    if (cur.has(id)) cur.delete(id);
    else cur.add(id);
    this.setF({ [key]: cur.size === all.length ? null : cur });
  }

  syncFilters() {
    const e = this.el;
    if (!e.chips) return;
    for (const key of ["kinds", "states", "layers"]) {
      for (const [id, b] of e.chips[key]) b.setAttribute("aria-pressed", String(!this.f[key] || this.f[key].has(id)));
    }
    if (e.search.value !== this.f.q) e.search.value = this.f.q;
    e.a7.checked = this.f.a7;
  }

  // ------------------------------------------------------------ шкала времени (F-28)
  buildTimeline() {
    const v = this.view;
    const e = this.el;
    if (!v.hasState) {
      e.timeline.replaceChildren(h("span", { class: "muted", text: "Шкала времени — когда есть срез." }));
      e.slider = null;
      return;
    }
    const n = v.days.length;
    e.slider = h("input", { type: "range", min: 0, max: n, step: 1, class: "dv-slider", "aria-label": "День окна", on: { input: (ev) => this.setDay(Number(ev.target.value)) } });
    e.play = h("button", { type: "button", class: "dv-btn", on: { click: () => this.togglePlay() } });
    e.dayLabel = h("output", { class: "dv-day" });
    e.timeline.replaceChildren(
      h("button", { type: "button", class: "dv-btn", "aria-label": "День назад", on: { click: () => this.stepDay(-1) }, text: "◀" }),
      e.play,
      h("button", { type: "button", class: "dv-btn", "aria-label": "День вперёд", on: { click: () => this.stepDay(1) }, text: "▶" }),
      e.slider,
      e.dayLabel,
      h("button", { type: "button", class: "dv-btn", on: { click: () => this.setDay(n) }, text: "Сейчас" }));
  }

  syncTimeline() {
    const e = this.el;
    if (!e.slider) return;
    const n = this.view.days.length;
    const d = this.f.day;
    e.slider.value = String(d === null ? n : d);
    e.dayLabel.textContent = d === null ? "сейчас — по срезу" : `${fmtDate(this.view.days[d])} — события дня`;
    e.play.textContent = this.playTimer ? "❚❚ Пауза" : "▷ Воспроизвести";
  }

  setDay(i) {
    const n = this.view.days.length;
    this.setF({ day: i >= n ? null : Math.max(0, i) });
  }

  stepDay(k) {
    const n = this.view.days.length;
    const cur = this.f.day === null ? n : this.f.day;
    this.setDay(Math.min(n, Math.max(0, cur + k)));
  }

  togglePlay() {
    if (this.playTimer) {
      clearInterval(this.playTimer);
      this.playTimer = 0;
      this.syncTimeline();
      return;
    }
    if (this.f.day === null) this.setDay(0);
    this.playTimer = setInterval(() => {
      const n = this.view.days.length;
      if (this.f.day === null || this.f.day >= n - 1) {
        clearInterval(this.playTimer);
        this.playTimer = 0;
        this.setDay(n);
        return;
      }
      this.setDay(this.f.day + 1);
    }, 1000);
    this.syncTimeline();
  }

  // ------------------------------------------------------------ легенда (F-36)
  buildLegend() {
    const v = this.view;
    const drv = (v.catalogLists.drivers || []).map((d) => h("span", { class: "lg" }, h("i", { class: `ln drv-${d.id}`, "aria-hidden": "true" }), d.name));
    this.el.legend.replaceChildren(
      h("div", { class: "lg-row" }, h("span", { class: "lg-ttl", text: "Кто движет:" }), ...drv),
      h("div", { class: "lg-row" }, h("span", { class: "lg-ttl", text: "Состояние:" }),
        h("span", { class: "lg" }, h("i", { class: "ln st-flowing", "aria-hidden": "true" }), "идёт или тихо — чем толще, тем больше событий за неделю"),
        h("span", { class: "lg" }, h("i", { class: "ln st-planned", "aria-hidden": "true" }), "план"),
        h("span", { class: "lg" }, h("i", { class: "ln st-blocked", "aria-hidden": "true" }), "заблокирован или выключен")),
      h("div", { class: "lg-row" }, h("span", { class: "lg-ttl", text: "Узлы:" }),
        h("span", { class: "lg" }, h("i", { class: "nd act-working", "aria-hidden": "true" }), "работает (яркая рамка)"),
        h("span", { class: "lg" }, h("i", { class: "nd", "aria-hidden": "true" }), "без работы"),
        h("span", { class: "lg" }, h("i", { class: "nd act-unknown", "aria-hidden": "true" }, "?"), "неизвестно"),
        h("span", { class: "lg" }, h("i", { class: "nd act-unobserved", "aria-hidden": "true" }), "не наблюдается"),
        v.hasBranches ? h("span", { class: "lg" }, h("i", { class: "bd", "aria-hidden": "true" }, "3"), "ветки с невлитой работой") : null),
      v.hidden?.undisclosed
        ? h("div", { class: "lg-row" }, h("span", { class: "lg-ttl", text: "Не показано:" }),
            h("span", { class: "lg", text: `ещё ${v.hidden.undisclosed} ${v.hidden.undisclosed === 1 ? "проект" : "проекта"} без публичного имени на схеме не показан` }))
        : null,
    );
  }

  // ------------------------------------------------------------ тема и клавиатура
  toggleTheme() {
    const t = this.root.dataset.theme === "dark" ? "light" : "dark";
    this.root.dataset.theme = t;
    storeSet(THEME_KEY, t);
    this.updateThemeBtn();
  }

  updateThemeBtn() {
    this.el.theme.textContent = this.root.dataset.theme === "dark" ? "Светлая тема" : "Тёмная тема";
  }

  onKey(ev) {
    if (!this.view || ev.defaultPrevented) return;
    const t = ev.target;
    const typing = t && (t.tagName === "INPUT" || t.tagName === "TEXTAREA" || t.isContentEditable);
    if (ev.key === "Escape") {
      if (typing) t.blur();
      this.el.tip.hidden = true;
      if (this.f.sel) this.select(null);
      return;
    }
    if (typing || ev.ctrlKey || ev.metaKey || ev.altKey) return;
    if (ev.key === "/") {
      ev.preventDefault();
      this.el.filters.open = true;
      this.el.search.focus();
    } else if (ev.key === " " && t.tagName !== "BUTTON" && !(t.getAttribute && t.getAttribute("role") === "button")) {
      if (this.f.tab !== "graph" || !this.view.hasState) return;
      ev.preventDefault();
      this.togglePlay();
    } else if ((ev.key === "ArrowLeft" || ev.key === "ArrowRight") && this.view.hasState && this.f.tab === "graph") {
      ev.preventDefault();
      this.stepDay(ev.key === "ArrowLeft" ? -1 : 1);
    }
  }
}

export function start() {
  for (const root of document.querySelectorAll("[data-dpvis]")) {
    const v = new Viewer(root);
    root.dpvis = v;
    v.load();
  }
}

if (typeof document !== "undefined") {
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", start);
  else start();
}
