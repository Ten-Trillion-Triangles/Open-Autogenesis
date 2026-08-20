package org.ttt.autogenesis.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Contract for [MusicTrackCatalog]: every category is populated, the three
 * special-track singletons are present, and [MusicTrackCatalog.default.allNames]
 * returns the union with no duplicates.
 */
class MusicTrackCatalogTest
{
    @Test
    fun initialConditions_hasExactlyOneTrack()
    {
        assertEquals(1, MusicTrackCatalog.default.initialConditions.size)
        assertEquals("Initial Conditions wet 1", MusicTrackCatalog.default.initialConditions[0].resourceName)
        assertEquals(MusicCategory.InitialConditions, MusicTrackCatalog.default.initialConditions[0].category)
    }

    @Test
    fun nemesis_hasExactlyOneTrack()
    {
        assertEquals(1, MusicTrackCatalog.default.nemesis.size)
        assertEquals("Nemesis wet 1", MusicTrackCatalog.default.nemesis[0].resourceName)
        assertEquals(MusicCategory.Nemesis, MusicTrackCatalog.default.nemesis[0].category)
    }

    @Test
    fun terminalConditions_hasExactlyOneTrack()
    {
        assertEquals(1, MusicTrackCatalog.default.terminalConditions.size)
        assertEquals("Terminal Conditions wet 1", MusicTrackCatalog.default.terminalConditions[0].resourceName)
        assertEquals(MusicCategory.TerminalConditions, MusicTrackCatalog.default.terminalConditions[0].category)
    }

    @Test
    fun drone_hasAllDTrackFiles()
    {
        val names = MusicTrackCatalog.default.drone.map { it.resourceName }.toSet()
        assertTrue("D-Track 1" in names, "drone catalog must contain D-Track 1")
        assertTrue("D-Track 2" in names, "drone catalog must contain D-Track 2")
        assertTrue("D-Track 3" in names, "drone catalog must contain D-Track 3")
        assertTrue("D-Track 4" in names, "drone catalog must contain D-Track 4")
        assertTrue("D-Track 5" in names, "drone catalog must contain D-Track 5")
        for(track in MusicTrackCatalog.default.drone)
        {
            assertEquals(MusicCategory.Drone, track.category, "drone entry ${track.resourceName} must be tagged Drone")
        }
    }

    @Test
    fun melody_hasAllMelodyTrackFiles()
    {
        val names = MusicTrackCatalog.default.melody.map { it.resourceName }.toSet()
        val expected = setOf(
            "Melody-Etnahta",
            "Melody-Mayela and Khefulah",
            "Melody-Pashta",
            "Melody-Shalshelet",
            "Melody-Siluk",
            "Melody-Tevir",
            "Melody-Tippeha",
            "Melody-Zakef"
        )
        assertEquals(expected, names, "melody catalog must contain exactly the base melody names")
        for(track in MusicTrackCatalog.default.melody)
        {
            assertEquals(MusicCategory.Melody, track.category)
        }
    }

    @Test
    fun rhythm_hasAllRTrackFiles()
    {
        val names = MusicTrackCatalog.default.rhythm.map { it.resourceName }.toSet()
        assertTrue("R-Track 1" in names, "rhythm catalog must contain R-Track 1")
        assertTrue("R-Track 2" in names, "rhythm catalog must contain R-Track 2")
        assertTrue("R-Track 3" in names, "rhythm catalog must contain R-Track 3")
        assertTrue("R-Track 4" in names, "rhythm catalog must contain R-Track 4")
        assertTrue("R-Track 5" in names, "rhythm catalog must contain R-Track 5")
        assertTrue("R-Track 6" in names, "rhythm catalog must contain R-Track 6")
        for(track in MusicTrackCatalog.default.rhythm)
        {
            assertEquals(MusicCategory.Rhythm, track.category)
        }
    }

    @Test
    fun harmony_hasAllHarmonyTrackFiles()
    {
        val names = MusicTrackCatalog.default.harmony.map { it.resourceName }.toSet()
        val expected = setOf("Harmony-1", "Harmony-2", "Harmony-3", "Harmony-E", "Harmony-F", "Harmony-R", "Harmony-Y")
        assertEquals(expected, names, "harmony catalog must contain exactly the base harmony names")
        for(track in MusicTrackCatalog.default.harmony)
        {
            assertEquals(MusicCategory.Harmony, track.category)
        }
    }

    @Test
    fun allNames_isUnionOfAllCategories()
    {
        val flat = MusicTrackCatalog.default.allNames()
        val expected = (MusicTrackCatalog.default.initialConditions +
                        MusicTrackCatalog.default.nemesis +
                        MusicTrackCatalog.default.terminalConditions +
                        MusicTrackCatalog.default.drone +
                        MusicTrackCatalog.default.melody +
                        MusicTrackCatalog.default.rhythm +
                        MusicTrackCatalog.default.harmony).map { it.resourceName }.toSet()
        assertEquals(expected, flat.toSet(), "allNames() must equal the union of all categories")
        assertEquals(flat.size, flat.toSet().size, "allNames() must contain no duplicates")
    }

    @Test
    fun specialTracks_areFindableByName()
    {
        assertNotNull(MusicTrackCatalog.default.findByName("Initial Conditions wet 1"))
        assertNotNull(MusicTrackCatalog.default.findByName("Nemesis wet 1"))
        assertNotNull(MusicTrackCatalog.default.findByName("Terminal Conditions wet 1"))
        assertEquals(
            MusicCategory.InitialConditions,
            MusicTrackCatalog.default.findByName("Initial Conditions wet 1")?.category
        )
        assertEquals(
            MusicCategory.Nemesis,
            MusicTrackCatalog.default.findByName("Nemesis wet 1")?.category
        )
    }

    @Test
    fun findByName_returnsNullForUnknown()
    {
        assertEquals(null, MusicTrackCatalog.default.findByName("not a real track"))
        assertEquals(null, MusicTrackCatalog.default.findByName(""))
    }
}