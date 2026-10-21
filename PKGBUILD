# PKGBUILD DPpass для Linux (D-74): Garuda/Arch, `makepkg -si` в каталоге с архивом.
# Собран из desktop/package/PKGBUILD.in (tools/desktop/package.py): версия и сумма — по архиву.
# Личная сборка: не публикуется (DPmetaApp:D-24).
pkgname=dppass-desktop
_ver=0.1.2-dev
pkgver=0.1.2dev
pkgrel=1
pkgdesc='DPpass для Linux: окно и туннель TUN через свои серверы (личная сборка)'
arch=('x86_64')
license=('LicenseRef-DPpass')
depends=('java-runtime>=17' 'iproute2' 'polkit' 'util-linux' 'glibc')
source=("dppass-desktop-${_ver}.tar.gz")
sha256sums=('a9a5c5786ad9c7d8bd1f630b0cc37d8ca0f18a41ba9b5d6a576e9d86bdef0d2b')
# Библиотека ядра — как собрана и сверена (native.py desktop): без strip и отладочного пакета.
options=('!strip' '!debug')

package() {
    cd "dppass-desktop-${_ver}"
    install -d "$pkgdir/usr/lib/dppass"
    cp -r --no-preserve=ownership bin lib native geodata "$pkgdir/usr/lib/dppass/"
    rm -f "$pkgdir/usr/lib/dppass/bin/dppass-desktop.bat"
    chmod 755 "$pkgdir/usr/lib/dppass/bin/dppass-desktop"
    install -Dm755 package/dppass-launch "$pkgdir/usr/lib/dppass/dppass-launch"
    install -Dm755 package/dppass "$pkgdir/usr/bin/dppass"
    install -Dm644 package/com.dpsmpx.dppass.policy "$pkgdir/usr/share/polkit-1/actions/com.dpsmpx.dppass.policy"
    install -Dm644 package/dppass.desktop "$pkgdir/usr/share/applications/dppass.desktop"
    install -Dm644 package/dppass.svg "$pkgdir/usr/share/icons/hicolor/scalable/apps/dppass.svg"
    install -Dm644 LICENSE "$pkgdir/usr/share/licenses/$pkgname/LICENSE"
    install -Dm644 geodata/NOTICE.txt "$pkgdir/usr/share/licenses/$pkgname/NOTICE-geodata.txt"
}
