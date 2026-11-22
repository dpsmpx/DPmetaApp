package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.files.FileNames
import com.dpsmpx.dpai.files.FileTree
import com.dpsmpx.dpai.core.git.GitNames
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.diagnostics.DiagCode

/** Обнаружение уже существующих клонов без Linux-процесса, target и сети (D-214). */
object ApiQuickStart {
    data class Clone(val relative: String, val branch: String) {
        val root: String get() = "/root/projects/$relative"
        val worktrees: String get() = root.substringBeforeLast('/') + "/.trees/" + root.substringAfterLast('/')
    }

    /** Место выбирается/создаётся приложением; прежние id, проверки и настройки не заменяются. */
    fun adopt(ids: IdGenerator, clone: Clone, targets: List<TargetEntry>, projects: List<ProjectEntry>, createTarget: () -> Outcome<TargetEntry>): Outcome<ProjectEntry> {
        if (!FileNames.pathValid(clone.relative) || clone.relative.split('/').size !in 1..2 || clone.relative.split('/').any { it.startsWith('.') } || !GitNames.cliBranch(clone.branch)) {
            return Outcome.failure(DiagCode.VALUE_INVALID, "недопустимый клон для быстрого старта")
        }
        val local = targets.filter { it.inApp }.map { it.id }.toSet()
        projects.firstOrNull { it.target in local && it.root.trimEnd('/') == clone.root }?.let { return Outcome.success(it) }
        val target = targets.firstOrNull { it.inApp && it.configDir != null } ?: when (val r = createTarget()) { is Outcome.Failure -> return r; is Outcome.Success -> r.value }
        return ProjectForm.validate(ids, target, clone.root, clone.worktrees, clone.branch, "", claudeOn = target)
    }

    fun clones(projects: FileTree): Outcome<List<Clone>> {
        val first = when (val r = projects.list("")) { is Outcome.Failure -> return r; is Outcome.Success -> r.value }
        val found = arrayListOf<Clone>()
        fun probe(relative: String): Boolean {
            val bytes = projects.read("$relative/.git/HEAD", 4096).valueOrNull() ?: return false
            val head = bytes.toString(Charsets.UTF_8).trim()
            val branch = head.removePrefix("ref: refs/heads/")
            if (!head.startsWith("ref: refs/heads/") || !GitNames.cliBranch(branch)) return false
            found += Clone(relative, branch)
            return true
        }
        for (owner in first.filter { it.dir && FileNames.segmentValid(it.name) && !it.name.startsWith('.') }.sortedBy { it.name }) {
            if (probe(owner.name)) continue
            val children = when (val r = projects.list(owner.name)) { is Outcome.Failure -> return r; is Outcome.Success -> r.value }
            for (repo in children.filter { it.dir && FileNames.segmentValid(it.name) && !it.name.startsWith('.') }.sortedBy { it.name }) probe("${owner.name}/${repo.name}")
        }
        return Outcome.success(found)
    }
}
