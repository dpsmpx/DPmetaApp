// Режим «показ» (F-32, D-08) и публичный формат (D-15).

import test from "node:test";
import assert from "node:assert/strict";

import { showMode } from "../src/main.js";
import { normalize } from "../src/data.js";
import { model, state } from "./fixture.js";

test("показ: имя закрытого-неназываемого и имена веток скрыты", () => {
  const { model: m, state: s } = showMode(model(), state());
  const beta = m.nodes.find((n) => n.id === "beta");
  assert.equal(beta.name, "закрытый проект");
  assert.ok(!JSON.stringify(m).includes("Beta"));
  assert.equal(s.nodes.alpha.git.branches, 1, "ветки — числом");
  assert.ok(!JSON.stringify(s).includes("claude/x"));
  const v = normalize(m, s);
  assert.equal(v.nodeById.get("alpha").branches.count, 1);
  assert.deepEqual(v.nodeById.get("alpha").branches.list, []);
});
