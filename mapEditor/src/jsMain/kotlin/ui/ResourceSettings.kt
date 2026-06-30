package ui

import enums.ResourceType
import interfaces.WidgetInterface
import io.kvision.core.AlignItems
import io.kvision.core.CssSize
import io.kvision.core.JustifyContent
import io.kvision.core.TextAlign
import io.kvision.core.UNIT
import io.kvision.core.onChange
import io.kvision.core.onClick
import io.kvision.core.onInput
import io.kvision.form.check.CheckBox
import io.kvision.form.check.checkBox
import io.kvision.form.select.Select
import io.kvision.form.select.select
import io.kvision.form.text.TextArea
import io.kvision.form.text.TextInput
import io.kvision.form.text.textArea
import io.kvision.form.text.textInput
import io.kvision.html.P
import io.kvision.html.p
import io.kvision.panel.VPanel
import kotlinx.serialization.json.Json
import structs.Resource

/**
 * Defines settings for this resource.
 */
class ResourceSettings : VPanel(
    justify = JustifyContent.FLEXSTART,
    alignItems = AlignItems.CENTER,
    spacing = 20,
    useWrappers = true
), WidgetInterface {
    var resourceData = Resource()
    private var widgetLabel = ""
    var onDataChange: (() -> Unit)? = null

    var resourceName: TextInput? = null
    var resourceType: Select? = null
    var isDepletable: CheckBox? = null
    var isDestructible: CheckBox? = null
    var description: TextArea? = null
    var abilities: TextArea? = null

    init {
        //Title for the input box
        p("Resource Name") {
            fontSize = CssSize(20, UNIT.px)
            marginBottom = CssSize(12, UNIT.px)
            padding = CssSize(0, UNIT.px)
            textAlign = TextAlign.CENTER
            width = CssSize(100, UNIT.perc)
        }

        //Input box to name the resource.
        resourceName = textInput {
            maxlength = 128
            width = CssSize(90, UNIT.perc)
            height = CssSize(40, UNIT.px)
            marginBottom = CssSize(32, UNIT.px)
            padding = CssSize(10, UNIT.px)

            onInput {
                resourceData.name = this.value ?: ""
                onDataChange?.invoke()
            }
        }

        //Title for type selection
        p("Resource Type") {
            fontSize = CssSize(20, UNIT.px)
            marginBottom = CssSize(12, UNIT.px)
            padding = CssSize(0, UNIT.px)
            textAlign = TextAlign.CENTER
            width = CssSize(100, UNIT.perc)
        }

        //Dropdown menu to allow the user to pick out resources.
        resourceType = select {
            width = CssSize(90, UNIT.perc)
            height = CssSize(35, UNIT.px)
            marginBottom = CssSize(32, UNIT.px)

            options = ResourceType.options()

            onChange {
                resourceData.type = enumValueOf<ResourceType>(this.value!!)
                onDataChange?.invoke()
            }
        }

        //If true the resource could run out of uses or need to be replenished based on story events.
        isDepletable = checkBox {
            inline = true
            addCssClass("form-switch")
            label = "Depletable"
            marginBottom = CssSize(20, UNIT.px)
            paddingTop = CssSize(80, UNIT.px)

            onClick {
                resourceData.depletable = this.value
                onDataChange?.invoke()
            }
        }

        //If true, the story agent could write an event that permanently destroys this resource.
        isDestructible = checkBox {
            inline = true
            addCssClass("form-switch")
            label = "Destructible"
            marginBottom = CssSize(32, UNIT.px)
            paddingTop = CssSize(20, UNIT.px)

            onClick {
                resourceData.destructible = this.value
                onDataChange?.invoke()
            }
        }

        p("Description") {
            fontSize = CssSize(20, UNIT.px)
            marginBottom = CssSize(12, UNIT.px)
            padding = CssSize(0, UNIT.px)
            textAlign = TextAlign.CENTER
            width = CssSize(100, UNIT.perc)
        }

        description = textArea {
            cols = 30
            rows = 4
            fontSize = CssSize(14, UNIT.px)
            marginBottom = CssSize(32, UNIT.px)
            width = CssSize(90, UNIT.perc)
            placeholder = "Describe what this resources is."

            onInput {
                resourceData.description = this.value ?: ""
                onDataChange?.invoke()
            }
        }

        p("Abilities") {
            fontSize = CssSize(20, UNIT.px)
            marginBottom = CssSize(12, UNIT.px)
            padding = CssSize(0, UNIT.px)
            textAlign = TextAlign.CENTER
            width = CssSize(100, UNIT.perc)
        }

        abilities = textArea {
            cols = 30
            rows = 4
            fontSize = CssSize(14, UNIT.px)
            marginBottom = CssSize(32, UNIT.px)
            width = CssSize(90, UNIT.perc)
            placeholder = "Describe what this resources does."

            onInput {
                resourceData.abilities = this.value ?: ""
                onDataChange?.invoke()
            }

        }
    }

    override fun getLabel(): String = widgetLabel

    override fun setLabel(label: String) {
        widgetLabel = label
    }

    override fun updateDataInternal(data: String) {
        val updatedResource = kotlinx.serialization.json.Json.decodeFromString<Resource>(data)
        resourceData = updatedResource
        
        // Update form fields to match received data
        resourceName?.value = updatedResource.name
        resourceType?.value = updatedResource.type.toString()
        isDepletable?.value = updatedResource.depletable
        isDestructible?.value = updatedResource.destructible
        description?.value = updatedResource.description
        abilities?.value = updatedResource.abilities
        refresh()
    }

    override fun destroyWidget() {
        parent?.remove(this)
        dispose()
    }
}