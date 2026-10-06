package com.dpsmpx.dpai.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliTest {
    private val out = mutableListOf<String>()
    private val err = mutableListOf<String>()
    private val cli = Cli(out = { out += it }, err = { err += it }, version = "1.2.3")

    @Test
    fun versionPrintsVersionAndSucceeds() {
        assertEquals(Cli.OK, cli.run(listOf("version")))
        assertEquals(listOf("dpai 1.2.3"), out)
    }

    @Test
    fun helpSucceeds() {
        assertEquals(Cli.OK, cli.run(listOf("help")))
        assertTrue(out.single().contains("dpai version"))
    }

    @Test
    fun noCommandIsUsageError() {
        assertEquals(Cli.USAGE_ERROR, cli.run(emptyList()))
        assertTrue(out.isEmpty())
        assertTrue(err.isNotEmpty())
    }

    @Test
    fun unknownCommandIsUsageError() {
        assertEquals(Cli.USAGE_ERROR, cli.run(listOf("launch-everything")))
        assertTrue(err.first().contains("launch-everything"))
    }
}
