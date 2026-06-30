package globals

import interfaces.WidgetInterface
import io.kvision.core.Widget
import io.kvision.panel.Root

/**
 * Global object that houses the root panel that drives this ui application.
 */
object KEnv
{
    var mainRoot: Root? = null
    private val attachedWidgets = mutableListOf<WidgetInterface>()

    fun setRoot(root: Root)
    {
        this.mainRoot = root
    }

    fun setBackgroundImage(path: String) {
        mainRoot?.setStyle("background-image", "url($path)")
        mainRoot?.setStyle("background-repeat", "no-repeat")
        mainRoot?.setStyle("background-size", "cover")
        mainRoot?.setStyle("background-attachment", "fixed")
        mainRoot?.setStyle("background-position", "center")
    }

    fun addWidget(widget: Widget)
    {
        mainRoot?.add(widget)
        attachedWidgets.add(widget as WidgetInterface)
    }

    fun saveWidget(widget: WidgetInterface)
    {
        attachedWidgets.add(widget)
    }

    fun removeWidget(widget: Widget)
    {
        mainRoot?.remove(widget)
        attachedWidgets.remove(widget as WidgetInterface)
    }

    fun getWidget(label: String): WidgetInterface?
    {
        println(attachedWidgets)
        return attachedWidgets.find { it.getLabel() == label }
    }
}