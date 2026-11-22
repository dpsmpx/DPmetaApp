package com.dpsmpx.dpai.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TermuxSetupTest {
    private val key = "ecdsa-sha2-nistp256 AAAAE2VjZHNh dpai-android"
    private val agentHash = "a".repeat(64)

    @Test fun localhostOnlyKeyLoginAndCurrentAgentFromAppDownloads() {
        assertEquals("127.0.0.1", TermuxSetup.HOST)
        assertEquals("8023", TermuxSetup.PORT)
        assertTrue(TermuxSetup.AGENT.startsWith("/data/data/com.termux/files/usr/"))
        assertTrue(TermuxSetup.CLAUDE_DIR.startsWith("/data/data/com.termux/files/home/"))
        val cmd = assertNotNull(TermuxSetup.pairing(key, agentHash))
        assertFalse('\n' in cmd, "команда должна вставляться одной строкой")
        assertTrue(cmd.contains("from=\\\"127.0.0.1,::1\\\" $key") || cmd.contains("from=\"127.0.0.1,::1\" $key"))
        assertTrue(cmd.contains("PasswordAuthentication=no"))
        assertTrue(cmd.contains("KbdInteractiveAuthentication=no"))
        assertTrue(cmd.contains("ListenAddress=127.0.0.1"))
        assertTrue(cmd.contains("dpai-agent.zip"))
        assertTrue(cmd.contains("sha256sum -c -"))
        assertTrue(cmd.contains(agentHash))
        assertTrue(cmd.contains("ssh_host_*_key.pub"))
        assertTrue(cmd.contains("mktemp"))
    }

    @Test fun onlyStrictlyValidatedPublicKeyCanEnterShellScript() {
        assertNull(TermuxSetup.pairing(null, agentHash))
        assertNull(TermuxSetup.pairing(key, null))
        assertNull(TermuxSetup.pairing(key, "wrong"))
        assertNull(TermuxSetup.pairing("ecdsa-sha2-nistp256 AAAA'; rm -rf . # dpai-android", agentHash))
    }

    @Test fun nativeAapt2AndSftpAreIncludedAndSdkCheckIsExplicit() {
        assertTrue(TermuxSetup.packages.contains("openssh-sftp-server"))
        assertTrue(TermuxSetup.packages.contains("aapt2"))
        assertTrue(TermuxSetup.packages.contains("java -version"))
        assertTrue(TermuxSetup.androidBuild.contains("android.aapt2FromMavenOverride"))
        assertTrue(TermuxSetup.androidBuild.contains("ANDROID_HOME"))
        assertFalse('\n' in TermuxSetup.androidBuild)
    }
}
