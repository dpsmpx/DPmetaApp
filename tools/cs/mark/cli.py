"""Команды метки (Д41): `python3 -m tools.cs mark init|verify|stamp`.

  mark init                  создать ключ метки (один раз, на машине автора; вне Git)
  mark verify ФАЙЛ|АДРЕС     чей файл и когда вышел: реестр, перцептивный хэш, метка, метки времени
  mark stamp [ВЫПУСК …]      поставить недостающие метки времени на манифесты выпусков
"""
from __future__ import annotations

import sys
from pathlib import Path

from ..common import ROOT


def cmd_mark(a) -> int:
    from . import key as mkey
    if a.action == "init":
        try:
            p = mkey.init()
        except FileExistsError as e:
            print(f"✗ {e}")
            return 1
        print(f"ключ метки создан: {p} (отпечаток {mkey.fingerprint(mkey.load(p))})")
        print("  · сохраните его в своей резервной копии вне Git: без ключа выпущенные метки не прочитать (Д41)")
        return 0
    if a.action == "verify":
        from . import verify
        if not a.target:
            print("укажите файл или адрес: mark verify ФАЙЛ|АДРЕС", file=sys.stderr)
            return 2
        t = a.target[0]
        if t.startswith(("http://", "https://")):
            data, name = verify.fetch(t), t
        else:
            p = Path(t)
            if not p.is_file():
                print(f"нет файла {t}", file=sys.stderr)
                return 2
            data, name = p.read_bytes(), p.name
        res = verify.check(ROOT, data, name, None if a.no_key else mkey.load())
        print("\n".join(res.lines))
        return 0 if res.found else 1
    from . import release, stamp
    mans = release.load_all(ROOT)
    if a.target:
        mans = [(p, m) for p, m in mans if m["id"] in a.target]
        missing = set(a.target) - {m["id"] for _, m in mans}
        if missing:
            print(f"нет выпусков: {', '.join(sorted(missing))}", file=sys.stderr)
            return 2
    if not mans:
        print("выпусков в реестре нет (data/provenance/site-releases)")
        return 0
    bad = 0
    for p, m in mans:
        for s in stamp.stamp_all(p, a.tsa or stamp.TSA_DEFAULT):
            bad += s.state == "failed"
            print(f"{m['id']}: {s.kind} — {s.state}{(': ' + s.note) if s.note else ''}")
    print("  · новые .tsr и .ots — закоммитить рядом с манифестом")
    return 1 if bad else 0


def register(sub) -> None:
    m = sub.add_parser("mark", help="водяной знак сайта и реестр выпусков (Д41)")
    m.add_argument("action", choices=["init", "verify", "stamp"])
    m.add_argument("target", nargs="*", help="verify: файл или адрес; stamp: id выпусков (по умолчанию — все)")
    m.add_argument("--no-key", action="store_true", help="verify: без проверки метки")
    m.add_argument("--tsa", help="stamp: служба RFC 3161 (по умолчанию — из site.yaml или freetsa.org)")
    m.set_defaults(fn=cmd_mark)
