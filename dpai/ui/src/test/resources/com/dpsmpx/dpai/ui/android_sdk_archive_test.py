"""Синтетические архивы с реальными суммами; генератор SDK исполняется обычным sh."""
import hashlib
import io
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import zipfile

script_path, command_pin, tools_pin, native_prefix, binary_pins = sys.argv[1:]
binary_pins = dict(item.split(":") for item in binary_pins.split(","))


def digest(data):
    return hashlib.sha256(data).hexdigest()


with tempfile.TemporaryDirectory() as temp:
    root = Path(temp)
    home = root / "home"
    sdk = home / "android-sdk"
    binary = root / "bin"
    binary.mkdir()
    native = root / "native"

    def write(path, data, executable=False):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        if executable:
            path.chmod(0o700)

    write(native / "lib/jvm/java-21-openjdk/bin/java", b"#!/bin/sh\nexit 0\n", True)
    write(native / "lib/libdpai-jvm-compat.so", b"")
    write(binary / "uname", b"#!/bin/sh\nprintf 'aarch64\\n'\n", True)
    manager = b"""#!/bin/sh
set -eu
for arg; do case "$arg" in --sdk_root=*) sdk=${arg#*=};; esac; done
mkdir -p "$sdk/platforms/android-37.0" "$sdk/build-tools/36.1.0/lib"
printf 'platform' > "$sdk/platforms/android-37.0/android.jar"
printf 'AndroidVersion.ApiLevel=37\\n' > "$sdk/platforms/android-37.0/source.properties"
printf 'Pkg.Revision=36.1.0\\n' > "$sdk/build-tools/36.1.0/source.properties"
printf 'signer' > "$sdk/build-tools/36.1.0/lib/apksigner.jar"
"""
    command = root / "cmdline.zip"
    with zipfile.ZipFile(command, "w") as archive:
        for name, data, mode in [("cmdline-tools/bin/sdkmanager", manager, 0o100755),
                                 ("cmdline-tools/source.properties", b"Pkg.Revision=20.0\n", 0o100644)]:
            entry = zipfile.ZipInfo(name)
            entry.create_system = 3
            entry.external_attr = mode << 16
            archive.writestr(entry, data)

    aapt = b"#!/bin/sh\nprintf 'aapt2 fixture\\n'\n"
    write(root / "aapt2", aapt)
    tool_bytes = {name: ("#!/bin/sh\nprintf '" + name + " fixture\\n'\n").encode() for name in binary_pins}
    tools = root / "tools.tar.xz"
    with tarfile.open(tools, "w:xz") as archive:
        for name, data in tool_bytes.items():
            entry = tarfile.TarInfo("build-tools/34.0.4/" + name)
            entry.size, entry.mode = len(data), 0o755
            archive.addfile(entry, io.BytesIO(data))
    ndk = root / "ndk.tar"
    with tarfile.open(ndk, "w") as archive:
        entry = tarfile.TarInfo("ndk/toolchains/llvm/prebuilt/linux-aarch64/bin/clang")
        data = b"#!/bin/sh\nprintf 'clang fixture\\n'\n"
        entry.size, entry.mode = len(data), 0o755
        archive.addfile(entry, io.BytesIO(data))
    tools_digest = digest(tools.read_bytes())
    script = Path(script_path).read_text().replace("/root", str(home)).replace(native_prefix, str(native))
    for name, pin in binary_pins.items():
        script = script.replace(pin, digest(tool_bytes[name]))
    script = script.replace(command_pin, digest(command.read_bytes())).replace(tools_pin, tools_digest)
    script = script.replace("a1" * 32, digest(aapt)).replace("b2" * 32, digest(ndk.read_bytes()))
    installed_script = root / "run.sh"
    installed_script.write_text(script)
    write(binary / "curl", b"""#!/bin/sh
set -eu
url=; out=
while test "$#" -gt 0; do
  case "$1" in https://*) url=$1;; -o) out=$2; shift;; esac
  shift
done
printf '%s\\n' "$url" >> "$TEST_ROOT/calls"
test "${TEST_OFFLINE:-0}" != 1 || exit 47
case "$url" in
  *commandlinetools*.zip) cp "$TEST_ROOT/cmdline.zip" "$out";;
  *build-tools*.tar.xz) cp "$TEST_ROOT/tools.tar.xz" "$out";;
  */aapt2) cp "$TEST_ROOT/aapt2" "$out";;
  */ndk.tar) cp "$TEST_ROOT/ndk.tar" "$out";;
  *) exit 48;;
esac
""", True)
    env = dict(os.environ, HOME=str(home), PATH=str(binary) + os.pathsep + os.environ["PATH"],
               TEST_ROOT=str(root), DPAI_DOWNLOAD_CACHE=str(root / "downloads"), GRADLE_USER_HOME=str(root / "gradle"))

    def run():
        return subprocess.run(["/bin/sh", str(installed_script)], env=env, text=True, capture_output=True, timeout=12)

    first = run()
    assert first.returncode == 0, first.stdout + first.stderr
    assert len((root / "calls").read_text().splitlines()) == 4
    assert (sdk / "ndk/community/toolchains/llvm/prebuilt/linux-aarch64/bin/clang").is_file()
    env["TEST_OFFLINE"] = "1"
    second = run()
    assert second.returncode == 0, second.stdout + second.stderr
    assert len((root / "calls").read_text().splitlines()) == 4, "repeat requested a network download"
    (sdk / "dpai/aapt2").write_bytes(b"damaged")
    (sdk / "build-tools/36.1.0/aidl").write_bytes(b"damaged")
    repaired = run()
    assert repaired.returncode == 0, repaired.stdout + repaired.stderr
    assert (sdk / "dpai/aapt2").read_bytes() == aapt
    assert (sdk / "build-tools/36.1.0/aidl").read_bytes() == tool_bytes["aidl"]
    assert len((root / "calls").read_text().splitlines()) == 4, "repair ignored the saved verified archive"
    (root / "downloads" / ("sha256-" + tools_digest)).write_bytes(b"corrupt archive")
    (sdk / "build-tools/36.1.0/aidl").write_bytes(b"damaged")
    rejected = run()
    assert rejected.returncode != 0, "corrupt archive was accepted"
    assert (sdk / "build-tools/36.1.0/aidl").read_bytes() == b"damaged"
    print("PASS: four verified transfers, offline repeat and repair, corrupt cache refused")
