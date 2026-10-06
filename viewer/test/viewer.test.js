// Тесты модулей просмотрщика без браузера: node --test viewer/test/

import test from "node:test";
import assert from "node:assert/strict";

import { fmtAge, fmtDate, fmtDateTime, isStale, plural, windowDays } from "../src/format.js";
import { classify, DataError, flowStateAt, nodeExistsAt, normalize } from "../src/data.js";
import { decode, defaults, encode, focusSet, visible } from "../src/filters.js";
import { arcTable, bezierPoint, layout, pointAtFraction } from "../src/layout.js";
import { model, state } from "./fixture.js";

test("даты — по московскому времени, возраст и «устарел»", () => {
  assert.equal(fmtDate("2026-10-05T22:30:00Z"), "06.10.2026");
  assert.equal(fmtDateTime("2026-10-06T17:03:37Z"), "06.10.2026 20:03");
  assert.equal(fmtDateTime("2026-10-06"), "06.10.2026");
  const now = Date.parse("2026-10-06T12:00:00Z");
  assert.equal(fmtAge("2026-10-06T11:48:00Z", now), "12 мин назад");
  assert.equal(fmtAge("2026-10-06T09:00:00Z", now), "3 ч назад");
  assert.equal(fmtAge(null, now), "нет данных");
  assert.equal(isStale("2026-10-05T11:00:00Z", now), true);
  assert.equal(isStale("2026-10-06T11:00:00Z", now), false);
  assert.equal(plural(21, "день", "дня", "дней"), "день");
  assert.equal(plural(12, "день", "дня", "дней"), "дней");
  assert.deepEqual(windowDays("2026-09-29", 3), ["2026-09-29", "2026-09-30", "2026-10-01"]);
});

test("classify: модель, срез, публичная сборка и понятный отказ (F-38)", () => {
  assert.ok(classify(model()).model);
  assert.ok(classify(state()).state);
  const p = classify({ schema: "dpvis-public/1", model: model(), state: state() });
  assert.equal(p.public, true);
  assert.throws(() => classify({ schema: "other/1" }), DataError);
  assert.throws(() => classify([1, 2]), DataError);
});

test("normalize: числа узлов и потоков, битые ссылки отброшены", () => {
  const v = normalize(model(), state());
  assert.equal(v.days.length, 10);
  assert.equal(v.flowById.has("dangling"), false, "поток к несуществующему узлу не рисуется");
  const a = v.nodeById.get("alpha");
  assert.equal(a.week, 15);
  assert.equal(a.branches.count, 1);
  assert.equal(a.firstIndex, 3);
  assert.equal(v.nodeById.get("author").daysHuman.at(-1), 2);
  assert.equal(v.flowById.get("work.alpha").week, 12);
});

test("normalize без среза: «нет данных», а не ноль (F-31)", () => {
  const v = normalize(model(), null);
  assert.equal(v.hasState, false);
  assert.equal(v.nodeById.get("alpha").activity, "nodata");
  assert.equal(v.flowById.get("host.srv").state, "planned");
});

test("публичная сборка: ветки — число, а не список (D-15)", () => {
  const s = state();
  s.nodes.alpha.git.branches = 3;
  delete s.nodes.alpha.git.head_time;
  s.nodes.alpha.git.head_date = "2026-10-06";
  const v = normalize(model(), s, { public: true });
  assert.equal(v.nodeById.get("alpha").branches.count, 3);
  assert.deepEqual(v.nodeById.get("alpha").branches.list, []);
});

test("шкала времени: день до первого коммита скрывает репозиторий, события дня (F-28)", () => {
  const v = normalize(model(), state());
  const a = v.nodeById.get("alpha");
  assert.equal(nodeExistsAt(a, 2), false);
  assert.equal(nodeExistsAt(a, 3), true);
  const f = v.flowById.get("work.alpha");
  assert.equal(flowStateAt(f, 9), "flowing");
  assert.equal(flowStateAt(f, 0), "quiet");
  assert.equal(flowStateAt(v.flowById.get("host.srv"), 9), "planned");
});

test("адрес вида: туда и обратно, мусор отбрасывается (F-27)", () => {
  const v = normalize(model(), state());
  const f = { ...defaults(), sel: "n:alpha", kinds: new Set(["work"]), q: "al", a7: true, day: 4, tab: "tables" };
  const back = decode(encode(f), v);
  assert.equal(back.sel, "n:alpha");
  assert.deepEqual([...back.kinds], ["work"]);
  assert.equal(back.q, "al");
  assert.equal(back.a7, true);
  assert.equal(back.day, 4);
  assert.equal(back.tab, "tables");
  assert.equal(encode(defaults()), "");
  const bad = decode("#sel=n:нет&day=99&tab=xss&q=%E0%A4%A", v);
  assert.equal(bad.sel, null);
  assert.equal(bad.day, null);
  assert.equal(bad.tab, "graph");
});

test("фильтры: виды, состояния, слои, «за 7 дней», фокус (F-26, F-27)", () => {
  const v = normalize(model(), state());
  let vis = visible(v, { ...defaults(), kinds: new Set(["work"]) });
  assert.deepEqual([...vis.flows].sort(), ["work.alpha", "work.beta"]);
  vis = visible(v, { ...defaults(), states: new Set(["planned"]) });
  assert.deepEqual([...vis.flows], ["host.srv"]);
  vis = visible(v, { ...defaults(), layers: new Set(["actors", "product"]) });
  assert.equal(vis.nodes.has("srv"), false);
  assert.equal(vis.flows.has("host.srv"), false);
  vis = visible(v, { ...defaults(), a7: true });
  assert.deepEqual([...vis.flows], ["work.alpha"]);
  assert.equal(vis.nodes.has("srv"), false);
  const fo = focusSet(v, "n:alpha");
  assert.ok(fo.nodes.has("bot") && fo.nodes.has("author") && fo.flows.has("decide.alpha"));
});

test("раскладка: все узлы на местах, без наложений, потоки — кривые", () => {
  const v = normalize(model(), state());
  const lay = layout(v);
  assert.equal(lay.nodes.size, v.nodes.length);
  const boxes = [...lay.nodes.values()];
  for (let i = 0; i < boxes.length; i++) {
    for (let j = i + 1; j < boxes.length; j++) {
      const a = boxes[i];
      const b = boxes[j];
      const overlap = a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;
      assert.equal(overlap, false, "узлы не накладываются");
    }
  }
  for (const f of v.flows) {
    const g = lay.flows.get(f.id);
    assert.match(g.d, /^M[\d.]+,[\d.]+ C/);
    const p0 = bezierPoint(g, 0);
    const p1 = bezierPoint(g, 1);
    assert.ok(Math.abs(p0.x - g.x1) < 1e-6 && Math.abs(p1.y - g.y2) < 1e-6);
    const t = arcTable(g);
    const mid = pointAtFraction(g, t, 0.5);
    assert.ok(Number.isFinite(mid.x) && Number.isFinite(mid.y));
  }
  assert.ok(lay.width > 0 && lay.height > 0);
});
