package com.dpsmpx.dpmeta.git

import com.dpsmpx.dpai.core.git.GitNames
import org.eclipse.jgit.lib.Repository
import kotlin.test.Test
import kotlin.test.assertTrue

class GitNamesCompatibilityTest {
    @Test fun commonCliBranchesRemainValidForTheExistingJgitTransport() {
        val parts = listOf("main", "тест", "HEAD", "@", "x.", "a.lock", ".hidden", "", "a..b", "a@{b", "a b", "a?b", "a\\b", "a\u007fb")
        for (a in parts) for (b in parts) for (name in listOf(a, "$a/$b")) {
            if (GitNames.cliBranch(name)) assertTrue(Repository.isValidRefName("refs/remotes/origin/$name"), name)
            // JGit читает и исторические refs, которые нативный Git не создаст.
            if (name == "a.lock/main") assertTrue(Repository.isValidRefName("refs/remotes/origin/$name"))
        }
    }
}
