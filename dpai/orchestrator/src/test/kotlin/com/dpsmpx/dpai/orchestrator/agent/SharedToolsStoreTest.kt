package com.dpsmpx.dpai.orchestrator.agent

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.execution.tools.SharedTools
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.vfs.NioVfs
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class SharedToolsStoreTest {
    @TempDir lateinit var home: Path

    @Test
    fun sharedConfigurationSurvivesRestartAndDisableDeletesPreviousVersion() {
        val store = SharedToolsStore(NioVfs(home))
        assertNull(store.read().valueOrNull())
        val config = SharedTools("/tools/DP-Verify", "/tools/runtime.py")
        assertTrue(store.write(config).isSuccess)
        assertEquals(config, SharedToolsStore(NioVfs(home)).read().valueOrNull())
        assertTrue(store.write(config.copy(python = "/usr/bin/python3")).isSuccess)
        assertTrue(store.disable().isSuccess)
        assertNull(SharedToolsStore(NioVfs(home)).read().valueOrNull())
    }

    @Test
    fun invalidConfigurationIsRefusedInsteadOfSilentlyDisablingProtection() {
        val config = SharedTools("/tools/DP-Verify", "/tools/runtime.py").toJson()
        DocumentStore(NioVfs(home), "config").write(SharedToolsStore.KEY,
            JsonObject(config.fields + ("controller" to JsonString("relative"))))
        assertIs<Outcome.Failure>(SharedToolsStore(NioVfs(home)).read())
    }
}
