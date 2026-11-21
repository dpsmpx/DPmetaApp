package com.dpsmpx.dpmeta.git

import com.dpsmpx.dpai.core.git.GitNames
import org.eclipse.jgit.lib.Repository
import kotlin.test.Test
import kotlin.test.assertEquals

class GitNamesCompatibilityTest {
    @Test fun commonBranchRefMatchesTheExistingJgitValidation() {
        val parts = listOf("main", "тест", "HEAD", "@", "x.", "a.lock", ".hidden", "", "a..b", "a@{b", "a b", "a?b", "a\\b", "a\u007fb")
        for (a in parts) for (b in parts) for (name in listOf(a, "$a/$b")) {
            assertEquals(Repository.isValidRefName("refs/remotes/origin/$name"), GitNames.branchRef(name), name)
        }
    }
}
