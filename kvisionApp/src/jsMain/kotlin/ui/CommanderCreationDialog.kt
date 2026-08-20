package ui

import globals.AccelByteEnv
import globals.KEnv
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
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.serialization.serializer

import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.RpcJson
import org.ttt.autogenesis.kvisionapp.CommanderCache
import org.ttt.autogenesis.kvisionapp.ServerExtendBridge

import structs.requests.CommanderCreateRequest
import structs.rpcRequests.CommanderCreateRpcRequest

/**
 * Commander creation dialog popup. Allows the player to define their commander's name,
 * description, and nation description. Validates that all fields are filled before allowing
 * creation.
 */
class CommanderCreationDialog(
    var onCreateCommander: ((name: String, description: String, nationDescription: String) -> Unit)? = null,
    var onCancel: (() -> Unit)? = null
) : SimplePanel(className = "commander-creation-overlay")
{

    // Public variables to store commander data
    var commanderName: String = ""
    var commanderDescription: String = ""
    var nationDescription: String = ""

    private val coroutineScope = MainScope()

    // Max character limits
    private val descriptionMaxChars = 15000
    private val nationDescMaxChars = 15000

    // UI element references
    private lateinit var errorText: Span
    private lateinit var nameInput: TextInput
    private lateinit var descriptionInput: TextArea
    private lateinit var descriptionCounter: Span
    private lateinit var nationInput: TextArea
    private lateinit var nationCounter: Span
    private lateinit var createButton: Button

    init
    {
        // Overlay styles (Backdrop)
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

        // Main dialog panel
        vPanel(className = "commander-creation-dialog") {
            width = 900.px
            height = 700.px
            background = Background(Color("#0d1120"))
            border = Border(2.px, BorderStyle.SOLID, Color("#383f59"))
            borderRadius = 12.px
            padding = 30.px
            justifyContent = JustifyContent.SPACEBETWEEN

            // Title
            h3("Create Your Commander") {
                textAlign = TextAlign.CENTER
                fontSize = CssSize(32, UNIT.px)
                marginTop = 0.px
                marginBottom = 10.px
                color = Color("#ffffff")
            }

            // Error message area (initially hidden)
            errorText = span("") {
                addCssClass("error-message")
                textAlign = TextAlign.CENTER
                fontSize = CssSize(16, UNIT.px)
                color = Color("#ff4444")
                marginBottom = 10.px
                visible = false
                fontWeight = FontWeight.BOLD
            }


            // Scrollable content area
            vPanel {
                overflow = Overflow.AUTO
                flexGrow = 1
                spacing = 15
                marginBottom = 20.px
                width = 100.perc
                paddingLeft = 10.perc
                paddingRight = 10.perc
                alignItems = AlignItems.CENTER

                // Commander Name Section
                p("Commander Name") {
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
                    placeholder = "Enter your commander's name..."

                    onInput {
                        commanderName = this.value ?: ""
                        clearError()
                    }
                }

                // Description Section
                p("Description") {
                    fontSize = CssSize(24, UNIT.px)
                    fontWeight = FontWeight.BOLD
                    marginTop = 20.px
                    marginBottom = 8.px
                    color = Color("#f4f6fb")
                    textAlign = TextAlign.CENTER
                    width = 100.perc
                }

                descriptionInput = textArea {
                    width = 85.perc
                    height = 320.px
                    fontSize = CssSize(16, UNIT.px)
                    padding = 15.px
                    setAttribute("maxlength", descriptionMaxChars.toString())
                    placeholder = "Describe your commander's background, personality, and abilities..."
                    cols = 60
                    rows = 10
                    paddingRight = CssSize(30, UNIT.px)

                    onInput {
                        commanderDescription = this.value ?: ""
                        updateDescriptionCounter()
                        clearError()
                    }
                }

                // Character counter for description
                descriptionCounter = span("0 / $descriptionMaxChars characters") {
                    fontSize = CssSize(14, UNIT.px)
                    color = Color("#a0a8cc")
                    marginTop = 5.px
                    textAlign = TextAlign.CENTER
                    width = 70.perc
                    alignSelf = AlignItems.CENTER
                }

                // Nation Description Section
                p("Nation Description") {
                    fontSize = CssSize(24, UNIT.px)
                    fontWeight = FontWeight.BOLD
                    marginTop = 20.px
                    marginBottom = 8.px
                    color = Color("#f4f6fb")
                    textAlign = TextAlign.CENTER
                    width = 100.perc
                }

                nationInput = textArea {
                    width = 85.perc
                    height = 320.px
                    fontSize = CssSize(16, UNIT.px)
                    padding = 15.px
                    setAttribute("maxlength", nationDescMaxChars.toString())
                    placeholder = "Describe the nation your commander leads..."
                    cols = 60
                    rows = 10
                    paddingRight = CssSize(30, UNIT.px)


                    onInput {
                        nationDescription = this.value ?: ""
                        updateNationCounter()
                        clearError()
                    }
                }

                // Character counter for nation description
                nationCounter = span("0 / $nationDescMaxChars characters") {
                    fontSize = CssSize(14, UNIT.px)
                    color = Color("#a0a8cc")
                    marginTop = 5.px
                    textAlign = TextAlign.CENTER
                    width = 70.perc
                    alignSelf = AlignItems.CENTER
                }
            }

            // Button Row
            hPanel(
                justify = JustifyContent.CENTER,
                spacing = 20,
                alignItems = AlignItems.CENTER,
            ) {
                width = 100.perc

                // Back button
                button("BACK") {
                    addCssClass("btn-secondary-action")
                    width = 180.px
                    height = 55.px
                    fontSize = CssSize(18, UNIT.px)

                    onClick {
                        onCancel?.invoke()
                        closeDialog()
                    }
                }

                // Create button
                createButton = button("CREATE") {
                    addCssClass("btn-play")
                    width = 180.px
                    height = 55.px
                    fontSize = CssSize(18, UNIT.px)

                    onClick {
                        if(validateInputs())
                        {
                            if(AccelByteEnv.userId.trim().isEmpty())
                            {
                                showError("You must be logged in to save a commander.")
                            }
                            else
                            {
                                val trimmedName = commanderName.trim()
                                val trimmedDescription = commanderDescription.trim()
                                val trimmedNation = nationDescription.trim()

                                val messageBox = MessageBox(
                                    boxTitle = "Saving Commander",
                                    message = "Preparing connection to server-extend...",
                                    showThrobber = true
                                )
                                KEnv.mainRoot?.add(messageBox)

                                coroutineScope.launch {
                                    try
                                    {
                                        messageBox.setMessage("Connecting to server-extend...")
                                        ServerExtendBridge.withTemporaryConnection {
                                            messageBox.setMessage("Saving commander \"$trimmedName\"...")
                                            val commanderRequest = CommanderCreateRequest(
                                                name = trimmedName,
                                                description = trimmedDescription,
                                                empireDescription = trimmedNation
                                            )

                                            val rpcRequest = CommanderCreateRpcRequest(
                                                accelbyteId = AccelByteEnv.userId,
                                                commanderRequest = commanderRequest
                                            )

                                            val response = ServerExtendBridge.rpcInvoker?.invoke(
                                                "extend.saveCommander",
                                                rpcRequest,
                                                CommanderCreateRpcRequest.serializer()
                                            )

                                            val success = response?.result?.let { RpcJson.decodeFromJsonElement(serializer<Boolean>(), it) } ?: false
                                            val succeeded = response?.error == null && success

                                            if(succeeded)
                                            {
                                                CommanderCache.removeCommanderRecord(trimmedName)

                                                val refreshResult = runCatching {
                                                    loadSavedCommanders(messageBox)
                                                }

                                                if(refreshResult.isFailure)
                                                {
                                                    Logger.error(
                                                        LogCategory.DATABASE,
                                                        "CommanderCreationDialog: failed to refresh saved commanders after saving '$trimmedName': ${refreshResult.exceptionOrNull()?.message}"
                                                    )
                                                }

                                                val successMessage = if(refreshResult.isSuccess)
                                                {
                                                    "Commander \"$trimmedName\" is saved in server-extend."
                                                }
                                                else
                                                {
                                                    val errMessage = refreshResult.exceptionOrNull()?.message ?: "unknown error"
                                                    "Commander \"$trimmedName\" was saved, but the commander list could not be refreshed: $errMessage"
                                                }

                                                messageBox
                                                    .setTitle("Commander Saved")
                                                    .setMessage(successMessage)
                                                    .setThrobber(false)
                                                    .setButtons(ok = true)
                                                messageBox.onConfirm = {
                                                    onCreateCommander?.invoke(trimmedName, trimmedDescription, trimmedNation)
                                                    closeDialog()
                                                }
                                            }
                                            else
                                            {
                                                val reason = response?.error?.message ?: "Server rejected the save."
                                                messageBox
                                                    .setTitle("Save Failed")
                                                    .setMessage("Unable to save commander: $reason")
                                                    .setThrobber(false)
                                                    .setButtons(ok = true)
                                                messageBox.onConfirm = {}
                                            }
                                        }
                                    }
                                    catch(err: Throwable)
                                    {
                                        messageBox
                                            .setTitle("Save Error")
                                            .setMessage("Unable to reach server-extend: ${err.message ?: "unknown error"}")
                                            .setThrobber(false)
                                            .setButtons(ok = true)
                                        messageBox.onConfirm = {}
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Validates that all required fields are filled out.
     *
     * @return True if all fields are valid, false otherwise.
     */
    private fun validateInputs(): Boolean
    {
        val emptyFields = mutableListOf<String>()

        if(commanderName.trim().isEmpty())
        {
            emptyFields.add("Commander Name")
        }
        if(commanderDescription.trim().isEmpty())
        {
            emptyFields.add("Description")
        }
        if(nationDescription.trim().isEmpty())
        {
            emptyFields.add("Nation Description")
        }

        if(emptyFields.isNotEmpty())
        {
            showError("Please fill out: ${emptyFields.joinToString(", ")}")
            highlightEmptyFields()
            return false
        }

        return true
    }

    /**
     * Shows error message at the top of the dialog.
     *
     * @param message The error message to display.
     */
    private fun showError(message: String)
    {
        errorText.content = message
        errorText.visible = true
        errorText.refresh()
    }

    /**
     * Clears the error message.
     */
    private fun clearError()
    {
        errorText.visible = false
        errorText.refresh()
        
        // Remove highlighting from all fields
        nameInput.removeCssClass("input-error")
        descriptionInput.removeCssClass("input-error")
        nationInput.removeCssClass("input-error")
    }

    /**
     * Highlights empty fields with red border.
     */
    private fun highlightEmptyFields()
    {
        if(commanderName.trim().isEmpty())
        {
            nameInput.addCssClass("input-error")
        }
        if(commanderDescription.trim().isEmpty())
        {
            descriptionInput.addCssClass("input-error")
        }
        if(nationDescription.trim().isEmpty())
        {
            nationInput.addCssClass("input-error")
        }
    }

    /**
     * Updates the character counter for description.
     */
    private fun updateDescriptionCounter()
    {
        val count = commanderDescription.length
        descriptionCounter.content = "$count / $descriptionMaxChars characters"
        
        // Change color if approaching limit
        if(count > descriptionMaxChars * 0.9)
        {
            descriptionCounter.color = Color("#ff9944")
        }
        else if(count > descriptionMaxChars * 0.7)
        {
            descriptionCounter.color = Color("#ffcc44")
        }
        else
        {
            descriptionCounter.color = Color("#a0a8cc")
        }
        
        descriptionCounter.refresh()
    }

    /**
     * Updates the character counter for nation description.
     */
    private fun updateNationCounter()
    {
        val count = nationDescription.length
        nationCounter.content = "$count / $nationDescMaxChars characters"
        
        // Change color if approaching limit
        if(count > nationDescMaxChars * 0.9)
        {
            nationCounter.color = Color("#ff9944")
        }
        else if(count > nationDescMaxChars * 0.7)
        {
            nationCounter.color = Color("#ffcc44")
        }
        else
        {
            nationCounter.color = Color("#a0a8cc")
        }
        
        nationCounter.refresh()
    }

    /**
     * Closes and destroys the dialog.
     */
    private fun closeDialog()
    {
        this.visible = false
        this.parent?.remove(this)
    }
}