#!/usr/bin/env python3
"""Стенд для dppass-via-phone: «интернет», «телефон» с входом раздачи DPpass
(Xray socks с паролем, как XrayCompiler.shareInbounds) и «ПК» — в своих
сетевых пространствах имён. Запуск от root:

    python3 via_phone_stand.py BIN_DIR SCRIPT

BIN_DIR — каталог с `sing-box` и `xray`, SCRIPT — `dppass-via-phone`.

Метки: 198.51.100.2 — выход «туннеля» телефона (sendThrough у Xray),
10.77.0.2 — адрес ПК (пакет прошёл мимо туннеля), 203.0.113.66 — ответ
DNS раздачи телефона (утечка DNS к оператору), 203.0.113.10 — ответ DNS
через туннель.
"""
import json
import os
import secrets
import signal
import socket
import socketserver
import struct
import subprocess
import sys
import tempfile
import threading
import time

NS = {"inet": "dpv-inet", "phone": "dpv-phone", "pc": "dpv-pc", "home": "dpv-home"}
# Вход — случайный на каждый запуск, алфавитом DPpass (ShareSettings): в файле паролей нет.
ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"
PORT = 41234
USER, PASS = ("".join(secrets.choice(ALPHABET) for _ in range(n)) for n in (8, 12))
LINK = f"socks5://{USER}:{PASS}@10.77.0.1:{PORT}#DPpass"
VPN_EXIT, PC, LEAK_DNS, TUNNEL_DNS = "198.51.100.2", "10.77.0.2", "203.0.113.66", "203.0.113.10"
failures = []


def sh(*args, check=True, ns=None, **kw):
    cmd = (["ip", "netns", "exec", NS[ns]] if ns else []) + list(args)
    r = subprocess.run(cmd, capture_output=True, text=True, **kw)
    if check and r.returncode != 0:
        raise SystemExit(f"✗ {' '.join(cmd)}: {r.returncode}\n{r.stdout}{r.stderr}")
    return r


def expect(what, ok, detail=""):
    print(("✓ " if ok else "✗ ") + what + (f" — {detail}" if detail and not ok else ""))
    if not ok:
        failures.append(what)


# ---------- серверы (запускаются в своих пространствах через --serve) ----------

def dns_answer(query, answer_ip, log, src, proto):
    qid, flags, qd = struct.unpack("!HHH", query[:6])
    i, labels = 12, []
    while query[i]:
        labels.append(query[i + 1:i + 1 + query[i]].decode())
        i += 1 + query[i]
    qtype = struct.unpack("!H", query[i + 1:i + 3])[0]
    question = query[12:i + 5]
    name = ".".join(labels).lower()
    with open(log, "a") as f:
        f.write(f"{proto} {src} {name} {qtype}\n")
    rr = b""
    if name == "check.example" and qtype == 1:
        rr = b"\xc0\x0c" + struct.pack("!HHIH", 1, 1, 30, 4) + socket.inet_aton(answer_ip)
    return struct.pack("!HHHHHH", qid, 0x8180, 1, 1 if rr else 0, 0, 0) + question + rr


def serve_dns(addr, answer_ip, log):
    class U(socketserver.BaseRequestHandler):
        def handle(self):
            data, s = self.request
            s.sendto(dns_answer(data, answer_ip, log, self.client_address[0], "udp"), self.client_address)

    class T(socketserver.BaseRequestHandler):
        def handle(self):
            n = struct.unpack("!H", self.request.recv(2))[0]
            q = b""
            while len(q) < n:
                q += self.request.recv(n - len(q))
            a = dns_answer(q, answer_ip, log, self.client_address[0], "tcp")
            self.request.sendall(struct.pack("!H", len(a)) + a)

    socketserver.ThreadingUDPServer.allow_reuse_address = True
    socketserver.ThreadingTCPServer.allow_reuse_address = True
    u = socketserver.ThreadingUDPServer((addr, 53), U)
    t = socketserver.ThreadingTCPServer((addr, 53), T)
    threading.Thread(target=u.serve_forever, daemon=True).start()
    t.serve_forever()


def serve_echo(addrs, log):
    class H(socketserver.StreamRequestHandler):
        def handle(self):
            self.rfile.readline()
            while self.rfile.readline() not in (b"\r\n", b"\n", b""):
                pass
            local = self.request.getsockname()[0]
            body = f"{self.client_address[0]} {local}\n".encode()
            with open(log, "a") as f:
                f.write(f"tcp {self.client_address[0]} {local}\n")
            self.wfile.write(b"HTTP/1.0 200 OK\r\nContent-Length: %d\r\n\r\n" % len(body) + body)

    class U(socketserver.BaseRequestHandler):
        def handle(self):
            data, s = self.request
            with open(log, "a") as f:
                f.write(f"udp {self.client_address[0]}\n")
            s.sendto(f"{self.client_address[0]}".encode(), self.client_address)

    socketserver.ThreadingTCPServer.allow_reuse_address = True
    for a in addrs:
        srv = socketserver.ThreadingTCPServer((a, 80), H)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        us = socketserver.ThreadingUDPServer((a, 5353), U)
        threading.Thread(target=us.serve_forever, daemon=True).start()
    while True:
        time.sleep(3600)


# ---------- стенд ----------

def setup(tmp):
    for n in NS.values():
        subprocess.run(["ip", "netns", "del", n], capture_output=True)
        sh("ip", "netns", "add", n)
    sh("ip", "link", "add", "ap0", "netns", NS["phone"], "type", "veth", "peer", "name", "wlan0", "netns", NS["pc"])
    sh("ip", "link", "add", "wan0", "netns", NS["phone"], "type", "veth", "peer", "name", "up0", "netns", NS["inet"])
    for ns, cmds in {
        "inet": [
            "ip link set lo up", "ip link set up0 up", "ip addr add 198.51.100.254/24 dev up0",
            "ip addr add 1.1.1.1/32 dev lo", f"ip addr add {TUNNEL_DNS}/32 dev lo", f"ip addr add {LEAK_DNS}/32 dev lo",
            "ip route add 10.77.0.0/24 via 198.51.100.1",
        ],
        "phone": [
            "ip link set lo up", "ip link set ap0 up", "ip link set wan0 up", "ip addr add 10.77.0.1/24 dev ap0",
            "ip addr add 198.51.100.1/24 dev wan0", f"ip addr add {VPN_EXIT}/24 dev wan0",
            "ip route add default via 198.51.100.254 src 198.51.100.1", "sysctl -qw net.ipv4.ip_forward=1",
        ],
        "pc": ["ip link set lo up", "ip link set wlan0 up", "ip addr add 10.77.0.2/24 dev wlan0", "ip route add default via 10.77.0.1"],
    }.items():
        for c in cmds:
            sh(*c.split(), ns=ns)
    os.makedirs(f"/etc/netns/{NS['pc']}", exist_ok=True)
    with open(f"/etc/netns/{NS['pc']}/resolv.conf", "w") as f:
        f.write("nameserver 10.77.0.1\n")  # как от DHCP точки доступа Android
    os.makedirs(f"/etc/netns/{NS['phone']}", exist_ok=True)
    with open(f"/etc/netns/{NS['phone']}/resolv.conf", "w") as f:
        f.write("nameserver 1.1.1.1\n")


def bg(ns, *args, log=None):
    out = open(log, "w") if log else subprocess.DEVNULL
    return subprocess.Popen(["ip", "netns", "exec", NS[ns]] + list(args), stdout=out, stderr=subprocess.STDOUT, start_new_session=True)


def xray_config(tmp):
    cfg = {
        "log": {"loglevel": "warning"},
        "inbounds": [{
            "tag": "dppass:share:0", "protocol": "socks", "listen": "10.77.0.1", "port": PORT,
            "settings": {"auth": "password", "accounts": [{"user": USER, "pass": PASS}], "udp": True, "ip": "10.77.0.1"},
            "sniffing": {"enabled": True, "destOverride": ["http", "tls", "quic"], "routeOnly": True},
        }],
        "outbounds": [{"tag": "tunnel", "protocol": "freedom", "sendThrough": VPN_EXIT}],
    }
    path = os.path.join(tmp, "xray.json")
    with open(path, "w") as f:
        json.dump(cfg, f)
    return path


def curl(url):
    r = sh("curl", "-s", "-m", "8", url, check=False, ns="pc")
    return r.stdout.strip() if r.returncode == 0 else f"curl rc={r.returncode}"


def udp_probe():
    code = ("import socket;s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);s.settimeout(5);"
            f"s.sendto(b'x',('{TUNNEL_DNS}',5353));print(s.recvfrom(100)[0].decode())")
    r = sh(sys.executable, "-I", "-c", code, check=False, ns="pc")
    return r.stdout.strip() if r.returncode == 0 else "нет ответа"


def lines(path):
    try:
        with open(path) as f:
            return f.read().splitlines()
    except FileNotFoundError:
        return []


def rules():
    return sh("ip", "rule", "show", ns="pc").stdout.strip().splitlines()


def wait_link(present, timeout=15):
    end = time.time() + timeout
    while time.time() < end:
        up = sh("ip", "link", "show", "dppass-phone", check=False, ns="pc").returncode == 0
        if up == present:
            return True
        time.sleep(0.3)
    return False


def start_script(script, env, args, log):
    return bg("pc", "env", *[f"{k}={v}" for k, v in env.items()], "bash", script, *args, log=log)


def stop(p):
    os.killpg(p.pid, signal.SIGINT)  # как Ctrl+C: сигнал всей группе
    try:
        return p.wait(15)
    except subprocess.TimeoutExpired:
        os.killpg(p.pid, signal.SIGKILL)
        return None


def main(bin_dir, script):
    tmp = tempfile.mkdtemp(prefix="dpv-")
    if not os.path.exists("/proc/net/if_inet6"):
        # Ядро без IPv6: strict_route добавляет правило «IPv6 недоступен» (sing-tun,
        # tun_linux.go, rules()) и падает. На Linux без auto_redirect это его
        # единственное действие — остальное проверяется и без него.
        print("! в ядре нет IPv6: проверка без strict_route, правило «IPv6 недоступен» не проверено")
        patched = os.path.join(tmp, "dppass-via-phone")
        with open(script) as f:
            text = f.read()
        assert '"strict_route": true' in text
        with open(patched, "w") as f:
            f.write(text.replace('"strict_route": true', '"strict_route": false'))
        script = patched
    home = os.path.join(tmp, "home")
    os.makedirs(home)
    env = {"HOME": home, "PATH": f"{bin_dir}:/usr/sbin:/usr/bin:/sbin:/bin", "XDG_RUNTIME_DIR": tmp}
    setup(tmp)
    procs = []
    try:
        me = os.path.abspath(__file__)
        procs.append(bg("inet", sys.executable, "-I", me, "--serve-echo", f"{TUNNEL_DNS},{LEAK_DNS}", f"{tmp}/echo.log"))
        procs.append(bg("inet", sys.executable, "-I", me, "--serve-dns", "1.1.1.1", TUNNEL_DNS, f"{tmp}/dns-public.log"))
        procs.append(bg("phone", sys.executable, "-I", me, "--serve-dns", "10.77.0.1", LEAK_DNS, f"{tmp}/dns-tether.log"))
        xray = bg("phone", os.path.join(bin_dir, "xray"), "run", "-c", xray_config(tmp), log=f"{tmp}/xray.log")
        procs.append(xray)
        time.sleep(1.5)
        base_rules = rules()

        # 1. Без скрипта: трафик точки доступа идёт мимо туннеля телефона (так ведёт себя Android).
        got = curl("http://check.example/")
        expect("без скрипта DNS ПК — у раздачи телефона, трафик — мимо туннеля", got == f"{PC} {LEAK_DNS}", got)

        # Способ «только браузер»: HTTP-прокси на том же порту, с тем же логином и паролем (D-51).
        r = sh("curl", "-s", "-m", "8", "-x", f"http://{USER}:{PASS}@10.77.0.1:{PORT}", "http://check.example/", check=False, ns="pc")
        expect("HTTP-прокси телефона с паролем: имя разрешает телефон, трафик — через туннель",
               r.stdout.strip() == f"{VPN_EXIT} {TUNNEL_DNS}", r.stdout + r.stderr)
        r = sh("curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", "-m", "8", "-x", f"http://10.77.0.1:{PORT}", "http://check.example/",
               check=False, ns="pc")
        expect("HTTP-прокси без пароля — 407", r.stdout.strip() == "407", r.stdout + r.stderr)

        # 2. Неверная ссылка — отказ.
        r = sh("env", *[f"{k}={v}" for k, v in env.items()], "bash", script, "http://x", check=False, ns="pc")
        expect("ссылка не socks5:// — отказ", r.returncode == 1 and "не ссылка входа" in r.stderr, r.stderr)

        # 3. Подключение со ссылкой.
        p = start_script(script, env, [LINK], f"{tmp}/run1.log")
        expect("TUN dppass-phone поднят", wait_link(True), "".join(lines(f"{tmp}/run1.log")))
        time.sleep(1.5)
        login = os.path.join(home, ".config/dppass-via-phone/login")
        st = os.stat(login)
        expect("вход запомнен с правами 0600", oct(st.st_mode & 0o777) == "0o600", oct(st.st_mode))
        expect("в запомненном нет адреса телефона", "10.77.0.1" not in open(login).read())
        tether_before = len(lines(f"{tmp}/dns-tether.log"))
        echo_start = len(lines(f"{tmp}/echo.log"))
        # Раньше в журнале — запросы самого «телефона» (HTTP-прокси выше): здесь важны только запросы ПК.
        pub_start = len(lines(f"{tmp}/dns-public.log"))
        got = curl("http://check.example/")
        expect("имя → через туннель: выход телефона и ответ DNS через туннель", got == f"{VPN_EXIT} {TUNNEL_DNS}", got)
        got = curl(f"http://{LEAK_DNS}/")
        expect("по IP → через туннель", got == f"{VPN_EXIT} {LEAK_DNS}", got)
        got = udp_probe()
        expect("UDP → через туннель (SOCKS5 UDP ASSOCIATE)", got == VPN_EXIT, got)
        r = sh("getent", "ahostsv4", "check.example", check=False, ns="pc")
        expect("резолвер системы получает ответ через туннель", TUNNEL_DNS in r.stdout, r.stdout + r.stderr)
        expect("DNS раздачи телефона (оператор) не спрошен ни разу", len(lines(f"{tmp}/dns-tether.log")) == tether_before,
               "\n".join(lines(f"{tmp}/dns-tether.log")[tether_before:]))
        pub = lines(f"{tmp}/dns-public.log")[pub_start:]
        expect("DNS-сервер спрошен по TCP с выхода телефона", bool(pub) and all(l.startswith(f"tcp {VPN_EXIT} ") for l in pub), "\n".join(pub))
        expect("ни одного пакета до «интернета» с адреса ПК", not any(f" {PC} " in l + " " for l in lines(f"{tmp}/echo.log")[echo_start:]),
               "\n".join(lines(f"{tmp}/echo.log")))
        # Повторный запуск при работающем — отказ.
        r = sh("env", *[f"{k}={v}" for k, v in env.items()], "bash", script, check=False, ns="pc")
        expect("второй запуск — отказ «уже запущен»", r.returncode == 1 and "уже запущен" in r.stderr, r.stderr)

        # 4. Ctrl+C — уборка.
        rc = stop(p)
        expect("Ctrl+C останавливает", rc is not None, "не остановился за 15 с")
        expect("TUN убран", wait_link(False, 5))
        expect("правила ip rule — как до запуска", rules() == base_rules, "\n".join(rules()))
        cfgs = [f for f in os.listdir(tmp) if f.startswith("dppass-via-phone.")]
        expect("файл конфигурации с паролем удалён", not cfgs, str(cfgs))
        got = curl("http://check.example/")
        expect("после остановки сеть ПК — как без скрипта", got == f"{PC} {LEAK_DNS}", got)

        # 5. Запомненный вход, но неверный пароль: мимо туннеля ничего не уходит.
        with open(login) as f:
            saved = f.read()
        with open(login, "w") as f:
            f.write(saved.replace(PASS, PASS[::-1]))
        echo_before = len(lines(f"{tmp}/echo.log"))
        p = start_script(script, env, [], f"{tmp}/run2.log")
        expect("с запомненным входом TUN поднят", wait_link(True), "".join(lines(f"{tmp}/run2.log")))
        time.sleep(1.5)
        got = curl(f"http://{LEAK_DNS}/")
        expect("неверный пароль: соединения нет, мимо туннеля — тоже", got.startswith("curl rc=") and len(lines(f"{tmp}/echo.log")) == echo_before,
               got)
        stop(p)
        wait_link(False, 5)
        with open(login, "w") as f:
            f.write(saved)

        # 6. Две сети: проводная по умолчанию (без телефона) и точка доступа — телефон найден среди шлюзов.
        # Роутера в «доме» нет: шлюз не отвечает, как роутер без порта раздачи.
        sh("ip", "link", "add", "eth0", "netns", NS["pc"], "type", "veth", "peer", "name", "lan0", "netns", NS["home"])
        sh("ip", "link", "set", "lan0", "up", ns="home")
        for c in ("ip link set eth0 up", "ip addr add 192.168.1.2/24 dev eth0", "ip route add default via 192.168.1.1 dev eth0 metric 50"):
            sh(*c.split(), ns="pc")
        p = start_script(script, env, [], f"{tmp}/run3.log")
        expect("две сети: TUN поднят", wait_link(True, 20), "".join(lines(f"{tmp}/run3.log")))
        time.sleep(1.5)
        got = curl("http://check.example/")
        expect("две сети: телефон найден вторым шлюзом, трафик — через туннель", got == f"{VPN_EXIT} {TUNNEL_DNS}", got)
        expect("две сети: в выводе — телефон через wlan0", "10.77.0.1:41234 через wlan0" in "".join(lines(f"{tmp}/run3.log")))
        stop(p)
        wait_link(False, 5)
        sh("ip", "link", "del", "eth0", ns="pc")

        # 7. Аварийный выход sing-box (kill -9): правила остаются, следующий запуск и Ctrl+C их убирают.
        p = start_script(script, env, [], f"{tmp}/run4.log")
        expect("перед аварией TUN поднят", wait_link(True), "".join(lines(f"{tmp}/run4.log")))
        sh("pkill", "-KILL", "-x", "sing-box", ns="pc")
        p.wait(10)
        expect("после kill -9 TUN исчез сам", wait_link(False, 5))
        left = rules() != base_rules
        print(f"  (правил после kill -9 осталось: {'да' if left else 'нет'})")
        p = start_script(script, env, [], f"{tmp}/run5.log")
        expect("запуск после аварии проходит", wait_link(True), "".join(lines(f"{tmp}/run5.log")))
        time.sleep(1.5)
        got = curl("http://check.example/")
        expect("и трафик снова через туннель", got == f"{VPN_EXIT} {TUNNEL_DNS}", got)
        stop(p)
        wait_link(False, 5)
        expect("Ctrl+C после аварии — правила как до запуска", rules() == base_rules, "\n".join(rules()))

        # 8. Телефон не слушает (раздача выключена) — отказ с причиной, TUN не поднимается.
        os.killpg(xray.pid, signal.SIGTERM)
        xray.wait(5)
        r = sh("env", *[f"{k}={v}" for k, v in env.items()], "bash", script, check=False, ns="pc", timeout=30)
        expect("раздача выключена — «телефон не найден»", r.returncode == 1 and "телефон не найден" in r.stderr, r.stderr)
        expect("и TUN не поднят", sh("ip", "link", "show", "dppass-phone", check=False, ns="pc").returncode != 0)
    finally:
        for p in procs:
            try:
                os.killpg(p.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
        for n in NS.values():
            subprocess.run(["ip", "netns", "del", n], capture_output=True)
        for n in ("pc", "phone"):
            subprocess.run(["rm", "-rf", f"/etc/netns/{NS[n]}"])
    print(f"журналы: {tmp}")
    if failures:
        print(f"✗ не прошло: {len(failures)}")
        return 1
    print("✓ всё прошло")
    return 0


if __name__ == "__main__":
    if sys.argv[1] == "--serve-dns":
        serve_dns(sys.argv[2], sys.argv[3], sys.argv[4])
    elif sys.argv[1] == "--serve-echo":
        serve_echo(sys.argv[2].split(","), sys.argv[3])
    else:
        sys.exit(main(*sys.argv[1:3]))
