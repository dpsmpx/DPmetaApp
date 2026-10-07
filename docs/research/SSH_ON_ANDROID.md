# SSH из приложения Android

Датированные факты о SSH-клиенте на JVM в Android, на которые опирается
D-35 (`JschProcessHost`, модуль `jsch`). Факт без даты и версии сюда не
пишется; изменилось поведение — новая запись с новой датой.

## 1. JSch на Android 11

Проверено **7 октября 2026**: JSch 2.28.7 (форк mwiede) в APK
приложения, запуск через `app_process` на Realme C21Y (Android 11,
D-33); сервер — OpenSSH 10.5 в Termux на том же телефоне
(`127.0.0.1:8022`), команда `echo ok` через `JschProcessHost`.

| Ключ входа | Ключ хоста в known_hosts | Итог |
|---|---|---|
| ECDSA P-256 | ECDSA | `ok`, код 0 |
| ed25519 | ed25519 | `SSH_HOST_KEY_REJECTED`: «reject HostKey» |
| ECDSA P-256 | ed25519 | `SSH_HOST_KEY_REJECTED` |
| ed25519 | ECDSA | `SSH_AUTH_FAILED`: «Auth fail» |

Вывод: в Android 11 JSch не умеет ed25519 — ни ключ хоста (сервер
договаривается о другом алгоритме, которого нет в known_hosts, и
строгая проверка отказывает), ни ключ входа: ed25519 в JSch опирается на
JCA Java 15+. Ключ приложения — ECDSA P-256, в known_hosts — ключ хоста
ECDSA (D-35). Отказы приходят нужными кодами, без молчаливого
добавления ключа (T2).

На JVM (JDK 21) тот же `JschProcessHost` работает с ed25519 против
Windows- и Linux-targets (`JschProcessHostTest`, `docs/TESTING.md`, §8).
