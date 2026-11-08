// Снимки и раскладка страниц сайта в Chromium (Playwright) — визуальная регрессия сайта (П2-17).
//
//   node tools/render/site_shots.js <job.json>
//
// job.json: {"base": "http://127.0.0.1:PORT", "pages": ["/", "/about/"], "widths": [390, 1280],
//            "themes": ["dark", "light"], "height": 900, "out": "<каталог снимков>"}
// Тема — настройка системы посетителя (prefers-color-scheme, Д49); снимок светлой — «…-light.png».
// На выходе (stdout) — JSON: по странице и ширине — снимок, высота, прокрутка вбок,
// элементы за краем экрана, сломанные изображения, шрифты, нарушения CSP,
// ошибки консоли и запросов, мелкий текст, малые цели касания. Правила по этим
// данным применяет tools/cs/site/shots.py. Сеть — только локальный сервер.
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
  console.error('site_shots.js: Playwright не найден (NODE_PATH или npm i -g playwright)');
  process.exit(2);
}

function slug(p) {
  const s = p.replace(/^\/+|\/+$/g, '').replace(/[^a-zA-Z0-9]+/g, '-');
  return s || 'home';
}

// Снимается в странице: всё, что видит человек на этой ширине.
function measure() {
  const vw = window.innerWidth;
  const visible = (el) => {
    const r = el.getBoundingClientRect();
    const cs = getComputedStyle(el);
    return r.width > 1 && r.height > 1 && cs.visibility !== 'hidden' && cs.display !== 'none';
  };
  const label = (el) => {
    const t = (el.innerText || el.getAttribute('alt') || '').trim().replace(/\s+/g, ' ').slice(0, 40);
    const svg = el.closest && el.closest('svg');  // элемент графика — назвать график
    const where = svg && svg !== el ? `svg#${(svg.getAttribute('aria-labelledby') || '').split(' ')[0].replace(/-t$/, '')} ` : '';
    const cls = el.getAttribute && el.getAttribute('class');
    return where + el.tagName.toLowerCase() + (cls ? '.' + cls.split(' ')[0] : '') + (t ? ` «${t}»` : '');
  };
  // скрыто от глаз: внутри закрытого <details> (кроме summary) или в контейнере со своей
  // прокруткой вбок, который сам не выходит за экран, — такой элемент страницу не раздвигает
  const clipped = (el) => {
    const d = el.closest('details:not([open])');
    if (d && !el.closest('summary')) return true;
    for (let a = el.parentElement; a && a !== document.body; a = a.parentElement) {
      const ox = getComputedStyle(a).overflowX;
      if (ox === 'auto' || ox === 'scroll' || ox === 'hidden' || ox === 'clip') {
        const ar = a.getBoundingClientRect();
        return ar.right <= vw + 1 && ar.left >= -1;
      }
    }
    return false;
  };
  const overflow = [];
  for (const el of document.body.querySelectorAll('*')) {
    if (!visible(el) || clipped(el)) continue;
    const r = el.getBoundingClientRect();
    if (r.right > vw + 1 || r.left < -1) {
      // предок уже за краем — достаточно его
      if (!overflow.some((o) => o.el.contains(el))) overflow.push({ el, right: Math.round(r.right), left: Math.round(r.left) });
    }
  }
  const images = [...document.images].map((img) => ({
    src: img.getAttribute('src'), ok: img.complete && img.naturalWidth > 0, alt: img.getAttribute('alt'),
  }));
  let minFont = 1e9;
  const small = [];
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  while (walker.nextNode()) {
    const n = walker.currentNode;
    if (!n.textContent.trim() || !n.parentElement || !visible(n.parentElement)) continue;
    const fs = parseFloat(getComputedStyle(n.parentElement).fontSize);
    if (fs < minFont) minFont = fs;
    if (fs < 12 && small.length < 5) small.push(`${label(n.parentElement)}: ${fs}px`);
  }
  const targets = [];
  for (const el of document.querySelectorAll('nav a, .links a, button, a.lang, footer a')) {
    if (!visible(el)) continue;
    const r = el.getBoundingClientRect();
    if (r.height < 24 || r.width < 24) targets.push(`${label(el)}: ${Math.round(r.width)}×${Math.round(r.height)}`);
  }
  // Пункты меню — не меньше 44 × 44 (WCAG 2.5.5, Д49), на любой ширине.
  const nav = [];
  for (const el of document.querySelectorAll('nav a')) {
    if (!visible(el)) continue;
    const r = el.getBoundingClientRect();
    if (r.height < 43.5 || r.width < 43.5) nav.push(`${label(el)}: ${Math.round(r.width)}×${Math.round(r.height)}`);
  }
  return {
    viewport: vw,
    scrollWidth: document.documentElement.scrollWidth,
    height: document.documentElement.scrollHeight,
    overflow: overflow.slice(0, 5).map((o) => `${label(o.el)} (${o.left}…${o.right} px)`),
    images,
    // fonts.check() истинно и без @font-face — нужен именно загруженный FontFace
    plex: [...document.fonts].some((f) => f.family.replace(/["']/g, '') === 'IBM Plex Sans' && f.status === 'loaded'),
    fontFamily: getComputedStyle(document.body).fontFamily,
    minFont: minFont === 1e9 ? null : minFont,
    smallText: small,
    smallTargets: targets.slice(0, 8),
    navTargets: nav.slice(0, 8),
    h1: document.querySelectorAll('h1').length,
    csp: (window.__cspViolations || []).slice(0, 5),
  };
}

(async () => {
  const job = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
  const { chromium } = loadPlaywright();
  const launch = { args: ['--no-sandbox', '--font-render-hinting=none', '--disable-lcd-text'] };
  if (process.env.CS_CHROMIUM) launch.executablePath = process.env.CS_CHROMIUM;
  const browser = await chromium.launch(launch);
  fs.mkdirSync(job.out, { recursive: true });
  const results = [];
  try {
    for (const theme of job.themes || ['dark']) for (const width of job.widths) {
      const ctx = await browser.newContext({ viewport: { width, height: job.height || 900 }, deviceScaleFactor: 1, colorScheme: theme });
      await ctx.addInitScript(() => {
        window.__cspViolations = [];
        document.addEventListener('securitypolicyviolation', (e) => {
          window.__cspViolations.push(`${e.violatedDirective}: ${e.blockedURI || 'inline'}`);
        });
      });
      for (const p of job.pages) {
        const page = await ctx.newPage();
        const console_ = [];
        const failed = [];
        page.on('console', (m) => {
          // CSP и неудачные загрузки ловятся отдельно (csp, failed) — без дублей
          const s = m.text();
          if (m.type() === 'error' && !/^Refused to |^Failed to load resource/.test(s)) console_.push(s.slice(0, 200));
        });
        page.on('pageerror', (e) => console_.push(String(e).slice(0, 200)));
        page.on('requestfailed', (r) => failed.push(`${r.url().replace(job.base, '')}: ${r.failure() && r.failure().errorText}`));
        page.on('response', (r) => {
          const u = r.url();
          if (u.startsWith(job.base) && r.status() >= 400 && u.replace(job.base, '') !== p) failed.push(`${u.replace(job.base, '')}: HTTP ${r.status()}`);
        });
        const resp = await page.goto(job.base + p, { waitUntil: 'networkidle' });
        await page.evaluate(() => document.fonts.ready);
        const m = await page.evaluate(measure);
        const shot = path.join(job.out, `${slug(p)}-${width}${theme === 'dark' ? '' : '-' + theme}.png`);
        await page.screenshot({ path: shot, fullPage: true });
        results.push({ path: p, width, theme, status: resp ? resp.status() : 0, shot, console: console_, failed, ...m });
        await page.close();
      }
      await ctx.close();
    }
  } finally {
    await browser.close();
  }
  process.stdout.write(JSON.stringify(results));
})().catch((e) => { console.error(String(e)); process.exit(1); });
