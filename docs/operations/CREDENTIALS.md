# Учётные данные

Решения — Д12 (только у автора), Д25 (граница площадка × окружение);
правила — `docs/SECURITY.md`. В репозитории — только **имя** переменной
(`credential_ref` в `data/platforms/destinations.yaml`), значение — у
автора: переменная окружения в его терминале или локальное хранилище
секретов, из которого она выставляется. Облачным сессиям ИИ рабочие
токены не передаются никогда.

## 1. Где хранить

| Способ | Когда |
|---|---|
| Переменная окружения в терминале автора (`export CS_PROD_TELEGRAM_BOT_TOKEN=…` перед запуском, не в профиле оболочки) | сейчас: просто, ничего не пишется на диск |
| Менеджер паролей / хранилище ОС, из которого переменная выставляется скриптом автора | удобно и безопасно; скрипт — вне репозитория |
| Зашифрованный файл (age/sops) в `local/` (закрыт `.gitignore`) | если нужно хранить на диске |

`local/credentials.yaml` (вне Git) — по желанию: даты смены и срока для
`health`, **без значений**:

```yaml
CS_PROD_TELEGRAM_BOT_TOKEN: {rotated: 2026-10-05, expires: null}
CS_PROD_YOUTUBE_OAUTH: {rotated: 2026-10-05, expires: 2026-10-12}
```

## 2. Подключение площадок

### Telegram (бот в канал) — первая площадка (П4)
1. @BotFather → новый бот (`/newbot`); токен — сразу в хранилище
   секретов, не в чат и не в репозиторий.
2. Создать **закрытый тестовый канал** без подписчиков; добавить бота
   администратором с правом «Публикация сообщений».
3. Узнать числовые id: канала (`-100…`) и бота. Записать в
   `destinations.yaml`: `tg-test.target.id`, `tg-test.account.id`,
   `status: active`.
4. `export CS_STAGING_TELEGRAM_BOT_TOKEN=…`;
   `python3 -m tools.cs health --remote` — сверка бота и канала.
5. `publish … --to tg-devlog --safe` — публикация в тестовый канал.
6. Для дневника: бот — администратор канала-дневника с «Публикацией
   сообщений» (и «Редактированием», если нужна правка); id канала и
   бота → `tg-devlog`, `status: active`; токен рабочий — отдельная
   переменная `CS_PROD_TELEGRAM_BOT_TOKEN` (лучше отдельный бот для
   staging).

### Сайт: Beget (rsync по SSH, Д35, Д36)
Делает автор на своей машине; в репозиторий и в облачные сессии не
попадает ничего, кроме имени переменной.
1. Панель Beget (`cp.beget.com`) → **главная страница** → блок с
   общей / технической информацией об аккаунте: переключатель **SSH**
   (по умолчанию выключен) и **имя сервера** для подключения. Логин и
   пароль — те же, что для панели; порт 22. Проверить: `ssh
   логин@сервер`, затем на сервере `rsync --version`. rsync на сервере
   Beget есть: `site deploy --dry-run` у автора прошёл (05.10.2026);
   пропадёт — сообщить сессии: выгрузка перейдёт на `kind: command`
   (например, `lftp mirror` по SFTP). Источник — база знаний Beget, «Как подключиться к серверу по
   SSH» и «Что такое SSH» (04.10.2026). В мобильной версии панели —
   значок ⓘ справа от имени на главной: «Тариф», «Сервер» и
   переключатель «SSH-доступ» (снимок автора, 04.10.2026: SSH включён,
   сервер — вида `<логин>.beget.tech`).
2. Первый вход — вручную: подтвердить отпечаток ключа сервера
   (`known_hosts`). Выгрузка идёт с `BatchMode=yes` и без этого шага
   откажет, а не спросит.
3. Отдельный ключ только для Beget: `ssh-keygen -t ed25519 -f
   ~/.ssh/beget_dpsmpx`, открытую часть (`.pub`) — в
   `~/.ssh/authorized_keys` на сервере (так Beget и описывает вход по
   ключу: статья «Автоматическая SSH-авторизация по ключу»). В
   `~/.ssh/config`: `Host <имя сервера>` → `IdentityFile
   ~/.ssh/beget_dpsmpx`. Пароль панели после этого сессиям ИИ не нужен и
   не передаётся.
4. `export CS_PROD_SITE_DEPLOY_TARGET='логин@сервер:dpsmpx.com/public_html/'`
   — в профиле оболочки, не в репозитории. Путь обязан кончаться на
   `public_html/` (`guard_suffix`), иначе выгрузка откажет.
5. `python3 -m tools.cs health` — строка «сайт / выгрузка»;
   `python3 -m tools.cs site deploy --dry-run` — что изменится.
6. Выгрузка разрешена (Д34): `production.live: true` в
   `data/website/site.yaml`, `site-production` — `status: active` в
   `destinations.yaml`. Сначала `site deploy --dry-run` — что изменится
   и удалится (`/.well-known/` под защитой), затем `site deploy` или
   `publish … --to site-production --production`.

`rsync` — Linux, macOS, WSL на Windows, Android (Termux — ниже).
Локальный Claude Code запускает выгрузку только после «да» на запрос
разрешения (`.claude/settings.json`: `ask` на `publish`, `site deploy`,
`rsync`, `ssh`, `scp`, `sftp`; чтение `~/.ssh/**` запрещено). Правила
`ask` спрашивают в любом режиме Claude Code, но сверяют текст команды —
это защита от случайности; ключ на машине доступен локальному агенту
технически (Д36).

#### С телефона: Termux (Android)
Автор работает с телефона в Termux (сообщено 4 октября 2026). Что
отличается от компьютера:
- **Claude Code в Termux официально не поддерживается.** Поддерживаемые
  системы: macOS, Windows, Ubuntu, Debian, Alpine; пакет npm — для
  `linux-arm64` на glibc и musl, а в Android — Bionic
  (code.claude.com/docs/en/setup, 04.10.2026). Для выгрузки он и не
  нужен: по Д36 выгружает автор, а запуск вне сессии ИИ проверка вида
  сессии не останавливает (`session_may_deploy` в
  `tools/cs/site/deploy.py`). Команды ниже автор вводит сам.
- **Рендер изображений** (Playwright, Chromium) на Android не работает;
  `site deploy` его не требует: сборка сайта — чистый Python.
- `rpds-py` (зависимость `jsonschema` ≥ 4.18) собирается из исходников
  компилятором Rust: готовых колёс для Termux нет.

Шаги. `ЛОГИН` и `СЕРВЕР` — из окна ⓘ на главной странице панели; по
Д35 адрес выгрузки в Git не записывается, поэтому здесь — заполнители:
перед запуском их **заменить**. Остальное вводится буквально; где
команда идёт на сервере, а не в Termux, — сказано (после входа меняется
приглашение строки). Команды репозитория — только из его каталога и
только через `python -m tools.cs …`: просто `site deploy` оболочка не
знает.
1. Termux — с F-Droid или из релизов `termux/termux-app` на GitHub,
   не вперемешку с другими источниками. Затем:
   `pkg update && pkg upgrade`,
   `pkg install openssh rsync git gh python python-pip clang rust`.
   `pip` в Termux не обновлять через `pip` — им управляет `pkg`.
2. Репозиторий — в домашний каталог Termux, не в `/sdcard` (там нет
   прав на исполнение): `gh auth login` (GitHub.com, HTTPS, вход через
   браузер; код вида `XXXX-XXXX` печатает Termux), `gh repo clone
   dpsmpx/SocialMediaImage ~/SocialMediaImage`. В Termux нет хранилища
   ключей: токен `gh` лежит открытым текстом в `~/.config/gh/hosts.yml`
   (`gh` так и предупреждает); потерян телефон — отозвать на GitHub,
   Settings → Applications.
3. Зависимости: `export ANDROID_API_LEVEL="$(getprop
   ro.build.version.sdk)"` (без неё maturin не соберёт `rpds-py`), затем
   `python -m pip install -r tools/requirements.txt` — несколько минут;
   проверка — `python -c "import yaml, jsonschema; print(jsonschema.__version__)"`.
   Именно `python -m pip`, а не `pip`: 04.10.2026 у автора `pip` поставил
   `jsonschema` не в тот интерпретатор, которым запускается
   `python -m tools.cs` (`ModuleNotFoundError: No module named
   'jsonschema'` после «Successfully installed»): `which -a python pip`
   показал первым `~/.local/bin/python` (Python 3.14), а `pip` — от
   Python 3.13 из `pkg`. `rpds-py` собирается под каждую версию Python
   отдельно — повторная сборка в несколько минут нормальна, прерывать её
   не нужно.
4. Первый вход паролем, шаги 1–2 выше:
   `ssh ЛОГИН@СЕРВЕР`, `yes` на отпечаток, пароль панели
   (на экране не виден). На сервере: `rsync --version | head -1`,
   `ls ~/dpsmpx.com` (ждём `public_html`), `exit`.
5. Ключ (шаг 3 выше):
   `ssh-keygen -t ed25519 -f ~/.ssh/beget_dpsmpx -C "dpsmpx deploy termux"`
   (без пароля на ключ — иначе нужен `ssh-agent`: выгрузка идёт с
   `BatchMode=yes`), затем
   `cat ~/.ssh/beget_dpsmpx.pub | ssh ЛОГИН@СЕРВЕР 'mkdir -p ~/.ssh && chmod 711 ~/.ssh && cat >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys'`
   (пароль панели — последний раз). В `~/.ssh/config` (`chmod 600`):
   ```
   Host СЕРВЕР
     User ЛОГИН
     IdentityFile ~/.ssh/beget_dpsmpx
     IdentitiesOnly yes
     ServerAliveInterval 30
   ```
   Проверка без пароля: `ssh -o BatchMode=yes СЕРВЕР 'echo ok'`.
6. Адрес выгрузки — в профиль своей оболочки (`echo $0`: `bash` →
   `~/.bashrc`, `zsh` → `~/.zshrc`; в Termux по умолчанию bash):
   `export CS_PROD_SITE_DEPLOY_TARGET='ЛОГИН@СЕРВЕР:dpsmpx.com/public_html/'`;
   новая вкладка Termux.
7. Проверки: `tools/check.sh --quick`, `python -m tools.cs health`,
   `python -m tools.cs site deploy --dry-run` — ничего не выкладывает,
   последняя строка «изменений: N».
8. Выгрузка (разрешена, Д34): `git pull` в `~/SocialMediaImage`, снова
   `python -m tools.cs site deploy --dry-run` — сверить, что удаляется,
   затем `python -m tools.cs site deploy`. В конце — проверка `/`,
   `/en/`, `/projects/` по HTTP: `✓` — страница на месте, `?` — адрес не
   открывается (домен не в реестре, ОР-11), `✗` — 404 или чужая
   страница.

Ключ на телефоне отзывается удалением его строки из
`~/.ssh/authorized_keys` на сервере (§3 «Ротация и отказ»); потерян
телефон — удалить строку сразу.

#### Если Beget отказывает
Точный текст ошибки и `ssh -v ЛОГИН@СЕРВЕР 2>&1 | tail -25`
показывают причину (пароль в выводе не печатается).

| Сообщение | Вероятная причина | Что делать |
|---|---|---|
| `Permission denied, please try again.` после пароля | неверный логин или пароль; SSH ещё выключен | логин — из «Общей информации», не имя и не почта; пароль — от панели (сброс — в панели); переключатель SSH включён; пароль — вставкой (долгое нажатие → Paste): клавиатура телефона может подменять символы и регистр |
| соединение закрывается сразу после пароля (`Connection closed by … port 22`) | SSH-доступ ещё не применился | выключить и включить SSH в панели, подождать несколько минут, войти снова; не помогает — тикет в поддержку Beget с текстом ошибки |
| `Connection timed out`, `Network is unreachable` | порт 22 закрыт сетью (мобильный оператор, VPN) | другая сеть или без VPN |
| `Could not resolve hostname` | опечатка в имени сервера | имя — из панели |
| `Host key verification failed` | первый вход был с `BatchMode` или отпечаток сменился | первый вход — вручную; сменился — сверить с поддержкой Beget, только потом `ssh-keygen -R СЕРВЕР` |
| `Permission denied (publickey,password)` с `BatchMode=yes` | ключ не принят | `ssh -v`: какой файл ключа предложен; на сервере `chmod 711 ~/.ssh`, `chmod 600 ~/.ssh/authorized_keys`; строка `.pub` целиком, одной строкой |
| `rsync: command not found` на сервере | rsync у Beget нет | сообщить сессии: выгрузка — `kind: command` (`lftp mirror` по SFTP; в Termux — `pkg install lftp`) |

Много неудачных попыток подряд сервер может временно блокировать
адрес — подождать перед новой попыткой (как именно у Beget —
UNKNOWN).

### VK, YouTube, Discord, GitHub, TikTok
Сейчас — ручной путь (`docs/platforms/PUBLISHING_CAPABILITY_MATRIX.md`).
Подключение — в своих фазах `ROADMAP/ROADMAP.md` (П5–П8): VK — ключ
пользователя после выдачи права `wall`; YouTube — OAuth-клиент Google
(в режиме «Testing» refresh token живёт 7 дней — перевести приложение в
production или входить заново раз в неделю); Discord — URL вебхука
(это секрет целиком); GitHub — fine-grained token только на нужные
репозитории с `Contents: write`.

## 3. Ротация и отказ (§31)

| Событие | Признак | Что делать |
|---|---|---|
| Истёк срок | `AUTH_EXPIRED`; `health` — «истёк» | обновить у площадки (OAuth — повторный вход), выставить переменную, отметить `rotated` |
| Плановая смена | раз в полгода или при смене машины | выпустить новый, проверить `health --remote`, отозвать старый |
| Отзыв | токен больше не нужен | отозвать у площадки (Telegram — `/revoke` у @BotFather; VK — настройки сообщества; Google — разрешения аккаунта; Discord — удалить вебхук; GitHub — удалить token) |
| Подозрение на утечку | токен попал в чат, лог, Git, чужой компьютер | **сначала отозвать у площадки**, затем выпустить новый; если в Git — `docs/SECURITY.md`, §4 |
| Потеря | токена нет | выпустить новый; старый отозвать |

Потеря или истечение токена одной площадки **не останавливает
остальные** (§31): адресат даёт `MISSING_CREDENTIALS`/`AUTH_EXPIRED`,
публикация остаётся `FAILED` без повторов, остальные площадки и сайт
работают; канон не затронут.

## 4. Что проверяется автоматически

- `secrets` — известные формы токенов (в том числе бота Telegram,
  Discord, GitHub, Google) и файлы секретов в репозитории;
- `destinations` — имя переменной соответствует окружению, одно имя не
  используется в двух окружениях;
- журнал и логи пишутся без токенов (`core/obs.py` вычищает их тем же
  сканером);
- адрес запроса Telegram (с токеном) не попадает в ошибки и логи.
