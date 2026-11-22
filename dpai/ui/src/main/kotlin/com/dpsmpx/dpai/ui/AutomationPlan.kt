package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.operations.ActionLevel
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.security.Proposal

/** Одобряется точный профиль прежнего исполнителя, а не право на следующие команды (D-217). */
data class AutomationPlan(val id: String, val title: String, val level: ActionLevel, val profile: ManualProfile,
    val snapshot: Boolean = true, val sdkLicense: Boolean = false, val estimatedBytes: Long? = null,
    val scope: Scope = Scope.GUEST, val effect: Effect? = null) {
    enum class Scope { GUEST, PROJECT }
    enum class Effect { DEPENDENCY_REPORT, GRADLE_PIN, PYTHON_PIN, CONAN_PIN, PYTHON_RESTORE }

    fun proposal(project: ProjectEntry, contextRevision: String): Proposal = Proposal(id, project.project.value,
        listOf(project.root, project.target.value, contextRevision, CanonicalJson.write(profile.toJson()),
            snapshot.toString(), sdkLicense.toString(), scope.name, effect?.name.orEmpty()))
}
