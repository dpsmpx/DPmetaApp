package com.dpsmpx.dpai.orchestrator.agent

/** Имена Windows регистронезависимы: API-ключ и управляющая переменная снимаются во всех написаниях (D-29, Fable:O-13). */
internal object ProcessEnvironment {
    fun build(base: Map<String, String>, unset: Collection<String>, set: Map<String, String>, windows: Boolean): Map<String, String> {
        val result: MutableMap<String, String> = if (windows) java.util.TreeMap(String.CASE_INSENSITIVE_ORDER) else linkedMapOf()
        result.putAll(base)
        unset.forEach(result::remove)
        result.putAll(set)
        return result
    }
}
