package ui

import accelbyte.user.sendRegisterCode
import io.kvision.core.AlignItems
import io.kvision.core.Background
import io.kvision.core.Border
import io.kvision.core.BorderStyle
import io.kvision.core.BoxShadow
import io.kvision.core.Color
import io.kvision.core.Cursor
import io.kvision.core.FlexDirection
import io.kvision.core.FontWeight
import io.kvision.core.JustifyContent
import io.kvision.core.Overflow
import io.kvision.core.Position
import io.kvision.core.StringPair
import io.kvision.core.onClick
import io.kvision.core.onInput
import io.kvision.form.select.SelectInput
import io.kvision.form.select.selectInput
import io.kvision.form.text.TextInput
import io.kvision.form.text.textInput
import io.kvision.html.button
import io.kvision.html.h1
import io.kvision.html.p
import io.kvision.panel.Root
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.simplePanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import io.kvision.utils.perc
import io.kvision.utils.vh
import io.kvision.utils.vw
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import org.ttt.autogenesis.accelbyte.util.parseAccelByteErrorCode

private const val MAX_MATCH_PREVIEW = 5

/**
 * Console interface for Autogenesis application with blueprint selection and testing capabilities.
 *
 * @param dropdownChoices List of available blueprint options.
 * @param root The root container for UI management.
 */
class AutogenesisConsole(dropdownChoices: List<StringPair>, root: Root) : SimplePanel()
{

    var loginPageRef: LoginPage? = null

    init
    {
        add(MessageBox(
            "This is a title",
            "This is the message in the box",
            true,
            showOk = true,
            showCancel = true))

        vPanel(
            JustifyContent.CENTER,
            AlignItems.CENTER
        ) {
            width = 100.vw
            height = 100.vh
            paddingTop = 70.px
            paddingBottom = 70.px
            paddingLeft = 32.px
            paddingRight = 32.px
            flexDirection = FlexDirection.COLUMN

            h1("Autogenesis Console").apply {
                marginBottom = 16.px
                fontSize = 48.px
                fontWeight = FontWeight.BOLD
                letterSpacing = 1.px
            }

            simplePanel {
                width = 420.px
                marginBottom = 64.px
                padding = 20.px

                var dropdownInput: SelectInput? = null
                var matchDisplay: SimplePanel? = null
                var searchInput: TextInput? = null
                var previewVisible = false
                var currentQuery = ""

                val bestMatches: (List<StringPair>, String) -> List<StringPair> = { candidates, query ->
                    val normalized = query.lowercase()
                    candidates
                        .map { option ->
                            val labelLower = option.first.lowercase()
                            val matchIndex = if(normalized.isBlank())
                            {
                                0
                            }
                            else
                            {
                                labelLower.indexOf(normalized).takeIf { it >= 0 } ?: Int.MAX_VALUE
                            }
                            Triple(option, matchIndex, labelLower)
                        }
                        .sortedWith(compareBy({ it.second }, { it.first.first }))
                        .map { it.first }
                        .take(MAX_MATCH_PREVIEW)
                }

                lateinit var refreshMatches: (List<StringPair>, String) -> Unit

                /**
                 * Updates the dropdown suggestions and visibility based on the query.
                 *
                 * @param query The current filter text.
                 * @param forceVisibility Optional flag to forcibly show/hide the preview.
                 */
                fun applySuggestions(query: String, forceVisibility: Boolean? = null)
                {
                    currentQuery = query
                    val filtered = if(query.isBlank())
                    {
                        dropdownChoices
                    }
                    else
                    {
                        dropdownChoices.filter { (label, key) ->
                            label.lowercase().contains(query) || key.lowercase().contains(query)
                        }
                    }
                    dropdownInput?.options = filtered
                    val preview = bestMatches(filtered, query)
                    refreshMatches(preview, query)
                    previewVisible = when(forceVisibility)
                    {
                        true ->
                        {
                            true
                        }
                        false ->
                        {
                            false
                        }
                        null ->
                        {
                            query.isNotBlank()
                        }
                    }
                    matchDisplay?.visible = previewVisible
                }

                val searchContainer = simplePanel {
                    width = 100.perc
                    position = Position.RELATIVE

                    hPanel(
                        justify = JustifyContent.START,
                        alignItems = AlignItems.CENTER,
                        spacing = 8
                    ) {
                        width = 100.perc
                        searchInput = textInput {
                            placeholder = "Search blueprints"
                            width = 100.perc
                            marginBottom = 16.px
                            height = 48.px
                            fontSize = 15.px
                            onInput {
                                val query = this.value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: ""
                                applySuggestions(query)
                            }
                            onClick {
                                applySuggestions(currentQuery, forceVisibility = true)
                            }
                        }
                        button("⌄") {
                            width = 48.px
                            height = 48.px
                            fontSize = 20.px
                            marginBottom = 16.px
                            padding = 0.px
                            onClick {
                                val nextVisible = !previewVisible
                                applySuggestions(currentQuery, forceVisibility = nextVisible)
                            }
                        }
                    }

                    matchDisplay = simplePanel {
                        width = 100.perc
                        position = Position.ABSOLUTE
                        top = 58.px
                        left = 0.px
                        zIndex = 10
                        maxHeight = 220.px
                        overflow = Overflow.AUTO
                        padding = 12.px
                        background = Background(Color("#0d1120"))
                        border = Border(1.px, BorderStyle.SOLID, Color("#1f2335"))
                        borderRadius = 12.px
                        boxShadow = BoxShadow(0.px, 14.px, 32.px, 0.px, Color("rgba(0,0,0,0.55)"))
                        visible = false
                    }
                }
                refreshMatches = { matches, query ->
                    matchDisplay?.apply {
                        removeAll()
                        if(matches.isEmpty())
                        {
                            p("No blueprints match \"$query\"") {
                                fontSize = 13.px
                                color = Color("#c7ccff")
                                margin = 0.px
                            }
                        }
                        else
                        {
                            val heading = if (query.isBlank()) "Available blueprints" else "Matches"
                            p("$heading (${matches.size})") {
                                fontSize = 13.px
                                color = Color("#9da4ff")
                                margin = 0.px
                                marginBottom = 6.px
                            }
                            matches.forEach { (label, value) ->
                                p(label) {
                                    fontSize = 14.px
                                    color = Color("#e6ebff")
                                    margin = 0.px
                                    marginBottom = 6.px
                                    cursor = Cursor.POINTER
                                    onClick {
                                        dropdownInput?.value = value
                                        searchInput?.value = label
                                        applySuggestions(label.lowercase(), forceVisibility = true)
                                    }
                                }
                            }
                        }
                    }
                }
                applySuggestions("", forceVisibility = false)

                dropdownInput = selectInput(
                    options = dropdownChoices
                ) {
                    placeholder = "Choose a blueprint"
                    width = 100.perc
                    height = 52.px
                    fontSize = 16.px
                }
            }

            hPanel(
                justify = JustifyContent.CENTER,
                alignItems = AlignItems.CENTER,
                spacing = 20
            ) {

                /**
                 * Test button. Allows us to issue and run unit tests from this page.
                 */
                button("Test") {
                    width = 200.px
                    height = 56.px
                    fontSize = 18.px

                    onClick {
                        MainScope().launch {
                            try {
                                sendRegisterCode("contact+test500@tentrilliontriangles.com").await()
                                console.log("Test: sendRegisterCode succeeded")
                            } catch (e: Throwable) {
                                val errorCode = parseAccelByteErrorCode(e)
                                console.error("Test: sendRegisterCode failed with error code $errorCode: ${e.message}")
                            }
                        }
                    }
                }

                /**
                 * Starts the app as normal. Will tear this widget down.
                 */
                button("Start App") {
                    width = 200.px
                    height = 56.px
                    fontSize = 18.px

                    onClick {
                        if(loginPageRef == null)
                        {
                            val login = LoginPage()
                            loginPageRef = login
                            root.add(login)
                        }
                        else
                        {
                            loginPageRef?.show()
                            loginPageRef?.toFront()
                        }
                        root.remove(this@AutogenesisConsole)
                    }
                }
            }
        }
    }
}
