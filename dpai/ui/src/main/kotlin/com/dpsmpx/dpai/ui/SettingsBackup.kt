package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonFields
import com.dpsmpx.dpai.core.serialization.JsonNull
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString

/** Настройки приложения, которые копируются (D-51): targets, проект, ключи хостов. */
data class SettingsSnapshot(val targets: List<TargetEntry>, val project: ProjectEntry?, val knownHosts: String)

/**
 * Копия настроек приложения `dpai.app-settings` v1 (D-51): файл вне
 * приложения переживает его удаление и переустановку с другой подписью.
 * Секретов нет (D-25): закрытый ключ SSH — в Keystore и в копию не
 * попадает; после восстановления на новом ключе приложения его открытую
 * строку надо снова добавить в `authorized_keys` targets.
 */
object SettingsBackup {
    const val FORMAT = "dpai.app-settings"
    const val VERSION = 1

    fun write(s: SettingsSnapshot, createdAtMillis: Long, appVersion: String?): String =
        CanonicalJson.write(
            JsonObject(
                linkedMapOf(
                    *FormatHeader.write(FORMAT, VERSION),
                    "createdAt" to JsonNumber.of(createdAtMillis),
                    "appVersion" to (appVersion?.let(::JsonString) ?: JsonNull),
                    "targets" to TargetBook.toJson(s.targets),
                    "project" to (s.project?.let(ProjectBook::toJson) ?: JsonNull),
                    "knownHosts" to JsonString(s.knownHosts),
                ),
            ),
        ) + "\n"

    fun read(text: String): Outcome<SettingsSnapshot> {
        val value = when (val p = JsonParser().parse(text.trim())) {
            is Outcome.Failure -> return p
            is Outcome.Success -> p.value
        }
        val d = DiagnosticCollector()
        val f = JsonFields.root(value, d) ?: return Outcome.Failure(d.all)
        FormatHeader.read(f, FORMAT, VERSION..VERSION, d) ?: return Outcome.Failure(d.all)
        f.long("createdAt")
        f.raw("appVersion", required = false)
        val targets = f.raw("targets")
        val project = f.raw("project", required = false)
        val knownHosts = f.string("knownHosts")
        f.finish()
        if (d.hasErrors || targets == null || knownHosts == null) return Outcome.Failure(d.all)
        val t = when (val r = TargetBook.fromJson(targets)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        val p = if (project == null || project == JsonNull) {
            null
        } else {
            when (val r = ProjectBook.fromJson(project)) {
                is Outcome.Failure -> return r
                is Outcome.Success -> r.value
            }
        }
        if (p != null && t.none { it.id == p.target }) {
            return Outcome.failure(DiagCode.VALUE_INVALID, "в копии проект на target, которого в ней нет", "project")
        }
        return Outcome.success(SettingsSnapshot(t, p, knownHosts))
    }

    /** Отпечаток содержимого — без времени создания: копия раз в час пишется, только если настройки изменились. */
    fun digest(s: SettingsSnapshot): String = Hashing.sha256(write(s, 0, null).toByteArray()).toString()
}
