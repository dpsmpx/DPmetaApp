# Нативный код приложения (D-75, шаг 3; D-17 — только в модуле android):
# процессы и псевдотерминал Linux-среды. Предупреждения — ошибки, как у Kotlin.
cmake_minimum_required(VERSION 3.22.1)
project(dpai_native C)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    add_library(dpai-jvm-compat SHARED dpai_jvm_compat.c)
    target_compile_options(dpai-jvm-compat PRIVATE -std=c11 -Wall -Wextra -Werror)
    # Без crtbegin_so и без NEEDED libc/libdl: библиотеку через LD_PRELOAD наследуют и программы musl.
    target_link_options(dpai-jvm-compat PRIVATE -nostdlib -Wl,-z,max-page-size=16384)
endif()

add_library(dpai-native SHARED dpai_native.c)
target_compile_options(dpai-native PRIVATE -std=c11 -Wall -Wextra -Werror)
# Страницы 16 КБ (Android 15+): выравнивание сегментов ELF — 16 КиБ.
target_link_options(dpai-native PRIVATE -Wl,-z,max-page-size=16384)
