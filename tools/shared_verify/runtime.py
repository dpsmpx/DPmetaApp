#!/usr/bin/env python3
"""Общая папка DP-Verify: lease и проверенная fast-forward синхронизация.

D-89. Не меняет работающие инструменты; проверяет кандидата до публикации.
Подключение Android/SSH-сессий описывается отдельно от этого контроллера.
"""
import argparse
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tempfile
from threading import Event
import uuid

if os.name == 'nt':
    import ctypes
    from ctypes import wintypes
    import msvcrt

    class Overlapped(ctypes.Structure):
        _fields_ = [('Internal', ctypes.c_size_t), ('InternalHigh', ctypes.c_size_t),
                    ('Offset', wintypes.DWORD), ('OffsetHigh', wintypes.DWORD),
                    ('hEvent', wintypes.HANDLE)]

    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.LockFileEx.argtypes = [wintypes.HANDLE, wintypes.DWORD, wintypes.DWORD,
                                 wintypes.DWORD, wintypes.DWORD, ctypes.POINTER(Overlapped)]
    kernel.LockFileEx.restype = wintypes.BOOL
    kernel.UnlockFileEx.argtypes = [wintypes.HANDLE, wintypes.DWORD, wintypes.DWORD,
                                   wintypes.DWORD, ctypes.POINTER(Overlapped)]
    kernel.UnlockFileEx.restype = wintypes.BOOL
    kernel.CreateEventW.argtypes = [ctypes.c_void_p, wintypes.BOOL, wintypes.BOOL, wintypes.LPCWSTR]
    kernel.CreateEventW.restype = wintypes.HANDLE
    kernel.OpenEventW.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.LPCWSTR]
    kernel.OpenEventW.restype = wintypes.HANDLE
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    kernel.CloseHandle.restype = wintypes.BOOL
else:
    import fcntl

UPSTREAM = 'https://github.com/dpsmpx/DP-Verify.git'
GIT_TIMEOUT = 120
VALIDATION_TIMEOUT = 15 * 60
POLL_SECONDS = 60
ACCOUNT = None
TOKEN_ENV = 'DPMETA_VERIFY_GITHUB_TOKEN'
EVENT_PREFIX = 'Global\\DPmetaAppVerify-'


def credential(operation):
    """Git helper: секрет только для официального HTTPS-пути, без сохранения."""
    if operation != 'get':
        return 0
    fields = dict(line.rstrip('\n').split('=', 1) for line in sys.stdin
                  if '=' in line)
    token = os.environ.get(TOKEN_ENV, '')
    if (fields.get('protocol') == 'https' and fields.get('host') == 'github.com'
            and fields.get('path') == 'dpsmpx/DP-Verify.git' and token):
        sys.stdout.write('username=x-access-token\npassword=' + token + '\n\n')
    return 0


def clean_environment():
    env = dict(os.environ)
    env.pop(TOKEN_ENV, None)
    return env


def git_environment(args):
    env = dict(clean_environment(), GIT_TERMINAL_PROMPT='0')
    if ACCOUNT is None:
        return env
    # Автор автоматических коммитов; пользовательский Git config не меняется.
    env.update(GIT_AUTHOR_NAME='DPmetaApp', GIT_AUTHOR_EMAIL='dpmetaapp@localhost',
               GIT_COMMITTER_NAME='DPmetaApp', GIT_COMMITTER_EMAIL='dpmetaapp@localhost')
    if args[0] not in ('clone', 'fetch', 'push'):
        return env
    # Только сетевые команды получают токен. Валидатор, hooks коммита и ИИ — нет.
    env[TOKEN_ENV] = ACCOUNT
    helper = '!' + shlex.quote(sys.executable) + ' ' + shlex.quote(str(Path(__file__).resolve())) + ' credential'
    settings = [('credential.helper', ''), ('credential.helper', helper),
                ('credential.useHttpPath', 'true'), ('http.followRedirects', 'false'),
                ('http.sslVerify', 'true')]
    count = int(env.get('GIT_CONFIG_COUNT', '0'))
    for index, (key, value) in enumerate(settings, count):
        env['GIT_CONFIG_KEY_' + str(index)] = key
        env['GIT_CONFIG_VALUE_' + str(index)] = value
    env['GIT_CONFIG_COUNT'] = str(count + len(settings))
    return env


def read_account():
    try:
        value = json.loads(sys.stdin.read(8193))
        token = value['token']
        if not isinstance(token, str) or not 0 < len(token) <= 4096 or any(not '!' <= c <= '~' for c in token):
            raise ValueError()
        return token
    except (ValueError, KeyError, TypeError) as error:
        raise Refused('invalid_account') from error


class Busy(Exception):
    """Общая папка используется; повторить в свободный момент."""


class Refused(Exception):
    """Состояние не допускает безопасную синхронизацию."""


def git(repo, *args):
    try:
        p = subprocess.run(['git', '-C', str(repo), *args], capture_output=True,
                           env=git_environment(args), timeout=GIT_TIMEOUT)
    except subprocess.TimeoutExpired as error:
        raise Refused('git_timeout') from error
    if p.returncode:
        # stderr git может содержать URL с учётными данными.
        raise Refused('git_failed')
    return p.stdout.decode('utf-8').strip()


def canonical(repo):
    path = Path(repo).resolve(strict=True)
    if Path(git(path, 'rev-parse', '--show-toplevel')).resolve() != path:
        raise Refused('repository_root_required')
    return path


def lock_path(repo):
    # git-common-dir один и у всех worktree: разные имена каталога не обходят lock.
    common = Path(git(repo, 'rev-parse', '--path-format=absolute', '--git-common-dir'))
    return common / 'dpmetaapp-tools.lock'


def private_file(handle, mode=0o600):
    # На Windows сохраняется унаследованный ACL папки; chmod не заменяет ACL.
    if os.name != 'nt':
        os.fchmod(handle.fileno(), mode)


def lock_file(descriptor, exclusive, blocking):
    if os.name != 'nt':
        try:
            fcntl.flock(descriptor, (fcntl.LOCK_EX if exclusive else fcntl.LOCK_SH) |
                        (0 if blocking else fcntl.LOCK_NB))
        except BlockingIOError as error:
            raise Busy('tools_in_use') from error
        return None
    overlapped = Overlapped()
    flags = (2 if exclusive else 0) | (0 if blocking else 1)
    if not kernel.LockFileEx(msvcrt.get_osfhandle(descriptor), flags, 0, 1, 0, ctypes.byref(overlapped)):
        error = ctypes.get_last_error()
        if error == 33:  # ERROR_LOCK_VIOLATION
            raise Busy('tools_in_use')
        raise ctypes.WinError(error)
    return overlapped


def unlock_file(descriptor, overlapped):
    if os.name != 'nt':
        fcntl.flock(descriptor, fcntl.LOCK_UN)
    elif not kernel.UnlockFileEx(msvcrt.get_osfhandle(descriptor), 0, 1, 0, ctypes.byref(overlapped)):
        raise ctypes.WinError(ctypes.get_last_error())


def event_exists(name):
    handle = kernel.OpenEventW(0x00100000, False, name)  # SYNCHRONIZE
    if handle:
        kernel.CloseHandle(handle)
        return True
    error = ctypes.get_last_error()
    if error == 2:  # ERROR_FILE_NOT_FOUND: последний владелец уже закончил работу.
        return False
    raise ctypes.WinError(error)


def windows_users(path):
    """LockFileEx привязан к процессу: у живого инструмента отдельный liveness handle."""
    folder = path.with_name(path.name + '.users')
    active = False
    for marker in folder.glob('*.json'):
        try:
            identity = marker.stem
            value = json.loads(marker.read_text())
            if (len(identity) != 32 or any(c not in '0123456789abcdef' for c in identity)
                    or value != {'event': EVENT_PREFIX + identity}):
                raise ValueError()
        except (ValueError, TypeError) as error:
            raise Refused('invalid_tools_lease') from error
        if event_exists(value['event']):
            active = True
        else:
            marker.unlink()
    return active


@contextmanager
def file_lease(path, exclusive=False, blocking=False):
    with path.open('a+b') as handle:
        private_file(handle)
        while True:
            locked = lock_file(handle.fileno(), exclusive, blocking)
            try:
                if not (os.name == 'nt' and exclusive and windows_users(path)):
                    yield handle.fileno()
                    return
                if not blocking:
                    raise Busy('tools_in_use')
            finally:
                unlock_file(handle.fileno(), locked)
            # Не держать writer lock в ожидании инструмента: ему может понадобиться
            # вложенная проверка под shared lease.
            Event().wait(0.05)


@contextmanager
def lease(repo, exclusive=False, blocking=False):
    with file_lease(lock_path(canonical(repo)), exclusive, blocking) as descriptor:
        yield descriptor


def state_folder(repo, state):
    state = Path(state).resolve()
    if state == repo or repo in state.parents:
        raise Refused('state_must_be_outside_tools')
    state.mkdir(parents=True, exist_ok=True, mode=0o700)
    return state


def write_state(state, name, value):
    temporary = state / (name + '.tmp')
    with temporary.open('w') as handle:
        private_file(handle)
        json.dump(value, handle)
        handle.write('\n')
    temporary.replace(state / name)


def validation_key(revision, command):
    return {'revision': revision, 'command': hashlib.sha256(json.dumps(list(command)).encode()).hexdigest()}


def previously_validated(state, revision, command):
    try:
        return json.loads((state / 'last-validation.json').read_text()) == validation_key(revision, command)
    except (OSError, ValueError):
        return False


def clean(repo):
    return not git(repo, 'status', '--porcelain', '--untracked-files=all')


def ancestor(repo, older, newer):
    p = subprocess.run(['git', '-C', str(repo), 'merge-base', '--is-ancestor', older, newer],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                       env=git_environment(('merge-base',)))
    if p.returncode not in (0, 1):
        raise Refused('history_unavailable')
    return p.returncode == 0


def identity(repo):
    return git(repo, 'rev-parse', 'HEAD'), git(repo, 'symbolic-ref', '--short', 'HEAD')


def check_remote(repo, expected):
    # get-url раскрывает insteadOf/pushInsteadOf. pushurl и несколько адресов
    # не должны незаметно отправить проверенные инструменты в другой репозиторий.
    if (git(repo, 'remote', 'get-url', '--all', 'origin') != expected or
            git(repo, 'remote', 'get-url', '--push', '--all', 'origin') != expected):
        raise Refused('unexpected_remote')


def validate(repo, revision, state, command):
    """Отдельное дерево: проверка не запускается поверх инструментов сессии."""
    with tempfile.TemporaryDirectory(prefix='candidate-', dir=state) as directory:
        candidate = Path(directory) / 'tree'
        git(repo, 'worktree', 'add', '--detach', str(candidate), revision)
        try:
            with (state / 'validation.log').open('wb') as log:
                private_file(log)
                try:
                    rc = subprocess.run(native_tool_command(command), cwd=candidate, stdout=log, stderr=subprocess.STDOUT,
                                        env=dict(clean_environment(), GIT_TERMINAL_PROMPT='0'),
                                        timeout=VALIDATION_TIMEOUT).returncode
                except subprocess.TimeoutExpired as error:
                    raise Refused('validation_timeout') from error
            if rc:
                raise Refused('validation_failed')
            if git(candidate, 'rev-parse', 'HEAD') != revision or not clean(candidate):
                raise Refused('validator_changed_tree')
            write_state(state, 'last-validation.json', validation_key(revision, command))
        finally:
            # Удаляется только наша временная worktree, исходная папка не сбрасывается.
            git(repo, 'worktree', 'remove', '--force', str(candidate))


def install(repo, state, expected_remote=UPSTREAM, command=('./dpv', 'run', 'full', '--ci')):
    """Проверенная первая копия. Занятый или существующий путь не перезаписывается."""
    repo = Path(repo).resolve()
    state = state_folder(repo, state)
    repo.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    with file_lease(repo.parent / ('.' + repo.name + '.install.lock'), exclusive=True):
        if repo.exists() or repo.is_symlink():
            raise Refused('tools_already_exist')
        if git(repo.parent, 'ls-remote', '--get-url', expected_remote) != expected_remote:
            raise Refused('unexpected_remote')
        with tempfile.TemporaryDirectory(prefix='.dpv-install-', dir=repo.parent) as directory:
            candidate = Path(directory) / 'tools'
            git(repo.parent, 'clone', '--branch', 'main', '--single-branch', '--no-tags', expected_remote, str(candidate))
            revision, branch = identity(candidate)
            if branch != 'main' or not clean(candidate):
                raise Refused('invalid_initial_copy')
            check_remote(candidate, expected_remote)
            validate(candidate, revision, state, command)
            check_remote(candidate, expected_remote)
            if repo.exists() or repo.is_symlink():
                raise Refused('tools_already_exist')
            candidate.rename(repo)
            result = {'status': 'installed', 'revision': revision}
            write_state(state, 'last-sync.json', result)
            return result


def commit_edits(repo):
    """Локальная точка восстановления до проверки. Ошибка не теряет исходные файлы."""
    try:
        git(repo, 'var', 'GIT_AUTHOR_IDENT')
    except Refused as error:
        raise Refused('author_identity_required') from error
    git(repo, 'add', '--all')
    # Собственные Git hooks не отключаются. Неудачный кандидат остаётся локально.
    git(repo, 'commit', '-m', 'Обновить общие инструменты ИИ')
    if not clean(repo):
        raise Refused('local_changed_during_commit')
    return identity(repo)[0]


def sync(repo, state, expected_remote=UPSTREAM, command=('./dpv', 'run', 'full', '--ci')):
    repo = canonical(repo)
    state = state_folder(repo, state)
    with lease(repo, exclusive=True):
        local, branch = identity(repo)
        if branch != 'main':
            raise Refused('main_required')
        check_remote(repo, expected_remote)
        git(repo, 'fetch', '--no-tags', 'origin', 'refs/heads/main:refs/remotes/origin/main')
        remote = git(repo, 'rev-parse', 'refs/remotes/origin/main')
        if not clean(repo):
            # Не создавать лишнюю ветку при уже известном конфликте двух копий.
            if remote != local and not ancestor(repo, remote, local):
                raise Refused('local_remote_changes')
            local = commit_edits(repo)
        if local == remote:
            direction, candidate = 'unchanged', local
        elif ancestor(repo, local, remote):
            direction, candidate = 'downloaded', remote
        elif ancestor(repo, remote, local):
            direction, candidate = 'published', local
        else:
            raise Refused('diverged')
        # Кеш применим только к отсутствию изменений. Публикация и активация
        # всегда требуют нового полного прогона, даже после предыдущего успеха.
        if direction != 'unchanged' or not previously_validated(state, candidate, command):
            validate(repo, candidate, state, command)
        if identity(repo) != (local, branch) or not clean(repo):
            raise Refused('local_changed_during_validation')
        check_remote(repo, expected_remote)
        if direction == 'downloaded':
            git(repo, 'merge', '--ff-only', candidate)
        elif direction == 'published':
            # Без force: новый несовместимый remote head отклоняется сервером.
            git(repo, 'push', 'origin', candidate + ':refs/heads/main')
        result = {'status': direction, 'revision': candidate}
        # Запись только после успешного переключения/публикации.
        write_state(state, 'last-sync.json', result)
        return result


def watch(repo, state, interval=POLL_SECONDS, pause=None, emit=None):
    """Такт с повтором после Busy/ошибки. Ожидание и вывод подменяются в тестах."""
    if interval <= 0:
        raise Refused('positive_interval_required')
    pause = pause or Event().wait
    emit = emit or (lambda value: print(json.dumps(value), flush=True))
    while True:
        try:
            result = sync(repo, state)
        except Busy:
            result = {'status': 'deferred', 'reason': 'tools_in_use'}
        except (Refused, OSError) as error:
            result = {'status': 'refused', 'reason': str(error) if isinstance(error, Refused) else 'io_failed'}
        emit(result)
        if pause(interval):
            return


def tool_environment(repo):
    return dict(clean_environment(), DP_VERIFY_HOME=str(repo), DP_VERIFY_REVISION=git(repo, 'rev-parse', 'HEAD'),
                DP_VERIFY_CONTROLLER=str(Path(__file__).resolve()), DP_VERIFY_PYTHON=sys.executable)


def native_tool_command(command):
    # DP Verify — Python launcher без .exe; CreateProcess не использует shebang.
    if os.name == 'nt' and Path(command[0]).name == 'dpv':
        return [sys.executable, '-X', 'utf8', '-I', '-B', *command]
    return command


def session_command(command):
    if os.name != 'nt':
        return command
    # У npm есть shell-launcher рядом с .cmd. Git Bash передаёт произвольный
    # prompt без повторного разбора метасимволов cmd.exe; нужен также Claude.
    git_program = shutil.which('git')
    if git_program:
        root = Path(git_program).resolve().parent
        for bash in (root / 'bash.exe', root.parent / 'bin' / 'bash.exe'):
            if bash.is_file():
                return [str(bash), '--noprofile', '--norc', '-c',
                        'exec ' + ' '.join(shlex.quote(part) for part in command)]
    raise Refused('git_bash_required')


def guarded_process(command, env, descriptor, lease_path=None):
    if os.name != 'nt':
        return subprocess.run(command, env=env, pass_fds=(descriptor,)).returncode
    if lease_path is None:
        raise Refused('lease_path_required')
    folder = Path(lease_path).with_name(Path(lease_path).name + '.users')
    folder.mkdir(exist_ok=True)
    identity = uuid.uuid4().hex
    name = EVENT_PREFIX + identity
    marker = folder / (identity + '.json')
    handle = kernel.CreateEventW(None, True, False, name)
    if not handle:
        raise ctypes.WinError(ctypes.get_last_error())
    startup = subprocess.STARTUPINFO()
    startup.lpAttributeList = {'handle_list': [handle]}
    try:
        # Запись предшествует запуску: после аварии до CreateProcess живого
        # события не будет; после запуска его держит наследник, даже без обёртки.
        write_state(folder, marker.name, {'event': name})
        os.set_handle_inheritable(handle, True)
        try:
            child = subprocess.Popen(command, env=env, close_fds=True, startupinfo=startup)
        finally:
            os.set_handle_inheritable(handle, False)
        with child:
            try:
                return child.wait()
            except BaseException:
                child.kill()
                child.wait()
                raise
    finally:
        kernel.CloseHandle(handle)
        # При любой неполной остановке оставить маркер: отказ безопаснее
        # обновления папки, которую всё ещё использует дочерний процесс.
        if not event_exists(name):
            marker.unlink(missing_ok=True)


def session(repo, command):
    """Ожидающая ИИ-сессия не держит lease. dpv на PATH берёт его на саму проверку (D-92)."""
    repo = canonical(repo)
    with lease(repo, blocking=True):
        key = hashlib.sha256(str(repo).encode()).hexdigest()[:16]
        bindir = repo.parent / '.dpmetaapp-tools' / key / 'bin'
        bindir.mkdir(parents=True, exist_ok=True, mode=0o700)
        argv = [sys.executable, str(Path(__file__).resolve()), '--repo', str(repo), 'run', '--', str(repo / 'dpv')]
        content = '#!/bin/sh\nexec ' + ' '.join(shlex.quote(part) for part in argv) + ' "$@"\n'
        # Отдельная папка: управляемая обёртка не меняет Git-дерево DP-Verify.
        with tempfile.NamedTemporaryFile(mode='w', dir=bindir, delete=False) as temporary:
            temporary.write(content)
            private_file(temporary, 0o700)
            temporary_path = Path(temporary.name)
        temporary_path.replace(bindir / 'dpv')
        env = tool_environment(repo)
        env['PATH'] = str(bindir) + os.pathsep + env.get('PATH', os.defpath)
    return subprocess.run(session_command(command), env=env).returncode


def run(repo, command, exclusive=False):
    repo = canonical(repo)
    with lease(repo, exclusive=exclusive, blocking=True) as descriptor:
        env = tool_environment(repo)
        # Дочерний процесс сохраняет lease даже при аварийном завершении обёртки.
        return guarded_process(native_tool_command(command), env, descriptor, lock_path(repo))


def main(argv=None):
    global ACCOUNT
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', required=True, type=Path)
    sub = parser.add_subparsers(dest='action', required=True)
    update = sub.add_parser('sync')
    update.add_argument('--state', required=True, type=Path)
    update.add_argument('--github-stdin', action='store_true')
    first = sub.add_parser('init')
    first.add_argument('--state', required=True, type=Path)
    first.add_argument('--github-stdin', action='store_true')
    ticker = sub.add_parser('watch')
    ticker.add_argument('--state', required=True, type=Path)
    ticker.add_argument('--interval', type=float, default=POLL_SECONDS)
    for action in ('run', 'edit', 'session'):
        use = sub.add_parser(action)
        use.add_argument('command', nargs=argparse.REMAINDER)
    args = parser.parse_args(argv)
    try:
        ACCOUNT = read_account() if getattr(args, 'github_stdin', False) else None
        if args.action == 'init':
            print(json.dumps(install(args.repo, args.state)))
            return 0
        if args.action == 'sync':
            print(json.dumps(sync(args.repo, args.state)))
            return 0
        if args.action == 'watch':
            watch(args.repo, args.state, args.interval)
            return 0
        command = args.command[1:] if args.command[:1] == ['--'] else args.command
        if not command:
            parser.error('нужна команда после run --')
        if args.action == 'session':
            return session(args.repo, command)
        return run(args.repo, command, exclusive=args.action == 'edit')
    except KeyboardInterrupt:
        return 130
    except Busy:
        print(json.dumps({'status': 'deferred', 'reason': 'tools_in_use'}))
        return 75
    except (Refused, OSError) as error:
        reason = str(error) if isinstance(error, Refused) else 'io_failed'
        print(json.dumps({'status': 'refused', 'reason': reason}))
        return 1


if __name__ == '__main__':
    if sys.argv[1:2] == ['credential']:
        sys.exit(credential(sys.argv[2] if len(sys.argv) > 2 else ''))
    sys.exit(main())
