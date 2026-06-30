package ui

import enums.CommanderTrait
import enums.CommanderType
import globals.KEnv
import globals.World
import io.kvision.core.AlignItems
import io.kvision.core.Background
import io.kvision.core.Border
import io.kvision.core.BorderStyle
import io.kvision.core.BoxShadow
import io.kvision.core.Col
import io.kvision.core.Color
import io.kvision.core.Display
import io.kvision.core.FlexDirection
import io.kvision.core.FontWeight
import io.kvision.core.JustifyContent
import io.kvision.core.JustifyItems
import io.kvision.core.Overflow
import io.kvision.core.Position
import io.kvision.core.TextAlign
import io.kvision.core.onClick
import io.kvision.core.onInput
import io.kvision.form.text.TextInput
import io.kvision.form.text.textInput
import io.kvision.html.button
import io.kvision.html.h2
import io.kvision.html.h3
import io.kvision.html.icon
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.perc
import io.kvision.utils.px
import kotlinx.browser.document
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import structs.Commander
import structs.requests.CommanderCreateRequest

private enum class CollectionPage
{
    COMMANDERS,
    STORIES
}

private data class CommanderDisplayInfo(
    val name: String,
    val description: String,
    val empire: String,
    val type: CommanderType,
    val trait: CommanderTrait,
    val iconClass: String,
    val source: Commander
)

private data class StoryEntry(
    val title: String,
    val dateCreated: String,
    val summary: String,
    val body: String
)

/**
 * Overlay that hosts the player's collection of commanders/stories. The widget is centered over the whole
 * viewport and keeps the same visual language as the other stat/detail dialogs.
 */
class CollectionOverlay : SimplePanel(className = "collection-overlay")
{
    private val commanderDetailWindow = CommanderDetailWindow()
    private val storyDetailWindow = StoryDetailWindow()
    private val storyEntries = listOf(
        StoryEntry(
            title = "Emberfall Siege",
            dateCreated = "December 04, 2025",
            summary = "A desperate stand under the Emberfall Rift where every commander learned what it means to lose control.",
            body = "December 4, 2025 - Emery, a young field marshal, ignored the wards and ordered his archers into the haunting glow..."
        ),
        StoryEntry(
            title = "Crown of Tidal Spires",
            dateCreated = "November 27, 2025",
            summary = "An aquatic alliance threatens to drown the outer provinces unless strange negotiations take hold.",
            body = "November 27, 2025 - The tide rose before the diplomats even reached the table. Admiral Kairu watched from the balcony..."
        ),
        StoryEntry(
            title = "Skybound Accord",
            dateCreated = "October 21, 2025",
            summary = "A fleet of flying citadels negotiates peace, but a warlord still brews in the lowlands.",
            body = "October 21, 2025 - The air itself hummed with tension. High Admiral Vela surveyed the crowd of envoys..."
        )
    )

    private var activePage = CollectionPage.COMMANDERS
    private lateinit var commanderTabButton: io.kvision.html.Button
    private lateinit var storyTabButton: io.kvision.html.Button
    private lateinit var searchField: TextInput
    private val commanderListContainer = vPanel {
        width = 100.perc
        flexGrow = 1
        overflow = Overflow.AUTO
        alignItems = AlignItems.CENTER
        spacing = 12
    }

    private val noCommanderMatches = p("No commanders match the current filter.") {
        width = 80.perc
        fontSize = 18.px
        textAlign = TextAlign.CENTER
        color = Color.name(Col.LIGHTGRAY)
        marginTop = 0.px
        marginBottom = 0.px
        visible = false
    }

    private data class CommanderCardEntry(val name: String, val panel: SimplePanel)

    private val commanderCards = mutableListOf<CommanderCardEntry>()

    private val storyListContainer = vPanel {
        width = 100.perc
        flexGrow = 1
        overflow = Overflow.AUTO
        alignItems = AlignItems.CENTER
        spacing = 16
    }

    private val commanderPage = vPanel(spacing = 16) {
        width = 100.perc
        flexGrow = 1
        overflow = Overflow.HIDDEN
        add(buildSearchRow())
        add(commanderListContainer)
    }

    private val storyPage = vPanel(spacing = 16) {
        width = 100.perc
        flexGrow = 1
        overflow = Overflow.HIDDEN
        add(p("Stories you have unlocked so far. Content playback still uses placeholder text until the editor content arrives.") {
            color = Color.name(Col.LIGHTGRAY)
            fontSize = 16.px
            textAlign = TextAlign.CENTER
            width = 100.perc
            marginTop = 0.px
            marginBottom = 0.px
        })
        add(storyListContainer)
    }

    init
    {
        width = 100.perc
        height = 100.perc
        position = Position.FIXED
        top = 0.px
        left = 0.px
        zIndex = 8500
        background = Background(Color("rgba(0, 0, 0, 0.85)"))
        display = Display.NONE
        justifyContent = JustifyContent.CENTER
        alignItems = AlignItems.CENTER

        vPanel(
            className = "collection-window",
            spacing = 10,
            alignItems = AlignItems.CENTER
        ) {
            width = 92.perc
            height = 90.perc
            borderRadius = 22.px
            padding = 20.px
            background = Background(Color("rgba(7, 10, 24, 0.95)"))
            border = Border(2.px, BorderStyle.SOLID, Color("rgba(94, 106, 220, 0.4)"))
            boxShadow = BoxShadow(0.px, 35.px, 60.px, color = Color("rgba(0, 0, 0, 0.8)"))
            flexDirection = FlexDirection.COLUMN
            position = Position.RELATIVE

            hPanel(
                justify = JustifyContent.FLEXSTART,
                alignItems = AlignItems.CENTER
            ) {
                h2("Collection") {
                    color = Color.name(Col.WHITE)
                    marginTop = 0.px
                    marginBottom = 0.px
                    fontWeight = FontWeight.BOLD
                }
            }

            button("", icon = "fas fa-times", className = "btn btn-play btn-close-collection") {
                width = 60.px
                height = 60.px
                onClick {
                    this@CollectionOverlay.hide()
                }
                position = Position.ABSOLUTE
                top = (2).px
                right = 6.px
                zIndex = 50
            }

            hPanel(
                className = "collection-content",
                spacing = 0,
                alignItems = AlignItems.STRETCH
            ) {
                width = 100.perc
                height = 100.perc
                flexGrow = 1
                vPanel {
                    width = 100.perc
                    flexGrow = 1
                    overflow = Overflow.HIDDEN
                    flexDirection = FlexDirection.COLUMN
                    add(commanderPage)
                    add(storyPage)
                }

                vPanel(
                    className = "collection-tab-strip",
                    spacing = 10,
                    alignItems = AlignItems.CENTER,
                    justify = JustifyContent.CENTER
                ) {
                    width = 90.px
                    height = 100.perc
                    commanderTabButton = button("", icon = "fas fa-user-astronaut", className = "collection-tab-button") {
                        title = "Commanders"
                        onClick { switchPage(CollectionPage.COMMANDERS) }
                    }
                    storyTabButton = button("", icon = "fas fa-book-open", className = "collection-tab-button") {
                        title = "Stories"
                        onClick { switchPage(CollectionPage.STORIES) }
                    }
                }
            }
        }

        add(commanderDetailWindow)
        add(storyDetailWindow)
    }

    /**
     * Displays the overlay and refreshes its contents.
     */
    override fun show()
    {
        // Ensure saved commanders are loaded before rendering.
        // During normal login this is handled by the login flow, but skipLogin bypasses
        // that path so we load on-demand here.
        MainScope().launch {
            val loadingBox = MessageBox(boxTitle = "Loading saved commanders...")
            KEnv.mainRoot?.add(loadingBox)
            try {
                loadSavedCommanders(loadingBox)
            } finally {
                loadingBox.hide()
            }
            rebuildCommanderCards()
            renderStoryList()
            setActivePage(CollectionPage.COMMANDERS)
        }
        display = Display.FLEX
        visible = true
    }

    override fun hide()
    {
        super.hide()
        dispositionDetailWindows()
        display = Display.NONE
        visible = false
    }

    private fun dispositionDetailWindows()
    {
        commanderDetailWindow.hide()
        storyDetailWindow.hide()
    }

    private fun buildSearchRow() = hPanel(
        className = "collection-search",
        spacing = 12,
        alignItems = AlignItems.CENTER
    ) {
        width = 100.perc
        icon("fas fa-search")
            searchField = textInput {
                width = 100.perc
                placeholder = "Search commanders..."
                onInput {
                    applyCommanderFilter()
                }
                setAttribute("spellcheck", "false")
            }
        }

    private fun rebuildCommanderCards()
    {
        commanderCards.clear()
        commanderListContainer.removeAll()
        val infos = collectCommanderInfos()
        infos.forEach { info ->
            val card = buildCommanderCard(info)
            commanderCards.add(CommanderCardEntry(info.name.lowercase(), card))
            commanderListContainer.add(card)
        }
        commanderListContainer.add(noCommanderMatches)
        applyCommanderFilter()
    }

    private fun applyCommanderFilter()
    {
        val filterValue = searchField.value?.trim()?.lowercase().orEmpty()
        var matchCount = 0
        commanderCards.forEach { entry ->
            val matches = filterValue.isBlank() || entry.name.contains(filterValue)
            entry.panel.display = if(matches) Display.FLEX else Display.NONE
            entry.panel.visible = matches
            if(matches)
            {
                matchCount++
            }
        }
        noCommanderMatches.visible = matchCount == 0
    }

    private fun buildCommanderCard(info: CommanderDisplayInfo) = hPanel(
        className = "collection-card",
        spacing = 18,
        alignItems = AlignItems.CENTER
    ) {
        width = 90.perc
        height = 120.px
        onClick {
            commanderDetailWindow.show(info)
        }

        icon(info.iconClass) {
            fontSize = 32.px
            color = Color.name(Col.CYAN)
        }

        vPanel(spacing = 6) {
            width = 100.perc
            h3(info.name) {
                color = Color.name(Col.WHITE)
                marginTop = 0.px
                marginBottom = 4.px
            }

            span("Type: ${info.type.name} • Trait: ${info.trait.name}") {
                color = Color.name(Col.LIGHTGRAY)
                fontSize = 16.px
            }
        }
    }

    private fun renderStoryList()
    {
        storyListContainer.removeAll()
        storyEntries.forEach { story ->
            storyListContainer.add(buildStoryEntry(story))
        }
    }

    private fun buildStoryEntry(story: StoryEntry) = vPanel(
        className = "collection-story-entry",
        spacing = 6
    ) {
        width = 90.perc
        alignItems = AlignItems.START
        onClick {
            storyDetailWindow.show(story)
        }

        h3(story.title) {
            marginTop = 0.px
            marginBottom = 4.px
            color = Color.name(Col.WHITE)
        }

        span(story.dateCreated) {
            color = Color.name(Col.LIGHTBLUE)
            fontSize = 14.px
            marginBottom = 4.px
        }

        p(story.summary) {
            color = Color.name(Col.LIGHTGRAY)
            fontSize = 15.px
            marginBottom = 0.px
            lineHeight = 22.px
        }
    }

    private fun collectCommanderInfos() = World.availableCommanders.map { buildCommanderInfo(it) }

    private fun buildCommanderInfo(entry: Commander): CommanderDisplayInfo
    {
        val request = entry
        val nameValue = request.name.ifBlank { "Unnamed Commander" }
        val description = request.description.ifBlank { "No description has been recorded for this commander yet." }
        val empire = request.empire.ifBlank { "No empire notes were provided." }
        val type = request.type
        val trait = request.trait
        val iconClass = when(type)
        {
            CommanderType.Flying -> "fas fa-feather-alt"
            CommanderType.Aquatic -> "fas fa-water"
            CommanderType.Land -> "fas fa-mountain"
        }

        return CommanderDisplayInfo(
            name = nameValue,
            description = description,
            empire = empire,
            type = type,
            trait = trait,
            iconClass = iconClass,
            source = entry
        )
    }

    private fun switchPage(page: CollectionPage)
    {
        if(activePage == page)
        {
            return
        }

        activePage = page
        when(page)
        {
            CollectionPage.COMMANDERS ->
            {
                commanderPage.display = Display.FLEX
                storyPage.display = Display.NONE
            }
            CollectionPage.STORIES ->
            {
                commanderPage.display = Display.NONE
                storyPage.display = Display.FLEX
            }
        }
        updateTabStyles()
    }

    private fun setActivePage(page: CollectionPage)
    {
        commanderPage.display = if(page == CollectionPage.COMMANDERS) Display.FLEX else Display.NONE
        storyPage.display = if(page == CollectionPage.STORIES) Display.FLEX else Display.NONE
        activePage = page
        updateTabStyles()
    }

    private fun updateTabStyles()
    {
        commanderTabButton.removeCssClass("collection-tab-button-active")
        storyTabButton.removeCssClass("collection-tab-button-active")

        when(activePage)
        {
            CollectionPage.COMMANDERS -> commanderTabButton.addCssClass("collection-tab-button-active")
            CollectionPage.STORIES -> storyTabButton.addCssClass("collection-tab-button-active")
        }
    }
}

private class CommanderDetailWindow : SimplePanel(className = "login-widget-window")
{
    private val commanderName = h3("Commander") {
        marginTop = 0.px
        marginBottom = 4.px
        color = Color.name(Col.WHITE)
        justifySelf = JustifyItems.CENTER
    }

    private val commanderMeta = span("Type / Trait") {
        color = Color.name(Col.LIGHTGRAY)
        fontSize = 16.px
        display = Display.BLOCK
        marginBottom = 8.px
        justifySelf = JustifyItems.CENTER
    }

    private val commanderDescriptionHeader = p("Commander Description") {
        color = Color.name(Col.LIGHTBLUE)
        fontSize = 18.px
        fontWeight = FontWeight.BOLD
        marginBottom = 4.px
        justifySelf = JustifyItems.CENTER
    }

    private val commanderDescription = p("Description") {
        color = Color.name(Col.WHITE)
        lineHeight = 22.px
        marginBottom = 10.px
        textAlign = TextAlign.JUSTIFY
        justifySelf = JustifyItems.CENTER
    }

    private val empireDescriptionHeader = p("Territory Notes") {
        color = Color.name(Col.LIGHTBLUE)
        fontSize = 18.px
        fontWeight = FontWeight.BOLD
        marginBottom = 4.px
        justifySelf = JustifyItems.CENTER
    }

    private val empireDescription = p("Empire Notes") {
        color = Color.name(Col.LIGHTGRAY)
        lineHeight = 20.px
        marginTop = 0.px
        textAlign = TextAlign.JUSTIFY
        justifySelf = JustifyItems.CENTER
    }

    init
    {
        width = 700.px
        height = 600.px
        position = Position.FIXED
        top = 50.perc
        left = 50.perc
        marginTop = (-300).px
        marginLeft = (-350).px
        zIndex = 9100
        padding = 25.px
        border = Border(2.px, BorderStyle.SOLID, Color("rgba(94, 106, 220, 0.5)"))
        background = Background(Color("rgba(8, 10, 24, 0.95)"))
        boxShadow = BoxShadow(0.px, 25.px, 50.px, color = Color("rgba(0, 0, 0, 0.85)"))
        flexDirection = FlexDirection.COLUMN
        display = Display.NONE

        add(h2("Commander Details") {
            marginTop = 0.px
            marginBottom = 12.px
            color = Color.name(Col.CYAN)
            textAlign = TextAlign.CENTER
        })

        vPanel {
            width = 100.perc
            flexGrow = 1
            overflow = Overflow.AUTO
            spacing = 12
            add(commanderName)
            add(commanderMeta)
            add(commanderDescriptionHeader)
            add(commanderDescription)
            add(empireDescriptionHeader)
            add(empireDescription)
        }

        hPanel(
            justify = JustifyContent.CENTER,
            alignItems = AlignItems.CENTER
        ) {
            width = 100.perc
            button("CLOSE", icon = "fas fa-times", className = "btn btn-secondary-action") {
                onClick {
                    this@CommanderDetailWindow.hide()
                }
            }
        }
    }

    fun show(info: CommanderDisplayInfo)
    {
        commanderName.content = info.name
        commanderMeta.content = "Type: ${info.type.name} • Trait: ${info.trait.name}"
        commanderDescription.content = info.description
        empireDescription.content = info.empire
        display = Display.FLEX
        visible = true
    }
}

private class StoryDetailWindow : SimplePanel(className = "login-widget-window")
{
    private val storyTitle = h3("Story Title") {
        marginTop = 0.px
        marginBottom = 6.px
        color = Color.name(Col.WHITE)
    }

    private val storyDate = span("Created") {
        color = Color.name(Col.LIGHTGRAY)
        fontSize = 14.px
        marginBottom = 10.px
    }

    private val storyBody = p("Story body") {
        color = Color.name(Col.LIGHTGRAY)
        lineHeight = 22.px
        textAlign = TextAlign.JUSTIFY
    }

    init
    {
        width = 700.px
        height = 650.px
        position = Position.FIXED
        top = 50.perc
        left = 50.perc
        marginTop = (-325).px
        marginLeft = (-350).px
        zIndex = 9100
        padding = 25.px
        border = Border(2.px, BorderStyle.SOLID, Color("rgba(94, 106, 220, 0.5)"))
        background = Background(Color("rgba(8, 10, 24, 0.95)"))
        boxShadow = BoxShadow(0.px, 25.px, 50.px, color = Color("rgba(0, 0, 0, 0.85)"))
        flexDirection = FlexDirection.COLUMN
        display = Display.NONE

        add(h2("Story Reader") {
            marginTop = 0.px
            marginBottom = 12.px
            color = Color.name(Col.CYAN)
        })

        vPanel {
            width = 100.perc
            flexGrow = 1
            overflow = Overflow.AUTO
            spacing = 0
            add(storyTitle)
            add(storyDate)
            add(storyBody)
        }

        hPanel(
            spacing = 12,
            justify = JustifyContent.CENTER,
            alignItems = AlignItems.CENTER
        ) {
            width = 100.perc
            button("Download .txt", icon = "fas fa-download", className = "btn btn-secondary-action") {
                onClick {
                    downloadCurrentStory()
                }
            }
            button("CLOSE", icon = "fas fa-times", className = "btn btn-secondary-action") {
                onClick {
                    this@StoryDetailWindow.hide()
                }
            }
        }
    }

    private var currentStory: StoryEntry? = null

    fun show(story: StoryEntry)
    {
        currentStory = story
        storyTitle.content = story.title
        storyDate.content = story.dateCreated
        storyBody.content = story.body + "\n\n[Story load is stubbed until backend playback is available.]"
        display = Display.FLEX
        visible = true
    }

    private fun downloadCurrentStory()
    {
        val story = currentStory ?: return
        val safeName = story.title.replace("\\s+".toRegex(), "-").lowercase()
        val blob = Blob(arrayOf(story.body), BlobPropertyBag(type = "text/plain;charset=utf-8"))
        val url = URL.createObjectURL(blob)
        val anchor = (document.createElement("a") as HTMLAnchorElement).apply {
            href = url
            download = "$safeName.txt"
        }

        document.body?.appendChild(anchor)
        anchor.click()
        document.body?.removeChild(anchor)
        URL.revokeObjectURL(url)
    }
}
