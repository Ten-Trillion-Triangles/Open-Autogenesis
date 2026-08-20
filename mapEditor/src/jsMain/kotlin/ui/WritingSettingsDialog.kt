package ui

import io.kvision.core.*
import io.kvision.form.check.CheckBox
import io.kvision.form.check.checkBox
import io.kvision.form.number.Spinner
import io.kvision.form.number.spinner
import io.kvision.form.select.Select
import io.kvision.form.select.select
import io.kvision.form.text.TextArea
import io.kvision.form.text.textArea
import io.kvision.html.*
import io.kvision.panel.HPanel
import io.kvision.panel.SimplePanel
import io.kvision.panel.VPanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.perc
import io.kvision.utils.px
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import ui.WritingSettingsCategorySection
import ui.WritingSettingsCriteriaSection
import structs.InjectableCriterion
import structs.InjectableRule
import structs.RuleCategory
import structs.StoryWeights
import structs.WriterSelectionStrategy
import structs.WritingAgentConfig
import structs.AuthorPersonalities
import structs.defaultRuleCategories
import structs.defaultSelectionCriteria
import structs.defaultWritingAgentConfig

/**
 * Dialog for configuring writing agent settings including author personality,
 * writing instructions, rule categories, story weights, and selection strategy.
 */
class WritingSettingsDialog(
    var onSave: ((author: String, writingInstructions: String, config: WritingAgentConfig) -> Unit)? = null,
    var onCancel: (() -> Unit)? = null,
    initialAuthor: String = "",
    initialWritingInstructions: String = "",
    initialConfig: WritingAgentConfig = defaultWritingAgentConfig()
) : SimplePanel(className = "commander-creation-overlay") {

    var author: String = initialAuthor
    var writingInstructions: String = initialWritingInstructions
    var procedure: String = initialConfig.procedure
    var config: WritingAgentConfig = initialConfig

    private lateinit var errorText: Span
    private lateinit var authorSelect: Select
    private lateinit var writingInstructionsInput: TextArea
    private lateinit var procedureInput: TextArea
    private lateinit var geopoliticsWeight: Spinner
    private lateinit var absurdityWeight: Spinner
    private lateinit var dreamlikeWeight: Spinner
    private lateinit var twistWeight: Spinner
    private lateinit var strategySelect: Select
    private lateinit var authorEnabledCheck: CheckBox
    private lateinit var alwaysApplyRulesEnabledCheck: CheckBox
    private lateinit var guardrailsEnabledCheck: CheckBox
    private val categoryPanels = mutableListOf<VPanel>()
    private val categorySpinnerValues = mutableMapOf<Int, Int>() // index -> chancePercent
    private val criteriaSpinnerValues = mutableMapOf<Int, Int>() // index -> chancePercent
    private val criteriaEnabledStates = mutableMapOf<Int, Boolean>() // index -> enabled
    private val storyWeightSpinnerValues = mutableMapOf<String, Int>() // "geopolitics"|"absurdity"|"dreamlike"|"twists" -> value
    private val categoryRulesValues = mutableListOf<RuleCategory>() // index -> full RuleCategory (preserves rules list)
    private var categoriesToShow: List<RuleCategory> = emptyList()
    private var criteriaToShow: List<InjectableCriterion> = emptyList()

    // Author character names - the first 3 entries (CSA, CGO, NDT) are real
    // author personalities that populate authorPersonality on selection. The
    // remaining entries are decorative AI player / story character names that
    // predate the new architecture; selecting them does nothing functional.
    private val authorCharacters: List<String> = buildList {
        addAll(AuthorPersonalities.dropdownEntries.map { it.first })
        addAll(listOf(
            "Zuzusarogorata Suguruzands",
            "Haematemesis Coprophobia",
            "N'zelquin G'zeeloth",
            "Bigwang McDouchebag",
            "Invis von Disappearo",
            "Big Googar",
            "Narjodo Bazingazooka",
            "Shitty Bob",
            "Officer Dave",
            "Robert the Destroyer",
            "Quag LoBogon",
            "Zeta Step Reasoner",
            "Narjan Goren",
            "Gl'kr'kr'kr'k Shshshsh-shsh-''''////",
            "Falkenda Unseppal",
            "Nina Aureus",
            "Pissy Will",
            "El Chipotle",
            "McSmarm Editconcise",
            "Cleopatrick",
            "Tau-Theta Processor",
            "Talya of the Green Sun",
            "There von Reappearo",
            "The Inverter",
            "Dr. Percival Thrustmore",
            "John Kirby",
            "Ogadi Okwengu",
            "Parikga-Palapabura"
        ))
    }

    // Maps dropdown display name -> AuthorPersonalities key. Only the three
    // canonical author entries have a mapping; selecting any other character
    // is decorative and does not change authorPersonality.
    private val authorToPromptKey: Map<String, String> =
        AuthorPersonalities.dropdownEntries.toMap()

    init {
        console.log("[WSD] === DIALOG INIT START ===")
        console.log("[WSD] initialConfig received: ruleCategories.size=${config.ruleCategories.size}")
        config.ruleCategories.forEachIndexed { index, cat ->
            console.log("[WSD] initialConfig.ruleCategory[$index] '${cat.name}': chancePercent=${cat.chancePercent}, rules=${cat.rules.size}")
        }
        console.log("[WSD] initialConfig.storyWeights=(${config.storyWeights.geopolitics}, ${config.storyWeights.absurdity}, ${config.storyWeights.dreamlikeQualities}, ${config.storyWeights.unexpectedTwists})")
        console.log("[WSD] initialAuthor='$initialAuthor', config.ruleCategories.size=${config.ruleCategories.size}")
        console.log("[WSD] config.ruleCategories: ${JSON.stringify(config.ruleCategories.map { mapOf("name" to it.name, "chance" to it.chancePercent) })}")

        // Pre-populate story weight tracking map with loaded values so save works even without user interaction
        storyWeightSpinnerValues["geopolitics"] = config.storyWeights.geopolitics
        storyWeightSpinnerValues["absurdity"] = config.storyWeights.absurdity
        storyWeightSpinnerValues["dreamlike"] = config.storyWeights.dreamlikeQualities
        storyWeightSpinnerValues["twists"] = config.storyWeights.unexpectedTwists
        console.log("[WSD] storyWeightSpinnerValues pre-populated: ${JSON.stringify(storyWeightSpinnerValues.toMap())}")

        // Pre-populate category rules tracking with loaded categories so they survive rebuild on save
        // These categories contain the loaded rules; they are used as the base list in buildConfigFromUI()
        categoryRulesValues.clear()
        categoryRulesValues.addAll(config.ruleCategories)
        console.log("[WSD] categoryRulesValues populated: size=${categoryRulesValues.size}")

        // Pre-populate category spinner values from loaded config so save works even without user interaction
        categorySpinnerValues.clear()
        config.ruleCategories.forEachIndexed { index, category ->
            categorySpinnerValues[index] = category.chancePercent
        }
        console.log("[WSD] categorySpinnerValues pre-populated: ${JSON.stringify(categorySpinnerValues.toMap())}")

        // Pre-populate criteria spinner values from loaded config so save works even without user interaction
        criteriaSpinnerValues.clear()
        config.selectionCriteria.forEachIndexed { index, criterion ->
            criteriaSpinnerValues[index] = criterion.chancePercent
        }
        console.log("[WSD] criteriaSpinnerValues pre-populated: ${JSON.stringify(criteriaSpinnerValues.toMap())}")
        console.log("[WSD] === DIALOG INIT DONE ===")

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
            height = 100.perc
            maxHeight = CssSize(100, UNIT.vh) // constrained by CSS .commander-creation-dialog max-height: calc(100vh - 100px)
            background = Background(Color("#0d1120"))
            border = Border(2.px, BorderStyle.SOLID, Color("#383f59"))
            borderRadius = 12.px
            padding = 30.px
            // SPACEBETWEEN removed — content stacks naturally, the outer dialog
            // height=1100px provides enough room without requiring flex distribution

            h3("Writing Settings") {
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
            vPanel(className = "wsd-scrollbar-panel") {
                overflow = Overflow.AUTO
                flexGrow = 1
                flexShrink = 1
                flexBasis = 700.px
                spacing = 15
                marginBottom = 20.px
                width = 100.perc
                paddingLeft = 5.perc
                paddingRight = 5.perc
                alignItems = AlignItems.STRETCH

                // Author dropdown
                p("Author Personality") {
                    fontSize = CssSize(20, UNIT.px)
                    fontWeight = FontWeight.BOLD
                    marginBottom = 8.px
                    color = Color("#f4f6fb")
                    textAlign = TextAlign.CENTER
                    width = 100.perc
                }

                authorSelect = select {
                    width = 75.perc
                    height = CssSize(40, UNIT.px)
                    fontSize = CssSize(16, UNIT.px)

                    options = authorCharacters.map { it to it }
                    value = author

                    onChange {
                        val selected = this.value ?: ""
                        author = selected
                        // If the selection has an author personality mapping, populate
                        // authorPersonality with that prompt text. Otherwise leave
                        // authorPersonality as-is (so a dev who manually edited the
                        // field is not clobbered by a decorative selection).
                        val promptKey = authorToPromptKey[selected]
                        if (promptKey != null) {
                            val promptText = AuthorPersonalities.promptForKey(promptKey) ?: ""
                            config = config.copy(authorPersonality = promptText)
                        }
                        clearError()
                    }
                }

                // Procedure (overrides the narrative procedure block; empty = use default)
                p("Procedure") {
                    fontSize = CssSize(20, UNIT.px)
                    fontWeight = FontWeight.BOLD
                    marginTop = 15.px
                    marginBottom = 8.px
                    color = Color("#f4f6fb")
                    textAlign = TextAlign.CENTER
                    width = 100.perc
                }

                procedureInput = textArea {
                    width = 85.perc
                    height = CssSize(200, UNIT.px)
                    fontSize = CssSize(14, UNIT.px)
                    padding = 15.px
                    placeholder = "###PROCEDURE:\n1. ...\n2. ...\n###OVERALL:\n..."
                    cols = 60
                    rows = 10

                    onInput {
                        procedure = this.value ?: ""
                        clearError()
                    }
                }.apply {
                    value = initialConfig.procedure
                }

                // Writing Instructions
                p("Writing Instructions") {
                    fontSize = CssSize(20, UNIT.px)
                    fontWeight = FontWeight.BOLD
                    marginTop = 15.px
                    marginBottom = 8.px
                    color = Color("#f4f6fb")
                    textAlign = TextAlign.CENTER
                    width = 100.perc
                }

                writingInstructionsInput = textArea {
                    width = 85.perc
                    height = CssSize(100, UNIT.px)
                    fontSize = CssSize(14, UNIT.px)
                    padding = 15.px
                    placeholder = "Custom instructions for the writing agent (optional)..."
                    cols = 60
                    rows = 4

                    onInput {
                        writingInstructions = this.value ?: ""
                        clearError()
                    }
                }.apply {
                    value = initialWritingInstructions
                }

                // Toggles row
                p("Options") {
                    fontSize = CssSize(20, UNIT.px)
                    fontWeight = FontWeight.BOLD
                    marginTop = 10.px
                    marginBottom = 10.px
                    color = Color("#f4f6fb")
                    textAlign = TextAlign.CENTER
                    width = 100.perc
                }

                hPanel {
                    width = 85.perc
                    justifyContent = JustifyContent.SPACEBETWEEN
                    spacing = 15

                    vPanel {
                        alignItems = AlignItems.CENTER

                        authorEnabledCheck = checkBox {
                            inline = true
                            label = "Author"
                            value = config.authorEnabled
                            onClick {
                                config = config.copy(authorEnabled = this.value)
                            }
                        }
                        p("Use author personality in prose") {
                            fontSize = CssSize(10, UNIT.px)
                            color = Color("#a0a8c0")
                            marginTop = 2.px
                        }
                    }

                    vPanel {
                        alignItems = AlignItems.CENTER

                        alwaysApplyRulesEnabledCheck = checkBox {
                            inline = true
                            label = "Always Rules"
                            value = config.alwaysApplyRulesEnabled
                            onClick {
                                config = config.copy(alwaysApplyRulesEnabled = this.value)
                            }
                        }
                        p("Apply fixed always-rules") {
                            fontSize = CssSize(10, UNIT.px)
                            color = Color("#a0a8c0")
                            marginTop = 2.px
                        }
                    }

                    vPanel {
                        alignItems = AlignItems.CENTER

                        guardrailsEnabledCheck = checkBox {
                            inline = true
                            label = "Guardrails"
                            value = config.guardrailsEnabled
                            onClick {
                                config = config.copy(guardrailsEnabled = this.value)
                            }
                        }
                        p("Enable content safety filter") {
                            fontSize = CssSize(10, UNIT.px)
                            color = Color("#a0a8c0")
                            marginTop = 2.px
                        }
                    }
                }

                // Story Weights section
                h3("Story Weights") {
                    fontSize = CssSize(20, UNIT.px)
                    fontWeight = FontWeight.BOLD
                    marginTop = 15.px
                    marginBottom = 8.px
                    color = Color("#f4f6fb")
                    textAlign = TextAlign.CENTER
                    width = 100.perc
                }

                hPanel {
                    width = 85.perc
                    justifyContent = JustifyContent.SPACEBETWEEN
                    spacing = 10

                    vPanel {
                        alignItems = AlignItems.CENTER
                        p("Geopolitics") {
                            fontSize = CssSize(14, UNIT.px)
                            marginBottom = 5.px
                            color = Color("#f4f6fb")
                        }
                        geopoliticsWeight = spinner {
                            min = 0
                            max = 100
                            step = 5
                            width = CssSize(80, UNIT.px)
                            value = config.storyWeights.geopolitics
                            onChange {
                                val value = (this.value as? Number)?.toInt() ?: 25
                                storyWeightSpinnerValues["geopolitics"] = value
                                config = config.copy(storyWeights = config.storyWeights.copy(geopolitics = value))
                            }
                        }
                    }

                    vPanel {
                        alignItems = AlignItems.CENTER
                        p("Absurdity") {
                            fontSize = CssSize(14, UNIT.px)
                            marginBottom = 5.px
                            color = Color("#f4f6fb")
                        }
                        absurdityWeight = spinner {
                            min = 0
                            max = 100
                            step = 5
                            width = CssSize(80, UNIT.px)
                            value = config.storyWeights.absurdity
                            onChange {
                                val value = (this.value as? Number)?.toInt() ?: 25
                                storyWeightSpinnerValues["absurdity"] = value
                                config = config.copy(storyWeights = config.storyWeights.copy(absurdity = value))
                            }
                        }
                    }

                    vPanel {
                        alignItems = AlignItems.CENTER
                        p("Dreamlike") {
                            fontSize = CssSize(14, UNIT.px)
                            marginBottom = 5.px
                            color = Color("#f4f6fb")
                        }
                        dreamlikeWeight = spinner {
                            min = 0
                            max = 100
                            step = 5
                            width = CssSize(80, UNIT.px)
                            value = config.storyWeights.dreamlikeQualities
                            onChange {
                                val value = (this.value as? Number)?.toInt() ?: 25
                                storyWeightSpinnerValues["dreamlike"] = value
                                config = config.copy(storyWeights = config.storyWeights.copy(dreamlikeQualities = value))
                            }
                        }
                    }

                    vPanel {
                        alignItems = AlignItems.CENTER
                        p("Twists") {
                            fontSize = CssSize(14, UNIT.px)
                            marginBottom = 5.px
                            color = Color("#f4f6fb")
                        }
                        twistWeight = spinner {
                            min = 0
                            max = 100
                            step = 5
                            width = CssSize(80, UNIT.px)
                            value = config.storyWeights.unexpectedTwists
                            onChange {
                                val value = (this.value as? Number)?.toInt() ?: 25
                                storyWeightSpinnerValues["twists"] = value
                                config = config.copy(storyWeights = config.storyWeights.copy(unexpectedTwists = value))
                            }
                        }
                    }
                }

                // Selection Strategy
                p("Selection Strategy") {
                    fontSize = CssSize(20, UNIT.px)
                    fontWeight = FontWeight.BOLD
                    marginTop = 15.px
                    marginBottom = 8.px
                    color = Color("#f4f6fb")
                    textAlign = TextAlign.CENTER
                    width = 100.perc
                }

                strategySelect = select {
                    width = 50.perc
                    height = CssSize(40, UNIT.px)
                    fontSize = CssSize(16, UNIT.px)

                    options = listOf(
                        WriterSelectionStrategy.RANDOM.name to "Random",
                        WriterSelectionStrategy.ORIGINAL.name to "Original",
                        WriterSelectionStrategy.GEOPOLITICS_ONLY.name to "Geopolitics Only",
                        WriterSelectionStrategy.WEIGHTED.name to "Weighted",
                        WriterSelectionStrategy.RANDOM_UP_TO_FIVE.name to "Random Up To Five"
                    )
                    value = config.selectionStrategy.name

                    onChange {
                        config = config.copy(selectionStrategy = enumValueOf<WriterSelectionStrategy>(this.value!!))
                    }
                }

                // Rule Categories section
                h3("Rule Categories") {
                    fontSize = CssSize(20, UNIT.px)
                    fontWeight = FontWeight.BOLD
                    marginTop = 15.px
                    marginBottom = 8.px
                    color = Color("#f4f6fb")
                    textAlign = TextAlign.CENTER
                    width = 100.perc
                }

                // Display existing categories or defaults
                categoriesToShow = if(config.ruleCategories.isNotEmpty()) {
                    config.ruleCategories
                } else {
                    defaultRuleCategories()
                }
                console.log("[WSD] categoriesToShow set: size=${categoriesToShow.size}, categories=${categoriesToShow.map { it.name }}")
                console.log("[WSD] inner vPanel will have height=${categoriesToShow.size * 70}px, items: ${(1..categoriesToShow.size).toList()}")

                // categoryRulesValues was pre-populated in init{} and must NOT be cleared here.
                // buildConfigFromUI() uses it to preserve the loaded rules list on save.
                // Placeholder div for the categories section — rendered via raw DOM after mount
                val categoriesPlaceholder = div("") {
                    id = "wsd-categories-placeholder"
                }
                add(categoriesPlaceholder)

                // Selection Criteria section
                h3("Selection Criteria") {
                    fontSize = CssSize(20, UNIT.px)
                    fontWeight = FontWeight.BOLD
                    marginTop = 15.px
                    marginBottom = 8.px
                    color = Color("#f4f6fb")
                    textAlign = TextAlign.CENTER
                    width = 100.perc
                }

                // Display existing criteria or defaults
                criteriaToShow = if(config.selectionCriteria.isNotEmpty()) {
                    config.selectionCriteria
                } else {
                    defaultSelectionCriteria()
                }

                criteriaEnabledStates.clear()
                // Placeholder div for the criteria section — rendered via raw DOM after mount
                val criteriaPlaceholder = div("") {
                    id = "wsd-criteria-placeholder"
                }
                add(criteriaPlaceholder)
            }

            // Mount raw HTML sections after the KVision render cycle completes
            // This bypasses KVision's flexbox layout entirely
            window.setTimeout({
                console.log("[WSD] setTimeout: categorySpinnerValues=${JSON.stringify(categorySpinnerValues.toMap())}")
                console.log("[WSD] setTimeout: categoriesToShow.size=${categoriesToShow.size}")
                categoriesToShow.forEachIndexed { index, cat ->
                    console.log("[WSD] setTimeout: categoriesToShow[$index] '${cat.name}': chancePercent=${cat.chancePercent}")
                }
                val catPh = document.getElementById("wsd-categories-placeholder") as? HTMLElement
                val critPh = document.getElementById("wsd-criteria-placeholder") as? HTMLElement
                if(catPh != null) {
                    val catSection = WritingSettingsCategorySection(
                        widthPx = 642,
                        rowHeightPx = 70,
                        spacingPx = 10,
                        categories = categoriesToShow,
                        categorySpinnerValues = categorySpinnerValues,
                        onCategoryChange = { index, newValue ->
                            console.log("[WSD] onCategoryChange: index=$index, newValue=$newValue")
                            console.log("[WSD] onCategoryChange: categorySpinnerValues before=${JSON.stringify(categorySpinnerValues.toMap())}")
                            val defaultCategories = defaultRuleCategories()
                            val updatedList = config.ruleCategories.toMutableList()
                            while(updatedList.size <= index) {
                                val padIndex = updatedList.size
                                updatedList.add(defaultCategories.getOrElse(padIndex) {
                                    RuleCategory("category_${padIndex + 1}", 0, emptyList())
                                })
                            }
                            updatedList[index] = updatedList[index].copy(chancePercent = newValue)
                            config = config.copy(ruleCategories = updatedList)
                        }
                    )
                    catSection.renderInto(catPh)
                }
                if(critPh != null) {
                    val critSection = WritingSettingsCriteriaSection(
                        widthPx = 642,
                        rowHeightPx = 55,
                        spacingPx = 8,
                        criteria = criteriaToShow,
                        criteriaSpinnerValues = criteriaSpinnerValues,
                        criteriaEnabledStates = criteriaEnabledStates,
                        onCriterionChange = { index, newValue ->
                            val defaultCriteria = defaultSelectionCriteria()
                            val updatedList = config.selectionCriteria.toMutableList()
                            while(updatedList.size <= index) {
                                val padIndex = updatedList.size
                                updatedList.add(defaultCriteria.getOrElse(padIndex) {
                                    InjectableCriterion(padIndex + 1, "Criterion ${padIndex + 1}", "", 0)
                                })
                            }
                            updatedList[index] = updatedList[index].copy(chancePercent = newValue)
                            config = config.copy(selectionCriteria = updatedList)
                        },
                        onCriterionToggle = { index, isEnabled ->
                            val newChance = if(isEnabled) 100 else 0
                            val defaultCriteria = defaultSelectionCriteria()
                            val updatedList = config.selectionCriteria.toMutableList()
                            while(updatedList.size <= index) {
                                val padIndex = updatedList.size
                                updatedList.add(defaultCriteria.getOrElse(padIndex) {
                                    InjectableCriterion(padIndex + 1, "Criterion ${padIndex + 1}", "", 0)
                                })
                            }
                            updatedList[index] = updatedList[index].copy(chancePercent = newChance)
                            config = config.copy(selectionCriteria = updatedList)
                        }
                    )
                    critSection.renderInto(critPh)
                }
            }, 50)

            // Button row
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
                        if(validateInputs()) {
                            onSave?.invoke(author, writingInstructions, buildConfigFromUI())
                            closeDialog()
                        }
                    }
                }
            }
        }
    }

    private fun createCategoryPanel(category: RuleCategory, index: Int): VPanel {
        return vPanel {
            width = 100.perc
            background = Background(Color("#1a1f30"))
            borderRadius = 8.px
            padding = 10.px
            spacing = 8

            hPanel {
                width = 100.perc
                justifyContent = JustifyContent.SPACEBETWEEN
                alignItems = AlignItems.CENTER

                p(category.name.replace("_", " ").replaceFirstChar { it.uppercase() }) {
                    fontSize = CssSize(16, UNIT.px)
                    fontWeight = FontWeight.BOLD
                    color = Color("#f4f6fb")
                    margin = CssSize(0, UNIT.px)
                }

                hPanel {
                    spacing = 5
                    alignItems = AlignItems.CENTER

                    p("Chance:") {
                        fontSize = CssSize(14, UNIT.px)
                        color = Color("#a0a8c0")
                        margin = CssSize(0, UNIT.px)
                    }

                    spinner {
                        min = 0
                        max = 100
                        step = 5
                        width = CssSize(70, UNIT.px)
                        value = category.chancePercent
                        onChange {
                            categorySpinnerValues[index] = (this.value as? Number)?.toInt() ?: category.chancePercent
                        }
                    }

                    p("%") {
                        fontSize = CssSize(14, UNIT.px)
                        color = Color("#a0a8c0")
                        margin = CssSize(0, UNIT.px)
                    }
                }
            }

            // Show rules count
            if(category.rules.isNotEmpty()) {
                p("${category.rules.size} rules") {
                    fontSize = CssSize(12, UNIT.px)
                    color = Color("#7080a0")
                    margin = CssSize(0, UNIT.px)
                }
            }
        }
    }

    private fun createCriterionPanel(criterion: InjectableCriterion, index: Int): VPanel {
        return vPanel {
            width = 100.perc
            background = Background(Color("#1a1f30"))
            borderRadius = 8.px
            padding = 10.px
            spacing = 8

            hPanel {
                width = 100.perc
                justifyContent = JustifyContent.SPACEBETWEEN
                alignItems = AlignItems.CENTER

                hPanel {
                    spacing = 10
                    alignItems = AlignItems.CENTER

                    checkBox {
                        inline = true
                        value = criteriaEnabledStates[index] ?: (criterion.chancePercent > 0)
                        onClick {
                            val isEnabled = this.value
                            criteriaEnabledStates[index] = isEnabled
                            val newChance = if(isEnabled) 100 else 0
                            criteriaSpinnerValues[index] = newChance
                            // Persist into config.selectionCriteria so buildConfigFromUI reads updated values
                            val defaultCriteria = defaultSelectionCriteria()
                            val updatedList = config.selectionCriteria.toMutableList()
                            while(updatedList.size <= index) {
                                val padIndex = updatedList.size
                                updatedList.add(defaultCriteria.getOrElse(padIndex) {
                                    InjectableCriterion(padIndex + 1, "Criterion ${padIndex + 1}", "", 0)
                                })
                            }
                            updatedList[index] = updatedList[index].copy(chancePercent = newChance)
                            config = config.copy(selectionCriteria = updatedList)
                        }
                    }

                    p(criterion.description) {
                        fontSize = CssSize(14, UNIT.px)
                        color = Color("#f4f6fb")
                        margin = CssSize(0, UNIT.px)
                    }
                }

                hPanel {
                    spacing = 5
                    alignItems = AlignItems.CENTER

                    p("Chance:") {
                        fontSize = CssSize(14, UNIT.px)
                        color = Color("#a0a8c0")
                        margin = CssSize(0, UNIT.px)
                    }

                    spinner {
                        min = 0
                        max = 100
                        step = 5
                        width = CssSize(70, UNIT.px)
                        value = criterion.chancePercent
                        onChange {
                            val newChance = (this.value as? Number)?.toInt() ?: criterion.chancePercent
                            criteriaSpinnerValues[index] = newChance
                            criteriaEnabledStates[index] = newChance > 0
                            // Persist into config.selectionCriteria so buildConfigFromUI reads updated values
                            val defaultCriteria = defaultSelectionCriteria()
                            val updatedList = config.selectionCriteria.toMutableList()
                            while(updatedList.size <= index) {
                                val padIndex = updatedList.size
                                updatedList.add(defaultCriteria.getOrElse(padIndex) {
                                    InjectableCriterion(padIndex + 1, "Criterion ${padIndex + 1}", "", 0)
                                })
                            }
                            updatedList[index] = updatedList[index].copy(chancePercent = newChance)
                            config = config.copy(selectionCriteria = updatedList)
                        }
                    }

                    p("%") {
                        fontSize = CssSize(14, UNIT.px)
                        color = Color("#a0a8c0")
                        margin = CssSize(0, UNIT.px)
                    }
                }
            }
        }
    }

    private fun buildConfigFromUI(): WritingAgentConfig {
        console.log("[WSD] === buildConfigFromUI() called ===")
        console.log("[WSD] categoryRulesValues.size=${categoryRulesValues.size}, categorySpinnerValues=${JSON.stringify(categorySpinnerValues.toMap())}")
        console.log("[WSD] criteriaSpinnerValues=${JSON.stringify(criteriaSpinnerValues.toMap())}, criteriaEnabledStates=${JSON.stringify(criteriaEnabledStates.toMap())}")

        // [APEX-INJECTED-FIX-20260529]
        // Rebuild rule categories from tracked spinner values
        // Use loaded categories (via categoryRulesValues) as the base list, overlay spinner values on top.
        // If categoryRulesValues is empty (map was saved with no rule categories), fall back to defaults
        // so the dialog's currently-displayed default categories survive the save.
        val baseCategories = if (categoryRulesValues.isNotEmpty()) {
            categoryRulesValues
        } else {
            defaultRuleCategories()
        }
        val rebuiltCategories = baseCategories.mapIndexed { index, loadedCategory ->
            val chancePercent = categorySpinnerValues[index] ?: loadedCategory.chancePercent
            console.log("[WSD] buildConfig: category[$index] '${loadedCategory.name}': spinnerValue=${categorySpinnerValues[index]}, loadedCategory.chancePercent=${loadedCategory.chancePercent}, final=$chancePercent")
            RuleCategory(
                name = loadedCategory.name,
                chancePercent = chancePercent,
                rules = loadedCategory.rules
            )
        }
        console.log("[WSD] rebuiltCategories.size=${rebuiltCategories.size}")

        // Rebuild selection criteria from tracked spinner values
        // Use loaded config criteria as base, pad with defaults for any missing IDs.
        // criteriaSpinnerValues may only have a subset of keys (e.g. if loaded config had fewer criteria).
        // criteriaEnabledStates is also consulted to derive chancePercent=0 for explicitly disabled criteria.
        val loadedCriteria = config.selectionCriteria
        val rebuiltCriteria = loadedCriteria.mapIndexed { index, loadedCriterion ->
            val spinnerValue = criteriaSpinnerValues[index]
            val isEnabled = criteriaEnabledStates[index] ?: (loadedCriterion.chancePercent > 0)
            val chancePercent = if (isEnabled) (spinnerValue ?: loadedCriterion.chancePercent) else 0
            InjectableCriterion(
                id = loadedCriterion.id,
                description = loadedCriterion.description,
                category = loadedCriterion.category,
                chancePercent = chancePercent
            )
        }

        // Rebuild story weights from tracked spinner values
        val rebuiltWeights = StoryWeights(
            geopolitics = storyWeightSpinnerValues["geopolitics"] ?: config.storyWeights.geopolitics,
            absurdity = storyWeightSpinnerValues["absurdity"] ?: config.storyWeights.absurdity,
            dreamlikeQualities = storyWeightSpinnerValues["dreamlike"] ?: config.storyWeights.dreamlikeQualities,
            unexpectedTwists = storyWeightSpinnerValues["twists"] ?: config.storyWeights.unexpectedTwists
        )

        return WritingAgentConfig(
            ruleCategories = rebuiltCategories,
            alwaysApplyRules = config.alwaysApplyRules,
            authorPersonality = config.authorPersonality,
            selectionCriteria = rebuiltCriteria,
            storyWeights = rebuiltWeights,
            selectionStrategy = config.selectionStrategy,
            authorEnabled = config.authorEnabled,
            alwaysApplyRulesEnabled = config.alwaysApplyRulesEnabled,
            guardrailsEnabled = config.guardrailsEnabled,
            writingInstructions = writingInstructions,
            procedure = procedure
        ).also { finalConfig ->
            console.log("[WSD] buildConfigFromUI: FINAL ruleCategories.size=${finalConfig.ruleCategories.size}")
            finalConfig.ruleCategories.forEachIndexed { index, cat ->
                console.log("[WSD] buildConfigFromUI: FINAL ruleCategory[$index] '${cat.name}': chancePercent=${cat.chancePercent}, rules=${cat.rules.size}")
            }
            console.log("[WSD] buildConfigFromUI: FINAL storyWeights=(${finalConfig.storyWeights.geopolitics}, ${finalConfig.storyWeights.absurdity}, ${finalConfig.storyWeights.dreamlikeQualities}, ${finalConfig.storyWeights.unexpectedTwists})")
        }
    }

    private fun validateInputs(): Boolean {
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
    }

    private fun closeDialog() {
        this.visible = false
        this.parent?.remove(this)
    }
}
