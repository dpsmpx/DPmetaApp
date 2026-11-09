"""Запустить команду под фильтром seccomp, как у приложения Android, — на машине сборки x86_64.

    python3 -I tools/linux/android-seccomp.py <каталог списков bionic> <команда> [аргументы…]

Android ставит процессу приложения фильтр seccomp: вызов не из списка — сигнал SIGSYS, и
процесс умирает с кодом 159 (128 + 31). Так на телефоне падали node/npm (io_uring), bash и
python (faccessat2), gh (faccessat2 из Go) — `docs/research/LINUX_IN_APP.md`, §10. Этот
скрипт ставит такой же фильтр себе и запускает команду — обычно proot среды из `host-env.sh`,
чтобы проверки Linux-среды на машине сборки видели то же, что телефон (`docs/TESTING.md`, «Слои»).

Списки — файлы bionic (`SYSCALLS.TXT`, `SECCOMP_*.TXT`) закреплённого коммита ветки Android 15;
их загружает `host-env.sh`. Разрешённое — как у `genseccomp.py` bionic для arm64 (телефон):
вызовы libc и белых списков минус чёрные. На x86_64 есть старые вызовы, которых на arm64 нет
вовсе (`open`, `stat`…): они разрешены, если разрешён их arm64-аналог (`openat`, `newfstatat`…).
Остальное — `SECCOMP_RET_TRAP`, как у приложений Android.
"""

import ctypes
import os
import pathlib
import re
import struct
import sys

LISTS_ALLOW = ("SYSCALLS.TXT", "SECCOMP_ALLOWLIST_COMMON.TXT", "SECCOMP_ALLOWLIST_APP.TXT", "SECCOMP_PRIORITY.TXT")
LISTS_BLOCK = ("SECCOMP_BLOCKLIST_COMMON.TXT", "SECCOMP_BLOCKLIST_APP.TXT")

# Старый вызов x86_64 → его замена на arm64.
LEGACY = {
    "open": "openat", "creat": "openat", "stat": "newfstatat", "lstat": "newfstatat", "access": "faccessat",
    "readlink": "readlinkat", "mkdir": "mkdirat", "rmdir": "unlinkat", "unlink": "unlinkat", "rename": "renameat",
    "link": "linkat", "symlink": "symlinkat", "chmod": "fchmodat", "chown": "fchownat", "lchown": "fchownat",
    "mknod": "mknodat", "utimes": "utimensat", "utime": "utimensat", "futimesat": "utimensat", "pipe": "pipe2",
    "dup2": "dup3", "poll": "ppoll", "select": "pselect6", "epoll_wait": "epoll_pwait", "epoll_create": "epoll_create1",
    "fork": "clone", "vfork": "clone", "getdents": "getdents64", "eventfd": "eventfd2", "signalfd": "signalfd4",
    "inotify_init": "inotify_init1", "time": "clock_gettime", "getpgrp": "getpgid", "alarm": "setitimer",
    "pause": "ppoll", "arch_prctl": "prctl",
}

LINE = re.compile(r"^\S+\s+\**([^\s(]+)\s*\(.*\)\s*([a-z0-9_,]+)\s*$")


def names(path: pathlib.Path, arch: str = "arm64") -> set[str]:
    """Имена вызовов файла bionic для [arch]: `тип имя[|псевдоним][:вызов[,вызов]](…) архитектуры`."""
    out: set[str] = set()
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.split("#")[0].strip()
        m = LINE.match(line) if line else None
        if not m or not ({"all", "lp64", arch} & set(m.group(2).split(","))):
            continue
        fn = m.group(1)
        calls = fn.split(":", 1)[1] if ":" in fn else fn.split("|")[0]
        out |= set(calls.split(","))
    return out


def allowed(lists: pathlib.Path) -> set[str]:
    allow = set().union(*(names(lists / f) for f in LISTS_ALLOW))
    block = set().union(*(names(lists / f) for f in LISTS_BLOCK))
    result = allow - block
    return result | {old for old, new in LEGACY.items() if new in result}


def numbers() -> dict[str, int]:
    for header in ("/usr/include/x86_64-linux-gnu/asm/unistd_64.h", "/usr/include/asm/unistd_64.h"):
        if os.path.isfile(header):
            text = pathlib.Path(header).read_text(encoding="utf-8")
            return {m.group(1): int(m.group(2)) for m in re.finditer(r"#define __NR_(\w+)\s+(\d+)", text)}
    sys.exit("android-seccomp: нет asm/unistd_64.h (пакет linux-libc-dev)")


def program(nums: list[int]) -> bytes:
    ld_w_abs, jeq_k, ret_k = 0x20, 0x15, 0x06
    allow, trap, kill = 0x7FFF0000, 0x00030000, 0x80000000
    audit_arch_x86_64 = 0xC000003E
    # Чужая архитектура — убить; номер из списка — разрешить; прочее — SIGSYS.
    insns = [(ld_w_abs, 0, 0, 4), (jeq_k, 1, 0, audit_arch_x86_64), (ret_k, 0, 0, kill), (ld_w_abs, 0, 0, 0)]
    for n in nums:
        insns += [(jeq_k, 0, 1, n), (ret_k, 0, 0, allow)]
    insns.append((ret_k, 0, 0, trap))
    return b"".join(struct.pack("HBBI", *i) for i in insns)


def main() -> None:
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    if os.uname().machine != "x86_64":
        sys.exit("android-seccomp: только x86_64 (на телефоне фильтр — настоящий)")
    lists = pathlib.Path(sys.argv[1])
    table = numbers()
    nums = sorted({table[n] for n in allowed(lists) if n in table})
    code = program(nums)

    class SockFprog(ctypes.Structure):
        _fields_ = [("len", ctypes.c_ushort), ("filter", ctypes.c_void_p)]

    buf = ctypes.create_string_buffer(code)
    prog = SockFprog(len(code) // 8, ctypes.cast(buf, ctypes.c_void_p))
    libc = ctypes.CDLL(None, use_errno=True)
    pr_set_no_new_privs, pr_set_seccomp, seccomp_mode_filter = 38, 22, 2
    if libc.prctl(pr_set_no_new_privs, 1, 0, 0, 0) != 0:
        sys.exit(f"android-seccomp: PR_SET_NO_NEW_PRIVS: {os.strerror(ctypes.get_errno())}")
    if libc.prctl(pr_set_seccomp, seccomp_mode_filter, ctypes.byref(prog), 0, 0) != 0:
        sys.exit(f"android-seccomp: PR_SET_SECCOMP: {os.strerror(ctypes.get_errno())}")
    os.execvp(sys.argv[2], sys.argv[2:])


if __name__ == "__main__":
    main()
