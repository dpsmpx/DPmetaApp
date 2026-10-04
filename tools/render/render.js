// Рендер композиции HTML → PNG/JPEG в Chromium (Playwright) и снятие раскладки.
//
//   node tools/render/render.js <page.html> <out.png|out.jpg> <width> <height> <layout.json> [quality]
//
// Страница открывается с диска (file://), сеть не нужна: шрифты и фотография —
// локальные файлы. Раскладка — элементы с data-role: рамка элемента и рамка
// самого текста, размер и насыщенность шрифта, токены цвета, признак
// переполнения и яркость фона под текстом (снимок со скрытым текстом). По ней
// tools/cs/visual.py проверяет читаемость, контраст, число групп текста и
// безопасные зоны.
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
      const isText = el.dataset.text !== '0';
      let tb = null;
      if (isText) {
        const range = document.createRange();
        range.selectNodeContents(el);
        const q = range.getBoundingClientRect();
        tb = { x: q.x, y: q.y, w: q.width, h: q.height };
      }
      out.push({
        role: el.dataset.role,
        group: el.dataset.group || null,
        text: isText ? (el.innerText || '').trim() || null : null,
        x: r.x, y: r.y, w: r.width, h: r.height,
        text_box: tb,
        font_px: parseFloat(cs.fontSize),
        font_weight: parseInt(cs.fontWeight, 10),
        font_family: cs.fontFamily,
        fg: el.dataset.fg || null,
        bg: el.dataset.bg || null,
        // Переполнение — только по оси, где у блока задан размер (иначе блок растёт с текстом,
        // а глифы выше line-height — не переполнение); допуск 2 px на дробные размеры шрифта.
        overflow: isText && ((!!el.style.width && el.scrollWidth > el.clientWidth + 2) ||
                             (!!el.style.height && el.scrollHeight > el.clientHeight + 2)),
      });
    }
    return out;
  });
  const type = outPath.endsWith('.jpg') ? 'jpeg' : 'png';
  const shot = { path: outPath, type, clip: { x: 0, y: 0, width, height } };
  if (type === 'jpeg') shot.quality = parseInt(quality || '90', 10);
  await page.screenshot(shot);

  // Фон под текстом: тот же кадр с прозрачным текстом; яркость — по пикселям рамки текста.
  await page.addStyleTag({ content:
    '[data-role]:not([data-text="0"]){color:transparent!important;text-shadow:none!important}' +
    '[data-role]:not([data-text="0"]) *{visibility:hidden!important}' });
  const bgShot = await page.screenshot({ type: 'png', clip: { x: 0, y: 0, width, height } });
  const boxes = layout.map(e => e.text_box);
  const lum = await page.evaluate(async ({ b64, boxes, width, height }) => {
    const img = new Image();
    img.src = 'data:image/png;base64,' + b64;
    await img.decode();
    const cv = document.createElement('canvas');
    cv.width = width; cv.height = height;
    const ctx = cv.getContext('2d');
    ctx.drawImage(img, 0, 0);
    const lin = c => { c /= 255; return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4); };
    return boxes.map(b => {
      if (!b || b.w < 1 || b.h < 1) return null;
      const x0 = Math.max(0, Math.floor(b.x)), y0 = Math.max(0, Math.floor(b.y));
      const x1 = Math.min(width, Math.ceil(b.x + b.w)), y1 = Math.min(height, Math.ceil(b.y + b.h));
      if (x1 <= x0 || y1 <= y0) return null;
      const d = ctx.getImageData(x0, y0, x1 - x0, y1 - y0).data;
      const vals = [];
      for (let i = 0; i < d.length; i += 4) {
        vals.push(0.2126 * lin(d[i]) + 0.7152 * lin(d[i + 1]) + 0.0722 * lin(d[i + 2]));
      }
      vals.sort((a, b) => a - b);
      // 95-й перцентиль: самый светлый фон под светлым текстом, без единичных пикселей.
      return { p95: vals[Math.floor(vals.length * 0.95)], max: vals[vals.length - 1] };
    });
  }, { b64: bgShot.toString('base64'), boxes, width, height });
  layout.forEach((e, i) => { e.bg_luminance = lum[i]; });

  fs.writeFileSync(layoutPath, JSON.stringify({
    width, height, fonts_loaded: [...new Set(fontsOk)], external_requests: external, elements: layout,
  }, null, 1));
  await browser.close();
})().catch(e => { console.error(e); process.exit(1); });
