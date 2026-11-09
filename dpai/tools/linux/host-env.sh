#!/bin/sh
# Linux-среда приложения на машине сборки (D-75, D-115): тот же proot (форк Termux, build-proot.sh)
# и Alpine 3.24.3, что в APK, но x86_64 — чтобы проверять ProotHost и Claude Code в госте без
# телефона (ProotLiveTest, docs/TESTING.md, «Слои»). В APK этот скрипт не участвует.
#
#   NDK=<каталог NDK> tools/linux/host-env.sh <каталог>   # подготовить: bin/proot, bin/loader, rootfs, пакеты, Claude Code
#   ./gradlew :execution:test --tests '*ProotLiveTest*' -Ddpai.test.proot=<каталог>
#
# bin/proot — proot под фильтром seccomp, как у приложения Android (android-seccomp.py по спискам
# bionic Android 15): вызов, который телефон запрещает, и здесь даёт SIGSYS — его должен
# перехватить proot (docs/research/LINUX_IN_APP.md, §10). Без переменной NDK — последняя версия
# из $ANDROID_HOME/ndk.
#
# Суммы закреплены 9 ноября 2026 по загрузке с dl-cdn.alpinelinux.org и android.googlesource.com
# (HTTPS); цепочку подписей Alpine для minirootfs не сверяли: среда — только для проверок на хосте.
# Пакеты внутри ставит apk гостя и проверяет их подписи сам.
# Прокси окружения (HTTPS_PROXY, сертификат SSL_CERT_FILE) передаётся гостю — для облачной сессии.
set -eu

DIR=${1:?каталог среды}
[ "$(uname -m)" = x86_64 ] || { echo "host-env.sh: нужна машина x86_64 (на arm64 — сам APK)" >&2; exit 2; }
BASE=https://dl-cdn.alpinelinux.org/alpine/v3.24
ROOTFS=alpine-minirootfs-3.24.3-x86_64.tar.gz
ROOTFS_SHA=f8e1cafba9b1b283b5d30d06f9885c8d2bc161ffe9083ade8e2e157813a53407
# Списки фильтра seccomp приложений: bionic, ветка android15-release, коммит 361ba867 (18 июля 2024).
BIONIC=https://android.googlesource.com/platform/bionic/+/361ba86734fb2821a6adcfdf775db8abd04e0de0/libc
BIONIC_LISTS="
SYSCALLS.TXT c3c72838cf1d4e8832941fe50ff520eae5d34829e20d2fbf5d3368bdd3209216
SECCOMP_ALLOWLIST_COMMON.TXT e7a43bd655a012562770d84891e1f2671321f19a1395eb2df924a6f9262e234c
SECCOMP_ALLOWLIST_APP.TXT 399da338f268dd52d9fe5ad66a023317158f28b4889d7d110c3c54f3a3d44f43
SECCOMP_BLOCKLIST_COMMON.TXT 41861b21a4471444a9987386726f93bac546921dd1b3ec912f20cdc53b2ac761
SECCOMP_BLOCKLIST_APP.TXT e263498e629301339a121703960458bf305ec6e559907bf6ae12d4b56c275d96
SECCOMP_PRIORITY.TXT 69719591740fe41a2d9662579d93a7874364fea9d341f348d0cba71ade5411da"
# bash — оболочка Claude Code: без bash или zsh он не выполняет команды.
PACKAGES=${PACKAGES:-git nodejs npm bash}
HERE=$(cd "$(dirname "$0")" && pwd)
NDK=${NDK:-$(ls -d "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"/ndk/*/ 2>/dev/null | sort | tail -n 1)}
[ -n "$NDK" ] || { echo "host-env.sh: нет NDK (переменная NDK или \$ANDROID_HOME/ndk)" >&2; exit 2; }
PYTHON=$(command -v python3) || { echo "host-env.sh: нужен python3 (android-seccomp.py)" >&2; exit 2; }

mkdir -p "$DIR/dl/bionic" "$DIR/bin" "$DIR/rootfs" "$DIR/tmp"
DIR=$(cd "$DIR" && pwd)

fetch() { # имя адрес сумма
    if [ ! -f "$DIR/dl/$1" ]; then curl -sS -fL -o "$DIR/dl/$1.part" "$2" && mv "$DIR/dl/$1.part" "$DIR/dl/$1"; fi
    echo "$3  $DIR/dl/$1" | sha256sum -c --quiet - || { rm -f "$DIR/dl/$1"; echo "host-env.sh: $1 — сумма не та" >&2; exit 1; }
}
fetch "$ROOTFS" "$BASE/releases/x86_64/$ROOTFS" "$ROOTFS_SHA"
echo "$BIONIC_LISTS" | while read -r name sum; do
    [ -n "$name" ] || continue
    # gitiles отдаёт файл в base64 (?format=TEXT).
    if [ ! -f "$DIR/dl/bionic/$name" ]; then
        curl -sS -fL "$BIONIC/$name?format=TEXT" | base64 -d > "$DIR/dl/bionic/$name.part"
        mv "$DIR/dl/bionic/$name.part" "$DIR/dl/bionic/$name"
    fi
    echo "$sum  $DIR/dl/bionic/$name" | sha256sum -c --quiet - || { rm -f "$DIR/dl/bionic/$name"; echo "host-env.sh: $name — сумма не та" >&2; exit 1; }
done

if [ ! -f "$DIR/rootfs/etc/alpine-release" ]; then tar -xzf "$DIR/dl/$ROOTFS" -C "$DIR/rootfs"; fi
NDK=$NDK "$HERE/build-proot.sh" x86_64 "$DIR/proot" "$DIR/dl"
cp "$DIR/proot/loader" "$DIR/bin/loader"
# bin/proot — proot под фильтром seccomp, как у приложения Android.
printf '#!/bin/sh\nexec "%s" -I "%s" "%s" "%s" "$@"\n' "$PYTHON" "$HERE/android-seccomp.py" "$DIR/dl/bionic" "$DIR/proot/proot" > "$DIR/bin/proot"
chmod 755 "$DIR/bin/proot" "$DIR/bin/loader"

# DNS сети машины — как приложение пишет DNS сети телефона (D-90); сертификат прокси — гостю.
grep '^nameserver' /etc/resolv.conf | head -3 > "$DIR/rootfs/etc/resolv.conf"
CA=
if [ -n "${SSL_CERT_FILE:-}" ] && [ -f "$SSL_CERT_FILE" ]; then cp "$SSL_CERT_FILE" "$DIR/rootfs/etc/ssl/host-ca.pem"; CA=/etc/ssl/host-ca.pem; fi

guest() { # [noproc] команда… — как ProotHost: узкий /dev, без /sys; /proc — кроме apk
    noproc=0; if [ "$1" = noproc ]; then noproc=1; shift; fi
    set -- -b /dev/null -b /dev/zero -b /dev/full -b /dev/random -b /dev/urandom -b /dev/tty -b /dev/ptmx -b /dev/pts -w /root "$@"
    if [ $noproc = 0 ]; then set -- -b /proc -b /proc/self/fd:/dev/fd "$@"; fi
    env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin LANG=C.UTF-8 TMPDIR=/tmp \
        PROOT_LOADER="$DIR/bin/loader" PROOT_TMP_DIR="$DIR/tmp" \
        ${HTTPS_PROXY:+HTTPS_PROXY=$HTTPS_PROXY https_proxy=$HTTPS_PROXY http_proxy=$HTTPS_PROXY npm_config_https_proxy=$HTTPS_PROXY} \
        ${CA:+SSL_CERT_FILE=$CA NODE_EXTRA_CA_CERTS=$CA} \
        "$DIR/bin/proot" --kill-on-exit -0 -r "$DIR/rootfs" "$@" </dev/null
}

# shellcheck disable=SC2086 # имена пакетов — словами
guest noproc apk add -U --no-progress $PACKAGES
guest /bin/sh -c 'command -v claude >/dev/null || npm install -g @anthropic-ai/claude-code; claude --version'
echo "готово: -Ddpai.test.proot=$DIR"
