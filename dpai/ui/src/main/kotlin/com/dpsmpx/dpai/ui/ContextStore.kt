package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.contexts.ContextPacket
import com.dpsmpx.dpai.contexts.ContextProfile
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.result.map
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.json
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.vfs.NioVfs
import java.io.IOException

data class ContextSettings(val profile: ContextProfile, val revision: String?, val explicit: Boolean)

/** Приватное CAS-хранилище настроек человека. Кеш не источник разрешений и не доказательство свежести. */
class ContextStore(private val vfs: NioVfs, private val location: String) {
    private val settings = DocumentStore(vfs, "settings")
    private val cache = DocumentStore(vfs, "packets")

    fun load(): Outcome<ContextSettings> = when (val r = settings.read("profile")) {
        is Outcome.Failure -> r
        is Outcome.Success -> {
            val doc = r.value
            if (doc == null) Outcome.success(ContextSettings(ContextProfile(), null, false))
            else if (r.diagnostics.isNotEmpty() || doc.fromLastKnownGood)
                Outcome.failure(DiagCode.STORAGE_CORRUPTED, "профиль контекста повреждён; восстановите настройки явно")
            else {
                val o = doc.content
                if (o.fields.keys != setOf("format", "version", "location", "profile") || o["format"] != json("dpai.context-settings") ||
                    o["version"] != json(1) || o["location"] != json(location) || o["profile"] !is JsonObject)
                    Outcome.failure(DiagCode.STATE_CONFLICT, "профиль контекста относится к другой привязке или имеет неподдержанный формат")
                else ContextProfile.read(o["profile"] as JsonObject).map { ContextSettings(it, doc.hash.hex, true) }
            }
        }
    }

    fun save(profile: ContextProfile, expected: String?): Outcome<Unit> = locked {
        val old = when (val r = load()) { is Outcome.Failure -> return@locked r; is Outcome.Success -> r.value }
        if (old.revision != expected) return@locked Outcome.failure(DiagCode.STATE_CONFLICT, "профиль изменился; перечитайте настройки")
        if ((profile.pinned + profile.constraints + profile.roots.filter { it != "." }).any { !DevelopmentTools.safePath(it) })
            return@locked Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "секретные и служебные пути не входят в профиль")
        settings.write("profile", JsonObject.of("format" to json("dpai.context-settings"), "version" to json(1),
            "location" to json(location), "profile" to profile.json())).map { }
    }

    /** Восстановление после смены привязки/порчи — только явный сброс человеком. */
    fun reset(): Outcome<Unit> = locked { settings.delete("profile") }

    fun remember(packet: ContextPacket): Outcome<Unit> = locked {
        val key = Hashing.sha256(packet.binding.owner).hex
        val current = when (val r = cache.read(key)) { is Outcome.Failure -> null; is Outcome.Success -> r.value }
        if (current?.content == packet.json() && !current.fromLastKnownGood) return@locked Outcome.success(Unit)
        val result = cache.write(key, packet.json()).map { }
        if (result is Outcome.Failure) return@locked result
        vfs.list("packets") // Отказ list не превращается в успешную очистку.
        val keys = cache.keys().filter { it != key }
        for (old in keys.dropLast(19)) when (val r = cache.delete(old)) { is Outcome.Failure -> return@locked r; is Outcome.Success -> Unit }
        Outcome.success(Unit)
    }

    private fun <T> locked(block: () -> Outcome<T>): Outcome<T> = try {
        vfs.mkdirs("")
        val lease = vfs.tryLease("context.lock")
        if (lease == null) Outcome.failure(DiagCode.WORKTREE_OWNED, "настройки контекста заняты") else lease.use { block() }
    } catch (_: IOException) { Outcome.failure(DiagCode.STORAGE_IO_FAILED, "хранилище контекста недоступно") }
}
