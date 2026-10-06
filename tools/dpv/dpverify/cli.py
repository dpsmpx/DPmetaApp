"""Командная строка `dpv` (ТЗ, §9.1–9.2).

Коды: 0 — пройдено; 1 — есть ✗; 2 — неверный вызов или профиль; 3 —
внутренняя ошибка DP Verify (V-10).
"""
import hashlib
import os
import sys
import traceback

from . import VERSION, info, kit, profile as profile_mod, report, scope, waivers
from .checks import REGISTRY
from .checks.integrity import MANIFEST, read_manifest
from .errors import UsageError
from .repo import Repo, find_root
from .runner import Options, Run

LEVELS = ("static", "affected", "full")

USAGE = """\
dpv — проверки репозитория (DP Verify {version})

  dpv run [static|affected|full] [ключи]   прогон; по умолчанию static
      --ci                 CI-контур: только с full, чистое дерево, пропуск без разрешения профиля — провал
      --only ID[,ID…]      только эти проверки и их after
      --base REF           база для affected (по умолчанию — начало задачи, merge-base с origin/main)
      --files Ф…           эти файлы вместо изменений git (affected)
      --fail-fast          остановиться на первом ✗
      --verbose            все проверки и находки на экран
      --no-baseline        находки из базовой линии — как новые
      --json ПУТЬ          копия report.json
  dpv plan [уровень]       что будет запущено и почему, без запуска (--base, --files — как у run)
  dpv list                 проверки профиля: вид, уровень, обязательность, триггеры, требования
  dpv explain ID[/правило] что ловит, откуда правило, как чинить, как оформить исключение
  dpv doctor               окружение: python, git, bash, инструменты [[tool]], целостность копии
  dpv init [--lang Я…] [--name ИМЯ]   недостающие файлы process-kit и профиль; существующие не трогает
  dpv init --adopt         только профиль — по найденному в репозитории; план подключения
  dpv scope start [--from SHA] "<задача>" ПУТЬ…   записать начало задачи и её пути
  dpv scope check | show   изменённое вне границ задачи; текущие границы
  dpv baseline write --reason "…" [--check ID] | prune | show   базовая линия по последнему прогону
  dpv report [--md] [--run КАТАЛОГ]   итог последнего прогона: экран или Markdown для PR
  dpv report --diff A.json B.json     сравнение двух отчётов без meta
  dpv version              версия и источник копии
  dpv help                 эта справка

У любой команды — --profile ПУТЬ: другой профиль (по умолчанию tools/dpverify.toml).
Коды возврата: 0 — пройдено; 1 — есть ✗; 2 — неверный вызов или профиль; 3 — сбой DP Verify."""


def package_root():
    return os.path.dirname(os.path.dirname(os.path.realpath(__file__)))


def main(argv=None, cwd=None, out=None, err=None):
    argv = sys.argv[1:] if argv is None else list(argv)
    out = out or sys.stdout
    err = err or sys.stderr
    try:
        return _dispatch(argv, cwd or os.getcwd(), out)
    except UsageError as e:
        print(f"dpv: {e}", file=err)
        return 2
    except Exception:  # noqa: BLE001 — сбой инструмента, а не кода (V-10)
        print("dpv: внутренняя ошибка DP Verify:\n" + traceback.format_exc(), file=err)
        return 3


def _dispatch(argv, cwd, out):
    if not argv or argv[0] in ("help", "-h", "--help"):
        print(USAGE.format(version=VERSION), file=out)
        return 0
    cmd, rest = argv[0], argv[1:]
    if cmd == "version":
        _version(out)
        return 0
    if cmd == "run":
        return _run(rest, cwd, out)
    if cmd == "init":  # профиля ещё нет — его init и создаёт
        return kit.main(find_root(cwd), rest, out, package_root(), cwd)
    if cmd in ("plan", "list", "explain", "doctor", "scope", "baseline", "report"):
        rest, root, prof = _load(rest, cwd)
        repo = Repo(root, prof.exclude, prof.logs)
        if cmd == "plan":
            return _plan(rest, repo, prof, out)
        if cmd == "list":
            _no_args(cmd, rest)
            _print(info.list_lines(prof, REGISTRY), out)
            return 0
        if cmd == "explain":
            if len(rest) != 1:
                raise UsageError("explain: нужен один id проверки — dpv explain ID[/правило]")
            _print(info.explain_lines(prof, REGISTRY, rest[0]), out)
            return 0
        if cmd == "doctor":
            _no_args(cmd, rest)
            lines, code = info.doctor(repo, prof, package_root(), REGISTRY)
            _print(lines, out)
            return code
        module = {"scope": scope, "baseline": waivers, "report": report}[cmd]
        return module.main(repo, prof, rest, out)
    raise UsageError(f"неизвестная команда {cmd!r}; справка — dpv help")


def _version(out):
    root = package_root()
    manifest = os.path.join(root, MANIFEST)
    if os.path.isfile(manifest):
        _, header = read_manifest(manifest)
        with open(manifest, "rb") as f:
            digest = hashlib.sha256(f.read()).hexdigest()
        print(f"DP Verify {VERSION} · копия {header.get('source', '?')} · манифест sha256:{digest}", file=out)
    else:
        print(f"DP Verify {VERSION} · без манифеста ({root})", file=out)


def _print(lines, out):
    for line in lines:
        print(line, file=out)


def _no_args(cmd, rest):
    if rest:
        raise UsageError(f"{cmd}: лишний аргумент {rest[0]!r}; справка — dpv help")


def _load(args, cwd):
    """`--profile ПУТЬ` в любом месте аргументов → (остальные аргументы, корень, профиль)."""
    rest, path, i = [], None, 0
    while i < len(args):
        if args[i] == "--profile":
            if i + 1 >= len(args) or args[i + 1].startswith("--"):
                raise UsageError("--profile: нужно значение")
            path = os.path.abspath(os.path.join(cwd, args[i + 1]))
            i += 2
            continue
        rest.append(args[i])
        i += 1
    root = find_root(cwd)
    return rest, root, profile_mod.load(profile_mod.find(root, path), REGISTRY)


def _options(args, cwd, command):
    """Ключи run и plan → Options."""
    opts = Options()
    i = 0
    while i < len(args):
        a = args[i]
        if a in LEVELS:
            opts.level = a
        elif a == "--ci" and command == "run":
            opts.ci = True
        elif a == "--fail-fast" and command == "run":
            opts.fail_fast = True
        elif a == "--verbose" and command == "run":
            opts.verbose = True
        elif a == "--no-baseline" and command == "run":
            opts.no_baseline = True
        elif a == "--files":
            files = []
            while i + 1 < len(args) and not args[i + 1].startswith("--"):
                i += 1
                files.append(args[i])
            if not files:
                raise UsageError("--files: нужен хотя бы один файл")
            opts.files = files
        elif a in ("--only", "--json", "--base") and (command == "run" or a == "--base"):
            if i + 1 >= len(args) or args[i + 1].startswith("--"):
                raise UsageError(f"{a}: нужно значение")
            i += 1
            if a == "--only":
                opts.only = [x for x in args[i].split(",") if x]
                if not opts.only:
                    raise UsageError("--only: пустой список проверок")
            elif a == "--json":
                opts.json_path = os.path.abspath(os.path.join(cwd, args[i]))
            else:
                opts.base = args[i]
        else:
            raise UsageError(f"{command}: неизвестный аргумент {a!r}; справка — dpv help")
        i += 1
    return opts


def _entry(root):
    """Как был вызван DP Verify — для report.md: ./dpv в DP-Verify, tools/dpv/dpv в проекте."""
    rel = os.path.relpath(os.path.join(package_root(), "dpv"), root).replace(os.sep, "/")
    if rel == "dpv":
        return "./dpv"
    return "dpv" if rel.startswith("..") else rel


def _run(args, cwd, out):
    args, root, prof = _load(args, cwd)
    opts = _options(args, cwd, "run")
    parts = [_entry(root), "run", opts.level] + (["--ci"] if opts.ci else [])
    if opts.only:
        parts += ["--only", ",".join(opts.only)]
    opts.invocation = " ".join(parts)
    repo = Repo(root, prof.exclude, prof.logs)
    return Run(repo, prof, opts, package_root()).execute(out)


def _plan(args, repo, prof, out):
    """F-11: что будет запущено и почему — без запуска."""
    run = Run(repo, prof, _options(args, repo.root, "plan"), package_root())
    steps = run.plan()
    head = f"dpv plan {run.level} · {prof.name}"
    sel = run.selection
    if sel is not None:
        base = (sel["base"] or "")[:7] or "—"
        head += f" · база {base} ({sel['source'] or 'нет'}) · изменено файлов {sel['changed']}"
    lines = [head]
    if sel is not None:
        lines.append(f"  полный прогон: {sel['reason']}" if sel["full"]
                     else f"  выбрано команд full: {sum(1 for c in prof.commands if c.level == 'full' and c.id in sel['commands'])}")
    width = max((len(cid) for cid, _, _ in steps), default=0) + 2
    for cid, kind, obj in steps:
        what = "встроенная" if kind == "builtin" else f"команда {obj.level}"
        why = ""
        if sel is not None and not sel["full"] and cid in sel["commands"]:
            path, rule = sel["commands"][cid]
            why = f" ← {path}: {rule}" if path else f" ← {rule}"
            if kind == "command" and obj.affected_args:
                why += f"; делегирование: {' '.join(obj.affected_args)}"
        lines.append(f"  {cid:<{width}} {what}{why}")
    lines.append(f"проверок {len(steps)}; встроенные идут по всему репозиторию (F-05)")
    _print(lines, out)
    return 0
