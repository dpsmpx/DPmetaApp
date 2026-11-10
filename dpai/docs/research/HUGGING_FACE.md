# Hugging Face: список файлов модели и скачивание GGUF

Датированные факты о Hugging Face Hub, на которые опираются загрузка
моделей GGUF (D-110, D-113, D-119) и клиент `HuggingFace` модуля
`providers`. Изменилось поведение — новая строка с новой датой, старая
остаётся.

## 1. Источник и дата

Проверено **10 ноября 2026** запросами из облачной сессии (без токена) к
открытой модели `Qwen/Qwen2.5-0.5B-Instruct-GGUF`, к модели с условиями
доступа `meta-llama/Llama-3.2-1B-Instruct` и к несуществующему репозиторию;
хосты хранилища — ещё по обсуждениям на форуме Hugging Face (ниже, §5).

## 2. Список файлов

| Что | Значение |
|---|---|
| Запрос | `GET https://huggingface.co/api/models/<владелец>/<имя>?blobs=true` |
| Коммит | поле `sha` — 40 знаков; файлы дальше берутся по нему, а не по `main` |
| Закрыта условиями | поле `gated`: `false` или строка (`"manual"`, `"auto"`) |
| Файлы | `siblings[]`: `rfilename` (путь в репозитории), `size`, `blobId` (SHA-1 git-объекта) |
| Сумма большого файла | у файла LFS — `lfs.sha256` (64 знака) и `lfs.size`; GGUF модели — LFS |

- Модель с условиями доступа (`gated: "manual"`) без токена: список файлов
  отдаётся (200), сам файл — 401.
- Несуществующий или приватный репозиторий без токена: 401 с телом
  `{"error":"Invalid username or password."}` — отличить «нет» от
  «закрыто» нельзя.

## 3. Скачивание

| Что | Значение |
|---|---|
| Запрос | `GET https://huggingface.co/<владелец>/<имя>/resolve/<коммит>/<путь>` |
| Файл LFS | 302, `location` — абсолютный адрес хранилища; заголовки `x-linked-size`, `x-linked-etag` (SHA-256 в кавычках), `x-repo-commit` |
| Малый файл не в LFS | 307 на относительный `/api/resolve-cache/models/…` того же `huggingface.co` |
| Хранилище 10.11.2026 | `us.aws.cdn.hf.co` (`/xet-bridge-us/…`, подписанный адрес со сроком) |
| Докачка | хранилище отвечает на `Range: bytes=N-` кодом 206 с `content-range`, `accept-ranges: bytes` |
| Закрытый файл без токена | 401 |

Токен — заголовком `Authorization: Bearer <токен>`; на хранилище он не
нужен: доступ — в подписи адреса переадресации.

## 4. Токен

Токен Hugging Face с правом чтения (`hf_…`) открывает приватные модели и
модели с условиями доступа, если человек принял условия на странице
модели. Без принятых условий — 401 или 403 и с токеном.

## 5. Хосты хранилища

Переадресация ведёт на разные хосты по региону и способу хранения (LFS
или Xet), и список меняется: `cdn-lfs.huggingface.co` → `cdn-lfs.hf.co`,
региональные `cdn-lfs-us-1.hf.co`, `cdn-lfs-eu-1.hf.co`, Xet —
`cas-bridge.xethub.hf.co`, `cas-server.xethub.hf.co`,
`transfer.xethub.hf.co` и их европейские варианты, CDN —
`us.aws.cdn.hf.co`, `us.gcp.cdn.hf.co`. Официального перечня нет
(обсуждения на форуме Hugging Face, 2023–2026:
`https://discuss.huggingface.co/t/how-to-get-a-list-of-all-huggingface-download-redirections-to-whitelist/30486`,
`https://discuss.huggingface.co/t/hf-hub-cdn-urls-changes-notifications/114653`).
Отсюда D-119: хранилище — поддомены доменов Hugging Face, а не перечень
имён.
