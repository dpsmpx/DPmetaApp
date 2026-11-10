#!/usr/bin/env python3
"""Общая папка DP-Verify: POSIX lease и проверенная fast-forward синхронизация.

D-89. Не меняет работающие инструменты; незакоммиченные правки и конфликты
сохраняются. Автокоммит и подключение Android/SSH-сессий — следующий этап.
"""
import argparse
from contextlib import contextmanager
import fcntl
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

UPSTREAM = 'https://github.com/dpsmpx/DP-Verify.git'


class Busy(Exception):
    """Общая папка используется; повторить в свободный момент."""


class Refused(Exception):
    """Состояние не допускает безопасную синхронизацию."""


def git(repo, *args):
    p = subprocess.run(['git', '-C', str(repo), *args], capture_output=True)
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
def lease(repo, exclusive=False):
    path = lock_path(canonical(repo))
    with path.open('a+b') as handle:
        os.chmod(path, 0o600)
        mode = fcntl.LOCK_EX if exclusive else fcntl.LOCK_SH
        try:
            fcntl.flock(handle, mode | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise Busy('tools_in_use') from error
        try:
            yield handle.fileno()
        finally:
            fcntl.flock(handle, fcntl.LOCK_UN)


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
                rc = subprocess.run(command, cwd=candidate, stdout=log, stderr=subprocess.STDOUT).returncode
            if rc:
                raise Refused('validation_failed')
            if git(candidate, 'rev-parse', 'HEAD') != revision or not clean(candidate):
                raise Refused('validator_changed_tree')
        finally:
            # Удаляется только наша временная worktree, исходная папка не сбрасывается.
            git(repo, 'worktree', 'remove', '--force', str(candidate))


def sync(repo, state, expected_remote=UPSTREAM, command=('./dpv', 'run', 'full', '--ci')):
    repo = canonical(repo)
    state = Path(state).resolve()
    if state == repo or repo in state.parents:
        raise Refused('state_must_be_outside_tools')
    state.mkdir(parents=True, exist_ok=True, mode=0o700)
    with lease(repo, exclusive=True):
        local, branch = identity(repo)
        if branch != 'main':
            raise Refused('main_required')
        if git(repo, 'remote', 'get-url', 'origin') != expected_remote:
            raise Refused('unexpected_remote')
        if not clean(repo):
            raise Refused('uncommitted_changes')
        git(repo, 'fetch', '--no-tags', 'origin', 'refs/heads/main:refs/remotes/origin/main')
        remote = git(repo, 'rev-parse', 'refs/remotes/origin/main')
        if local == remote:
            direction, candidate = 'unchanged', local
        elif ancestor(repo, local, remote):
            direction, candidate = 'downloaded', remote
        elif ancestor(repo, remote, local):
            direction, candidate = 'published', local
        else:
            raise Refused('diverged')
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
        temporary = state / 'last-sync.tmp'
        temporary.write_text(json.dumps(result) + '\n')
        temporary.replace(state / 'last-sync.json')
        return result


def run(repo, command):
    repo = canonical(repo)
    with lease(repo) as descriptor:
        revision = git(repo, 'rev-parse', 'HEAD')
        env = dict(os.environ, DP_VERIFY_HOME=str(repo), DP_VERIFY_REVISION=revision)
        # Дочерний процесс сохраняет lease даже при аварийном завершении обёртки.
        return subprocess.run(command, env=env, pass_fds=(descriptor,)).returncode


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', required=True, type=Path)
    sub = parser.add_subparsers(dest='action', required=True)
    update = sub.add_parser('sync')
    update.add_argument('--state', required=True, type=Path)
    use = sub.add_parser('run')
    use.add_argument('command', nargs=argparse.REMAINDER)
    args = parser.parse_args(argv)
    try:
        if args.action == 'sync':
            print(json.dumps(sync(args.repo, args.state)))
            return 0
        command = args.command[1:] if args.command[:1] == ['--'] else args.command
        if not command:
            parser.error('нужна команда после run --')
        return run(args.repo, command)
    except Busy:
        print(json.dumps({'status': 'deferred', 'reason': 'tools_in_use'}))
        return 75
    except (Refused, OSError) as error:
        reason = str(error) if isinstance(error, Refused) else 'io_failed'
        print(json.dumps({'status': 'refused', 'reason': reason}))
        return 1


if __name__ == '__main__':
    sys.exit(main())
