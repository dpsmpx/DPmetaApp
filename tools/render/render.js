// Рендер композиции HTML → PNG/JPEG в Chromium (Playwright) и снятие раскладки.
//
//   node tools/render/render.js <page.html> <out.png|out.jpg> <width> <height> <layout.json> [quality]
//
// Страница открывается с диска (file://), сеть не нужна: шрифты и фотография —
// локальные файлы. Раскладка — элементы с data-role: рамка, размер шрифта,
// токены цвета и признак переполнения; по ней tools/cs/visual.py проверяет
// читаемость, контраст, число групп текста и безопасные зоны.
const fs = require('fs');
const path = require('path');

function loadPlaywright() {
  const candidates = [
    'playwright',
    path.join(process.env.NODE_PATH || '', 'playwright'),
    '/opt/node22/lib/node_modules/playwright',
  ];
  for (const c of candidates) {
    try { return require(c); } catch (e) { /* следующий */ }
  }
  console.error('render.js: Playwright не найден (NODE_PATH или npm i -g playwright)');
  process.exit(2);
}

(async () => {
  const [htmlPath, outPath, w, h, layoutPath, quality] = process.argv.slice(2);
  if (!layoutPath) {
    console.error('usage: render.js <page.html> <out> <width> <height> <layout.json> [quality]');
    process.exit(2);
  }
  const width = parseInt(w, 10);
  const height = parseInt(h, 10);
  const { chromium } = loadPlaywright();
  const launch = { args: ['--no-sandbox', '--font-render-hinting=none', '--disable-lcd-text'] };
  if (process.env.CS_CHROMIUM) launch.executablePath = process.env.CS_CHROMIUM;
  const browser = await chromium.launch(launch);
  const page = await browser.newPage({ viewport: { width, height }, deviceScaleFactor: 1 });
  // Только локальные файлы: любой сетевой запрос — ошибка композиции (гейт Legal).
  const external = [];
  await page.route('**/*', route => {
    const url = route.request().url();
    if (url.startsWith('file://') || url.startsWith('data:')) return route.continue();
    external.push(url);
    return route.abort();
  });
  await page.goto('file://' + path.resolve(htmlPath), { waitUntil: 'load' });
  await page.evaluate(() => document.fonts.ready);
  const fontsOk = await page.evaluate(() =>
    [...document.fonts].filter(f => f.status === 'loaded').map(f => f.family.replace(/"/g, '')));
  const layout = await page.evaluate(() => {
    const out = [];
    for (const el of document.querySelectorAll('[data-role]')) {
      const r = el.getBoundingClientRect();
      const cs = getComputedStyle(el);
      out.push({
        role: el.dataset.role,
        group: el.dataset.group || null,
        text: el.dataset.text === '0' ? null : (el.innerText || '').trim() || null,
        x: r.x, y: r.y, w: r.width, h: r.height,
        font_px: parseFloat(cs.fontSize),
        font_family: cs.fontFamily,
        fg: el.dataset.fg || null,
        bg: el.dataset.bg || null,
        overflow: el.scrollWidth > el.clientWidth + 1 || el.scrollHeight > el.clientHeight + 1,
      });
    }
    return out;
  });
  const type = outPath.endsWith('.jpg') ? 'jpeg' : 'png';
  const shot = { path: outPath, type, clip: { x: 0, y: 0, width, height } };
  if (type === 'jpeg') shot.quality = parseInt(quality || '90', 10);
  await page.screenshot(shot);
  fs.writeFileSync(layoutPath, JSON.stringify({
    width, height, fonts_loaded: [...new Set(fontsOk)], external_requests: external, elements: layout,
  }, null, 1));
  await browser.close();
})().catch(e => { console.error(e); process.exit(1); });
