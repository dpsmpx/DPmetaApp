package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.files.FileNames
import com.dpsmpx.dpai.files.FileTree
import com.dpsmpx.dpai.core.git.GitNames

/** Обнаружение уже существующих клонов без Linux-процесса, target и сети (D-214). */
object ApiQuickStart {
    data class Clone(val relative: String, val branch: String) {
        val root: String get() = "/root/projects/$relative"
        val worktrees: String get() = root.substringBeforeLast('/') + "/.trees/" + root.substringAfterLast('/')
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
