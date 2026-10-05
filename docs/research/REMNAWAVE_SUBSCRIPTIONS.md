# Подписки панели Remnawave

Дата: **5 октября 2026**. Зачем: автор DPpass пользуется подпиской,
которую выдаёт панель Remnawave, и DPpass должен такие подписки
поддерживать. Решение по итогам — D-33.

Источники:
- документация Remnawave, «HWID device limit» (`docs.rw/features/hwid-device-limit`),
  прочитана 5 октября 2026;
- исходник генератора ссылок `remnawave/backend`
  (`src/modules/subscription-template/generators/xray.generator.service.ts`,
  ветка `main`), прочитан 5 октября 2026 — только как источник фактов
  о формате, код не переносится (D-20);
- ответы подписки автора на запросы 5 октября 2026, с его разрешения
  (`docs/direction/RESEARCH_SCOPE.md`). Адрес подписки — секрет (в нём
  токен), поэтому ни адрес, ни значения из ответов здесь не записаны;
  записаны вид ответа, имена заголовков, схемы и имена параметров.

## 1. Лимит устройств (HWID)

- При включённом лимите панель ждёт заголовок запроса `x-hwid`. По
  документации обязателен только он. Это непрозрачная строка, которую
  выбирает клиент: 10–64 символа из латиницы, цифр, `=` и `-`.
  `x-device-os`, `x-ver-os`, `x-device-model` и `User-Agent` нужны
  только для более точного описания устройства и не обязательны.
- Без `x-hwid` панель отвечает признаками `x-hwid-active: true` и
  `x-hwid-not-supported: true`, а вместо серверов отдаёт заглушку — одну
  запись «App not supported» (VLESS без защиты на служебный адрес).
  Документация говорит о коде 404, но подписка автора ответила 200 с
  заглушкой: так панель ведёт себя с включёнными «своими» именами
  записей.
- Если устройств уже столько, сколько разрешено, ответ — признак
  `x-hwid-max-devices-reached: true` и такая же заглушка. Новое
  устройство при этом не добавляется.
- В карточке пользователя панели видно, с каких устройств добавлена
  подписка, и их можно удалить.

Проверено на подписке автора. Без `x-hwid` пришла заглушка — со всеми
тремя испробованными `User-Agent` (`Python-urllib`, `DPpass/0.1`,
`v2rayNG/…`). Со случайным `x-hwid` и исчерпанным лимитом —
`x-hwid-max-devices-reached` и заглушка. После того как автор докупил
место, — серверы.

## 2. Формат ответа

- **Формат выбирает `User-Agent`.** Неизвестному клиенту (в том числе
  `DPpass/…`) панель отдала список ссылок в base64. Строке
  `v2rayNG/…` — JSON: массив готовых конфигураций Xray с DNS,
  маршрутизацией поставщика и локальными входами SOCKS/HTTP на
  127.0.0.1. Свой `User-Agent` DPpass даёт список ссылок, и чужой
  клиент изображать не нужно (D-01).
- **Ссылки** (генератор `remnawave/backend`):
  - `vless://UUID@адрес:порт?…#имя` — `type`, `security`, `encryption`,
    `flow`, транспорт (`path`, `host`, `headerType`, `serviceName`,
    `mode`, `authority`, `extra`), TLS (`sni`, `fp`, `alpn`, `pcs`,
    `vcn`), REALITY (`pbk`, `sid`, `pqv`, `spx`), `fm` (finalmask Xray в
    JSON);
  - `trojan://пароль@…` — те же, кроме `encryption`/`flow`;
  - `ss://BASE64(метод:пароль)@адрес:порт#имя` — стандартный base64,
    без параметров;
  - `hysteria2://пароль@адрес:порт/?…#имя` — `sni`, `obfs=salamander` и
    `obfs-password`, `pinSHA256`, `fm`.
  Параметр выдаётся, только если он задан на сервере.
- **Заголовки ответа подписки автора.** Информационные:
  `profile-title` и `announce` (`base64:…`), `profile-update-interval`,
  `subscription-userinfo`, `profile-web-page-url`, `support-url` (пустой).
  Управляющие: `hide-settings`, `new-domain`, `routing` (пустой),
  `ping-type`, `ping-result`, `check-url-via-proxy`,
  `notification-subs-expire`, `providerid`,
  `subscription-always-hwid-enable`, `subscription-auto-update-enable`,
  `subscription-ping-onopen-enabled`, `subscription-request-timeout`,
  `subscriptions-collapse`. Признаки лимита: `x-hwid-*`.
  Незаполненные поля приходят пустыми заголовками.

## 3. Состав подписки автора и что из него выражает DPpass

35 записей. В IR v1 (D-16) без потери смысла выражаются 27:

| Записей | Вид | В DPpass |
|---|---|---|
| 23 | VLESS + REALITY + Vision, TCP, `fp=firefox`, без `sid` | ✓ |
| 4 | VLESS + WebSocket + TLS, `fp=firefox`, `alpn=http/1.1` | ✓ |
| 2 | VLESS + REALITY + Vision, `fp=qq` | ✗ отпечатка `qq` нет в IR |
| 3 | VLESS + WebSocket + TLS, `fp=edge` | ✗ отпечатка `edge` нет в IR |
| 1 | VLESS + XHTTP + REALITY, `extra` (`xmux`, `xPaddingBytes`, `scMaxEachPostBytes`, `scMinPostsIntervalMs`, `noGRPCHeader`) | ✗ настроек XHTTP нет в IR |
| 1 | Hysteria2, `fm` = `quicParams.congestion: bbr`, без `obfs` | ✗ управления перегрузкой нет в IR |
| 1 | Shadowsocks `chacha20-ietf-poly1305` | ✗ старый AEAD без защиты от повтора (D-16) |

Отвергнутые записи DPpass пропускает с названием параметра, а не
подключает «похоже» (D-05). Чтобы принять отпечатки `edge`/`qq`, нужны
новые значения в закрытом наборе формата, а `extra` XHTTP и
`congestion` Hysteria2 — новые поля. По правилам формата
(`docs/CONFIG_FORMAT.md`, §8) это новая версия формата (`TODO.md`).
