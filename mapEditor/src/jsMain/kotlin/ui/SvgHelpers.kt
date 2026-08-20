package ui

import io.kvision.html.CustomTag
import io.kvision.html.customTag
import io.kvision.panel.SimplePanel

/**
 * Creates an SVG container element within a SimplePanel.
 *
 * @param block DSL block for configuring the SVG element.
 * @return The created CustomTag representing the SVG element.
 */
fun SimplePanel.svg(block: CustomTag.() -> Unit = {}): CustomTag
{
    return customTag("svg") {
        setAttribute("width", "100%")
        setAttribute("height", "100%")
        setAttribute("style", "position: absolute; top: 0; left: 0; pointer-events: none;")
        block()
    }
}

/**
 * Creates an SVG defs element for defining reusable SVG components.
 *
 * @param block DSL block for configuring the defs element.
 */
fun CustomTag.svgDefs(block: CustomTag.() -> Unit = {})
{
    customTag("defs") {
        block()
    }
}

/**
 * Creates an SVG arrow marker for use with lines.
 *
 * @param id The marker identifier for referencing.
 * @param color The arrow color in hex format.
 */
fun CustomTag.arrowMarker(id: String, color: String = "#00ff00")
{
    customTag("marker") {
        setAttribute("id", id)
        setAttribute("markerWidth", "10")
        setAttribute("markerHeight", "10")
        setAttribute("refX", "9")
        setAttribute("refY", "3")
        setAttribute("orient", "auto")
        
        customTag("polygon") {
            setAttribute("points", "0 0, 10 3, 0 6")
            setAttribute("fill", color)
        }
    }
}

/**
 * Creates an SVG line element with optional arrow marker.
 *
 * @param x1 Starting X coordinate as percentage.
 * @param y1 Starting Y coordinate as percentage.
 * @param x2 Ending X coordinate as percentage.
 * @param y2 Ending Y coordinate as percentage.
 * @param stroke Line color in hex format.
 * @param strokeWidth Line width as string.
 * @param markerEnd Optional marker ID for arrow end.
 * @param dataConnection Optional data attribute for connection tracking.
 */
fun CustomTag.svgLine(
    x1: Double, 
    y1: Double, 
    x2: Double, 
    y2: Double, 
    stroke: String = "#00ff00",
    strokeWidth: String = "2",
    markerEnd: String? = null,
    dataConnection: String? = null
)
{
    customTag("line") {
        setAttribute("x1", "$x1%")
        setAttribute("y1", "$y1%")
        setAttribute("x2", "$x2%")
        setAttribute("y2", "$y2%")
        setAttribute("stroke", stroke)
        setAttribute("stroke-width", strokeWidth)
        markerEnd?.let { setAttribute("marker-end", "url(#$it)") }
        dataConnection?.let { setAttribute("data-connection", it) }
    }
}