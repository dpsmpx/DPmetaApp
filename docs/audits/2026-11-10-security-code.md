# Аудит безопасности и ошибок кода — 10 ноября 2026

**WIP: черновик, аудит идёт.** Срез — `main` `7c73993b`. Ниже — рабочие
заметки по уже проверенному; итоговый текст заменит их.

## Корень (core/, app/, shell/, cli/, tools/) — проверено

- K-01 (Средняя, безопасность). `core/git/…/Clones.kt:231-239, 158-165` и
  `app/…/PultRepository.kt:436`: «основная ветка» клона берётся из
  `.git/config` клона (`dpmeta.main`) и `origin/HEAD` — их пишет гость Linux
  (D-72). `mainSha` из `ls-remote` берётся по этому имени → `trustHost(sha)`
  → `ecosystemManifest` читает реестр и очередь DPmeta с любой ветки GitHub,
  которую выбрал гость. Мера T19 («только на голове, которую назвал GitHub»)
  обходится без выхода из proot.
- K-02 (Средняя, безопасность). `Clones.kt:169-173`: `git.fetch().setRemote("origin")`
  применяет `url.<base>.insteadOf` из конфига клона → `git://`, `amazon-s3://`,
  локальный путь — транспорты JGit мимо пропуска `GatedFactory` (D-10).
  Проверено на JGit 5.13.3: `TransportGitAnon`.
- K-03 (Низкая, цепочка поставки). JGit закреплён на 5.13.3; в Maven Central
  есть 5.13.4 и 5.13.5.
- K-04 (Низкая, надёжность). `Net.get` кладёт в кэш любой `ETag`; непечатный
  ETag → каждый следующий запрос адреса отказывает `VALUE_INVALID` навсегда.
- K-05 (Низкая, надёжность). `StoredEtagCache` растёт без предела и
  переписывается целиком; `Base64.decode` испорченного документа — исключение.
- K-06 (Средняя, ошибка). `GitHub.pulls/branches`: только первая страница
  (`per_page=100`), обрезка молча; у самого DPmetaApp уже 136 веток.
- K-07 (Низкая). `Clones.withRepo` не ловит `RevisionSyntaxException`,
  `LargeObjectException` (RuntimeException JGit).
- K-08 (Инфо, известно — TODO N-14). Нет `gradle/verification-metadata.xml`
  у корня и `dpai/`.
- K-09 (Низкая). `socialmediaimage/tools/requirements.txt` — версии «не ниже»,
  без хешей; ставится хуком начала сессии.
