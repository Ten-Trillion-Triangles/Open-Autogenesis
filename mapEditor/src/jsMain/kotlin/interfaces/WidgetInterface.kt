package interfaces

import io.kvision.core.AlignItems
import io.kvision.core.CssSize
import io.kvision.core.JustifyItems
import io.kvision.core.UNIT
import structs.Border

enum class LayoutSlot {
    TOP,
    RIGHT,
    BOTTOM,
    LEFT,
    CENTER,
    OVERLAY, // e.g. absolute overlay on top of CENTER
    FLEXSTART,
    FLEXEND,
    START,
    END,
    STRETCH,
    SPACEBETWEEN
}

/**
 * Data class that can support any definable data for adding children to control their size and other layout settings.
 * What settings are respected, and how they behave will be fully defined by the widget inheriting the interface.
 * Some settings may not be respected at all, and some widgets may not provide any allowances for changing any of
 * these settings.
 */
data class ChildOptions(
    // --- Identity / interaction ---
    var id: String? = null,               // forwarded to widget.id
    var label: String? = null,      // your own key for lookup / signals
    var tags: Set<String> = emptySet(),   // roles, groups, etc. ("toolbar", "pin", "propertyPanel")

    // --- Layout hints (container-level) ---
    var slot: LayoutSlot = LayoutSlot.CENTER,
    var order: Int = 0,                   // z-order or flex order within that slot

    // size hints (used when the parent is a FlexPanel / HPanel / VPanel, etc.)
    var width: CssSize? = null,
    var height: CssSize? = null,
    var minWidth: CssSize? = null,
    var minHeight: CssSize? = null,
    var maxWidth: CssSize? = null,
    var maxHeight: CssSize? = null,

    // Flex-related hints (for HPanel/VPanel/FlexPanel parents) :contentReference[oaicite:1]{index=1}
    var grow: Int? = null,
    var shrink: Int? = null,
    var justify: JustifyItems? = null,
    var alignItems: AlignItems? = null,
    
    //Absolute position controls
    var posX: CssSize? = null,
    var posY: CssSize? = null,

    //Padding hints
    var padding: CssSize? = null,
    var padLeft: CssSize? = null,
    var padRight: CssSize? = null,
    var padTop: CssSize? = null,
    var padBottom: CssSize? = null,

    // --- Basic style hooks ---
    var cssClasses: Set<String> = emptySet(),
    var tooltip: String? = null,

    // --- State ---
    var visible: Boolean = true,
    var enabled: Boolean = true,

    // --- Extensibility / escape hatches ---
    var attributes: Map<String, String> = emptyMap(), // forwarded via setAttribute() :contentReference[oaicite:2]{index=2}
    var userData: Map<String, Any?> = emptyMap()      // arbitrary metadata for your own wiring
)


/**
 * Interface that provides standard behavior and features to normalize widget interaction, and access between widgets
 * in a similar fashion to canvas based ui designers like umg or unity canvas.
 */
interface WidgetInterface
{
    /**
     * Get the defined label of the widget which allows us to distinguish widgets even if they are the same class.
     */
    fun getLabel() : String {return ""}

    /**
     * Assign a label to a widget allowing us to uniquely identify it among other widgets of it's class.
     */
    fun setLabel(label: String) {}

    /**
     * Return all children this widget has inside of it.
     */
    fun getChildren(getTopLevel: Boolean = true) : List<WidgetInterface>? {return null}

    /**
     * Interface function to add a child to a container.
     */
    fun addChild(widget: WidgetInterface, settings: ChildOptions? = null) {}

    /**
     * Get a child based on its label.
     */
    fun getChild(label: String) : WidgetInterface? {return null}

    /**
     * Get settings if the widget stores, or allows it.
     */
    fun getSettings() : ChildOptions? {return null}

    /**
     * Update any settings for the widget's layout if the widget supports or allows it.
     */
    fun updateSettings(settings: ChildOptions) {}

    /**
     * Transfer a reference of one widget to be sent to another widget. Useful for callbacks and back and forth
     * data transfers.
     */
    fun sendRef(ref: WidgetInterface) {}

    fun updateDataInternal(data: String) {}

    /**
     * Transfer border data from one widget to another. Commonly used for the pins.
     */
    fun sendBorderData(data: Border) {}

    /**
     * Destructor call to remove the widget from its parent.
     */
    fun destroyWidget() {}
}

/**
 * Update the widget's data internally. Accepts an object that can be serialized, which we will then deserialize on
 * the other end to create a standard widget to widget contract on updating data without exposing methods, or creating
 * circular dependencies.
 *
 * @param data The data to update the widget with.
 */
inline fun <reified T> WidgetInterface.updateData(data: T)
{
    val serialized = JSON.stringify(data)
    updateDataInternal(serialized)
}