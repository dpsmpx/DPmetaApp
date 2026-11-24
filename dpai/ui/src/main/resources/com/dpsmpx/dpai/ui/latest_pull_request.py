"""Последний открытый PR origin; без reset, stash, удаления веток и перезаписи локальных коммитов."""
import json
import re
import subprocess
import sys
from urllib.parse import urlsplit


def command(*args, codes=(0,)):
    result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=600)
    if result.returncode not in codes:
        raise RuntimeError(result.stderr.decode("utf-8", "replace").strip() or "команда завершилась с кодом " + str(result.returncode))
    return result.stdout.decode("utf-8", "strict").strip()


def clean():
    if command("git", "status", "--porcelain=v1", "--untracked-files=all"):
        raise RuntimeError("сначала сохраните и закоммитьте изменения и новые файлы; последний PR не переключён")


def main():
    clean()
    remote = command("git", "config", "--get", "remote.origin.url")
    if remote.startswith("git@github.com:"):
        repo = remote[len("git@github.com:"):]
    else:
        parsed = urlsplit(remote)
        if parsed.hostname != "github.com" or parsed.scheme not in ("https", "ssh"):
            raise RuntimeError("origin должен указывать на GitHub")
        repo = parsed.path.lstrip("/")
    repo = repo.removesuffix(".git")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
        raise RuntimeError("не удалось определить репозиторий origin")
    rows = json.loads(command("gh", "api", "--hostname", "github.com", "repos/" + repo + "/pulls?state=open&sort=created&direction=desc&per_page=1"))
    if not isinstance(rows, list) or not rows:
        raise RuntimeError("в origin нет открытых PR; текущая ветка и привязка сохранены")
    pr = rows[0]
    number, head = pr["number"], pr["head"]["sha"]
    if type(number) is not int or not 1 <= number <= 2147483647 or pr["state"] != "open" or pr["base"]["repo"]["full_name"].lower() != repo.lower():
        raise RuntimeError("GitHub вернул неподходящий PR")
    branch = pr["head"]["ref"] if (pr["head"].get("repo") or {}).get("full_name", "").lower() == repo.lower() else "pr/" + str(number)
    if not re.fullmatch(r"[0-9a-f]{40}", head) or not re.fullmatch(r"[A-Za-z0-9._/-]{1,100}", branch) or branch.startswith("-"):
        raise RuntimeError("GitHub вернул недопустимую ветку или HEAD")
    command("git", "check-ref-format", "--branch", branch)
    print("Последний открытый PR #" + str(number) + ": " + branch + " @ " + head, flush=True)
    command("git", "fetch", "--no-tags", "origin", "refs/pull/" + str(number) + "/head")
    if command("git", "rev-parse", "FETCH_HEAD") != head:
        raise RuntimeError("PR обновлён во время загрузки; повторите подготовку")
    existing = command("git", "rev-parse", "--verify", "--quiet", "refs/heads/" + branch, codes=(0, 1))
    if existing:
        ancestor = subprocess.run(["git", "merge-base", "--is-ancestor", existing, head], timeout=30)
        if ancestor.returncode != 0:
            raise RuntimeError("локальная ветка " + branch + " содержит свои коммиты; автоматическая перезапись запрещена")
    clean()
    if not existing:
        command("git", "branch", "--no-track", "--", branch, head)
    command("git", "switch", "--no-guess", branch)
    command("git", "merge", "--ff-only", "--no-edit", head)
    clean()
    if command("git", "symbolic-ref", "--short", "HEAD") != branch or command("git", "rev-parse", "HEAD") != head:
        raise RuntimeError("рабочая ветка не совпала с выбранным PR")
    print("DPAI_LATEST_PR_READY " + json.dumps(dict(number=number, branch=branch, head=head, url="https://github.com/" + repo + "/pull/" + str(number))), flush=True)


try:
    main()
except (RuntimeError, subprocess.SubprocessError, OSError, ValueError, KeyError, TypeError) as error:
    print("Подготовка последнего PR остановлена: " + str(error), file=sys.stderr)
    sys.exit(1)
