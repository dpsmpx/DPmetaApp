package com.dpsmpx.dpmeta.app

import com.dpsmpx.dpmeta.app.screens.ProjectMap
import com.dpsmpx.dpmeta.app.screens.Screens
import com.dpsmpx.dpmeta.model.Place
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectMapTest {
    @Test
    fun mapUsesOnlyRegistryGroupsAndKeepsHiddenPeripheryAvailable() {
        fun group(place: Place, name: String) = Screens.Group(place.title, listOf(Screens.ProjectRow(name, "неизвестно", "неизвестно", "неизвестно", "неизвестно", "неизвестно", 0)), place)
        val core = group(Place.CORE, "DP-Verify")
        val peripheral = group(Place.PERIPHERY, "TrueJointTileMaker")
        val archive = group(Place.ARCHIVE, "old")
        val source = listOf(core, peripheral, archive)
        assertEquals(listOf(core), ProjectMap.groups(source, false))
        assertEquals(listOf(core, peripheral), ProjectMap.groups(source, true))
        assertEquals("неизвестно", ProjectMap.groups(source, false).single().rows.single().working)
    }
}
