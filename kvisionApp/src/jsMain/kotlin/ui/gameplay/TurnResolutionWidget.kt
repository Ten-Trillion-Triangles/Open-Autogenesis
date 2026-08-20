package ui.gameplay

import kotlinx.coroutines.*
import io.kvision.core.*
import io.kvision.html.*
import io.kvision.panel.*
import io.kvision.utils.perc
import io.kvision.utils.px
import kotlinx.coroutines.channels.Channel
import org.w3c.dom.HTMLElement
import structs.World
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import org.ttt.autogenesis.network.ActionIntent
import org.ttt.autogenesis.network.NemesisThreatAnnouncementData
import org.ttt.autogenesis.network.NemesisThreatKind
import org.ttt.autogenesis.network.ActiveTurnData
import org.ttt.autogenesis.network.TurnOrderAnnouncementData
import ui.gameplay.TurnOrderDemoProvider
import org.ttt.autogenesis.network.TurnOrderParticipant


/**
 * The primary UI controller for the "Turn Resolution" phase of the game.
 * 
 * This widget functions as a finite state machine, displaying a sequence of "Pages" (stack steps)
 * that correspond to the server-side turn progression (Player Action -> Intent -> Story -> Judgement -> Dispatch).
 *
 * **Key Responsibilities:**
 * - **State Management**: switching between start, waiting, action, and result screens.
 * - **Demo Automation**: In [demoMode], it can auto-advance steps to simulate a game flow without user input.
 * - **Interactivity Control**: It locks/unlocks the global command console via [onSetCommandInteractive] to prevent race conditions during processing.
 * - **Counter-Play**: It displays prompts when the player is targeted by an event and must respond.
 *
 * @param onSwitchToMap Callback to return the view to the world map (usually for inputting commands).
 * @param onSetCommandInteractive Callback to enable/disable the command console text input.
 * @param onPromptCommandEntry Callback to force focus the command entry field (e.g. for counter-play).
 * @param demoMode If true, the widget runs self-contained animations and transitions for testing/showcase.
 */
class TurnResolutionWidget(
    val onSwitchToMap: () -> Unit,
    val onSetCommandInteractive: (Boolean) -> Unit,
    private val onPromptCommandEntry: () -> Unit,
    private val onRequestShow: () -> Unit,
    private val onDemoUpdateScore: (Int) -> Unit = {},
    private val onDemoUpdateResources: (mil: String, dip: String, res: String, sum: String) -> Unit = { _, _, _, _ -> },
    private val onDemoUpdateWorld: (World) -> Unit = {},
    private val onCounterPlayModeChange: (Boolean) -> Unit = {},
    private val onCounterIgnoreCallback: () -> Unit = {},
    private val demoMode: Boolean = false
) : DockPanel()
{

    private val pageStack = StackPanel {
        width = 100.perc
        height = 100.perc
    }

    /**
     * Displays the turn order announcement page and schedules auto-advance.
     */
    fun showTurnOrderAnnouncement(data: TurnOrderAnnouncementData)
    {
        if(pageStack.activeIndex == NEMESIS_THREAT_PAGE_INDEX)
        {
            pendingTurnOrderAfterThreat = data
            Logger.debug(LogCategory.UI, "TurnResolutionWidget: Queued turn order announcement until nemesis threat page completes.")
            return
        }
        Logger.info(LogCategory.UI, "TurnResolutionWidget: Showing turn order announcement for Round ${data.roundNumber}")
        
        // Release map lock if we are showing turn order - this is a hard override
        isLockedOnMap = false
        worldUpdateJob?.cancel()
        
        cancelDemoTransition()
        cancelAnnouncementTimeout()
        pageStack.activeIndex = TURN_ORDER_PAGE_INDEX
        progressBar.setActive(0)
        progressBar.setInstruction("Round ${data.roundNumber} turn order")
        showCommandPromptBanner("Round ${data.roundNumber} starts shortly...")
        onSetCommandInteractive(false)
        onRequestShow()
        val localName = globals.World.localPlayer.name
        turnOrderAnnouncementPage.render(data, localName)
    }

    /**
     * Displays the nemesis threat announcement page and auto-advances to turn order when ready.
     */
    fun showNemesisThreatAnnouncement(data: NemesisThreatAnnouncementData)
    {
        Logger.info(LogCategory.UI, "TurnResolutionWidget: Showing nemesis threat announcement for Round ${data.roundNumber} (${data.kind})")
        cancelDemoTransition()
        cancelAnnouncementTimeout()
        pageStack.activeIndex = NEMESIS_THREAT_PAGE_INDEX
        progressBar.setActive(0)
        progressBar.setInstruction("Round ${data.roundNumber} nemesis threat")
        showCommandPromptBanner("Nemesis threat detected for Round ${data.roundNumber}")
        onSetCommandInteractive(false)
        onRequestShow()
        nemesisThreatPage.render(data)
        scheduleAnnouncementTimeout(5000L)
        {
            if(pageStack.activeIndex != NEMESIS_THREAT_PAGE_INDEX)
            {
                Logger.debug(LogCategory.UI, "TurnResolutionWidget: Nemesis threat timeout reached, but page index has changed to ${pageStack.activeIndex}. Aborting auto-advance.")
                return@scheduleAnnouncementTimeout
            }
            showCommandPromptBanner(null)
            val pendingTurnOrder = pendingTurnOrderAfterThreat
            pendingTurnOrderAfterThreat = null
            if(pendingTurnOrder != null)
            {
                showTurnOrderAnnouncement(pendingTurnOrder)
            }
            else
            {
                showStart()
            }
        }
    }

    /**
     * Updates the widget to reflect which actor currently has the live turn.
     *
     * @param data Active turn metadata emitted from the server.
     */
    fun showActiveTurn(data: ActiveTurnData)
    {
        Logger.info(LogCategory.UI, "TurnResolutionWidget: showActiveTurn actor=${data.actorName} round=${data.roundNumber} timer=${data.timerSeconds}.")
        
        // Ensure global state correctly reflects who is acting before command box is evaluated
        val localName = globals.World.localPlayer.name
        val isOurTurn = localName.equals(data.actorName, ignoreCase = true)
        globals.World.activeTurnActor = data.actorName
        globals.World.isPlayerTurn = isOurTurn

        // Retrieve and configure the TurnStartPage (index 0) dynamically
        val startPage = pageStack.getChildren()[0] as TurnStartPage
        startPage.updateForActor(data.actorName, isOurTurn)

        if(isOurTurn)
        {
            showCommandPromptBanner("It is your turn. You have ${data.timerSeconds}s to respond.")
        }
        else
        {
            showCommandPromptBanner(null)
        }
        progressBar.setInstruction("Active actor: ${data.actorName}")
    }

    fun triggerDemoNemesisThreat()
    {
        val demoThreat = NemesisThreatAnnouncementData(
            roundNumber = globals.World.worldData.roundNumber.coerceAtLeast(1),
            nemesisName = "General Vaskov",
            kind = NemesisThreatKind.REVIVAL,
            reason = "General Vaskov has returned to the battlefield."
        )
        showNemesisThreatAnnouncement(demoThreat)
    }
    private val counterPlayPage = CounterPlayPage(this)
    private val storyStreamingPage = StoryStreamingPage(this)
    private val nemesisThreatPage = NemesisThreatPage(this)
    private val progressBar = AgentProgressBar()
    private lateinit var commandPromptLabel: P
    private val commandPromptBanner = vPanel {
        width = 100.perc
        padding = 10.px
        background = Background(Color("rgba(16, 26, 44, 0.85)"))
        border = Border(1.px, BorderStyle.SOLID, Color.hex(0x3a4b73))
        borderRadius = 8.px
        marginBottom = 10.px
        visible = false

        commandPromptLabel = p("") {
            addCssClass("command-prompt-banner")
            color = Color.name(Col.LIGHTGREEN)
            fontSize = 20.px
            fontWeight = FontWeight.BOLD
            textAlign = TextAlign.CENTER
        }
    }
    private val waitingForOtherPlayerPage = WaitingForOtherPlayerPage(this)
    private val turnOrderAnnouncementPage = TurnOrderAnnouncementPage(this)
    private var pendingTurnOrderAfterThreat: TurnOrderAnnouncementData? = null
    private var demoJob: Job? = null
    private var scheduledDemoActiveIndex: Int? = null
    private var announcementJob: Job? = null
    private var isLockedOnMap: Boolean = false
    private var lastRequestedStep: Int = 0

    companion object {
        private const val WAITING_PAGE_INDEX = 9
        private const val TURN_ORDER_PAGE_INDEX = 10
        private const val NEMESIS_THREAT_PAGE_INDEX = 11
    }

    init {
        width = 100.perc
        height = 100.perc
        // Standard dark background to overlay map
        background = Background(Color.hex(0x1a1a2e))
        zIndex = 200

        val commandPromptBanner = vPanel {
            width = 100.perc
            padding = 10.px
            background = Background(Color("rgba(16, 26, 44, 0.85)"))
            border = Border(1.px, BorderStyle.SOLID, Color.hex(0x3a4b73))
            borderRadius = 8.px
            marginBottom = 10.px
            visible = false
        }
        val commandPromptLabel = p("") {
            addCssClass("command-prompt-banner")
            color = Color.name(Col.LIGHTGREEN)
            fontSize = 20.px
            fontWeight = FontWeight.BOLD
            textAlign = TextAlign.CENTER
        }
        commandPromptBanner.add(commandPromptLabel)

        center {
            width = 100.perc
            height = 100.perc
            add(vPanel(spacing = 0) {
                width = 100.perc
                height = 100.perc
                add(commandPromptBanner)
                add(pageStack)
            })
        }

        down {
            add(progressBar)
        }

        // Add Pages (Indices 0-8)
        pageStack.add(TurnStartPage(this)) // 0
        pageStack.add(PlayerActionPage(this)) // 1
        pageStack.add(TurnIntentPage(this)) // 2
        pageStack.add(storyStreamingPage) // 3
        pageStack.add(JudgementSummaryPage(this)) // 4
        pageStack.add(DispatchResourcesPage(this)) // 5
        pageStack.add(UpdateNpcsPage(this)) // 6
        pageStack.add(UpdateWorldPage(this)) // 7
        pageStack.add(counterPlayPage) // 8
        pageStack.add(waitingForOtherPlayerPage) // 9
        pageStack.add(turnOrderAnnouncementPage) // 10
        pageStack.add(nemesisThreatPage) // 11

        showStart()
        Logger.debug(LogCategory.UI, "TurnResolutionWidget INIT: demoMode is $demoMode")
    }

    /**
     * Resets progress and displays the start screen.
     */
    fun showStart()
    {
        Logger.debug(LogCategory.UI, "[TurnResolution] [DemoDebug] showStart() ENTER")
        pageStack.activeIndex = 0
        progressBar.setActive(0)
        onSetCommandInteractive(true) // Unlock so player can send commands
        cancelDemoTransition()
        cancelAnnouncementTimeout()
        progressBar.clearInstruction()
        showCommandPromptBanner(null)
        pendingTurnOrderAfterThreat = null
        Logger.debug(LogCategory.UI, "[TurnResolution] [DemoDebug] showStart() EXIT")
    }

    /**
     * Displays the waiting page with an optional custom instruction.
     *
     * @param message Message shown to the player.
     */
    /**
     * Displays the "Waiting for Other Player" page.
     * 
     * This locks the interface ([onSetCommandInteractive] = false) to prevent the user from issuing commands
     * while it is not their turn.
     *
     * @param message Optional custom message (e.g. "Waiting for Host..."). Defaults to a generic wait message.
     */
    fun showWaitingForOtherPlayerTurn(message: String? = null)
    {
        pageStack.activeIndex = WAITING_PAGE_INDEX
        progressBar.setActive(0)
        progressBar.setInstruction(message ?: "Waiting for another player to take their turn...")
        onSetCommandInteractive(false)
        cancelDemoTransition()
        cancelAnnouncementTimeout()
        showCommandPromptBanner(null)
    }

    /**
     * Indicates whether the widget runs in demo mode.
     *
     * @return True when automated transitions are enabled.
     */
    fun isDemoMode(): Boolean
    {
        return demoMode
    }

    /**
     * Returns the currently active page index in the stack.
     */
    fun getActiveStepIndex(): Int
    {
        return pageStack.activeIndex
    }

    /**
     * Displays the player action screen after a command is submitted.
     */
    fun showPlayerAction()
    {
        resetPlayerActionDisplay()
        Logger.debug(LogCategory.UI, "[TurnResolution] [DemoDebug] showPlayerAction() ENTER")
        pageStack.activeIndex = 1
        progressBar.setActive(1)
        progressBar.clearInstruction()
        showCommandPromptBanner(null)
        onSetCommandInteractive(false) // Lock during processing
        scheduleDemoTransition(2000)
        {
            Logger.debug(LogCategory.UI, "[TurnResolution] [DemoDebug] Transitioning from PlayerAction -> Intent")
            showIntent()
        }
        Logger.debug(LogCategory.UI, "[TurnResolution] [DemoDebug] showPlayerAction() EXIT")
    }

    /**
     * Displays the intent/planning screen.
     */
    /**
     * Displays the "Intent Analysis" page.
     * 
     * Shows the result of the [agent.builders.validateAction.buildTargetDetectorAgent], explaining
     * what the system thinks the player wants to do.
     * In Demo Mode, this auto-advances to [showStory] after 2 seconds.
     */
    fun showIntent()
    {
        Logger.debug(LogCategory.UI, "[TurnResolution] [DemoDebug] showIntent() ENTER")
        pageStack.activeIndex = 2
        progressBar.setActive(2)
        progressBar.clearInstruction()
        showCommandPromptBanner(null)
        onSetCommandInteractive(false) // Keep locked during processing
        // Auto-advance for demo purposes after delay
        scheduleDemoTransition(2000)
        {
            Logger.debug(LogCategory.UI, "[TurnResolution] [DemoDebug] Transitioning from Intent -> Story")
            showStory()
        }
        Logger.debug(LogCategory.UI, "[TurnResolution] [DemoDebug] showIntent() EXIT")
    }

    /**
     * Displays the story streaming screen.
     */
    fun showStory()
    {
        Logger.debug(LogCategory.UI, "[TurnResolution] [DemoDebug] showStory() ENTER")
        pageStack.activeIndex = 3
        progressBar.setActive(3)
        progressBar.clearInstruction()
        showCommandPromptBanner(null)
        onSetCommandInteractive(false) // Keep locked during processing
        
        // Prepare clean slate with animation
        Logger.debug(LogCategory.UI, "[TurnResolution] [DemoDebug] showStory calling prepareForNewStory")
        // Dynamic access to match revert
        val storyPage = pageStack.getChildren()[3] as StoryStreamingPage
        storyPage.prepareForNewStory()
        
        // Auto-advance for demo
        Logger.debug(LogCategory.UI, "[TurnResolution] [DemoDebug] showStory scheduling narrative simulation")
        scheduleDemoTransition(1000)
        {
            Logger.debug(LogCategory.UI, "[TurnResolution] [DemoDebug] Demo transition: Updating narrative")
            updateNarrative("The atmospheric processors hummed with an unnatural vibration, the sky turning a bruised purple...")
            delay(5000)
            updateNarrative("Sensors picked up a rapid displacement in the southern sector. Something was rewriting the local physics.")
            delay(4000)
            updateNarrative("The agent's decision was instantaneous: adapt or be erased. The counter-sequence began.")
            delay(4000)
            Logger.debug(LogCategory.UI, "[TurnResolution] [DemoDebug] Transitioning from Story -> Judgement")
            showJudgement()
        }
        Logger.debug(LogCategory.UI, "[TurnResolution] [DemoDebug] showStory() EXIT")
    }

    /**
     * Displays the judgement summary.
     */
    fun showJudgement()
    {
        Logger.debug(LogCategory.UI, "[TurnResolution] [DemoDebug] showJudgement() ENTER")
        pageStack.activeIndex = 4
        progressBar.setActive(4)
        progressBar.clearInstruction()
        showCommandPromptBanner(null)
        onSetCommandInteractive(false) // Keep locked during processing

        scheduleDemoTransition(2000)
        {
            Logger.debug(LogCategory.UI, "[TurnResolution] [DemoDebug] Simulating Judgement Result")
            updateJudgementResult(true, "VICTORY", "Operation Successful. Territory Secured.")
            delay(4000)
            Logger.debug(LogCategory.UI, "[TurnResolution] [DemoDebug] Transitioning from Judgement -> Dispatch")
            showDispatch()
        }
        Logger.debug(LogCategory.UI, "[TurnResolution] [DemoDebug] showJudgement() EXIT")
    }

    /**
     * Displays the dispatch step.
     */
    fun showDispatch()
    {
        Logger.debug(LogCategory.UI, "[TurnResolution] [DemoDebug] showDispatch() ENTER")
        pageStack.activeIndex = 5
        progressBar.setActive(5)
        progressBar.clearInstruction()
        showCommandPromptBanner(null)
        onSetCommandInteractive(false) // Keep locked during processing
        scheduleDemoTransition(1500)
        {
            if(demoMode)
            {
                 Logger.debug(LogCategory.UI, "[TurnResolution] [DemoDebug] Simulating Dispatch Data")
                 val dummyData = org.ttt.autogenesis.network.DispatchData(
                     usedAssets = listOf("Fuel", "Ammo"),
                     gainedAssets = listOf("Scrap", "Intel"),
                     lostAssets = emptyList(),
                     territoryGained = listOf("Sector 7"),
                     territoryLost = emptyList()
                 )
                 updateDispatchData(dummyData)
                 delay(3000)
            }
            Logger.debug(LogCategory.UI, "[TurnResolution] [DemoDebug] Transitioning from Dispatch -> UpdateNpcs")
            showUpdateNpcs()
        }
        Logger.debug(LogCategory.UI, "[TurnResolution] [DemoDebug] showDispatch() EXIT")
    }

    /**
     * Displays the NPC update step.
     */
    fun showUpdateNpcs()
    {
        Logger.debug(LogCategory.UI, "[TurnResolution] [DemoDebug] showUpdateNpcs() ENTER")
        pageStack.activeIndex = 6
        progressBar.setActive(6)
        progressBar.clearInstruction()
        showCommandPromptBanner(null)
        onSetCommandInteractive(false) // Keep locked during processing
        scheduleDemoTransition(1500)
        {
            Logger.debug(LogCategory.UI, "[TurnResolution] [DemoDebug] Transitioning from UpdateNpcs -> UpdateWorld")
            showUpdateWorld()
        }
        Logger.debug(LogCategory.UI, "[TurnResolution] [DemoDebug] showUpdateNpcs() EXIT")
    }

    /**
     * Shows a specific step based on index.
     *
     * @param index The step index to display.
     */
    fun showStep(index: Int)
    {
        Logger.debug(LogCategory.UI, "[TurnResolution] ShowStep: $index (Locked=$isLockedOnMap)")
        val prevStep = pageStack.activeIndex
        lastRequestedStep = index

        // Update progress bar regardless of lock
        progressBar.setActive(index)

        if (index == 7) {
            showUpdateWorld()
            return
        }

        if (isLockedOnMap) {
            Logger.debug(LogCategory.UI, "[TurnResolution] UI is LOCKED on map. Skipping view transition to step $index until lock expires.")
            return
        }

        // If stepping to Waiting (9) or Start (0), return to widget view
        if(index == WAITING_PAGE_INDEX || index == 0)
        {
            onRequestShow()
        }

        // If we are already on this page, only proceed with minor updates, skip resets
        val isRedundant = (prevStep == index)
        
        pageStack.activeIndex = index
        Logger.debug(LogCategory.UI, "[TurnResolution] showStep called with index: $index. Current activeIndex: ${pageStack.activeIndex} (Redundant: $isRedundant)")
        Logger.debug(LogCategory.UI, "[TurnResolution] STATE DUMP -> Index: $index, DemoMode: $demoMode, ParentVisible: ${this.visible}")
        
        if(index in 0..WAITING_PAGE_INDEX)
        {
            if(index == 1 && !isRedundant)
            {
                resetPlayerActionDisplay()
            }
            
            cancelDemoTransition()
            cancelAnnouncementTimeout()
            
            // If entering planning page, reset to default text
            if(index == 2 && !isRedundant)
            {
                val intentPage = pageStack.getChildren()[2] as? TurnIntentPage
                intentPage?.reset()
            }
            
            // If entering story page, ensure it is ready for new content
            if(index == 3 && !isRedundant)
            {
                // Revert to dynamic access to ensure we get the attached component
                val storyPage = pageStack.getChildren()[3] as StoryStreamingPage
                Logger.debug(LogCategory.UI, "[TurnResolution] showStep calling prepareForNewStory for index 3")
                storyPage.prepareForNewStory()
            }
            
            // If entering judgement page, reset to processing state (hide old results)
            if(index == 4 && !isRedundant)
            {
                Logger.debug(LogCategory.UI, "[TurnResolution] showStep calling reset() for JudgementSummaryPage (index 4)")
                val judgementPage = pageStack.getChildren()[4] as JudgementSummaryPage
                judgementPage.reset()
            }
            
            // Handle some specific locking/unlocking
            when(index)
            {
                0 -> onSetCommandInteractive(globals.World.isPlayerTurn)
                8 -> onSetCommandInteractive(false) // CounterPlay starts locked
                else -> onSetCommandInteractive(false)
            }

        }
    }

    /**
     * Updates the narrative text in the story page.
     *
     * @param text The narrative content to display.
     */
    /**
     * Updates the narrative text in the story page by appending the chunk.
     * Note: Does NOT wipe the screen. Use prepareForNewStory() for that.
     *
     * @param text The narrative content to display.
     */
    fun updateNarrative(text: String)
    {
        Logger.debug(LogCategory.UI, "[TurnResolution] updateNarrative CALLED: text.length=${text.length} pageStack.activeIndex=${pageStack.activeIndex}")
        // Ensure we are showing the Story page if text is arriving
        if (pageStack.activeIndex != 3) {
            Logger.debug(LogCategory.UI, "[TurnResolution] Received narrative while not on story page. Syncing to index 3.")
            pageStack.activeIndex = 3
            progressBar.setActive(3)
        }
        
        onRequestShow() // Ensure the widget is visible over the map
        
        val storyPage = pageStack.getChildren()[3] as StoryStreamingPage
        // Direct stream, no wiping
        storyPage.streamChunk(text)
    }

    /**
     * Wipes the story screen and prepares it for a new incoming stream.
     * Should be called before starting a new text generation sequence.
     */
    fun prepareForNewStory()
    {
        Logger.debug(LogCategory.UI, "[TurnResolution] ▶ Received explicit WIP signal (prepareForNewStory). Clearing screen.")
        val storyPage = pageStack.getChildren()[3] as StoryStreamingPage
        storyPage.prepareForNewStory()
    }

    /**
     * Completely overwrites the narrative with a glitch animation (used for refinements).
     *
     * @param text The new narrative content to replay.
     */
    fun overwriteNarrative(text: String)
    {
        val storyPage = pageStack.getChildren()[3] as StoryStreamingPage
        storyPage.wipeAndType(text)
    }

    /**
     * Updates the progress bar state and instruction.
     *
     * @param activeIndex The active step index.
     * @param instruction Optional instruction text to display.
     */
    fun setProgressBarState(activeIndex: Int, instruction: String? = null)
    {
        progressBar.setActive(activeIndex)
        progressBar.setInstruction(instruction)
    }

    private var worldUpdateJob: Job? = null

    /**
     * Displays the world update/transition screen.
     */
    fun showUpdateWorld()
    {
        if (pageStack.activeIndex == 7)
        {
            Logger.debug(LogCategory.UI, "[TurnResolution] showUpdateWorld() - Already at step 7, skipping redundant transition.")
            return
        }
        
        Logger.debug(LogCategory.UI, "[TurnResolution] showUpdateWorld() ENTER")
        
        pageStack.activeIndex = 7
        progressBar.setActive(7)
        progressBar.clearInstruction()
        onSetCommandInteractive(false) // Keep locked during processing
        Logger.debug(LogCategory.UI, "[TurnResolution] showUpdateWorld() EXIT")
    }

    /**
     * Synchronously switches to the map view for world update animations and applies a temporary lock.
     * This should be called when world data actually arrives or the explicit step signal is received.
     */
    fun syncMapForWorldUpdate()
    {
        if (isLockedOnMap) return
        
        Logger.debug(LogCategory.UI, "[TurnResolution] syncMapForWorldUpdate() - Switching to map and locking for 4s")
        isLockedOnMap = true
        
        // Ensure progress bar/page are synced to 7 if we aren't there yet
        showUpdateWorld()
        
        onSwitchToMap()

        // Use a coroutine to handle the map transition delay
        worldUpdateJob?.cancel()
        worldUpdateJob = GlobalScope.launch {
            if(demoMode)
            {
                Logger.debug(LogCategory.UI, "[TurnResolution] [DemoDebug] Simulating waiting for opponent")
                onDemoUpdateScore(3450)

                // Simulate World Update for Animation
                if(globals.World.worldData.mapTiles.isNotEmpty())
                {
                    Logger.debug(LogCategory.UI, "[TurnResolution] [DemoDebug] Modifying world data to trigger animation")
                    
                    globals.World.activeTurnActor = globals.World.localPlayer.name
                    val targetTile = globals.World.worldData.mapTiles.find { it.ruler != globals.World.localPlayer.name } 
                        ?: globals.World.worldData.mapTiles.first()
                    
                    targetTile.ruler = globals.World.localPlayer.name
                    onDemoUpdateWorld(globals.World.worldData)
                }
            }
            
            // Allow time for territory pulse animations (1.2s) and user observation
            delay(4000L) 
            isLockedOnMap = false
            
            // Sync back to whatever the latest requested step was
            Logger.debug(LogCategory.UI, "[TurnResolution] Map lock released. Syncing to last requested step: $lastRequestedStep")
            showStep(lastRequestedStep)
            
            worldUpdateJob = null
            Logger.debug(LogCategory.UI, "[TurnResolution] UpdateWorld transition complete.")
        }
    }

    /**
     * Displays the incoming event counter-play screen.
     */
    /**
     * Displays the incoming event counter-play screen.
     * 
     * @param attackerName The name of the actor initiating the event.
     * @param actionDescription The description of the action being taken.
     * @param actionIntent Whether the action is Hostile or Friendly.
     */
    fun showCounterPlay(attackerName: String, actionDescription: String, actionIntent: ActionIntent = ActionIntent.Hostile)
    {
        Logger.info(LogCategory.UI, "TurnResolutionWidget: showCounterPlay attacker=$attackerName intent=$actionIntent")
        pageStack.activeIndex = 8
        progressBar.setActive(8)
        onSetCommandInteractive(false) // Keep locked initially, CounterPlayPage will unlock on "Respond"
        progressBar.setInstruction("Incoming event! Click Respond to counter in the command console.")
        cancelDemoTransition()
        showCommandPromptBanner("An incoming event is forcing you to issue a counter command.")
        counterPlayPage.setEventDetails(attackerName, actionDescription, actionIntent)
        counterPlayPage.resetActions()
    }

    /**
     * Prompts the player to type their counter command.
     */
    fun promptCommandResponse()
    {
        progressBar.setInstruction("Enter your counter command into the command console below.")
        onSetCommandInteractive(true)
        onPromptCommandEntry()
        showCommandPromptBanner("Type your counter command now.")
    }

    /**
     * Handles the player choosing to respond to an event.
     */
    fun onCounterRespond()
    {
        counterPlayPage.showRespondInstruction("Enter your counter command into the console below.")
        promptCommandResponse()
    }

    /**
     * Called when the player clicks "Ignore" on the counter-play page.
     * Notifies the server and proceeds to the intent phase.
     */
    fun onCounterIgnore()
    {
        Logger.info(LogCategory.UI, "TurnResolutionWidget: Player ignored counter-play")
        onCounterPlayModeChange(false)
        onCounterIgnoreCallback()
        showIntent()
    }
    
    /**
     * Updates the text displayed on the Intent/Planning page.
     * 
     * @param text The new intent description to display.
     */
    fun updateIntentText(text: String)
    {
        Logger.debug(LogCategory.UI, "[TurnResolution] updateIntentText: '$text'")
        // Safety check index 2 (TurnIntentPage)
        if(pageStack.getChildren().size > 2)
        {
            val page = pageStack.getChildren()[2] as? TurnIntentPage
            page?.reset()
            page?.setIntentText(text)
        }
    }

    /**
     * Updates the text displayed on the Player Action page.
     * 
     * @param text The new action description to display.
     */
    fun updatePlayerActionText(text: String)
    {
        Logger.debug(LogCategory.UI, "[TurnResolution] updatePlayerActionText: '$text'")
        // Safety check index 1 (PlayerActionPage)
        if(pageStack.getChildren().size > 1)
        {
            val page = pageStack.getChildren()[1] as? PlayerActionPage
            page?.setActionText(text)
        }
    }

    private fun resetPlayerActionDisplay()
    {
        if(pageStack.getChildren().size > 1)
        {
            val page = pageStack.getChildren()[1] as? PlayerActionPage
            page?.setActionText("> PROCESSING COMMAND...")
        }
    }

    /**
     * Updates the Dispatch page with logistical results.
     */
    fun updateDispatchData(data: org.ttt.autogenesis.network.DispatchData)
    {
        Logger.debug(LogCategory.UI, "[TurnResolution] updateDispatchData invoked.")
        // Safety check index 5 (DispatchResourcesPage)
        if(pageStack.getChildren().size > 5)
        {
            val page = pageStack.getChildren()[5] as? DispatchResourcesPage
            page?.updateData(data)
        }
    }

    /**
     * Cancels any pending demo transition.
     */
    /**
     * Cancels any pending demo transition.
     */
    private fun cancelDemoTransition()
    {
        if(demoJob != null)
        {
            Logger.debug(LogCategory.UI, "[TurnResolution] [DemoDebug] Cancelling pending demo transition job: $demoJob")
            demoJob?.cancel()
            demoJob = null
        }
        else
        {
            Logger.debug(LogCategory.UI, "[TurnResolution] [DemoDebug] cancelDemoTransition called, but no job to cancel")
        }
    }

    /**
     * Schedules a demo transition after a delay.
     *
     * @param delayMillis Delay in milliseconds before executing the action.
     * @param action The action to execute after the delay.
     */
    private fun scheduleDemoTransition(delayMillis: Long, action: suspend () -> Unit)
    {
        Logger.debug(LogCategory.UI, "[TurnResolution] [DemoDebug] scheduleDemoTransition requested. Delay=$delayMillis, DemoMode=$demoMode")
        if(!demoMode)
        {
            Logger.debug(LogCategory.UI, "[TurnResolution] [DemoDebug] scheduleDemoTransition ABORTED: demoMode is false")
            return
        }

        if(demoJob != null)
        {
            Logger.debug(LogCategory.UI, "[TurnResolution] [DemoDebug] Existing demo job found, cancelling before scheduling new one.")
            demoJob?.cancel()
        }

        // BUG-9 FIX: Capture the activeIndex at scheduling time so we can detect
        // if the page has changed when the lambda fires. This prevents a stale
        // transition (e.g., showUpdateWorld() from UpdateNpcsPage) from firing
        // after the game has already moved to a different page (Start, Waiting, etc.).
        scheduledDemoActiveIndex = pageStack.activeIndex
        Logger.debug(LogCategory.UI, "[TurnResolution] [DemoDebug] Captured activeIndex=$scheduledDemoActiveIndex for scheduled demo transition.")

        demoJob = GlobalScope.launch {
            Logger.debug(LogCategory.UI, "[TurnResolution] [DemoDebug] Demo job STARTED. Waiting ${delayMillis}ms...")
            delay(delayMillis)
            // BUG-9 FIX: Guard — abort if activeIndex changed since scheduling.
            // This handles the race where showStep() was called during the delay,
            // moving to a different page. The lambda must not fire in that case.
            val capturedIndex = scheduledDemoActiveIndex
            if (capturedIndex != null && pageStack.activeIndex != capturedIndex) {
                Logger.debug(LogCategory.UI, "[TurnResolution] [DemoDebug] Demo transition ABORTED: activeIndex changed from $capturedIndex to ${pageStack.activeIndex} during delay.")
                scheduledDemoActiveIndex = null
                return@launch
            }
            Logger.debug(LogCategory.UI, "[TurnResolution] [DemoDebug] Demo job wait COMPLETE. Executing action...")
            action()
            scheduledDemoActiveIndex = null
            Logger.debug(LogCategory.UI, "[TurnResolution] [DemoDebug] Demo job action FINISHED.")
        }
    }

    private fun cancelAnnouncementTimeout()
    {
        if(announcementJob != null)
        {
            Logger.debug(LogCategory.UI, "[TurnResolution] Cancelling pending announcement job: $announcementJob")
            announcementJob?.cancel()
            announcementJob = null
        }
        else
        {
            Logger.debug(LogCategory.UI, "[TurnResolution] cancelAnnouncementTimeout called, but no job exists")
        }
    }

    private fun scheduleAnnouncementTimeout(delayMillis: Long, action: suspend () -> Unit)
    {
        Logger.debug(LogCategory.UI, "[TurnResolution] scheduleAnnouncementTimeout requested. Delay=$delayMillis")
        if(announcementJob != null)
        {
            Logger.debug(LogCategory.UI, "[TurnResolution] Existing announcement job found, cancelling before scheduling new one.")
            announcementJob?.cancel()
        }

        announcementJob = GlobalScope.launch {
            delay(delayMillis)
            Logger.debug(LogCategory.UI, "[TurnResolution] Announcement delay complete. Executing action.")
            announcementJob = null
            action()
        }
    }

    /**
     * Shows or hides the command prompt banner.
     *
     * @param text The text to display, or null to hide the banner.
     */
    private fun showCommandPromptBanner(text: String?)
    {
        if(text.isNullOrBlank())
        {
            commandPromptBanner.visible = false
        }
        else
        {
            commandPromptLabel.content = text
            commandPromptBanner.visible = true
        }
    }

    // --- Sub-Pages ---

    /**
     * Turn start page that allows the player to begin their turn or simulate events.
     *
     * @param parentWidget Reference to the parent [TurnResolutionWidget].
     */
    class TurnStartPage(private val parentWidget: TurnResolutionWidget) : VPanel(alignItems = AlignItems.CENTER, justify = JustifyContent.CENTER)
    {
        private val titleLabel = h1("Your Turn To Act") {
            color = Color.name(Col.WHITE)
            fontSize = 48.px
            textShadow = TextShadow(0.px, 0.px, 10.px, Color.name(Col.CYAN))
        }

        private val subtitleLabel = p("Review the map and issue your commands.") {
            color = Color.name(Col.LIGHTGRAY)
            fontSize = 18.px
        }

        init {
            width = 100.perc
            height = 100.perc
            spacing = 20

            add(titleLabel)
            add(subtitleLabel)

            button("Go To Map", className = "btn btn-play") {
                width = 200.px
                height = 60.px
                fontSize = 24.px
                onClick {
                    // Switch to map so player can input commands
                    parentWidget.onSwitchToMap()
                }
            }

            if(parentWidget.isDemoMode())
            {
                button("Simulate Nemesis Threat", className = "btn btn-secondary") {
                    width = 220.px
                    height = 44.px
                    fontSize = 16.px
                    onClick {
                        parentWidget.triggerDemoNemesisThreat()
                    }
                }

                button("Demo Narrative Animation", className = "btn btn-secondary") {
                    width = 220.px
                    height = 44.px
                    fontSize = 16.px
                    onClick {
                        parentWidget.showStory()
                    }
                }

                button("Simulate Incoming Event", className = "btn btn-secondary") {
                    width = 220.px
                    height = 44.px
                    fontSize = 16.px
                    onClick {
                        parentWidget.showCounterPlay("Warlord Ghengis Khan", "Mobilizing forces to seize the Northern Oil Fields.", ActionIntent.Hostile)
                    }
                }

                button("Simulate Friendly Proposal", className = "btn btn-secondary") {
                    width = 220.px
                    height = 44.px
                    fontSize = 16.px
                    onClick {
                        parentWidget.showCounterPlay("Ambassador Chen", "Proposing a trade alliance for mutual resource sharing.", ActionIntent.Friendly)
                    }
                }
            }
        }

        fun updateForActor(actorName: String, isLocalPlayer: Boolean)
        {
            if(isLocalPlayer)
            {
                titleLabel.content = "Your Turn To Act"
                titleLabel.color = Color.name(Col.WHITE)
                titleLabel.textShadow = TextShadow(0.px, 0.px, 10.px, Color.name(Col.CYAN))
                subtitleLabel.content = "Review the map and issue your commands."
            }
            else
            {
                titleLabel.content = "Awaiting $actorName"
                titleLabel.color = Color.name(Col.LIGHTGREEN)
                titleLabel.textShadow = TextShadow(0.px, 0.px, 12.px, Color.hex(0x3fc5ff))
                subtitleLabel.content = "The game is waiting on $actorName to finish their move. Sit tight or use the time to review the battlefield."
            }
        }
    }

    /**
     * Waiting page displayed when another player is taking their turn.
     *
     * @param parentWidget Reference to the parent [TurnResolutionWidget].
     */
    class WaitingForOtherPlayerPage(private val parentWidget: TurnResolutionWidget) : VPanel(alignItems = AlignItems.CENTER, justify = JustifyContent.CENTER)
    {
        init {
            width = 100.perc
            height = 100.perc
            spacing = 24

            h1("Awaiting Another Player") {
                color = Color.name(Col.LIGHTGREEN)
                fontSize = 44.px
                textShadow = TextShadow(0.px, 0.px, 12.px, Color.hex(0x3fc5ff))
            }

            icon("fas fa-hourglass-half") {
                fontSize = 70.px
                color = Color.hex(0x3fc5ff)
            }

            p("The game is waiting on the other player to finish their move. Sit tight or use the time to review the battlefield before the next action.") {
                width = 70.perc
                textAlign = TextAlign.CENTER
                color = Color.name(Col.LIGHTGRAY)
                fontSize = 18.px
            }

            p("You can still view stats, resources, and the world map while you wait.") {
                color = Color.name(Col.GRAY)
                fontSize = 16.px
                textAlign = TextAlign.CENTER
            }

            hPanel(spacing = 0) {
                button("Return To Map") {
                    width = 240.px
                    addCssClass("waiting-return-button")
                    onClick {
                        parentWidget.onSwitchToMap()
                    }
                }
            }
        }
    }

    /**
     * Player action page showing the command being executed.
     *
     * @param parentWidget Reference to the parent [TurnResolutionWidget].
     */
    class PlayerActionPage(private val parentWidget: TurnResolutionWidget) : VPanel(alignItems = AlignItems.CENTER, justify = JustifyContent.CENTER)
    {
        private val actionLabel = p("") {
             addCssClass("shimmer-text")
             fontSize = 24.px
             fontFamily = "Courier New"
             fontWeight = FontWeight.BOLD
        }

        init {
            width = 100.perc
            height = 100.perc
            spacing = 30
            
            h2("Executing Player Command...") {
                addCssClass("shimmer-text")
            }
            
            icon("fas fa-terminal") {
                fontSize = 60.px
                color = Color.name(Col.WHITE)
            }
            
            add(actionLabel)
            
            // Set default text based on mode
            if(parentWidget.isDemoMode())
            {
                 setActionText("> ATTACK NORTHERN SECTOR")
                 actionLabel.color = Color.name(Col.LIME)
                 actionLabel.addCssClass("blink-text")
            }
            else
            {
                 setActionText("> PROCESSING COMMAND...")
            }
        }
        
        fun setActionText(text: String)
        {
            actionLabel.content = text
        }
    }

    /**
     * Turn intent page showing agent planning phase.
     *
     * @param parentWidget Reference to the parent [TurnResolutionWidget].
     */
    class TurnIntentPage(private val parentWidget: TurnResolutionWidget) : VPanel(alignItems = AlignItems.CENTER, justify = JustifyContent.CENTER)
    {
        private val intentLabel = p("Analyzing world state and formulating strategy.") {
            color = Color.name(Col.GRAY)
        }

        init {
            width = 100.perc
            height = 100.perc
            spacing = 30

            h2("Agent Planning...") {
                addCssClass("shimmer-text")
            }

            icon("fas fa-cog fa-spin") {
                fontSize = 80.px
                color = Color.name(Col.WHITE)
            }
            
            add(intentLabel)
        }

        fun setIntentText(text: String)
        {
            intentLabel.content = text
        }

        fun reset()
        {
            intentLabel.content = "Analyzing world state and formulating strategy."
        }
    }

    /**
     * Story streaming page that displays narrative text with a typing effect.
     *
     * @param parentWidget Reference to the parent [TurnResolutionWidget].
     */
    class StoryStreamingPage(private val parentWidget: TurnResolutionWidget) : VPanel(alignItems = AlignItems.CENTER, justify = JustifyContent.CENTER)
    {
        private lateinit var storyLabel: P
        private lateinit var storyContainer: Div
        // [Bug fix — writing stream] The typing loop is the ONLY consumer of
        // [activeText] — if its scope is cancelled the narrative buffer fills
        // silently and nothing displays (see browser-2026-06-16-144809.log
        // around 18:58:22 where opening SettingsWidget fired afterDestroy and
        // killed the loop, leaving 382 updateNarrative CALLED entries with
        // zero typing-loop-progress logs afterwards). Hold the scope as a
        // `var` so we can re-create it if it has been cancelled by a legacy
        // code path, and DO NOT cancel it in [afterDestroy] below.
        private var streamingScope = MainScope()
        // Track the typing loop's job so re-attaching this widget instance
        // (KVision may re-fire init on detach/reattach in some flows) does
        // not double-launch the loop and race the prior one on the same
        // fields. The loop is the sole consumer of [activeText] /
        // [displayedLength]; only one may run at a time.
        private var typingLoopJob: Job? = null
        private val activeText = StringBuilder()
        private var displayedLength = 0
        private var isWiping = false

        private var wipeJob: Job? = null

        /**
         * KVision lifecycle hook. Fires when the widget is removed from the
         * DOM. We deliberately do NOT cancel [streamingScope] here — the
         * typing loop launched in [init] is the only consumer of the
         * narrative buffer ([activeText]) and must survive the widget being
         * re-attached to a different parent (KVision can re-fire
         * [afterDestroy] when sibling widgets like [ui.gameplay.SettingsWidget]
         * toggle their `display` between NONE and FLEX — this used to
         * permanently kill the writing stream from turn 2 onwards). The
         * per-glitch [wipeJob] is the only coroutine we DO cancel, because
         * its job is to play a short animation in this widget's lifetime.
         */
        override fun afterDestroy()
        {
            Logger.debug(LogCategory.UI, "[StoryStreaming] afterDestroy called - cancelling wipeJob only (preserving typing loop)")
            wipeJob?.cancel()
            wipeJob = null
            super.afterDestroy()
        }

            init {
                width = 100.perc
                height = 100.perc
                padding = 50.px
            
            storyContainer = div {
                width = 80.perc
                height = 360.px
                maxHeight = 360.px
                background = Background(Color.hex(0x111111))
                border = Border(1.px, BorderStyle.SOLID, Color.hex(0x444444))
                padding = 20.px
                overflowY = Overflow.AUTO
                overflowX = Overflow.HIDDEN
                addCssClass("story-streaming-container")
                
                this@StoryStreamingPage.storyLabel = p("") {
                    color = Color.name(Col.LIGHTGREEN)
                    fontFamily = "Courier New"
                    fontSize = 18.px
                    maxWidth = 100.perc
                    wordBreak = WordBreak.BREAKALL
                    whiteSpace = WhiteSpace.PREWRAP
                    addCssClass("typing-cursor")
                }
            }
            
            // [Bug fix — writing stream] Robust typing loop to update
            // display from buffer. Guarded so re-attaching the same
            // widget instance (KVision may re-fire init on detach/reattach)
            // does not start a second loop racing the first on the same
            // [activeText] / [displayedLength] fields. If a prior version
            // of the code cancelled [streamingScope] in afterDestroy, the
            // scope here is dead — replace it with a fresh one so the
            // session recovers instead of going silently dead.
            if (!streamingScope.isActive) {
                Logger.warn(
                    LogCategory.UI,
                    "[StoryStreaming] init: streamingScope was inactive (cancelled by a legacy path); creating a fresh scope so the typing loop can run"
                )
                streamingScope = MainScope()
            }
            if (typingLoopJob?.isActive != true) {
                typingLoopJob = streamingScope.launch {
                    Logger.debug(LogCategory.UI, "[StoryStreaming] ═══ Typing loop started ═══")
                while(isActive)
                {
                    if(!isWiping && displayedLength < activeText.length)
                    {
                        // Dynamic Speed: The more we are lagging, the faster we type.
                        val lag = activeText.length - displayedLength
                        
                        // Calculate increment: 1 char minimum, up to 50 chars if lagging significantly
                        // This allows us to clear a 10k buffer in ~6 seconds (200 ticks * 50 chars)
                        val increment = when {
                            lag > 500 -> 50
                            lag > 200 -> 20
                            lag > 100 -> 10
                            lag > 50 -> 5
                            lag > 20 -> 2
                            else -> 1
                        }
                        
                        displayedLength = (displayedLength + increment).coerceAtMost(activeText.length)
                        storyLabel.content = activeText.substring(0, displayedLength)
                        scrollToBottom()
                        
                        // [Diagnostic] Sample the typing loop every ~200 chars so a future
                        // regression where the loop silently stops or skips the DOM update
                        // shows up in the log immediately. One modulo per iteration is
                        // cheap; left in permanently while we chase the streaming regression.
                        if (displayedLength >= 200 && displayedLength % 200 < increment) {
                            Logger.debug(LogCategory.UI, "[StoryStreaming] typing loop progressed: displayedLength=$displayedLength / activeText.length=${activeText.length} lag=$lag")
                        }
                        // Slightly faster tick when lagging
                        val baseDelay = if (lag > 100) 15L else 30L
                        delay(baseDelay)
                    }
                    else
                    {
                        if(isWiping)
                        {
                             delay(200) // Wait longer when wiping
                        }
                        else
                        {
                            delay(100) // Idle wait when caught up
                        }
                    }
                }
            }
        }
    }

        /**
         * Prepares the view for a new story sequence by wiping old text and playing an intro animation.
         * This handles the "glitch" transition between turns.
         */
        fun prepareForNewStory()
        {
            Logger.debug(LogCategory.UI, "[StoryStreaming] ▶▶▶ prepareForNewStory() CALLED ◀◀◀")
            // console.log("[StoryStreaming] STATE BEFORE: isWiping=$isWiping, activeText.length=${activeText.length}, displayedLength=$displayedLength")
            
            // Cancel any previous wipe job to prevent race conditions
            Logger.debug(LogCategory.UI, "[StoryStreaming] Cancelling previous wipeJob: ${if(wipeJob != null) "exists" else "null"}")
            wipeJob?.cancel()
            
            isWiping = true
            Logger.debug(LogCategory.UI, "[StoryStreaming] ⚠️ isWiping=TRUE ⚠️")
            Logger.debug(LogCategory.UI, "[StoryStreaming] Clearing activeText buffer (was ${activeText.length} chars)")
            activeText.clear()
            displayedLength = 0
            Logger.debug(LogCategory.UI, "[StoryStreaming] Reset displayedLength=0")
            
            wipeJob = streamingScope.launch {
                try {
                    Logger.debug(LogCategory.UI, "[StoryStreaming] 🔵 Wipe coroutine STARTED")
                    Logger.debug(LogCategory.UI, "[StoryStreaming] Current storyLabel.content: '${storyLabel.content?.take(30)}...' (${storyLabel.content?.length ?: 0} chars)")
                    
                    if(storyLabel.content?.isNotEmpty() == true)
                    {
                        Logger.debug(LogCategory.UI, "[StoryStreaming] Content present (${storyLabel.content?.length} chars), adding glitch-wipe CSS")
                        storyLabel.addCssClass("glitch-wipe")
                        Logger.debug(LogCategory.UI, "[StoryStreaming] Waiting 400ms for glitch-wipe animation...")
                        delay(400)
                        Logger.debug(LogCategory.UI, "[StoryStreaming] Glitch-wipe animation completed")
                    }
                    else
                    {
                        Logger.debug(LogCategory.UI, "[StoryStreaming] No content to wipe, skipping animation")
                    }
                    
                    Logger.debug(LogCategory.UI, "[StoryStreaming] Clearing storyLabel.content")
                    storyLabel.content = ""
                    storyLabel.removeCssClass("glitch-wipe")
                    // Reset visibility/opacity that the animation set to hidden.
                    // KVision's `visible` only toggles the HTML5 `hidden` class, not
                    // CSS `visibility: hidden` -- the glitch-wipe keyframes end at
                    // `visibility: hidden` with `forwards` fill mode freezing that final
                    // state, so removing the class alone is not enough to make the text
                    // visible again. Explicitly clear it via the underlying DOM element.
                    storyLabel.opacity = 1.0
                    storyLabel.visible = true
                    storyLabel.getElement()?.let { (it as? HTMLElement)?.style?.let { s -> s.visibility = "visible"; s.opacity = "1" } }
                    Logger.debug(LogCategory.UI, "[StoryStreaming] ✅ Content CLEARED, removed glitch-wipe CSS, reset visibility (CSS visibility explicitly cleared)")

                    // Intro flicker
                    Logger.debug(LogCategory.UI, "[StoryStreaming] Adding glitch-text CSS for intro flicker")
                    storyLabel.addCssClass("glitch-text")
                    
                    Logger.debug(LogCategory.UI, "[StoryStreaming] Waiting 200ms for intro flicker...")
                    delay(200)
                    storyLabel.removeCssClass("glitch-text")
                    storyContainer.getElement()?.let { el ->
                        (el as? HTMLElement)?.scrollTop = 0.0
                    }
                    Logger.debug(LogCategory.UI, "[StoryStreaming] 🔵 Intro flicker COMPLETE, wipe coroutine DONE")
                } finally {
                    isWiping = false
                    Logger.debug(LogCategory.UI, "[StoryStreaming] ✅ isWiping=FALSE (finally reset)")
                }
            }
        }

        /**
         * Streams a chunk of text to the buffer.
         */
        fun streamChunk(text: String)
        {
            // console.log("[StoryStreaming] ▶▶▶ streamChunk() CALLED ◀◀◀")
            // console.log("[StoryStreaming] receiving text length: ${text.length}")
            
            activeText.append(text)
        }

        /**
         * Clears the current story and resets the view.
         */
        fun clear()
        {
            Logger.debug(LogCategory.UI, "[StoryStreaming] ▶▶▶ clear() CALLED ◀◀◀")
            Logger.debug(LogCategory.UI, "[StoryStreaming] STATE BEFORE: activeText.length=${activeText.length}, displayedLength=$displayedLength")
            storyLabel.content = ""
            activeText.clear()
            displayedLength = 0
            // console.log("[StoryStreaming] ✅ ALL CLEARED: buffer, display, displayedLength all reset to 0")
        }

        /**
         * Wipes and starts typing new text (simulated by clearing and appending).
         */
        fun wipeAndType(text: String)
        {
            Logger.debug(LogCategory.UI, "[StoryStreaming] ▶▶▶ wipeAndType() CALLED ◀◀◀")
            Logger.debug(LogCategory.UI, "[StoryStreaming] Incoming text: '${text.take(50)}...' (${text.length} chars)")
            streamingScope.launch {
                isWiping = true
                try {
                    Logger.debug(LogCategory.UI, "[StoryStreaming] 🔵 wipeAndType coroutine STARTED")
                    Logger.debug(LogCategory.UI, "[StoryStreaming] Adding glitch-wipe CSS")
                    storyLabel.addCssClass("glitch-wipe")
                    Logger.debug(LogCategory.UI, "[StoryStreaming] Waiting 400ms for wipe animation...")
                    delay(400)
                    Logger.debug(LogCategory.UI, "[StoryStreaming] Wipe animation complete")
                    
                    // Hard reset
                    Logger.debug(LogCategory.UI, "[StoryStreaming] Calling clear()")
                    clear()
                    
                    Logger.debug(LogCategory.UI, "[StoryStreaming] Removing glitch-wipe, adding glitch-text CSS")
                    storyLabel.removeCssClass("glitch-wipe")
                    storyLabel.addCssClass("glitch-text")
                    
                    // Start streaming new text
                    Logger.debug(LogCategory.UI, "[StoryStreaming] Calling streamChunk() with new text")
                    streamChunk(text)
                    
                    Logger.debug(LogCategory.UI, "[StoryStreaming] Waiting 200ms for intro flicker...")
                    delay(200)
                    storyLabel.removeCssClass("glitch-text")
                    Logger.debug(LogCategory.UI, "[StoryStreaming] 🔵 wipeAndType coroutine COMPLETE")
                } finally {
                    isWiping = false
                    Logger.debug(LogCategory.UI, "[StoryStreaming] ✅ isWiping=FALSE (wipeAndType finally reset)")
                }
            }
        }

        private fun scrollToBottom()
        {
            storyContainer.getElement()?.let { el ->
                val htmlElement = el as? HTMLElement
                if(htmlElement != null)
                {
                    htmlElement.scrollTop = htmlElement.scrollHeight.toDouble()
                }
            }
        }
     }


    /**
     * Judgement summary page showing the outcome of the turn.
     *
     * @param parentWidget Reference to the parent [TurnResolutionWidget].
     */
    /**
     * Updates the judgement page with result data.
     */
    fun updateJudgementResult(isSuccess: Boolean, header: String, subtext: String)
    {
         Logger.debug(LogCategory.UI, "[TurnResolution] ▶ updateJudgementResult CALLED. Header='$header', SubtextUser='${subtext.take(20)}...'")
         Logger.debug(LogCategory.UI, "[TurnResolution] Current activeIndex before check: ${pageStack.activeIndex}")
         // Ensure we are on the page
         if(pageStack.activeIndex != 4)
         {
             Logger.debug(LogCategory.UI, "[TurnResolution] CORRECTING Page Index: switch to 4 (Judgement)")
             showJudgement()
         }
         
         try
         {
             val judgementPage = pageStack.getChildren()[4] as JudgementSummaryPage
             Logger.debug(LogCategory.UI, "[TurnResolution] Retrieved JudgementSummaryPage. updating result...")
             judgementPage.updateResult(isSuccess, header, subtext)
         } catch(e: Exception)
         {
             Logger.error(LogCategory.UI, "[TurnResolution] Failed to update judgement page: ${e.message}")
         }
    }

    class JudgementSummaryPage(private val parentWidget: TurnResolutionWidget) : VPanel(alignItems = AlignItems.CENTER, justify = JustifyContent.CENTER)
    {
        private val resultTitle = p("Turn Resolution Complete") {
             color = Color.name(Col.WHITE)
             fontWeight = FontWeight.BOLD
             fontSize = 20.px
        }
        private val resultSubtext = p("") {
             color = Color.name(Col.LIGHTGRAY)
             fontSize = 16.px
             textAlign = TextAlign.CENTER
        }
        
        // Processing State Panel
        private val processingPanel = VPanel(alignItems = AlignItems.CENTER, justify = JustifyContent.CENTER) {
            spacing = 20
            visible = false
            width = 100.perc
            
            h2("Evaluating Outcomes...") {
                color = Color.name(Col.CYAN)
            }
            icon("fas fa-spinner") {
                fontSize = 50.px
                color = Color.name(Col.WHITE)
                addCssClass("fa-spin")
            }
            p("The AI is judging the consequences of actions.") {
                color = Color.name(Col.GRAY)
            }
        }

        // Result State Panel
        private val resultPanel = VPanel(alignItems = AlignItems.CENTER, justify = JustifyContent.CENTER) {
            width = 100.perc
            visible = false // Hidden by default until results arrive
            spacing = 20
        }
    
        init {
            width = 100.perc
            height = 100.perc
            spacing = 20
            
            // Build Result Panel Content (Moved from original init)
            resultPanel.h1("Resolution Complete") {
                color = Color.name(Col.GOLD)
            }
            
            resultPanel.div {
                addCssClass("judgement-summary-card")
                width = 440.px
                maxWidth = 520.px
                minHeight = 150.px
                
                // Content Structure
                hPanel(spacing = 16, alignItems = AlignItems.CENTER) {
                    width = 100.perc
                    vPanel(spacing = 8) {
                        width = 100.perc
                        add(this@JudgementSummaryPage.resultTitle)
                        add(this@JudgementSummaryPage.resultSubtext)
                    }
                }
            }
            
            // Add both panels to the main page
            add(processingPanel)
            add(resultPanel)
            
            // Start in processing state
            reset()
        }
        
        /**
         * Resets the page to the "Processing" state.
         */
        fun reset() {
            Logger.debug(LogCategory.UI, "[JudgementSummaryPage] Resetting to Processing state.")
            processingPanel.visible = true
            resultPanel.visible = false
        }
        
        fun updateResult(isSuccess: Boolean, header: String, subtext: String)
        {
            Logger.debug(LogCategory.UI, "[JudgementSummaryPage] updateResult() invoked. Header: $header, Subtext: $subtext")
            resultTitle.content = header
            resultTitle.refresh()
            resultSubtext.content = subtext
            resultSubtext.refresh()
            
            if(isSuccess)
            {
                resultTitle.color = Color.name(Col.LIGHTGREEN)
            }
            else
            {
                resultTitle.color = Color.name(Col.RED)
            }
            resultTitle.refresh()
            
            // Switch to Result View
            processingPanel.visible = false
            resultPanel.visible = true
            
            Logger.debug(LogCategory.UI, "[JudgementSummaryPage] Content updated and Result Panel shown.")
            this.refresh()
        }
    }

    /**
     * Dispatch resources page showing resource allocation phase.
     *
     * @param parentWidget Reference to the parent [TurnResolutionWidget].
     */
    /**
     * Dispatch resources page showing resource allocation phase.
     *
     * @param parentWidget Reference to the parent [TurnResolutionWidget].
     */
    class DispatchResourcesPage(private val parentWidget: TurnResolutionWidget) : VPanel(alignItems = AlignItems.CENTER, justify = JustifyContent.CENTER)
    {
        private val listPanel = VPanel(alignItems = AlignItems.CENTER, spacing = 8) {
            width = 80.perc
            maxHeight = 60.perc
            overflow = Overflow.AUTO
        }

        init {
            width = 100.perc
            height = 100.perc
            spacing = 20
            
            h2("Dispatching Resources...") {
                color = Color.name(Col.CYAN)
            }
            
            icon("fas fa-truck-loading") {
                fontSize = 50.px
                color = Color.name(Col.WHITE)
                addCssClass("fa-bounce")
            }
            
            add(listPanel)

            p("Syncing logistical data with server...") {
                color = Color.name(Col.GRAY)
                fontSize = 14.px
                fontStyle = FontStyle.ITALIC
            }
        }

        fun updateData(data: org.ttt.autogenesis.network.DispatchData)
        {
            listPanel.removeAll()
            
            // Used Assets (Red/Orange)
            data.usedAssets.forEach { asset ->
                listPanel.add(
                    p("- $asset used") {
                        color = Color.hex(0xffaa55)
                        fontWeight = FontWeight.BOLD
                        fontSize = 16.px
                    }
                )
            }

            // Gained Assets (Green)
            data.gainedAssets.forEach { asset ->
                listPanel.add(
                    p("+ $asset acquired") {
                        color = Color.name(Col.LIGHTGREEN)
                        fontWeight = FontWeight.BOLD
                        fontSize = 16.px
                    }
                )
            }

            // Gained Territory (Cyan)
            data.territoryGained.forEach { territory ->
                 listPanel.add(
                    p("Secured: $territory") {
                        color = Color.name(Col.CYAN)
                        fontWeight = FontWeight.BOLD
                        fontSize = 16.px
                        borderBottom = Border(1.px, BorderStyle.SOLID, Color.name(Col.CYAN))
                    }
                )
            }

            // Territory Exchanges (Neutral/Blue)
            data.territoryExchanges.forEach { exchange ->
                val from = if (exchange.from.isBlank()) "Neutral" else exchange.from
                val to = if (exchange.to.isBlank()) "Neutral" else exchange.to
                listPanel.add(
                    p("${exchange.territoryName}: $from ➔ $to") {
                        color = Color.hex(0x3fc5ff)
                        fontWeight = FontWeight.BOLD
                        fontSize = 14.px
                    }
                )
            }

            // Lost Assets (Red)
            data.lostAssets.forEach { asset ->
                listPanel.add(
                    p("- $asset lost") {
                         color = Color.name(Col.RED)
                         fontWeight = FontWeight.BOLD
                         fontSize = 16.px
                    }
                )
            }

            // Lost Territory (Red)
             data.territoryLost.forEach { territory ->
                 listPanel.add(
                    p("Lost: $territory") {
                        color = Color.name(Col.RED)
                        fontWeight = FontWeight.BOLD
                        fontSize = 16.px
                        textDecoration = TextDecoration(TextDecorationLine.LINETHROUGH)
                    }
                )
            }
            
            if(listPanel.getChildren().isEmpty())
            {
                listPanel.add(
                     p("Logistics stable. No transfers.") {
                        color = Color.name(Col.GRAY)
                    }
                )
            }
        }
    }

    /**
     * Update NPCs page showing NPC simulation phase.
     *
     * @param parentWidget Reference to the parent [TurnResolutionWidget].
     */
    class UpdateNpcsPage(private val parentWidget: TurnResolutionWidget) : VPanel(alignItems = AlignItems.CENTER, justify = JustifyContent.CENTER)
    {
        init {
            width = 100.perc
            height = 100.perc
            spacing = 30
            
            h2("Updating NPCs...") {
                color = Color.name(Col.CYAN)
            }
            
            icon("fas fa-users") {
                fontSize = 60.px
                color = Color.name(Col.WHITE)
                addCssClass("fa-pulse")
            }
            
            p("Simulating character reactions and movements.") {
                color = Color.name(Col.GRAY)
            }
        }
    }

    /**
     * Update world page showing world state update phase.
     *
     * @param parentWidget Reference to the parent [TurnResolutionWidget].
     */
    class UpdateWorldPage(private val parentWidget: TurnResolutionWidget) : VPanel(alignItems = AlignItems.CENTER, justify = JustifyContent.CENTER)
    {
        init {
            width = 100.perc
            height = 100.perc
            spacing = 30
            
            h2("Updating World State...") {
                color = Color.name(Col.CYAN)
            }
            
            icon("fas fa-globe-americas") {
                fontSize = 60.px
                color = Color.name(Col.WHITE)
                addCssClass("fa-spin")
            }
            
            p("Applying global changes and territory shifts.") {
                color = Color.name(Col.GRAY)
            }
        }
    }

    /**
     * Counter-play page for responding to incoming events.
     *
     * @param parentWidget Reference to the parent [TurnResolutionWidget].
     */
    class CounterPlayPage(private val parentWidget: TurnResolutionWidget) : VPanel(alignItems = AlignItems.CENTER, justify = JustifyContent.CENTER)
    {
        private lateinit var actionPanel: HPanel
        private val respondInstruction = p("") {
            color = Color.name(Col.LIGHTGRAY)
            fontSize = 18.px
            fontWeight = FontWeight.BOLD
            textAlign = TextAlign.CENTER
            visible = false
        }
        
        // Dynamic Labels
        private val attackerLabel = h1("EVENT IMMINENT!") {
            color = Color.name(Col.RED)
            fontSize = 48.px
        }
        
        private val descriptionLabel = p("Loading event details...") {
            color = Color.name(Col.WHITE)
            fontSize = 24.px
        }

        init {
            width = 100.perc
            height = 100.perc
            spacing = 30
            
            add(attackerLabel)
            add(descriptionLabel)
            
            actionPanel = hPanel(spacing = 40) {
                addCssClass("counter-action-panel")
                button("Ignore") {
                    width = 160.px
                    addCssClass("counter-action-button")
                    addCssClass("counter-action-secondary")
                    onClick {
                        parentWidget.onCounterIgnore()
                    }
                }
                button("Respond") {
                    width = 160.px
                    addCssClass("counter-action-button")
                    addCssClass("counter-action-primary")
                    onClick {
                        parentWidget.onCounterRespond()
                    }
                }
            }
            
            p("Click 'Respond' to issue a counter-measure via the command console below.") {
                marginTop = 20.px
                color = Color.name(Col.GRAY)
                fontStyle = FontStyle.ITALIC
            }
            add(respondInstruction)
        }

        /**
         * Resets the action panel to its initial state.
         */
        fun resetActions()
        {
            actionPanel.visible = true
            respondInstruction.visible = false
        }

        /**
         * Shows the respond instruction and hides the action panel.
         *
         * @param text The instruction text to display.
         */
        fun showRespondInstruction(text: String)
        {
            actionPanel.visible = false
            respondInstruction.content = text
            respondInstruction.visible = true
        }

        /**
         * Updates the event details with dynamic text and styling based on intent.
         */
        fun setEventDetails(attacker: String, description: String, actionIntent: ActionIntent = ActionIntent.Hostile)
        {
        Logger.debug(LogCategory.UI, "TurnResolutionWidget: setEventDetails attacker=$attacker intent=$actionIntent description=${description.take(80)}")
        when(actionIntent)
        {
            ActionIntent.Hostile -> {
                attackerLabel.content = "HOSTILE EVENT INCOMING!"
                attackerLabel.color = Color.name(Col.RED)
                // Optional: Add hostile styling to the entire page
                background = Background(Color("rgba(40, 0, 0, 0.1)")) // Subtle red tint
            }
            ActionIntent.Friendly -> {
                attackerLabel.content = "INCOMING PROPOSAL"
                attackerLabel.color = Color.name(Col.LIGHTGREEN)
                // Optional: Add friendly styling to the entire page
                background = Background(Color("rgba(0, 40, 0, 0.1)")) // Subtle green tint
            }
        }
        descriptionLabel.content = "$attacker: $description"
        Logger.debug(LogCategory.UI, "TurnResolutionWidget: description set for $attacker (intent=$actionIntent)")
        }
    }

    /**
     * Page that announces a Nemesis arrival/revival before turn order is shown.
     */
    class NemesisThreatPage(private val parentWidget: TurnResolutionWidget) : VPanel(alignItems = AlignItems.CENTER, justify = JustifyContent.CENTER)
    {
        private val alertLabel = h1("NEMESIS THREAT DETECTED") {
            color = Color.hex(0xff4d4d)
            fontSize = 40.px
            textAlign = TextAlign.CENTER
            textShadow = TextShadow(0.px, 0.px, 14.px, Color.hex(0xaa1212))
            marginBottom = 10.px
            addCssClass("nemesis-fire-text")
        }
        private val roundLabel = p("Round 1 - World Alert") {
            color = Color.name(Col.LIGHTGRAY)
            fontSize = 20.px
            textAlign = TextAlign.CENTER
            marginBottom = 16.px
        }
        private val nemesisNameLabel = h2("Nemesis: Unknown") {
            color = Color.name(Col.WHITE)
            fontSize = 30.px
            textAlign = TextAlign.CENTER
            marginBottom = 8.px
        }
        private val kindBadge = span("ARRIVAL") {
            color = Color.hex(0xffc2c2)
            background = Background(Color.hex(0x5f1010))
            border = Border(1.px, BorderStyle.SOLID, Color.hex(0xff5a5a))
            borderRadius = 6.px
            padding = 8.px
            fontWeight = FontWeight.BOLD
            addCssClass("nemesis-kind-badge")
        }
        private val reasonLabel = p("Reason unavailable.") {
            color = Color.name(Col.LIGHTGRAY)
            fontSize = 18.px
            textAlign = TextAlign.CENTER
            marginTop = 16.px
            marginBottom = 12.px
            width = 80.perc
        }
        private val warningLabel = p("All commanders are advised to coordinate an immediate response.") {
            color = Color.hex(0xff7f7f)
            fontSize = 18.px
            fontWeight = FontWeight.BOLD
            textAlign = TextAlign.CENTER
            marginBottom = 14.px
            width = 80.perc
        }
        private val alarmRing = div {
            addCssClass("nemesis-alarm-ring")
            icon("fas fa-dragon") {
                fontSize = 46.px
                color = Color.hex(0xffb347)
            }
        }

        init {
            width = 100.perc
            height = 100.perc
            spacing = 12
            padding = 32.px
            background = Background(Color("rgba(36, 6, 6, 0.72)"))

            add(alertLabel)
            add(roundLabel)
            add(alarmRing)
            add(nemesisNameLabel)
            add(kindBadge)
            add(reasonLabel)
            add(warningLabel)
        }

        fun render(data: NemesisThreatAnnouncementData)
        {
            roundLabel.content = "Round ${data.roundNumber} - World Alert"
            nemesisNameLabel.content = "Nemesis: ${data.nemesisName}"
            kindBadge.content = data.kind.name
            reasonLabel.content = data.reason
            warningLabel.content = if(data.kind == NemesisThreatKind.REVIVAL)
            {
                "A previously defeated Nemesis has returned. Prepare for coordinated defense."
            }
            else
            {
                "A new Nemesis has arrived. Expect aggressive expansion and targeted attacks."
            }
        }
    }

    /**
     * Page that announces the round turn order before resolution begins.
     */
    class TurnOrderAnnouncementPage(private val parentWidget: TurnResolutionWidget) : VPanel(alignItems = AlignItems.CENTER, justify = JustifyContent.CENTER)
    {
        private val maxVisibleRows = 4
        private val estimatedRowHeightPx = 58
        private val rowSpacingPx = 10
        private val listViewportHeightPx = (maxVisibleRows * estimatedRowHeightPx) + ((maxVisibleRows - 1) * rowSpacingPx)

        private val titleLabel = h1("Round ? Turn Order") {
            color = Color.name(Col.GOLD)
            fontSize = 36.px
            textAlign = TextAlign.CENTER
            marginBottom = 8.px
        }
        private val subtitleLabel = p("Preparing the actors for the upcoming round...") {
            color = Color.name(Col.LIGHTGRAY)
            fontSize = 18.px
            textAlign = TextAlign.CENTER
            marginBottom = 16.px
        }
        private val listPanel = vPanel(spacing = 10) {
            width = 90.perc
            maxHeight = listViewportHeightPx.px
            overflow = Overflow.AUTO
        }
        private val footerLabel = p("First actor: TBD") {
            color = Color.name(Col.CYAN)
            fontSize = 16.px
            fontWeight = FontWeight.BOLD
            marginTop = 14.px
        }
        private val timerLabel = p("Auto-advancing shortly...") {
            color = Color.name(Col.GRAY)
            fontSize = 14.px
            fontStyle = FontStyle.ITALIC
            marginTop = 6.px
        }

        init {
            width = 100.perc
            height = 100.perc
            spacing = 12
            padding = 32.px

            add(titleLabel)
            add(subtitleLabel)
            add(listPanel)
            add(footerLabel)
            add(timerLabel)
        }

        fun render(data: TurnOrderAnnouncementData, localPlayerName: String)
        {
            titleLabel.content = "Round ${data.roundNumber} Turn Order"
            subtitleLabel.content = "The round will proceed in the following sequence:"
            listPanel.removeAll()

            if(data.participants.isEmpty())
            {
                listPanel.p("Turn order is still being computed...") {
                    color = Color.name(Col.LIGHTGRAY)
                    fontSize = 16.px
                    textAlign = TextAlign.CENTER
                }
            }
            else
            {
                data.participants.forEachIndexed { index, participant ->
                    listPanel.add(createRow(index, participant, localPlayerName))
                }
            }

            footerLabel.content = data.firstActor?.let { "First actor: $it" } ?: "First actor: TBD"
            timerLabel.content = "Auto-advancing in a few seconds..."
        }

        private fun createRow(index: Int, participant: TurnOrderParticipant, localPlayerName: String): SimplePanel
        {
            val isLocalPlayer = participant.isPlayer && participant.name.equals(localPlayerName, ignoreCase = true)
            val backgroundColor = if(isLocalPlayer) Color.hex(0x2a2a4e) else Color.hex(0x131321)
            val borderColor = if(isLocalPlayer) Color.name(Col.GOLD) else Color.hex(0x383f59)
            val iconClass: String
            val iconColor: Color
            if(participant.isPlayer)
            {
                iconClass = "fas fa-user-astronaut"
                iconColor = if(isLocalPlayer) Color.name(Col.GOLD) else Color.name(Col.CYAN)
            }
            else
            {
                val npcType = participant.npcType ?: enums.NpcType.Passive
                iconClass = getNpcTypeIcon(npcType)
                iconColor = getNpcTypeColor(npcType)
            }

            return hPanel(spacing = 10, alignItems = AlignItems.CENTER)
            {
                width = 100.perc
                padding = 10.px
                background = Background(backgroundColor)
                border = Border(1.px, BorderStyle.SOLID, borderColor)
                borderRadius = 6.px

                span((index + 1).toString())
                {
                    color = Color.name(Col.GRAY)
                    fontSize = 18.px
                    fontWeight = FontWeight.BOLD
                    minWidth = 26.px
                    textAlign = TextAlign.CENTER
                }

                icon(iconClass)
                {
                    fontSize = 22.px
                    color = iconColor
                    marginRight = 6.px
                }

                span(participant.name)
                {
                    color = Color.name(Col.WHITE)
                    fontSize = 18.px
                    fontWeight = if(isLocalPlayer) FontWeight.BOLD else FontWeight.NORMAL
                }

                if(isLocalPlayer)
                {
                    span("(YOU)")
                    {
                        color = Color.name(Col.GOLD)
                        fontSize = 12.px
                        fontWeight = FontWeight.BOLD
                        marginLeft = 6.px
                    }
                }

                if(!participant.isPlayer)
                {
                    val npcTypeLabel = participant.npcType?.name ?: "NPC"
                    span(npcTypeLabel) {
                        color = iconColor
                        fontSize = 12.px
                        fontWeight = FontWeight.BOLD
                        marginLeft = 12.px
                    }
                }
            }
        }
    }

    // --- Progress Bar ---

    /**
     * Progress bar widget showing the current turn resolution step.
     */
    class AgentProgressBar : VPanel(spacing = 10)
    {
        private val steps = listOf(
            "Start" to "fas fa-flag",
            "Action" to "fas fa-terminal",
            "Planning" to "fas fa-brain",
            "Writing" to "fas fa-pen-nib",
            "Judging" to "fas fa-gavel",
            "Dispatch" to "fas fa-truck-loading",
            "NPCs" to "fas fa-users",
            "World" to "fas fa-globe-americas",
            "Counter" to "fas fa-exclamation-triangle"
        )
        private val icons = mutableListOf<io.kvision.html.Icon>()
        private val instructionLabel = span("") {
            addCssClass("shimmer-text")
            fontSize = 12.px
            textAlign = TextAlign.CENTER
            visible = false
            width = 100.perc
        }

        init {
            width = 100.perc
            background = Background(Color.hex(0x0e0e1a))
            borderTop = Border(2.px, BorderStyle.SOLID, Color.hex(0x333333))
            alignItems = AlignItems.CENTER
            justifyContent = JustifyContent.CENTER
            paddingTop = 8.px

            hPanel(spacing = 25, alignItems = AlignItems.CENTER, justify = JustifyContent.CENTER) {
                steps.forEach { (label, iconClass) ->
                    vPanel(alignItems = AlignItems.CENTER, spacing = 5) {
                        val ico = icon(iconClass) {
                            fontSize = 18.px
                            color = Color.name(Col.GRAY)
                        }
                        span(label) {
                            color = Color.name(Col.GRAY)
                            fontSize = 10.px
                        }
                        icons.add(ico)
                    }
                }
            }

            add(instructionLabel)
        }

        /**
         * Sets the active step by index.
         *
         * @param index The step index to activate.
         */
        fun setActive(index: Int)
        {
            icons.forEachIndexed { i, icon ->
                if(i == index)
                {
                    icon.color = Color.name(Col.CYAN)
                    icon.addCssClass("fa-pulse") // Pulse animation for active
                }
                else
                {
                    icon.color = Color.name(Col.GRAY)
                    icon.removeCssClass("fa-pulse")
                }
            }
        }

        /**
         * Sets the instruction text.
         *
         * @param text The instruction text to display, or null to hide.
         */
        fun setInstruction(text: String?)
        {
            if(text.isNullOrBlank())
            {
                instructionLabel.visible = false
            }
            else
            {
                instructionLabel.content = text
                instructionLabel.visible = true
            }
        }

        /**
         * Clears the instruction text.
         */
        fun clearInstruction()
        {
            setInstruction(null)
        }
    }
}