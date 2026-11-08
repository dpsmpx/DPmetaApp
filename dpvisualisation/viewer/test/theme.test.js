// Тема (D-06, N-06) и неподвижность (D-16): контраст текста по токенам обеих тем,
// цвета — только переменными в одном месте, в стилях нет анимаций.

import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { themeFor } from "../src/main.js";

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

test("цвета — только в переменных блоков темы (D-06) и встраивания на сайт (D-28)", () => {
  const rest = [".dpvis", '.dpvis[data-theme="light"]', '.dpvis[data-embed="site"]', '.dpvis[data-embed="site"][data-theme="light"]']
    .reduce((t, b) => t.replace(block(b), ""), css);
  const stray = rest.match(/#[0-9a-fA-F]{3,8}\b/g) || [];
  assert.deepEqual(stray, [], "цвет вне переменных темы");
});

// Различимость при нарушениях цветового зрения (Machado 2009, тяжесть 1,0; ΔE CIE76 в Lab).
const CVD = {
  protan: [[0.152286, 1.052583, -0.204868], [0.114503, 0.786281, 0.099216], [-0.003882, -0.048116, 1.051998]],
  deutan: [[0.367322, 0.860646, -0.227968], [0.280085, 0.672501, 0.047413], [-0.01182, 0.04294, 0.968881]],
  tritan: [[1.255528, -0.076749, -0.178779], [-0.078411, 0.930809, 0.147602], [0.004733, 0.691367, 0.3039]],
};
const lin = (c) => (c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4);
const gam = (c) => { const v = Math.min(1, Math.max(0, c)); return v <= 0.0031308 ? 12.92 * v : 1.055 * v ** (1 / 2.4) - 0.055; };
const rgb = (hex) => [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255);
function lab([r, g, b]) {
  const [R, G, B] = [r, g, b].map(lin);
  const x = (0.4124 * R + 0.3576 * G + 0.1805 * B) / 0.95047;
  const y = 0.2126 * R + 0.7152 * G + 0.0722 * B;
  const z = (0.0193 * R + 0.1192 * G + 0.9505 * B) / 1.08883;
  const f = (t) => (t > 0.008856 ? Math.cbrt(t) : 7.787 * t + 16 / 116);
  return [116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z))];
}
const sim = (c, m) => { const l = c.map(lin); return m.map((row) => gam(row[0] * l[0] + row[1] * l[1] + row[2] * l[2])); };
const dE = (a, b) => Math.hypot(...lab(a).map((v, i) => v - lab(b)[i]));

test("на сайте — токены бренда (D-28): различимы при нарушениях цветового зрения, линии ≥ 3 к фону", () => {
  const siteDark = { ...dark, ...vars(block('.dpvis[data-embed="site"]')) };
  const siteLight = { ...light, ...vars(block('.dpvis[data-embed="site"][data-theme="light"]')) };
  // SocialMediaImage:assets/brand/tokens.json — color и site_light.
  assert.deepEqual([siteDark.human, siteDark.ai, siteDark.check].map((c) => c.toLowerCase()), ["#ff7a59", "#5cc8d7", "#7fd68e"]);
  for (const [name, t] of [["тёмная", siteDark], ["светлая", siteLight]]) {
    const drv = ["human", "ai", "check", "data"];
    for (let i = 0; i < drv.length; i++) {
      for (let j = i + 1; j < drv.length; j++) {
        for (const [mode, m] of Object.entries(CVD)) {
          const k = dE(sim(rgb(t[drv[i]]), m), sim(rgb(t[drv[j]]), m));
          assert.ok(k >= 9, `${name}, ${mode}: --${drv[i]} и --${drv[j]} — ΔE ${k.toFixed(1)}`);
        }
      }
      if (drv[i] !== "data") assert.ok(contrast(t[drv[i]], t.bg) >= 3, `${name}: --${drv[i]} к фону — ${contrast(t[drv[i]], t.bg).toFixed(2)}`);
    }
  }
});

test("без анимаций (D-16)", () => {
  assert.doesNotMatch(css, /@keyframes|\banimation\s*:|\btransition\s*:/);
});

test("тема: выбор кнопкой, иначе тема хозяина; «auto» — настройка системы (SocialMediaImage:Д49)", () => {
  assert.equal(themeFor(null, "auto", true), "light");
  assert.equal(themeFor(null, "auto", false), "dark");
  assert.equal(themeFor("dark", "auto", true), "dark", "выбор человека главнее системы");
  assert.equal(themeFor("light", "dark", false), "light");
  assert.equal(themeFor(null, "light", false), "light", "явная тема хозяина — без системы");
  assert.equal(themeFor(null, undefined, true), "dark", "без темы — тёмная, как раньше");
  assert.equal(themeFor("blue", "auto", true), "dark", "неизвестное — тёмная");
});
