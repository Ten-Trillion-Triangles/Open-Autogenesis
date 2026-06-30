package org.ttt.autogenesis.audiotrackseditor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the [Category] enum. The original four entries (DRONE,
 * MELODY, RHYTHM, HARMONY) cover the four musical layers a track can
 * belong to. The four new entries (MENU, START, NEMESIS, END) cover
 * the scenario-level tracks the music selector picks under specific
 * game conditions.
 *
 * Enum order matters: [Category.values] is iterated in declaration
 * order to build the tab strip in [Render.renderTabStrip], and the
 * scenario tabs must appear before the layer tabs to match the
 * product spec ("menu, start, nemesis, end" listed first).
 */
class CategoryTest
{
    @Test
    fun category_hasFourOriginalLayerValues()
    {
        // Backward-compat: the original four layer categories must still
        // be present and addressable by name.
        assertNotNull(Category.valueOf("DRONE"))
        assertNotNull(Category.valueOf("MELODY"))
        assertNotNull(Category.valueOf("RHYTHM"))
        assertNotNull(Category.valueOf("HARMONY"))
    }

    @Test
    fun category_hasFourNewScenarioValues()
    {
        // The new tabs requested in the spec.
        assertNotNull(Category.valueOf("MENU"))
        assertNotNull(Category.valueOf("START"))
        assertNotNull(Category.valueOf("NEMESIS"))
        assertNotNull(Category.valueOf("END"))
    }

    @Test
    fun category_valuesListIsInSpecOrder()
    {
        // The values list is iterated by the renderer to build the tab
        // strip. The spec lists the four scenario tabs first, then the
        // four layer tabs.
        val values = Category.values().toList()
        assertEquals(8, values.size, "Category must have exactly 8 values (4 scenario + 4 layer)")
        assertEquals(
            listOf(
                Category.MENU,
                Category.START,
                Category.NEMESIS,
                Category.END,
                Category.DRONE,
                Category.MELODY,
                Category.RHYTHM,
                Category.HARMONY
            ),
            values,
            "values() order must be scenario tabs first, then layer tabs"
        )
    }

    @Test
    fun allEightCategoryNamesAreDistinct()
    {
        val names = Category.values().map { it.name }.toSet()
        assertEquals(8, names.size, "category names must be unique")
        assertTrue("MENU" in names)
        assertTrue("START" in names)
        assertTrue("NEMESIS" in names)
        assertTrue("END" in names)
        assertTrue("DRONE" in names)
        assertTrue("MELODY" in names)
        assertTrue("RHYTHM" in names)
        assertTrue("HARMONY" in names)
    }
}
