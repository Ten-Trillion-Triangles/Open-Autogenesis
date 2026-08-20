package ui

import globals.KEnv
import interfaces.WidgetInterface
import io.kvision.core.CssSize
import io.kvision.core.UNIT
import io.kvision.html.div
import io.kvision.html.label
import io.kvision.panel.DockPanel
import io.kvision.panel.HPanel
import io.kvision.panel.SimplePanel
import io.kvision.panel.VPanel
import io.kvision.panel.simplePanel

/**
 * Base root class that houses the main dock of the system. A top bar for button controls, a side dock on the right
 * that allows for field properties to be assigned to each territory defined by pins, and a center area that has a
 * fillable canvas with an image background, and pins that can be dragged into the canvas area.
 */
class EditorPanel : DockPanel(), WidgetInterface
{
    var widgetName = "EditorPanel"
    var interfaceChildren = mutableListOf<WidgetInterface>()

    val topBar = TopBar()
    val sidebar = PropertySidebar()
    val mapCanvas = MapCanvas()

    init {
        width = CssSize(100, UNIT.perc)
        height = CssSize(100, UNIT.perc)


        up {
            add(topBar)
        }

       right {
           add(sidebar)
       }


       center {
           add(mapCanvas)
           mapCanvas.height = CssSize(680, UNIT.px)
           refresh()
       }

        topBar.setLabel("TopBar")
        sidebar.setLabel("Sidebar")
        mapCanvas.setLabel("MapCanvas")

        KEnv.saveWidget(topBar)
        KEnv.saveWidget(sidebar)
        KEnv.saveWidget(mapCanvas)
    }

//=============================================WidgetInterface==========================================================

    override fun setLabel(label: String)
    {
        super.setLabel(label)
        widgetName = label
    }

    override fun getLabel(): String
    {
        return widgetName
    }

    override fun getChild(label: String): WidgetInterface?
    {
        for(widget in interfaceChildren)
        {
            if(widget.getLabel() == label)
            {
                return widget
            }
        }

        return null
    }

    override fun getChildren(getTopLevel: Boolean): List<WidgetInterface>?
    {
        return interfaceChildren
    }
}
