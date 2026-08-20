package ui

import structs.MapData

/**
 * Pure-logic helper that turns a [MapData] into the per-hover line
 * geometry used by [MapViewer] to draw hover-state border lines.
 *
 * The renderer is intentionally pure (no DOM, no KVision, no widget
 * imports). That keeps it trivially unit-testable from the
 * Kotlin/JS jsTest source set — no JSDOM, no karma Chrome
 * dependency — and lets the e2e harness verify the math without
 * standing up a full KVision tree. The MapViewer takes care of all
 * DOM work; this class only answers "given a hovered territory name,
 * what lines should I draw?".
 *
 * Two design decisions are encoded here:
 *
 *  1. The neighbor index is **bidirectional**. A `ConnectionData`
 *     from `A` to `B` registers B as a neighbor of A *and* A as a
 *     neighbor of B. The hover dispatch in [MapViewer] only knows
 *     the territory's name (it does not know which end of the
 *     connection the cursor is closer to), so the lookup must work
 *     regardless of which pin was stored as `fromPinId` vs
 *     `toPinId`. Duplicates are collapsed.
 *  2. The emitted [LineSpec] coordinates are stored as raw
 *     **percent** values (0..100) so the SVG layer in
 *     [MapViewer] can write them straight into the
 *     `x1`/`y1`/`x2`/`y2` attributes with a `%` suffix and the
 *     lines track the background image as the container resizes.
 *     No scaling, no rounding — what the [structs.Territory] stored
 *     is what the line carries.
 *
 * This class has no `WidgetInterface`, no live DOM, and no
 * drag/draw hooks. That is deliberate: the map editor's
 * `connectedPins` list owns the *authoring* side of the
 * connection graph and must stay snappy under pointer events,
 * while this class owns the *display* side and only answers
 * read-only queries.
 *
 * @param mapData The map data to index. Captured by reference; the
 *   renderer does not mutate it. If the map is reloaded, the
 *   caller is expected to build a new renderer instance.
 */
class HoverBorderLineRenderer(mapData: MapData)
{
    /**
     * One SVG line segment to draw between the hovered territory
     * and one of its direct neighbors.
     *
     * The [x1]/[y1] pair is the hovered territory's position
     * (the "from" end). The [x2]/[y2] pair is the neighbor's
     * position (the "to" end). The orientation is consistent so
     * the MapViewer can later add directional markers (e.g. arrows
     * for one-way connections) without re-running the lookup.
     *
     * @property otherTerritoryName The neighbor's territory name
     *   (the "to" end of the line). Stored explicitly so the
     *   MapViewer can attach a `data-connection` attribute for
     *   e2e selection without re-resolving the pinId.
     * @property connectionKey A stable string that uniquely
     *   identifies the underlying [structs.ConnectionData] across
     *   the two pinIds. Format: `"<fromPinId>::<toPinId>"` with
     *   the pinIds sorted alphabetically so the same unordered
     *   connection always yields the same key regardless of which
     *   end the map author typed first.
     * @property x1 Hovered-territory X position in percent (0..100).
     * @property y1 Hovered-territory Y position in percent (0..100).
     * @property x2 Neighbor X position in percent (0..100).
     * @property y2 Neighbor Y position in percent (0..100).
     */
    data class LineSpec(
        val otherTerritoryName: String,
        val connectionKey: String,
        val x1: Double,
        val y1: Double,
        val x2: Double,
        val y2: Double
    )

    /**
     * Territory name -> X/Y percent. Built once at construction.
     */
    private val territoryNameToPosition: Map<String, Pair<Double, Double>> =
        mapData.pins.associate { pin ->
            pin.territory.name to (pin.territory.xPos to pin.territory.yPos)
        }

    /**
     * Territory name -> ordered list of neighbor territory names.
     *
     * Built bidirectionally: for every `A -> B` connection the
     * renderer appends B to A's neighbor list AND A to B's
     * neighbor list. A `LinkedHashSet` is used for the dedupe pass
     * so a connection that appears twice (or its reverse) only
     * registers once per end.
     */
    private val territoryNameToNeighborNames: Map<String, List<String>> = run {
        val pinIdToTerritoryName: Map<String, String> =
            mapData.pins.associate { it.pinId to it.territory.name }

        val rawAccumulator: MutableMap<String, MutableSet<String>> = mutableMapOf()
        for(conn in mapData.connections)
        {
            val fromName = pinIdToTerritoryName[conn.fromPinId] ?: continue
            val toName = pinIdToTerritoryName[conn.toPinId] ?: continue
            if(fromName == toName) continue
            rawAccumulator.getOrPut(fromName) { linkedSetOf() }.add(toName)
            rawAccumulator.getOrPut(toName) { linkedSetOf() }.add(fromName)
        }
        rawAccumulator.mapValues { (_, neighbors) -> neighbors.toList() }
    }

    /**
     * Stable, order-independent key for a connection. The two
     * pinIds are sorted and joined with `"::"` so the same
     * unordered connection always hashes the same way.
     */
    private fun connectionKey(fromPinId: String, toPinId: String): String
    {
        val sorted = if(fromPinId <= toPinId) listOf(fromPinId, toPinId) else listOf(toPinId, fromPinId)
        return "${sorted[0]}::${sorted[1]}"
    }

    /**
     * Returns the list of border lines to draw for a hovered
     * territory.
     *
     * The result is empty (never null) when:
     *  - the territory name is unknown to this map (defensive
     *    against stale hover keys after a map reload);
     *  - the territory has no connections (an "island" pin);
     *  - the territory's neighbor positions are missing (a
     *    data-integrity issue — the hover layer should never see
     *    this, but the helper is defensive).
     *
     * The list is stable: two calls with the same input return
     * equal lists in the same order. Order is determined by the
     * insertion order of the connection's first appearance in
     * [MapData.connections], which is deterministic and matches
     * the editor's authored order.
     *
     * @param territoryName The territory name under the cursor.
     * @return 0..N [LineSpec] entries, one per direct neighbor.
     */
    fun linesForHoveredTerritory(territoryName: String): List<LineSpec>
    {
        val neighborNames = territoryNameToNeighborNames[territoryName] ?: return emptyList()
        val hoveredPos = territoryNameToPosition[territoryName] ?: return emptyList()

        val out = mutableListOf<LineSpec>()
        for(neighborName in neighborNames)
        {
            val neighborPos = territoryNameToPosition[neighborName] ?: continue
            val key = stableKey(territoryName, neighborName)
            out.add(
                LineSpec(
                    otherTerritoryName = neighborName,
                    connectionKey = key,
                    x1 = hoveredPos.first,
                    y1 = hoveredPos.second,
                    x2 = neighborPos.first,
                    y2 = neighborPos.second
                )
            )
        }
        return out
    }

    /**
     * Builds a stable connection key from two territory names.
     *
     * Sorting alphabetically means the same unordered pair
     * always produces the same key — independent of the
     * `fromPinId`/`toPinId` orientation the map author chose.
     * The e2e test asserts on this key for selectability; the
     * visual layer does not need it.
     */
    private fun stableKey(a: String, b: String): String
    {
        return if(a <= b) "$a::$b" else "$b::$a"
    }
}