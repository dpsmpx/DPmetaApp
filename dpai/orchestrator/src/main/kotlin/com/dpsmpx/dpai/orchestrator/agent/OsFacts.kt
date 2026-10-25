package com.dpsmpx.dpai.orchestrator.agent

import java.lang.management.ManagementFactory

/**
 * Память машины для `doctor` агента: всего и свободно в байтах;
 * неизвестно — `null` (D-13). Доступную память `AgentRuntime` уточняет
 * по `/proc/meminfo` сам.
 */
data class OsFacts(val totalBytes: Long?, val freeBytes: Long?) {
    companion object {
        /** Сведений нет: в приложении (ART) `java.lang.management` нет — память берётся из `/proc/meminfo`. */
        val NONE: OsFacts = OsFacts(null, null)
    }
}

/** Сведения JDK — только на JVM target (D-26): в ART `java.lang.management` нет (D-75). */
object JvmOsFacts {
    fun read(): OsFacts {
        val os = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean ?: return OsFacts.NONE
        return OsFacts(os.totalMemorySize, os.freeMemorySize)
    }
}
