#!/usr/bin/env python3
"""SHA-256 содержимого APK без блока подписи (этап 20, reproducible.sh).

Подпись APK (схемы v2/v3) с ECDSA содержит случайное число и от сборки к
сборке разная; воспроизводимость проверяется по содержимому: имена,
порядок, метаданные и байты каждой записи — как у F-Droid.

    python3 tools/verify/apk_digest.py путь.apk…   # «хеш путь» на строку
"""
import hashlib
import sys
import zipfile

for path in sys.argv[1:]:
    h = hashlib.sha256()
    with zipfile.ZipFile(path) as z:
        for i in z.infolist():
            h.update(repr((i.filename, i.date_time, i.compress_type, i.external_attr, i.extra)).encode())
            h.update(z.read(i))
    print(f"{h.hexdigest()} *{path}")
