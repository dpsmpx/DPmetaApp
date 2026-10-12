// Панель подробностей (F-24, F-25): узел или поток; у каждого графика — таблица значений.

import { h, svgEl } from "./dom.js";
import { fmtAge, fmtDate, fmtDateTime, fmtNum, fmtShare, sum } from "./format.js";
import { STATE_NAMES } from "./filters.js";

function row(label, value) {
  if (value === null || value === undefined || value === "") return null;
  return h("div", { class: "kv" }, h("dt", { text: label }), h("dd", {}, value));
}

// Столбики по дням: стек серий (снизу вверх), подсказка на столбике, таблица под графиком.
export function dayChart(days, series, caption) {
  const n = days.length;
  const totals = days.map((_, i) => series.reduce((s, x) => s + (x.values[i] || 0), 0));
  const max = Math.max(1, ...totals);
  const W = 300;
  const H = 86;
  const bw = W / n;
  const svg = svgEl("svg", { class: "daychart", viewBox: `0 0 ${W} ${H + 16}`, role: "img", "aria-label": caption });
  svgEl("line", { x1: 0, x2: W, y1: H, y2: H, class: "axis" }, svg);
  for (let i = 0; i < n; i++) {
    let y = H;
    for (const s of series) {
      const v = s.values[i] || 0;
      if (!v) continue;
      const bh = Math.max(1.5, (v / max) * (H - 4));
      svgEl("rect", { x: (i * bw + 1).toFixed(1), y: (y - bh).toFixed(1), width: Math.max(1, bw - 2).toFixed(1), height: (bh - (y < H ? 1 : 0)).toFixed(1), rx: 1.5, class: `bar ${s.cls}` }, svg);
      y -= bh;
    }
    const hit = svgEl("rect", { x: (i * bw).toFixed(1), y: 0, width: bw.toFixed(1), height: H, class: "bar-hit" }, svg);
    const parts = series.map((s) => `${s.name}: ${s.values[i] || 0}`).join(", ");
    svgEl("title", {}, hit).textContent = `${fmtDate(days[i])} — ${parts}`;
  }
  const lbl = (i, anchor) => {
    const t = svgEl("text", { x: anchor === "end" ? W : 0, y: H + 13, "text-anchor": anchor, class: "tick" }, svg);
    t.textContent = fmtDate(days[i]).slice(0, 5);
  };
  lbl(0, "start");
  lbl(n - 1, "end");
  const maxT = svgEl("text", { x: 0, y: 10, class: "tick" }, svg);
  maxT.textContent = `макс. ${max}`;

  const legend = series.length > 1
    ? h("div", { class: "chart-legend" }, series.map((s) => h("span", { class: "lg" }, h("i", { class: `sw ${s.cls}` }), s.name)))
    : null;
  const table = h("table", { class: "vtable" },
    h("caption", { text: caption }),
    h("thead", {}, h("tr", {}, h("th", { text: "день" }), series.map((s) => h("th", { text: s.name })))),
    h("tbody", {}, days.map((d, i) => (totals[i] ? h("tr", {}, h("td", { text: fmtDate(d) }), series.map((s) => h("td", { text: String(s.values[i] || 0) }))) : null)).filter(Boolean)),
  );
  const empty = totals.every((t) => !t);
  return h("figure", { class: "chart" },
    h("figcaption", { text: caption }),
    empty ? h("p", { class: "muted", text: "событий в окне нет" }) : svg,
    legend,
    empty ? null : h("details", { class: "values" }, h("summary", { text: "Таблица значений" }), table),
  );
}

function flowLink(view, f, dir, select) {
  const other = view.nodeById.get(dir === "out" ? f.to : f.from);
  const kind = view.catalog.flowKinds.get(f.kind)?.name || f.kind;
  return h("li", {},
    h("button", { type: "button", class: "link", on: { click: () => select(`f:${f.id}`) } },
      h("i", { class: `sw drv-${f.driver}`, "aria-hidden": "true" }),
      `${dir === "out" ? "→" : "←"} ${other.name}`),
    h("span", { class: "muted", text: ` ${kind} · ${STATE_NAMES[f.state] || f.state}` }));
}

function refsList(refs) {
  if (!refs || !refs.length) return null;
  return h("details", { class: "refs" }, h("summary", { text: "Источники" }), h("ul", {}, refs.map((r) => h("li", { text: r }))));
}

export function nodePanel(view, n, select) {
  const cat = view.catalog;
  const layer = view.layers.find((l) => l.id === n.layer);
  const kind = cat.nodeKinds.get(n.kind)?.name || n.kind;
  const vis = cat.visibility.get(n.visibility)?.name;
  const act = STATE_NAMES[n.activity] || n.activity;
  const items = [];
  items.push(h("p", { class: "lead", text: n.role || "" }));
  const dl = h("dl", { class: "kvs" },
    row("Вид", `${kind}${layer ? " · " + layer.name : ""}`),
    row("Код", vis || null),
    row("Стадия", n.stage || null),
    row("По модели", cat.statuses.get(n.status)?.name || n.status),
    row("Сейчас", view.hasState ? `${act}${n.last ? ` · последнее событие ${fmtDateTime(n.last)} (${fmtAge(n.last)})` : ""}` : "нет среза"),
    n.note ? row("Заметка", n.note) : null,
  );
  items.push(dl);
  if (n.git) {
    const g = n.git;
    const ai = g.window?.ai || 0;
    const human = g.window?.human || 0;
    items.push(h("dl", { class: "kvs" },
      row("Основная ветка", g.head_time || g.head_date ? `последний коммит ${fmtDateTime(g.head_time || g.head_date)} (${fmtAge(g.head_time || g.head_date)})` : "нет коммитов"),
      row("Первый коммит", g.first_time || g.first_date ? fmtDate(g.first_time || g.first_date) : null),
      row(`За ${view.days.length} дней`, `${fmtNum(ai + human)} коммитов, из них ИИ ${fmtShare(ai, ai + human)}${g.window?.merges !== undefined ? `; вливаний ${fmtNum(g.window.merges)}` : ""}`),
      n.branches.known ? row("Ветки с невлитой работой", n.branches.count ? String(n.branches.count) : "нет") : null,
      g.history && g.history !== "complete" ? row("История", "неполная (клон с ограниченной глубиной)") : null,
    ));
    if (n.branches.list.length) {
      items.push(h("ul", { class: "branches" }, n.branches.list.map((b) => h("li", {}, h("code", { text: b.name }), h("span", { class: "muted", text: ` +${b.ahead} · ${fmtDateTime(b.time)}` })))));
    }
    items.push(dayChart(view.days, [
      { name: "ИИ", cls: "drv-ai", values: n.daysAi },
      { name: "человек", cls: "drv-human", values: n.daysHuman },
    ], `Коммиты основной ветки по дням, ${fmtDate(view.days[0])}–${fmtDate(view.days[view.days.length - 1])}`));
  } else if (n.kind === "actor" && sum(n.daysTotal)) {
    const isAuthor = n.id === "author";
    items.push(dayChart(view.days, [{ name: isAuthor ? "вливания и коммиты" : "коммиты сессий", cls: isAuthor ? "drv-human" : "drv-ai", values: n.daysTotal }],
      `${isAuthor ? "Решения автора в git" : "Работа сессий"} по дням`));
  }
  const out = view.outgoing.get(n.id) || [];
  const inc = view.incoming.get(n.id) || [];
  if (out.length) items.push(h("h3", { text: `Исходящие потоки · ${out.length}` }), h("ul", { class: "flowlist" }, out.map((f) => flowLink(view, f, "out", select))));
  if (inc.length) items.push(h("h3", { text: `Входящие потоки · ${inc.length}` }), h("ul", { class: "flowlist" }, inc.map((f) => flowLink(view, f, "in", select))));
  items.push(refsList(n.refs));
  return { title: n.name, body: items };
}

export function flowPanel(view, f, select) {
  const cat = view.catalog;
  const a = view.nodeById.get(f.from);
  const b = view.nodeById.get(f.to);
  const drv = cat.drivers.get(f.driver)?.name || f.driver;
  const items = [];
  items.push(h("p", { class: "lead", text: f.what || "" }));
  items.push(h("p", { class: "ends" },
    h("button", { type: "button", class: "link", on: { click: () => select(`n:${a.id}`) }, text: a.name }),
    f.bidirectional ? " ⇄ " : " → ",
    h("button", { type: "button", class: "link", on: { click: () => select(`n:${b.id}`) }, text: b.name })));
  items.push(h("dl", { class: "kvs" },
    row("Вид", cat.flowKinds.get(f.kind)?.name || f.kind),
    row("Кто движет", h("span", {}, h("i", { class: `sw drv-${f.driver}`, "aria-hidden": "true" }), drv)),
    row("По модели", cat.statuses.get(f.status)?.name || f.status),
    row("По данным", view.hasState ? STATE_NAMES[f.state] || f.state : "нет среза"),
    row("Путь", cat.transports.get(f.transport)?.name || f.transport),
    row("Когда", f.trigger || null),
    f.contracts && f.contracts.length ? row("Контракт", f.contracts.join(", ")) : null,
    row("Последнее событие", f.lastEvent ? `${fmtDateTime(f.lastEvent)} (${fmtAge(f.lastEvent)})` : view.hasState && ["flowing", "quiet"].includes(f.state) ? "в окне не было" : null),
    view.hasState && f.total !== undefined && ["flowing", "quiet"].includes(f.state) ? row("События", `за ${view.rateDays} дней — ${f.week}, за ${view.days.length} — ${f.total}`) : null,
  ));
  if (["flowing", "quiet"].includes(f.state)) {
    items.push(dayChart(view.days, [{ name: "события", cls: `drv-${f.driver}`, values: f.days }], "События потока по дням"));
  } else if (f.state === "unobserved") {
    items.push(h("p", { class: "muted", text: "Сигнала у этого потока нет: он не наблюдается по устройству, а не «ноль»." }));
  } else if (f.state === "unknown") {
    items.push(h("p", { class: "muted", text: "Сигнал есть, но наблюдение не удалось — «неизвестно», а не «ноль»." }));
  }
  items.push(refsList(f.refs));
  return { title: `${a.name} → ${b.name}`, body: items };
}
