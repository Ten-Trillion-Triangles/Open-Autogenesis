package ui.gameplay

import io.kvision.html.div
import io.kvision.html.span
import io.kvision.html.button
import io.kvision.html.Align
import io.kvision.panel.vPanel
import io.kvision.panel.hPanel
import io.kvision.utils.px
import io.kvision.utils.perc

import io.kvision.core.*
import io.kvision.window.Window
import kotlinx.browser.window
import kotlin.random.Random

/**
 * Window to track and display the status of active prompts.
 */
class PromptStatusWidget(private val demo: Boolean = false) : Window(className = "login-widget-window")
{
    private var playStatus: Container? = null
    private var answerStatus: Container? = null
    private var chatStatus: Container? = null
    private var openStatus: Container? = null

    init
    {
        // Window Configuration
        caption = "Agent Status"
        width = 500.px
        height = 400.px
        
        // Centering Title (GameHistoryWindow pattern)
        justifyItems = JustifyItems.CENTER
        captionContainer.justifyItems = JustifyItems.CENTER
        captionContainer.alignSelf = AlignItems.CENTER
        captionContainer.justifySelf = JustifyItems.CENTER
        captionContainer.align = Align.CENTER
        captionContainer.fontSize = 24.px
        
        // Add Close Button to Header
        val closeBtn = io.kvision.html.Button("", icon = "fas fa-times") {
            position = Position.ABSOLUTE
            right = 15.px
            top = 12.px // Align roughly with text
            color = Color.name(Col.WHITE)
            background = Background(color = Color("transparent"))
            border = Border(width = 0.px)
            fontSize = 20.px
            cursor = Cursor.POINTER
            onClick {
                this@PromptStatusWidget.hide()
            }
        }
        captionContainer.add(closeBtn)

        // Calculate position relative to NeuralLinkWindow (which is centered at 800x600)
        val browserWidth = window.innerWidth
        val browserHeight = window.innerHeight
        
        val nlWidth = 800
        val nlHeight = 600
        val nlLeft = (browserWidth - nlWidth) / 2
        val nlTop = (browserHeight - nlHeight) / 2

        position = Position.FIXED
        top = nlTop.px
        left = (nlLeft + nlWidth + 20).px 
        isDraggable = true
        isResizable = false
        zIndex = 200

        // Content
        vPanel(spacing = 20) { // Increased spacing
            padding = 20.px
            width = 100.perc
            alignItems = AlignItems.STRETCH // Stretch rows

            playStatus = createStatusRow("Play Agent")
            answerStatus = createStatusRow("Answer Agent")
            chatStatus = createStatusRow("Chat Agent")
            openStatus = createStatusRow("Open Agent")

            add(playStatus!!)
            add(answerStatus!!)
            add(chatStatus!!)
            add(openStatus!!)
            
            // Spacer
            div {
                flexGrow = 1
            }
            
            // Close Button
            button("CLOSE", icon = "fas fa-times", className = "btn btn-play") {
                width = 100.perc
                height = 50.px
                fontSize = 18.px
                fontWeight = FontWeight.BOLD
                color = Color.name(Col.WHITE)
                marginTop = 20.px
                onClick {
                    this@PromptStatusWidget.hide()
                }
            }
        }

        // Initially hidden
        hide()
        
        if(demo)
        {
             window.setInterval({
                 val usage = structs.AgentUsage(
                     runningPlayAgent = Random.nextBoolean(),
                     runningAnswerAgent = Random.nextBoolean(),
                     runningChatAgent = Random.nextBoolean(),
                     runningOpenAgent = Random.nextBoolean()
                 )
                 updateStatus(usage)
             }, 2000)
        }
    }

    private fun createStatusRow(label: String): Container
    {
        return hPanel(justify = JustifyContent.SPACEBETWEEN, alignItems = AlignItems.CENTER) {
            width = 100.perc
            padding = 10.px // More padding
            setStyle("border-bottom", "1px solid rgba(255, 255, 255, 0.2)")
            
            span(label) {
                fontWeight = FontWeight.BOLD
                fontSize = 18.px // Larger font
                color = Color.name(Col.WHITE)
            }

            span("Ready") {
                addCssClass("status-indicator")
                color = Color.name(Col.LIME)
                fontSize = 18.px // Larger font
                fontWeight = FontWeight.BOLD
            }
        }
    }

    fun updateStatus(usage: structs.AgentUsage)
    {
        updateRow(playStatus, usage.runningPlayAgent)
        updateRow(answerStatus, usage.runningAnswerAgent)
        updateRow(chatStatus, usage.runningChatAgent)
        updateRow(openStatus, usage.runningOpenAgent)
    }

    private fun updateRow(row: Container?, isRunning: Boolean)
    {
        row?.let { container ->
            // Assuming the second child is the status span
            val statusSpan = container.getChildren()[1] as? io.kvision.html.Span
            statusSpan?.let {
                if(isRunning)
                {
                    it.content = "Running..."
                    it.color = Color.name(Col.ORANGE)
                    it.addCssClass("blinking-text") 
                    it.setStyle("animation", "blink 1s infinite")
                }
                else
                {
                    it.content = "Ready"
                    it.color = Color.name(Col.LIME)
                    it.removeCssClass("blinking-text")
                    it.setStyle("animation", "none")
                }
            }
        }
    }
}
