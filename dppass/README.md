# DPpass для ПК 0.1.20-dev — пакет для Linux (Arch, Garuda)

Собран из `main` DPmetaApp `08da814088f7ebcee5c9e8550cdee38d4fe28324`
(31 октября 2026): `./gradlew :desktop:distTar`, затем
`python3 tools/desktop/package.py` — архив, `PKGBUILD` с его суммой и
`SHA256SUMS`. Оболочка — `dppass/docs/direction/DECISIONS.md`, D-74;
ядро Xray — в процессе, для x86_64.

Установка на ПК — из каталога с этими файлами:

```sh
sha256sum -c SHA256SUMS
makepkg -si
```

Запуск — ярлык «DPpass» или `dppass`: polkit спрашивает пароль
администратора, приложение работает от имени пользователя с одной
возможностью `CAP_NET_ADMIN`.

Что нового с 0.1.17-dev:
- 0.1.18–0.1.19 — та же оболочка стала и оболочкой Windows (D-97); на
  Linux поведение прежнее;
- 0.1.20 — профиль Happ с «всё напрямую» (`GlobalProxy: false`) называет
  это в сводке «не взято», `keyword:` — только латиницей.

Что проверить — `dppass/TODO.md`, «На устройстве». Сборки — только для
устройств автора (`DPmetaApp:D-24`), это не выпуск.
