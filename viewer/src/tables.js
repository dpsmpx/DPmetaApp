// Вкладки «Таблицы» (F-29) и «Сверка» (F-30): всё, что видно на графе, читается без графа.

import { h } from "./dom.js";
import { fmtDate, fmtNum, sum } from "./format.js";
import { STATE_NAMES } from "./filters.js";

export function tablesView(view, vis, select) {
  const cat = view.catalog;
  const nodes = view.nodes.filter((n) => vis.nodes.has(n.id)).sort((a, b) => a.layerIndex - b.layerIndex || a.name.localeCompare(b.name, "ru"));
  const flows = view.flows.filter((f) => vis.flows.has(f.id)).sort((a, b) => b.week - a.week || a.id.localeCompare(b.id));
  const layerName = (id) => view.layers.find((l) => l.id === id)?.name || id;
  const pick = (sel) => ({ click: () => select(sel, true) });

  const nodeTable = h("table", { class: "vtable wide" },
    h("caption", { text: `Узлы · ${nodes.length}` }),
    h("thead", {}, h("tr", {}, ["Узел", "Слой", "Вид", "По модели", "Сейчас", "Последнее", `Коммиты за ${view.rateDays} дн.`, `за ${view.days.length} дн.`, "ИИ / человек", view.hasBranches ? "Ветки" : null].filter(Boolean).map((t) => h("th", { scope: "col", text: t })))),
    h("tbody", {}, nodes.map((n) => {
      const ai = n.git?.window?.ai ?? (n.id === "author" ? 0 : sum(n.daysAi));
      const hu = n.git?.window?.human ?? sum(n.daysHuman);
      return h("tr", {},
        h("th", { scope: "row" }, h("button", { type: "button", class: "link", on: pick(`n:${n.id}`), text: n.name })),
        h("td", { text: layerName(n.layer) }),
        h("td", { text: cat.nodeKinds.get(n.kind)?.name || n.kind }),
        h("td", { text: cat.statuses.get(n.status)?.name || n.status }),
        h("td", { text: STATE_NAMES[n.activity] || n.activity }),
        h("td", { text: n.last ? fmtDate(n.last) : "—" }),
        h("td", { class: "num", text: n.kind === "repo" || n.kind === "actor" ? fmtNum(n.week) : "—" }),
        h("td", { class: "num", text: n.kind === "repo" || n.kind === "actor" ? fmtNum(n.windowTotal) : "—" }),
        h("td", { class: "num", text: n.git ? `${fmtNum(ai)} / ${fmtNum(hu)}` : "—" }),
        view.hasBranches ? h("td", { class: "num", text: n.kind === "repo" ? String(n.branches.count) : "—" }) : null,
      );
    })),
  );

  const flowTable = h("table", { class: "vtable wide" },
    h("caption", { text: `Потоки · ${flows.length}` }),
    h("thead", {}, h("tr", {}, ["Откуда → куда", "Вид", "Кто движет", "По модели", "По данным", `За ${view.rateDays} дн.`, `за ${view.days.length} дн.`, "Последнее"].map((t) => h("th", { scope: "col", text: t })))),
    h("tbody", {}, flows.map((f) => h("tr", {},
      h("th", { scope: "row" }, h("button", { type: "button", class: "link", on: pick(`f:${f.id}`), text: `${view.nodeById.get(f.from).name} → ${view.nodeById.get(f.to).name}` })),
      h("td", { text: cat.flowKinds.get(f.kind)?.name || f.kind }),
      h("td", {}, h("i", { class: `sw drv-${f.driver}`, "aria-hidden": "true" }), cat.drivers.get(f.driver)?.name || f.driver),
      h("td", { text: cat.statuses.get(f.status)?.name || f.status }),
      h("td", { text: STATE_NAMES[f.state] || f.state }),
      h("td", { class: "num", text: ["flowing", "quiet"].includes(f.state) ? fmtNum(f.week) : "—" }),
      h("td", { class: "num", text: ["flowing", "quiet"].includes(f.state) ? fmtNum(f.total) : "—" }),
      h("td", { text: f.lastEvent ? fmtDate(f.lastEvent) : "—" }),
    ))),
  );
  const note = h("p", { class: "muted", text: "«Неизвестно» — сигнал есть, наблюдение не удалось; «не наблюдается» — сигнала нет по устройству; ни то, ни другое не ноль. Фильтры схемы действуют и здесь." });
  return h("div", { class: "tables" }, note, h("div", { class: "scroll" }, nodeTable), h("div", { class: "scroll" }, flowTable));
}

export function reconView(view) {
  const out = [];
  const reg = view.registry;
  if (reg) {
    out.push(h("h3", { text: "Сверка с реестром DPmeta" }));
    out.push(h("dl", { class: "kvs" },
      h("div", { class: "kv" }, h("dt", { text: "Реестр" }), h("dd", { text: reg.checked ? `прочитан (${reg.ref || "?"}), репозиториев ${reg.registry_repos ?? "?"}` : "не прочитан" })),
      h("div", { class: "kv" }, h("dt", { text: "Контракты" }), h("dd", { text: `${(reg.contracts_covered || []).length} из ${(reg.contracts || []).length} покрыты потоками` })),
    ));
    const list = (title, items) => (items && items.length ? [h("h4", { text: `${title} · ${items.length}` }), h("ul", {}, items.map((x) => h("li", {}, h("code", { text: x }))))] : []);
    out.push(...list("В реестре есть, в модели нет", reg.missing_in_model));
    out.push(...list("В модели есть, в реестре нет", reg.not_in_registry));
  }
  const diags = view.diagnostics || [];
  out.push(h("h3", { text: `Диагностика сборщика · ${diags.length}` }));
  if (!diags.length) out.push(h("p", { class: "muted", text: "Записей нет." }));
  const groups = new Map();
  for (const d of diags) {
    if (!groups.has(d.code)) groups.set(d.code, []);
    groups.get(d.code).push(d);
  }
  for (const [code, list] of groups) {
    out.push(h("h4", {}, h("code", { text: code }), ` · ${list.length}`));
    out.push(h("ul", {}, list.map((d) => h("li", {}, h("code", { text: d.subject }), ` — ${d.message}`))));
  }
  return h("div", { class: "recon" }, out);
}
