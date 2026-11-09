#!/bin/sh
# Linux-среда приложения на машине сборки (D-75, D-82): тот же proot-static 5.4.0 и Alpine 3.24.3,
# что в APK, но x86_64 — чтобы проверять ProotHost и Claude Code в госте без телефона
# (ProotLiveTest, docs/TESTING.md, «Слои»). В APK этот скрипт не участвует.
#
#   tools/linux/host-env.sh <каталог>           # подготовить: bin/proot, bin/loader, rootfs, пакеты, Claude Code
#   ./gradlew :execution:test --tests '*ProotLiveTest*' -Ddpai.test.proot=<каталог>
#
# Суммы закреплены 9 ноября 2026 по загрузке с dl-cdn.alpinelinux.org (HTTPS); цепочку подписей
# Alpine, как для файлов APK (dpai/android/build.gradle.kts), не сверяли: среда — только для
# проверок на хосте. Пакеты внутри ставит apk гостя и проверяет их подписи сам.
# Прокси окружения (HTTPS_PROXY, сертификат SSL_CERT_FILE) передаётся гостю — для облачной сессии.
set -eu

DIR=${1:?каталог среды}
[ "$(uname -m)" = x86_64 ] || { echo "host-env.sh: нужна машина x86_64 (на arm64 — сам APK)" >&2; exit 2; }
BASE=https://dl-cdn.alpinelinux.org/alpine/v3.24
ROOTFS=alpine-minirootfs-3.24.3-x86_64.tar.gz
ROOTFS_SHA=f8e1cafba9b1b283b5d30d06f9885c8d2bc161ffe9083ade8e2e157813a53407
PROOT_APK=proot-static-5.4.0-r2.apk
PROOT_SHA=d1fb48b5bcf97ecaf90feea3560f377cd0b84a6b855809c38f43cedeb4843088
PACKAGES=${PACKAGES:-git nodejs npm}

mkdir -p "$DIR/dl" "$DIR/bin" "$DIR/rootfs" "$DIR/tmp"
DIR=$(cd "$DIR" && pwd)

fetch() { # имя адрес сумма
    if [ ! -f "$DIR/dl/$1" ]; then curl -sS -fL -o "$DIR/dl/$1.part" "$2" && mv "$DIR/dl/$1.part" "$DIR/dl/$1"; fi
    echo "$3  $DIR/dl/$1" | sha256sum -c --quiet - || { rm -f "$DIR/dl/$1"; echo "host-env.sh: $1 — сумма не та" >&2; exit 1; }
}
fetch "$ROOTFS" "$BASE/releases/x86_64/$ROOTFS" "$ROOTFS_SHA"
fetch "$PROOT_APK" "$BASE/community/x86_64/$PROOT_APK" "$PROOT_SHA"

if [ ! -f "$DIR/rootfs/etc/alpine-release" ]; then tar -xzf "$DIR/dl/$ROOTFS" -C "$DIR/rootfs"; fi
tar -xzf "$DIR/dl/$PROOT_APK" -C "$DIR/tmp" usr/bin/proot.static 2>/dev/null
cp "$DIR/tmp/usr/bin/proot.static" "$DIR/bin/proot"
# Вшитый загрузчик — второй ELF файла до конца таблицы разделов: так же его берёт сборка APK.
python3 - "$DIR/bin/proot" "$DIR/bin/loader" <<'PY'
import struct, sys
p = open(sys.argv[1], "rb").read()
at = p.find(b"\x7fELF", 1)
shoff, = struct.unpack_from("<Q", p, at + 0x28)
size, num = struct.unpack_from("<HH", p, at + 0x3a)
open(sys.argv[2], "wb").write(p[at:at + shoff + size * num])
PY
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
