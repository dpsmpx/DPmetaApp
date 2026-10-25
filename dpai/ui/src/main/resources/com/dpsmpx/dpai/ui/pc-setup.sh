# Настройка ПК с Linux как target одной командой (D-70; docs/SETUP_PC_LINUX.md).
# DPAI_KEY_LINE — строка ключа приложения (PcSetup подставляет только ключ вида «тип base64 dpai-android»).
# Запуск — от своего пользователя: sudo спросит пароль там, где нужен root.
set -u
KEY='DPAI_KEY_LINE'
if [ "$(id -u)" = 0 ]; then echo 'Запустите от своего пользователя, не от root: sudo спросит пароль, где нужно'; exit 1; fi
# Без sudo каждый шаг падал бы со своей непонятной ошибкой (Debian с паролем root ставит систему без sudo).
if ! command -v sudo >/dev/null 2>&1; then echo 'Нет sudo: под root поставьте его (apt install sudo, dnf install sudo или pacman -S sudo), добавьте себя в группу sudo или wheel (usermod -aG sudo ИМЯ), войдите заново и запустите команду снова'; exit 1; fi
sudo -v || { echo 'sudo не дал прав: ваш пользователь должен быть в группе sudo или wheel'; exit 1; }

echo '[1/5] Пакеты: OpenSSH Server, Java 17+, Git, unzip'
jv=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/.*java.specification.version = //p'); jv=${jv%%.*}
if command -v apt-get >/dev/null 2>&1; then
  sudo apt-get update -qq </dev/null
  sudo apt-get install -y -qq openssh-server git unzip </dev/null
  [ "${jv:-0}" -ge 17 ] 2>/dev/null || sudo apt-get install -y -qq openjdk-21-jre-headless </dev/null || sudo apt-get install -y -qq openjdk-17-jre-headless </dev/null
elif command -v dnf >/dev/null 2>&1; then
  sudo dnf install -y -q openssh-server git unzip </dev/null
  [ "${jv:-0}" -ge 17 ] 2>/dev/null || sudo dnf install -y -q java-21-openjdk-headless </dev/null || sudo dnf install -y -q java-17-openjdk-headless </dev/null
elif command -v pacman >/dev/null 2>&1; then
  # Arch — только полным обновлением (-Syu): со старыми списками пакетов зеркала отвечают 404.
  sudo pacman -Syu --needed --noconfirm openssh git unzip </dev/null
  # Java по умолчанию выбирает archlinux-java: новая JRE сама её не меняет.
  [ "${jv:-0}" -ge 17 ] 2>/dev/null || { sudo pacman -S --needed --noconfirm jre21-openjdk-headless </dev/null && sudo archlinux-java set java-21-openjdk; }
else
  echo '  Менеджер пакетов не узнан: поставьте openssh-server, Java 17 или новее, git и unzip вручную и запустите команду снова'
fi

echo '[2/5] SSH-сервер: только по ключу'
if grep -qs '^Include /etc/ssh/sshd_config.d/\*\.conf' /etc/ssh/sshd_config; then
  printf 'PasswordAuthentication no\nKbdInteractiveAuthentication no\n' | sudo tee /etc/ssh/sshd_config.d/10-dpai.conf >/dev/null
  echo '  Вернуть вход по паролю: sudo rm /etc/ssh/sshd_config.d/10-dpai.conf, затем перезапустить ssh'
else
  # Основной sshd_config скрипт не меняет (D-70): без Include — шаг вручную.
  echo '  В /etc/ssh/sshd_config нет строки Include /etc/ssh/sshd_config.d/*.conf — основной файл не меняется.'
  echo '  Чтобы вход был только по ключу, впишите в его начало PasswordAuthentication no и KbdInteractiveAuthentication no и перезапустите ssh'
fi
# Ключи хоста и каталог разделения прав: на свежей системе (Arch, Fedora без запуска sshd, Ubuntu 24.04 с ssh.socket)
# их ещё нет, и проверка sshd -t падала бы, а сервер так и не запускался.
sudo ssh-keygen -A >/dev/null
[ -d /run/sshd ] || sudo install -d -m 755 /run/sshd
if sudo sshd -t; then
  sudo systemctl enable --now ssh 2>/dev/null || sudo systemctl enable --now sshd 2>/dev/null || echo '  systemd нет: запустите sshd вручную'
  sudo systemctl reload ssh 2>/dev/null || sudo systemctl reload sshd 2>/dev/null || true
else
  echo '  sshd_config с ошибкой: сервер не перезапускался — смотрите вывод выше'
fi

echo '[3/5] Ключ приложения (только из частных сетей — дом, точка доступа телефона, свой VPN; прежний ключ DP AI заменяется)'
mkdir -p "$HOME/.ssh" && chmod 700 "$HOME/.ssh"
touch "$HOME/.ssh/authorized_keys"
{ grep -v ' dpai-android$' "$HOME/.ssh/authorized_keys"; printf 'from="DPAI_NETS" %s\n' "$KEY"; } > "$HOME/.ssh/authorized_keys.dpai-new"
mv "$HOME/.ssh/authorized_keys.dpai-new" "$HOME/.ssh/authorized_keys" && chmod 600 "$HOME/.ssh/authorized_keys"
# Брандмауэр (D-84): включённый ufw пускает к порту 22 только частные сети; firewalld пускает ssh в зоне по умолчанию.
if command -v ufw >/dev/null 2>&1 && sudo ufw status 2>/dev/null | grep -q '^Status: active'; then
  for n in $(echo 'DPAI_NETS' | tr ',' ' '); do sudo ufw allow from "$n" to any port 22 proto tcp >/dev/null; done
  echo '  ufw: порт 22 открыт только для частных сетей'
fi

echo '[4/5] Агент dpai'
dl=$(xdg-user-dir DOWNLOAD 2>/dev/null || echo "$HOME/Downloads")
zip="$dl/dpai-agent.zip"
[ -f "$zip" ] || zip="$HOME/Downloads/dpai-agent.zip"
if [ -f "$zip" ]; then
  unzip -oq "$zip" -d "$HOME"
  # Архив не обязан нести право на исполнение (zip из Windows, старая сборка): без него обёртка — «Permission denied».
  chmod +x "$HOME/dpai/bin/dpai"
  # Локаль UTF-8 для агента: по SSH без неё кириллица в аргументах не передаётся (docs/EXECUTION_MODEL.md, §5).
  printf '#!/bin/sh\nexport LANG=C.UTF-8 LC_ALL=C.UTF-8\nexec "$HOME/dpai/bin/dpai" "$@"\n' > "$HOME/dpai-agent" && chmod 755 "$HOME/dpai-agent"
  "$HOME/dpai-agent" agent doctor
  echo '  (claude found:false на ПК — так и должно быть: Claude работает на телефоне)'
else
  echo "  Нет $zip: сохраните архив в приложении (Инструкция), перенесите на ПК в «Загрузки» и запустите команду снова"
fi

echo '[5/5] Поля для «Добавить» в приложении'
# Адреса ПК в частных сетях (D-84): «Хост» — адрес в сети, общей с телефоном.
if command -v ip >/dev/null 2>&1; then all=$(ip -4 -o addr show scope global 2>/dev/null | awk '{print $4}' | cut -d/ -f1); else all=$(hostname -I 2>/dev/null | tr ' ' '\n'); fi
ips=$(printf '%s\n' $all | grep -E '^(10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[01])\.|100\.(6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])\.)' | tr '\n' ' ')
[ -n "$ips" ] || ips='не найден — подключите ПК к сети, общей с телефоном'
echo "  Хост: $ips  Порт: 22   Пользователь: $(id -un)   Windows: не отмечать"
echo '  Дома — адрес ПК в домашней сети; вне дома — ПК на точке доступа телефона, интернет ПК — через раздачу DPpass (docs/SETUP_PC_LINUX.md)'
echo "  Команда агента dpai: $HOME/dpai-agent"
echo '  Каталог Claude слота: оставить пустым (Claude — на телефоне)'
echo '  Отпечатки ключа хоста — сверить с «Получить ключ хоста»:'
for f in /etc/ssh/ssh_host_*_key.pub; do ssh-keygen -l -f "$f"; done
