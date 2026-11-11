#!/usr/bin/env python3
"""Общая папка DP-Verify: POSIX lease и проверенная fast-forward синхронизация.

D-89. Не меняет работающие инструменты; проверяет кандидата до публикации.
Подключение Android/SSH-сессий описывается отдельно от этого контроллера.
"""
import argparse
from contextlib import contextmanager
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
from threading import Event

UPSTREAM = 'https://github.com/dpsmpx/DP-Verify.git'
GIT_TIMEOUT = 120
VALIDATION_TIMEOUT = 15 * 60
POLL_SECONDS = 60


class Busy(Exception):
    """Общая папка используется; повторить в свободный момент."""


class Refused(Exception):
    """Состояние не допускает безопасную синхронизацию."""


def git(repo, *args):
    try:
        p = subprocess.run(['git', '-C', str(repo), *args], capture_output=True,
                           env=dict(os.environ, GIT_TERMINAL_PROMPT='0'), timeout=GIT_TIMEOUT)
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


@contextmanager
def file_lease(path, exclusive=False, blocking=False):
    with path.open('a+b') as handle:
        os.chmod(path, 0o600)
        mode = fcntl.LOCK_EX if exclusive else fcntl.LOCK_SH
        try:
            fcntl.flock(handle, mode | (0 if blocking else fcntl.LOCK_NB))
        except BlockingIOError as error:
            raise Busy('tools_in_use') from error
        try:
            yield handle.fileno()
        finally:
            fcntl.flock(handle, fcntl.LOCK_UN)


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
        os.fchmod(handle.fileno(), 0o600)
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
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if p.returncode not in (0, 1):
        raise Refused('history_unavailable')
    return p.returncode == 0


def identity(repo):
    return git(repo, 'rev-parse', 'HEAD'), git(repo, 'symbolic-ref', '--short', 'HEAD')


def validate(repo, revision, state, command):
    """Отдельное дерево: проверка не запускается поверх инструментов сессии."""
    with tempfile.TemporaryDirectory(prefix='candidate-', dir=state) as directory:
        candidate = Path(directory) / 'tree'
        git(repo, 'worktree', 'add', '--detach', str(candidate), revision)
        try:
            with (state / 'validation.log').open('wb') as log:
                os.fchmod(log.fileno(), 0o600)
                try:
                    rc = subprocess.run(command, cwd=candidate, stdout=log, stderr=subprocess.STDOUT,
                                        env=dict(os.environ, GIT_TERMINAL_PROMPT='0'),
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
        with tempfile.TemporaryDirectory(prefix='.dpv-install-', dir=repo.parent) as directory:
            candidate = Path(directory) / 'tools'
            git(repo.parent, 'clone', '--branch', 'main', '--single-branch', '--no-tags', expected_remote, str(candidate))
            revision, branch = identity(candidate)
            if branch != 'main' or not clean(candidate):
                raise Refused('invalid_initial_copy')
            validate(candidate, revision, state, command)
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
        if git(repo, 'remote', 'get-url', 'origin') != expected_remote:
            raise Refused('unexpected_remote')
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
    return dict(os.environ, DP_VERIFY_HOME=str(repo), DP_VERIFY_REVISION=git(repo, 'rev-parse', 'HEAD'),
                DP_VERIFY_CONTROLLER=str(Path(__file__).resolve()), DP_VERIFY_PYTHON=sys.executable)


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
            os.fchmod(temporary.fileno(), 0o700)
            temporary_path = Path(temporary.name)
        temporary_path.replace(bindir / 'dpv')
        env = tool_environment(repo)
        env['PATH'] = str(bindir) + os.pathsep + env.get('PATH', os.defpath)
    return subprocess.run(command, env=env).returncode


def run(repo, command, exclusive=False):
    repo = canonical(repo)
    with lease(repo, exclusive=exclusive, blocking=True) as descriptor:
        env = tool_environment(repo)
        # Дочерний процесс сохраняет lease даже при аварийном завершении обёртки.
        return subprocess.run(command, env=env, pass_fds=(descriptor,)).returncode


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', required=True, type=Path)
    sub = parser.add_subparsers(dest='action', required=True)
    update = sub.add_parser('sync')
    update.add_argument('--state', required=True, type=Path)
    first = sub.add_parser('init')
    first.add_argument('--state', required=True, type=Path)
    ticker = sub.add_parser('watch')
    ticker.add_argument('--state', required=True, type=Path)
    ticker.add_argument('--interval', type=float, default=POLL_SECONDS)
    for action in ('run', 'edit', 'session'):
        use = sub.add_parser(action)
        use.add_argument('command', nargs=argparse.REMAINDER)
    args = parser.parse_args(argv)
    try:
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
    sys.exit(main())
