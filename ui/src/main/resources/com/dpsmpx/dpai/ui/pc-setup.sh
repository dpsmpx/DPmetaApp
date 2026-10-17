# Настройка ПК с Linux как target одной командой (D-56; docs/SETUP_PC_LINUX.md).
# DPAI_KEY_LINE — строка ключа приложения (PcSetup подставляет только ключ вида «тип base64 dpai-android»).
# Запуск — от своего пользователя: sudo спросит пароль там, где нужен root.
set -u
KEY='DPAI_KEY_LINE'
if [ "$(id -u)" = 0 ]; then echo 'Запустите от своего пользователя, не от root: sudo спросит пароль, где нужно'; exit 1; fi

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
  sudo pacman -S --needed --noconfirm openssh git unzip </dev/null
  [ "${jv:-0}" -ge 17 ] 2>/dev/null || sudo pacman -S --needed --noconfirm jre21-openjdk-headless </dev/null
else
  echo '  Менеджер пакетов не узнан: поставьте openssh-server, Java 17 или новее, git и unzip вручную и запустите команду снова'
fi

echo '[2/5] SSH-сервер: только по ключу (вернуть пароли: sudo rm /etc/ssh/sshd_config.d/10-dpai.conf)'
if grep -qs '^Include /etc/ssh/sshd_config.d/\*\.conf' /etc/ssh/sshd_config; then
  printf 'PasswordAuthentication no\nKbdInteractiveAuthentication no\n' | sudo tee /etc/ssh/sshd_config.d/10-dpai.conf >/dev/null
elif ! grep -qs '^PasswordAuthentication no' /etc/ssh/sshd_config; then
  sudo cp /etc/ssh/sshd_config /etc/ssh/sshd_config.bak-dpai && { echo 'PasswordAuthentication no'; sudo cat /etc/ssh/sshd_config.bak-dpai; } | sudo tee /etc/ssh/sshd_config >/dev/null
fi
if sudo sshd -t; then
  sudo systemctl enable --now ssh 2>/dev/null || sudo systemctl enable --now sshd 2>/dev/null || echo '  systemd нет: запустите sshd вручную'
  sudo systemctl reload ssh 2>/dev/null || sudo systemctl reload sshd 2>/dev/null || true
else
  echo '  sshd_config с ошибкой: сервер не перезапускался — смотрите вывод выше'
fi

echo '[3/5] Ключ приложения (только из сети Tailscale; прежний ключ DP AI заменяется)'
mkdir -p "$HOME/.ssh" && chmod 700 "$HOME/.ssh"
touch "$HOME/.ssh/authorized_keys"
{ grep -v ' dpai-android$' "$HOME/.ssh/authorized_keys"; printf 'from="100.64.0.0/10" %s\n' "$KEY"; } > "$HOME/.ssh/authorized_keys.dpai-new"
mv "$HOME/.ssh/authorized_keys.dpai-new" "$HOME/.ssh/authorized_keys" && chmod 600 "$HOME/.ssh/authorized_keys"

echo '[4/5] Агент dpai'
dl=$(xdg-user-dir DOWNLOAD 2>/dev/null || echo "$HOME/Downloads")
zip="$dl/dpai-agent.zip"
[ -f "$zip" ] || zip="$HOME/Downloads/dpai-agent.zip"
if [ -f "$zip" ]; then
  unzip -oq "$zip" -d "$HOME"
  # Локаль UTF-8 для агента: по SSH без неё кириллица в аргументах не передаётся (docs/EXECUTION_MODEL.md, §5).
  printf '#!/bin/sh\nexport LANG=C.UTF-8 LC_ALL=C.UTF-8\nexec "$HOME/dpai/bin/dpai" "$@"\n' > "$HOME/dpai-agent" && chmod 755 "$HOME/dpai-agent"
  "$HOME/dpai-agent" agent doctor
  echo '  (claude found:false на ПК — так и должно быть: Claude работает на телефоне)'
else
  echo "  Нет $zip: сохраните архив в приложении (Инструкция), перенесите на ПК в «Загрузки» и запустите команду снова"
fi

echo '[5/5] Поля для «Добавить» в приложении'
ip=$(tailscale ip -4 2>/dev/null | head -n 1)
[ -n "$ip" ] || ip='нет Tailscale — поставьте: https://tailscale.com/download/linux'
echo "  Хост: $ip   Порт: 22   Пользователь: $(id -un)   Windows: не отмечать"
echo "  Команда агента dpai: $HOME/dpai-agent"
echo '  Каталог Claude слота: оставить пустым (Claude — на телефоне)'
echo '  Отпечатки ключа хоста — сверить с «Получить ключ хоста»:'
for f in /etc/ssh/ssh_host_*_key.pub; do ssh-keygen -l -f "$f"; done
