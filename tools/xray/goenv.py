"""Окружение Go для сборок из `tools/xray` — одно для всех (T10, D-24, D-25).

Ядро для проверки на хосте (`check_xray.py`) и библиотека ядра для
приложения и теста JNI (`native.py`) собираются из одного `go.mod` одним
и тем же способом: модули сверяются с `go.sum` и sum.golang.org, суммы
не меняются (`-mod=readonly`), версия toolchain — из директивы `go`.
"""
import os

# Эти переменные выключают сверку модулей с sum.golang.org или пускают мимо прокси без неё.
UNSAFE_ENV = ("GONOSUMDB", "GONOSUMCHECK", "GONOSUMCHECKS", "GOINSECURE", "GOPRIVATE", "GONOPROXY")


class UnsafeGoEnv(Exception):
    """Сверка модулей выключена окружением — сборка не запускается."""


def go_env(base=None):
    """Окружение для `go build`/`go test`: проверка небезопасных настроек и закрепление режима модулей."""
    env = dict(os.environ if base is None else base)
    for k in UNSAFE_ENV:
        if env.get(k):
            raise UnsafeGoEnv(f"{k}={env[k]}: сверка модулей Go с sum.golang.org выключена — сборка не запускается (T10)")
    if env.get("GOSUMDB", "").strip().lower() == "off":
        raise UnsafeGoEnv("GOSUMDB=off: сверка модулей Go выключена — сборка не запускается (T10)")
    if "-insecure" in env.get("GOFLAGS", ""):
        raise UnsafeGoEnv("GOFLAGS содержит -insecure — сборка не запускается (T10)")
    env["GOFLAGS"] = "-mod=readonly"
    # Версию toolchain задаёт go.mod; local запретил бы её скачать, а чужая версия — не та сборка.
    env["GOTOOLCHAIN"] = "auto"
    return env
