package com.dpsmpx.dpai.execution.tools

import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.execution.process.ProcessSpec

/** Транспорт подключённого аккаунта: только stdin контроллера, без URL/argv/файла (D-125). */
object SharedToolsSync {
    /** SSH → агент → контроллер: учётные данные остаются только в stdin. */
    fun agentRequest(command: List<String>, home: String?, first: Boolean, repository: String, python: String, token: Secret): ProcessSpec = ProcessSpec(
        command + listOf("tools", if (first) "init" else "sync", "--repo", repository, "--python", python, "--github-stdin") +
            (home?.let { listOf("--home", it) } ?: emptyList()),
        stdin = CanonicalJson.write(JsonObject(mapOf("token" to JsonString(token.reveal())))),
    )

    fun request(tools: SharedTools, state: String, first: Boolean, token: Secret): ProcessSpec = ProcessSpec(
        listOf(tools.python, tools.controller, "--repo", tools.repository, if (first) "init" else "sync", "--state", state, "--github-stdin"),
        stdin = CanonicalJson.write(JsonObject(mapOf("token" to JsonString(token.reveal())))),
    )
}
