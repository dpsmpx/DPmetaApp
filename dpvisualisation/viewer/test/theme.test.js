// Тема (D-06, N-06) и неподвижность (D-16): контраст текста по токенам обеих тем,
// цвета — только переменными в одном месте, в стилях нет анимаций.

import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const css = readFileSync(new URL("../style.css", import.meta.url), "utf8");

function block(selector) {
  const i = css.indexOf(`${selector} {`);
  assert.ok(i >= 0, `нет блока ${selector}`);
  return css.slice(i, css.indexOf("}", i));
}

function vars(text) {
  const out = {};
  for (const m of text.matchAll(/--([\w-]+):\s*(#[0-9a-fA-F]{6})\s*;/g)) out[m[1]] = m[2];
  return out;
}

function luminance(hex) {
  const c = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255).map((v) => (v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4));
  return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
}

export function contrast(a, b) {
  const [x, y] = [luminance(a), luminance(b)].sort((p, q) => q - p);
  return (x + 0.05) / (y + 0.05);
}

const dark = vars(block(".dpvis"));
const light = { ...dark, ...vars(block('.dpvis[data-theme="light"]')) };

test("контраст текста не ниже 4,5 на фоне и панелях обеих тем (N-06)", () => {
  for (const [name, t] of [["тёмная", dark], ["светлая", light]]) {
    for (const fg of ["text", "text-2", "muted"]) {
      for (const bg of ["bg", "surface"]) {
        const k = contrast(t[fg], t[bg]);
        assert.ok(k >= 4.5, `${name}: --${fg} на --${bg} — ${k.toFixed(2)}`);
      }
    }
  }
});

test("тёмная тема — нейтральные токены автора (D-06)", () => {
  // SocialMediaImage:assets/brand/tokens.json — ink, graphite, line, paper, mist
  assert.equal(dark.bg.toLowerCase(), "#111214");
  assert.equal(dark.surface.toLowerCase(), "#1a1b1e");
  assert.equal(dark.line.toLowerCase(), "#2e3036");
  assert.equal(dark.text.toLowerCase(), "#eeeeec");
  assert.equal(dark.muted.toLowerCase(), "#a3a6ad");
});

test("цвета — только в переменных двух блоков темы (D-06)", () => {
  const rest = css.replace(block(".dpvis"), "").replace(block('.dpvis[data-theme="light"]'), "");
  const stray = rest.match(/#[0-9a-fA-F]{3,8}\b/g) || [];
  assert.deepEqual(stray, [], "цвет вне переменных темы");
});

test("без анимаций (D-16)", () => {
  assert.doesNotMatch(css, /@keyframes|\banimation\s*:|\btransition\s*:/);
});
