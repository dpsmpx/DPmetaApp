package com.dpsmpx.dpai.ui

/** apk предоставляет виртуальные имена: установленный clang22 удовлетворяет запросу clang (D-140). */
object AlpinePackageState {
    fun names(lines: Sequence<String>): Set<String> = buildSet {
        for (line in lines) when {
            line.startsWith("P:") -> add(line.removePrefix("P:").trim())
            line.startsWith("p:") -> line.removePrefix("p:").split(' ').forEach { provide ->
                val name = provide.takeWhile { it !in "=<>~" }
                if (com.dpsmpx.dpai.orchestrator.linux.LinuxEnvironment.PACKAGE.matches(name)) add(name)
            }
        }
    }
}
