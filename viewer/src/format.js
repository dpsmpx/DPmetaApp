// Форматирование для просмотрщика: даты, возраст, числа, склонения (D-10: тексты по-русски).

const MSK_OFFSET_MIN = 180; // дни и время — по московскому времени автора (rules.TZ)

export function toMsk(iso) {
  if (!iso) return null;
  const t = Date.parse(iso.length === 10 ? iso + "T00:00:00+03:00" : iso);
  if (Number.isNaN(t)) return null;
  return new Date(t + MSK_OFFSET_MIN * 60000);
}

function pad(n) {
  return String(n).padStart(2, "0");
}

// «06.10.2026»; у даты без времени — то же.
export function fmtDate(iso) {
  const d = toMsk(iso);
  if (!d) return "—";
  return `${pad(d.getUTCDate())}.${pad(d.getUTCMonth() + 1)}.${d.getUTCFullYear()}`;
}

// «06.10.2026 20:03»; у даты без времени — только дата.
export function fmtDateTime(iso) {
  if (!iso) return "—";
  if (iso.length === 10) return fmtDate(iso);
  const d = toMsk(iso);
  if (!d) return "—";
  return `${fmtDate(iso)} ${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}`;
}

export function plural(n, one, few, many) {
  const a = Math.abs(n) % 100;
  const b = a % 10;
  if (a > 10 && a < 20) return many;
  if (b > 1 && b < 5) return few;
  if (b === 1) return one;
  return many;
}

// Возраст момента относительно now: «12 мин назад», «3 ч назад», «2 дн. назад».
// Дата без времени — возраст в днях от начала дня.
export function fmtAge(iso, now = Date.now()) {
  if (!iso) return "нет данных";
  const t = Date.parse(iso.length === 10 ? iso + "T00:00:00+03:00" : iso);
  if (Number.isNaN(t)) return "нет данных";
  const min = Math.max(0, Math.round((now - t) / 60000));
  if (iso.length === 10) {
    const days = Math.floor(min / 1440);
    if (days === 0) return "сегодня";
    if (days === 1) return "вчера";
    return `${days} ${plural(days, "день", "дня", "дней")} назад`;
  }
  if (min < 1) return "только что";
  if (min < 60) return `${min} мин назад`;
  const h = Math.round(min / 60);
  if (h < 48) return `${h} ч назад`;
  const days = Math.round(h / 24);
  return `${days} ${plural(days, "день", "дня", "дней")} назад`;
}

// Срез старше суток — «устарел» (F-31).
export function isStale(iso, now = Date.now()) {
  const t = Date.parse(iso || "");
  return Number.isNaN(t) ? true : now - t > 24 * 3600 * 1000;
}

export function fmtNum(n) {
  if (n === null || n === undefined) return "—";
  return String(n).replace(/\B(?=(\d{3})+(?!\d))/g, " ");
}

export function fmtShare(part, total) {
  if (!total) return "—";
  return `${Math.round((100 * part) / total)} %`;
}

// Дни окна: ISO-даты от start до end включительно.
export function windowDays(start, days) {
  const out = [];
  const t0 = Date.parse(start + "T00:00:00Z");
  for (let i = 0; i < days; i++) {
    const d = new Date(t0 + i * 86400000);
    out.push(`${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`);
  }
  return out;
}

export function sum(a) {
  return (a || []).reduce((x, y) => x + y, 0);
}
