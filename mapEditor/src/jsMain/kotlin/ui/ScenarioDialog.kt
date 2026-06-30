package ui

import io.kvision.core.*
import io.kvision.form.text.TextArea
import io.kvision.form.text.TextInput
import io.kvision.form.text.textArea
import io.kvision.form.text.textInput
import io.kvision.html.*
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.perc
import io.kvision.utils.px

/**
 * Scenario dialog popup. Allows the developer to define the world name and story scenario
 * for the map being created.
 */
class ScenarioDialog(
    var onSave: ((worldName: String, storyScenario: String) -> Unit)? = null,
    var onCancel: (() -> Unit)? = null,
    initialWorldName: String = "",
    initialStoryScenario: String = ""
) : SimplePanel(className = "commander-creation-overlay") {

    var worldName: String = initialWorldName
    var storyScenario: String = initialStoryScenario

    private lateinit var errorText: Span
    private lateinit var nameInput: TextInput
    private lateinit var scenarioInput: TextArea

    init {
        console.log("ScenarioDialog created with initialWorldName='$initialWorldName', initialStoryScenario length=${initialStoryScenario.length}")
        
        position = Position.FIXED
        top = 0.px
        left = 0.px
        width = 100.perc
        height = 100.perc
        zIndex = 9000
        background = Background(Color("rgba(0, 0, 0, 0.7)"))
        display = Display.FLEX
        justifyContent = JustifyContent.CENTER
        alignItems = AlignItems.CENTER

        vPanel(className = "commander-creation-dialog") {
            width = 900.px
            height = 600.px
            background = Background(Color("#0d1120"))
            border = Border(2.px, BorderStyle.SOLID, Color("#383f59"))
            borderRadius = 12.px
            padding = 30.px
            justifyContent = JustifyContent.SPACEBETWEEN

            h3("World Scenario") {
                textAlign = TextAlign.CENTER
                fontSize = CssSize(32, UNIT.px)
                marginTop = 0.px
                marginBottom = 10.px
                color = Color("#ffffff")
            }

            errorText = span("") {
                addCssClass("error-message")
                textAlign = TextAlign.CENTER
                fontSize = CssSize(16, UNIT.px)
                color = Color("#ff4444")
                marginBottom = 10.px
                visible = false
                fontWeight = FontWeight.BOLD
            }

            vPanel {
                overflow = Overflow.AUTO
                flexGrow = 1
                spacing = 15
                marginBottom = 20.px
                width = 100.perc
                paddingLeft = 10.perc
                paddingRight = 10.perc
                alignItems = AlignItems.CENTER

                p("World Name") {
                    fontSize = CssSize(24, UNIT.px)
                    fontWeight = FontWeight.BOLD
                    marginBottom = 8.px
                    color = Color("#f4f6fb")
                    textAlign = TextAlign.CENTER
                    width = 100.perc
                }

                nameInput = textInput {
                    width = 75.perc
                    height = 70.px
                    fontSize = CssSize(18, UNIT.px)
                    padding = 15.px
                    setAttribute("maxlength", "100")
                    placeholder = "Enter the world name..."

                    onInput {
                        worldName = this.value ?: ""
                        clearError()
                    }
                }.apply {
                    console.log("Setting nameInput.value to '$initialWorldName'")
                    value = initialWorldName
                    console.log("nameInput.value is now: '${this.value}'")
                }

                p("Story Scenario") {
                    fontSize = CssSize(24, UNIT.px)
                    fontWeight = FontWeight.BOLD
                    marginTop = 20.px
                    marginBottom = 8.px
                    color = Color("#f4f6fb")
                    textAlign = TextAlign.CENTER
                    width = 100.perc
                }

                scenarioInput = textArea {
                    width = 85.perc
                    height = 250.px
                    fontSize = CssSize(16, UNIT.px)
                    padding = 15.px
                    placeholder = "Describe the story scenario for this world..."
                    cols = 60
                    rows = 10
                    paddingRight = CssSize(30, UNIT.px)

                    onInput {
                        storyScenario = this.value ?: ""
                        clearError()
                    }
                }.apply {
                    console.log("Setting scenarioInput.value to text of length ${initialStoryScenario.length}")
                    value = initialStoryScenario
                    console.log("scenarioInput.value is now length: ${this.value?.length ?: 0}")
                }

            }

            hPanel(
                justify = JustifyContent.CENTER,
                spacing = 20,
                alignItems = AlignItems.CENTER,
            ) {
                width = 100.perc

                button("CANCEL") {
                    addCssClass("btn-secondary-action")
                    width = 180.px
                    height = 55.px
                    fontSize = CssSize(18, UNIT.px)

                    onClick {
                        onCancel?.invoke()
                        closeDialog()
                    }
                }

                button("SAVE") {
                    addCssClass("btn-play")
                    width = 180.px
                    height = 55.px
                    fontSize = CssSize(18, UNIT.px)

                    onClick {
                        if (validateInputs()) {
                            onSave?.invoke(worldName, storyScenario)
                            closeDialog()
                        }
                    }
                }
            }
        }
    }

    private fun validateInputs(): Boolean {
        if (worldName.trim().isEmpty()) {
            showError("Please enter a world name")
            nameInput.addCssClass("input-error")
            return false
        }
        return true
    }

    private fun showError(message: String) {
        errorText.content = message
        errorText.visible = true
        errorText.refresh()
    }

    private fun clearError() {
        errorText.visible = false
        errorText.refresh()
        nameInput.removeCssClass("input-error")
        scenarioInput.removeCssClass("input-error")
    }

    private fun closeDialog() {
        this.visible = false
        this.parent?.remove(this)
    }
}
