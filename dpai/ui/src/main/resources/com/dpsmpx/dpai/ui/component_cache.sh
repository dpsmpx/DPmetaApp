# Общий кэш установщиков (DPmetaApp:D-193). Подключается до их trap/работы.
# Не публикуем частичный файл, не доверяем одному имени/маркеру готовности.
dpai_fetch() (
    set -eu
    dp_url=$1; dp_sum=$(printf '%s' "$2" | tr 'A-F' 'a-f'); dp_dest=$3; dp_label=$4
    dp_algo=${5:-sha256}; dp_timeout=${6:-1800}; dp_checksum_code=${7:-DPAI_DOWNLOAD_CHECKSUM}
    case "$dp_url" in https://*) ;; *) echo 'DPAI_DOWNLOAD_URL: нужен HTTPS'; exit 1 ;; esac
    case "$dp_algo" in sha256) dp_length=64 ;; sha1) dp_length=40 ;; *) exit 1 ;; esac
    test "${#dp_sum}" = "$dp_length" || exit 1
    case "$dp_sum" in *[!0-9a-f]*) exit 1 ;; esac
    case "$dp_timeout" in ''|*[!0-9]*) exit 1 ;; esac
    dp_root=${DPAI_DOWNLOAD_CACHE:-${XDG_CACHE_HOME:-${HOME:-/root}/.cache}/dpai/downloads}
    umask 077
    mkdir -p "$dp_root" || exit
    dp_file="$dp_root/$dp_algo-$dp_sum"
    dp_lock="$dp_file.lock"
    dp_part=
    cleanup_download() { if test -n "$dp_part"; then rm -f "$dp_part"; fi; }
    trap cleanup_download EXIT
    trap 'exit 130' HUP INT TERM
    verify_download() { test -f "$dp_file" && printf '%s  %s\n' "$dp_sum" "$dp_file" | "${dp_algo}sum" -c - >/dev/null 2>&1; }
    use_download() {
        printf 'DPAI_PROGRESS cache %s\n' "$dp_label"
        cp "$dp_file" "$dp_dest"
    }
    if verify_download; then use_download; exit; fi
    # flock есть в поставляемом Alpine. Ядро освобождает замок и после SIGKILL,
    # поэтому повтор PID или перезапуск не превращают старый lock в вечный.
    command -v flock >/dev/null || { echo 'DPAI_DOWNLOAD_TOOLS_MISSING: flock'; exit 1; }
    test ! -L "$dp_lock" || { echo 'DPAI_DOWNLOAD_LOCK: ссылка вместо lock'; exit 1; }
    exec 9>>"$dp_lock" || exit
    dp_wait=0
    until flock -n 9; do
        dp_wait=$((dp_wait + 1))
        test "$dp_wait" -le 120 || { echo 'DPAI_DOWNLOAD_BUSY: повторите после другой установки'; exit 1; }
        printf 'DPAI_PROGRESS waiting %s\n' "$dp_label"
        sleep 1
    done
    if verify_download; then use_download; exit; fi
    # Только владелец замка создаёт part; после аварии старые неполные файлы
    # можно удалить, не затронув работающую загрузку или завершённый архив.
    for dp_old in "$dp_file".part.*; do
        if test -f "$dp_old" || test -L "$dp_old"; then rm -f "$dp_old" || exit; fi
    done
    dp_part=$(mktemp "$dp_file.part.XXXXXX") || exit
    printf 'DPAI_PROGRESS download %s\n' "$dp_label"
    curl --fail --location --retry 2 --proto '=https' --connect-timeout 30 --max-time "$dp_timeout" --progress-bar "$dp_url" -o "$dp_part" || exit
    printf 'DPAI_PROGRESS verify %s\n' "$dp_label"
    printf '%s  %s\n' "$dp_sum" "$dp_part" | "${dp_algo}sum" -c - || { echo "$dp_checksum_code: $dp_label"; exit 1; }
    mv -f "$dp_part" "$dp_file" || exit
    cp "$dp_file" "$dp_dest" || exit
    printf 'DPAI_PROGRESS ready %s\n' "$dp_label"
)
