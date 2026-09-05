package com.danemadsen.atlas.ui.savedlocations

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for the pure ops of [SavedLocationStore]. The org.json codec
 * is stubbed on the JVM (android.jar stubs throw), so encode/decode
 * round-trips are device/androidTest territory; here we pin the list
 * semantics the ViewModel relies on.
 */
class SavedLocationStoreTest {

    private fun loc(id: String, name: String = id, slot: SavedSlot? = null) =
        SavedLocation(id = id, name = name, lon = 153.0, lat = -27.5, slot = slot)

    @Test
    fun `upsert appends non-slot locations`() {
        val result = SavedLocationStore.upsert(
            listOf(loc("a"), loc("b")),
            loc("c", name = "Cafe"),
        )
        assertEquals(3, result.size)
        assertEquals("Cafe", result.last().name)
    }

    @Test
    fun `upsert replaces the same-slot occupant`() {
        val result = SavedLocationStore.upsert(
            listOf(loc("old", name = "Old", slot = SavedSlot.HOME)),
            loc("new", name = "New", slot = SavedSlot.HOME),
        )
        assertEquals(1, result.size)
        assertEquals("New", result.single().name)
        assertEquals(SavedSlot.HOME, result.single().slot)
    }

    @Test
    fun `upsert keeps other slots intact`() {
        val result = SavedLocationStore.upsert(
            listOf(
                loc("home", slot = SavedSlot.HOME),
                loc("work", slot = SavedSlot.WORK),
                loc("gym"),
            ),
            loc("home2", name = "New Home", slot = SavedSlot.HOME),
        )
        assertEquals(3, result.size)
        assertEquals("New Home", result.first { it.slot == SavedSlot.HOME }.name)
        assertEquals("work", result.first { it.slot == SavedSlot.WORK }.id)
        assertEquals("gym", result.first { it.slot == null }.id)
    }

    @Test
    fun `rename delete and clearSlot target the right id`() {
        val base = listOf(loc("a"), loc("b", slot = SavedSlot.WORK))
        val renamed = SavedLocationStore.rename(base, "a", "New")
        assertEquals("New", renamed.first { it.id == "a" }.name)
        assertEquals("b", renamed.first { it.id == "b" }.name)
        val deleted = SavedLocationStore.delete(base, "a")
        assertEquals(listOf("b"), deleted.map { it.id })
        val cleared = SavedLocationStore.clearSlot(base, "b")
        assertEquals(SavedSlot.WORK, base.first { it.id == "b" }.slot)
        assertEquals(null, cleared.first { it.id == "b" }.slot)
    }
}