package ui

import structs.ConnectionData
import structs.MapData
import structs.PinData
import structs.Territory
import enums.TerritoryType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract for [HoverBorderLineRenderer].
 *
 * The renderer is a pure-logic helper that turns a [MapData] into a
 * per-hover lookup of border-line geometry. The two design
 * constraints the user is depending on (and the plan asserts
 * explicitly):
 *
 *  1. The neighbor index is **bidirectional** — a `ConnectionData`
 *     `A -> B` registers B as a neighbor of A *and* A as a neighbor
 *     of B. Hover dispatch only knows the hovered territory's name
 *     (it does not know which end of the connection the cursor is
 *     closer to), so the lookup must work regardless of which pin
 *     was stored as `fromPinId` vs `toPinId`.
 *  2. Coordinates in the emitted [HoverBorderLineRenderer.LineSpec]
 *     are stored as **percent** values (0..100) so the SVG layer
 *     can write them straight into the `x1`/`y1`/`x2`/`y2`
 *     attributes with a `%` suffix and the lines track the
 *     background image as the container resizes.
 *
 * These tests pin the contract. They run as Kotlin/JS unit tests
 * (no JSDOM, no KVision, no DOM) so the helper stays pure and the
 * tests stay fast.
 */
class HoverBorderLineRendererTest
{
    /**
     * Builds a single pin with the given territory.
     *
     * @param pinId Stable identifier for the pin (used by [ConnectionData]).
     * @param name Human-readable territory name (used as the hover key).
     * @param x Horizontal percent position on the map.
     * @param y Vertical percent position on the map.
     * @return A fully-formed [PinData] entry.
     */
    private fun pin(pinId: String, name: String, x: Double = 0.0, y: Double = 0.0): PinData
    {
        return PinData(
            pinId = pinId,
            territory = Territory(
                name = name,
                type = TerritoryType.Land,
                xPos = x,
                yPos = y
            )
        )
    }

    /**
     * Builds a [MapData] from the supplied pins and connections.
     *
     * @param pins List of pins to populate the map with.
     * @param connections List of pin-to-pin connections.
     * @return A [MapData] ready to be passed to [HoverBorderLineRenderer].
     */
    private fun mapData(pins: List<PinData>, connections: List<ConnectionData>): MapData
    {
        return MapData(pins = pins, connections = connections)
    }

    // ─── 1. Bidirectional neighbor index ────────────────────────────────

    @Test
    fun buildsBidirectionalNeighborIndex_fromUnidirectionalConnections()
    {
        // A single connection A -> B must register BOTH directions
        // so hover-from-A returns a line to B AND hover-from-B
        // returns a line to A. The `fromPinId`/`toPinId` orientation
        // is not a hover hint — it is just a serialization convenience.
        val data = mapData(
            pins = listOf(
                pin(pinId = "p-A", name = "A", x = 10.0, y = 20.0),
                pin(pinId = "p-B", name = "B", x = 50.0, y = 60.0)
            ),
            connections = listOf(
                ConnectionData(fromPinId = "p-A", toPinId = "p-B")
            )
        )

        val renderer = HoverBorderLineRenderer(data)

        val fromA = renderer.linesForHoveredTerritory("A")
        assertEquals(1, fromA.size, "hovering A must yield exactly one line (to B)")
        assertEquals("B", fromA[0].otherTerritoryName, "the line from A must terminate at B")

        val fromB = renderer.linesForHoveredTerritory("B")
        assertEquals(1, fromB.size, "hovering B must yield exactly one line (back to A)")
        assertEquals("A", fromB[0].otherTerritoryName, "the line from B must terminate at A")
    }

    // ─── 2. Unknown territory is a no-op, not a throw ───────────────────

    @Test
    fun linesForHoveredTerritory_returnsEmpty_forUnknownTerritory()
    {
        // Defensive: a stale hover key (e.g. a territory that was
        // removed by a re-load) must not throw — it must return an
        // empty list so the MapViewer's `removeAll()` is a safe no-op.
        val data = mapData(
            pins = listOf(pin(pinId = "p-A", name = "A")),
            connections = emptyList()
        )

        val renderer = HoverBorderLineRenderer(data)
        val result = renderer.linesForHoveredTerritory("Z")

        assertTrue(result.isEmpty(), "unknown territory must return an empty list, not throw")
    }

    // ─── 3. Island pin (no connections) returns empty list ──────────────

    @Test
    fun linesForHoveredTerritory_returnsEmpty_forTerritoryWithNoConnections()
    {
        val data = mapData(
            pins = listOf(
                pin(pinId = "p-A", name = "A"),
                pin(pinId = "p-B", name = "B")
            ),
            connections = emptyList()
        )

        val renderer = HoverBorderLineRenderer(data)
        val result = renderer.linesForHoveredTerritory("A")

        assertTrue(result.isEmpty(), "a pin with no connections must yield no lines")
    }

    // ─── 4. Star topology: center has N neighbors, each leaf has 1 ─────

    @Test
    fun linesForHoveredTerritory_returnsAllNeighbors()
    {
        // Center node "A" connects to three leaves (B, C, D). The
        // three lines must all be present; the leaves must each
        // report one line back to the center.
        val data = mapData(
            pins = listOf(
                pin(pinId = "p-A", name = "A", x = 50.0, y = 50.0),
                pin(pinId = "p-B", name = "B", x = 10.0, y = 20.0),
                pin(pinId = "p-C", name = "C", x = 80.0, y = 30.0),
                pin(pinId = "p-D", name = "D", x = 40.0, y = 90.0)
            ),
            connections = listOf(
                ConnectionData(fromPinId = "p-A", toPinId = "p-B"),
                ConnectionData(fromPinId = "p-A", toPinId = "p-C"),
                ConnectionData(fromPinId = "p-A", toPinId = "p-D")
            )
        )

        val renderer = HoverBorderLineRenderer(data)

        val fromA = renderer.linesForHoveredTerritory("A")
        assertEquals(3, fromA.size, "A is the center of a 3-leaf star, must yield 3 lines")
        val aNeighbors = fromA.map { it.otherTerritoryName }.toSet()
        assertEquals(setOf("B", "C", "D"), aNeighbors, "all three leaves must appear in A's line set")

        // Each leaf has exactly one neighbor (back to A).
        for(leaf in listOf("B", "C", "D"))
        {
            val lines = renderer.linesForHoveredTerritory(leaf)
            assertEquals(1, lines.size, "leaf '$leaf' must report exactly one line back to the center")
            assertEquals("A", lines[0].otherTerritoryName, "leaf '$leaf' line must terminate at A")
        }
    }

    // ─── 5. Coordinates are emitted as raw percent values (no scaling) ──

    @Test
    fun linesForHoveredTerritory_emitsPercentCoordinates()
    {
        // The SVG layer writes `$x%`/`$y%` to the line element
        // verbatim, so the renderer must NOT pre-convert to pixels
        // or rounded integers. The contract is: what comes out equals
        // what the Territory stored.
        val data = mapData(
            pins = listOf(
                pin(pinId = "p-A", name = "A", x = 12.5, y = 25.0),
                pin(pinId = "p-B", name = "B", x = 75.5, y = 80.25)
            ),
            connections = listOf(
                ConnectionData(fromPinId = "p-A", toPinId = "p-B")
            )
        )

        val renderer = HoverBorderLineRenderer(data)
        val lines = renderer.linesForHoveredTerritory("A")
        assertEquals(1, lines.size)

        val line = lines[0]
        assertEquals(12.5, line.x1, 0.0001, "x1 must equal A's xPos verbatim")
        assertEquals(25.0, line.y1, 0.0001, "y1 must equal A's yPos verbatim")
        assertEquals(75.5, line.x2, 0.0001, "x2 must equal B's xPos verbatim")
        assertEquals(80.25, line.y2, 0.0001, "y2 must equal B's yPos verbatim")
    }

    // ─── 6. Duplicate connection entries are deduped ─────────────────────

    @Test
    fun linesForHoveredTerritory_dedupesSelfLoopsAndDuplicates()
    {
        // A duplicate `A -> B` connection must collapse to a single
        // line in the rendered output. The editor's
        // `connectionsList.distinct()` already enforces this on the
        // authoring side; the renderer must be defensive against
        // duplicates slipping in (e.g. from a merge of two map packs).
        val data = mapData(
            pins = listOf(
                pin(pinId = "p-A", name = "A"),
                pin(pinId = "p-B", name = "B")
            ),
            connections = listOf(
                ConnectionData(fromPinId = "p-A", toPinId = "p-B"),
                ConnectionData(fromPinId = "p-A", toPinId = "p-B"),
                ConnectionData(fromPinId = "p-B", toPinId = "p-A")
            )
        )

        val renderer = HoverBorderLineRenderer(data)
        val fromA = renderer.linesForHoveredTerritory("A")
        assertEquals(1, fromA.size, "duplicate A->B and its reverse B->A must collapse to one line")

        val fromB = renderer.linesForHoveredTerritory("B")
        assertEquals(1, fromB.size, "duplicate A->B and its reverse B->A must collapse to one line from B's side too")
    }

    // ─── 7. Lookup is by territory NAME, not by pinId ──────────────────

    @Test
    fun build_usesTerritoryNameLookupNotPinId()
    {
        // The MapViewer only has the territory name from the icon
        // (TerritoryIcon.territory.name), so the renderer must
        // resolve the hover target by name. Pins can be re-keyed at
        // load time (pinIds are not stable across save/load cycles)
        // without breaking the hover feature, but a rename of the
        // territory itself would — and that is the user's problem
        // to think about, not ours to silently absorb.
        val data = mapData(
            pins = listOf(
                pin(pinId = "uuid-1", name = "Alpha", x = 10.0, y = 20.0),
                pin(pinId = "uuid-2", name = "Beta", x = 30.0, y = 40.0)
            ),
            connections = listOf(
                ConnectionData(fromPinId = "uuid-1", toPinId = "uuid-2")
            )
        )

        val renderer = HoverBorderLineRenderer(data)

        // Using the pinId MUST NOT find a line — the hover key is
        // the territory name, not the pinId.
        val byPinId = renderer.linesForHoveredTerritory("uuid-1")
        assertTrue(byPinId.isEmpty(), "looking up by pinId must return empty (the hover key is the territory name)")

        // Using the territory name MUST find the line.
        val byName = renderer.linesForHoveredTerritory("Alpha")
        assertEquals(1, byName.size, "looking up by territory name must return the line")
        assertEquals("Beta", byName[0].otherTerritoryName)
    }
}
