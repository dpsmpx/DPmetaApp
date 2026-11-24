/* Только для процессов Bionic JDK в Linux студии: HotSpot теряет верхний байт
 * некоторых указателей, поэтому маркировку кучи выключаем до старта JVM.
 * В сам процесс Android эту библиотеку не загружаем.
 *
 * LD_PRELOAD наследуют и программы Alpine (musl) в той же сборке: mallopt —
 * слабый символ, а библиотека собирается без crtbegin_so и без NEEDED
 * (-nostdlib): у musl нет libdl.so, а __register_atfork есть только в Bionic. */
#include <stddef.h>

extern int mallopt(int param, int value) __attribute__((weak));

#define DPAI_M_BIONIC_SET_HEAP_TAGGING_LEVEL (-204)
#define DPAI_M_HEAP_TAGGING_LEVEL_NONE 0

__attribute__((constructor)) static void dpai_jvm_heap_compat(void) {
    if (mallopt != NULL) {
        (void)mallopt(DPAI_M_BIONIC_SET_HEAP_TAGGING_LEVEL, DPAI_M_HEAP_TAGGING_LEVEL_NONE);
    }
}
