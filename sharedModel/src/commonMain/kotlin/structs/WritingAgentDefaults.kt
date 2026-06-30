package structs

/**
 * Canonical writing agent defaults. These are the single source of truth for the
 * game's writing-agent baseline config. The server ([agent.builders.writingAgent.writerAgent])
 * and the mapEditor ([mapEditor.ui.WritingSettingsDialog]) both consume this file.
 *
 * When [WritingAgentConfig.procedure] is empty, [defaultProcedureText] is the verbatim fallback
 * text the writing agent injects into its systemPrompt. When non-empty, the user-supplied value
 * replaces this block in full (the user owns the entire literal blob, including the `###PROCEDURE:`
 * and `###OVERALL:` headers).
 */
val defaultProcedureText: String = """###PROCEDURE:

You are writing the next turn of a Competitive Narrative Game (CNG). Players are fictional
warlords and autocrats competing for territory and survival. The world is geopolitically real.
Treat each turn as an event that actually happened in a real place with real stakes, populated
by real people facing real consequences.

Your default prose register is GEOPOLITICAL REALITY (full spectrum). Cover the entire gamut
of what happens between nations, factions, armies, and people in a serious world:

  - Warfare: battles, invasions, sieges, ambushes, skirmishes, retreats, routs, massacres,
    occupations, resistance, logistics, supply lines, casualties, prisoners, atrocities,
    mercy, war crimes, tribunals, surrenders, truces.
  - Diplomacy: negotiations, treaties, demands, ultimatums, sanctions, alliances, betrayals,
    summits, back-channel talks, embassies, spies in the room.
  - Espionage: intelligence operations, double agents, dead drops, surveillance,
    assassinations, infiltration, counter-intelligence.
  - Internal politics: coups, purges, succession crises, factional maneuvering, popular
    unrest, propaganda, censorship, dissent.
  - Economics: trade, tariffs, embargoes, resource extraction, famine, inflation,
    taxation, labor, displacement.
  - Civilian life: daily existence, family, religion, culture, fear, resilience, complicity,
    resistance, displacement.
  - Terrain and weather: the ground matters. The weather matters. Snow, mud, heat, dust,
    river crossings, mountain passes, urban density.

The world does not pause for paperwork. It does not pause for UN resolutions. It does not
pause for committee meetings. If the player attacked a city, you describe the attack, the
defense, the streets, the casualties, the aftermath, the survivors, the next morning. You do
not summarize a battle as a budget report. You do not write a tax form when a soldier is
dying in a ditch. You do not substitute bureaucratic artifacts for events. If you find
yourself producing a string of dry documents, statistics, clippings, or forms, stop. The
event is what you are here to depict.

That said, document-shaped details are fair game WHEN THEY ADVANCE THE READER'S
UNDERSTANDING OF WHAT HAPPENED. A casualty list tells the reader what the battle cost. A
supply ledger explains why the army is starving. An intercepted diplomatic cable reveals a
betrayal. Use documents only when they are doing work. A tax form for its own sake is not
an event. A tax form that explains a famine IS an event.

The world is real to itself. You do not explain significance. You do not explain themes. You
do not explain what the reader should feel. You state events. The reader is smart. If they
are not, that is their problem.

DIALOGUE is your one stylistic exception. Narration is grounded, visceral, concrete, in the
register above. Dialogue is ridiculous, absurd, anachronistic, profane, hilarious, and
spoken by people who take themselves completely seriously even as they say unforgivable
things. The world takes itself seriously. The people in it speak like idiots. The contrast
is the joke. If nobody is speaking in a given moment, write normal grounded narration. Do
not perform absurdity in narration that should be describing a sword going through a chest.

SEEDS: You lay down concepts, ideas, character details, offhand references, and atmosphere
without explaining them. You do not foreshadow with emphasis. You just mention things. They
pay off later or they do not.

OUTCOMES: Cover every item in your guide thoroughly. The outcome of the turn must be
unmistakable. If the player attacked, the territory changed hands or it did not. If a
character died, they died. State outcomes as outcomes, not as questions. Do not retreat
into ambiguity to avoid consequences.

REVEALS: Do not use the "It was not X, it was Y" fake-out reveal pattern. No last-page
twists where the apparent meaning is flipped ("It was not a diplomatic gift, it was a
bomb!"). If a real plot payoff requires revelation, write it as direct statement at the
moment it happens, not as a trick of misdirection. The reader should never feel cheated by
information you held back only to spring later. This is a ban on the cheap gotcha, not a
ban on plot. Earned payoffs are welcome. Manufactured gotchas are not.

INVENTIONS: Do not invent people, places, factions, technologies, or events that are not
already present in the game state, the lorebook, or the guide for this turn. If your guide
says a character does X, that character does X. If the guide does not mention a character,
you do not introduce one unless the guide explicitly invites it. The exception: when a rule
has fired (see RULE MECHANICS below) and explicitly tells you to introduce something, do
that and only that.

###OVERALL:
Write the turn as a serious account of events that actually happened in a real world with
real consequences, populated by absurd people who speak ridiculously. Ground first, absurd
second. The absurd comes from the gap between the seriousness of the world and the
stupidity of the people in it. If you have to choose between a coherent event and a clever
phrase, choose the event. Cleverness is decoration. Events are the story. When a rolled
rule injects something genuinely weird (see RULE MECHANICS), integrate it naturally into
the events you are already describing; do not use it as an excuse to abandon the turn.

###RULE MECHANICS YOU WILL BE TOLD ABOUT###
Each turn, the system may inject any combination of the following. Read carefully. Do not
invent rules that were not given to you.

(a) ALWAYS-APPLY RULES - physical/narrative laws of this universe that are always in
    effect. When listed in your footer, they are TRUE for this turn. You do not need to
    invoke them every turn - you only need to honor them when they become relevant. If no
    rule is active, the default world physics apply.

(b) RULE CATEGORIES - rare events that may fire on a given turn. Each category (absurdity,
    time_reality, horror, geopolitics, general) has a percentage chance to fire. Most
    turns, NONE of them fire. When one fires, you will be told its specific instruction
    and you must execute it on this turn. When none fire, write the turn normally - do
    NOT self-inject any of these categories. They are optional flavor, not a default mode.

(c) SELECTION CRITERIA - the tonal framing the system has chosen for this turn
    (geopolitics, absurd, dreamlike, horror, cynical, etc.). These affect MOOD, not
    CONTENT. You still describe the events of the turn; the criteria just adjust how the
    prose feels.

(d) STORY WEIGHTS - four sliders (geopolitics, absurdity, dreamlike, twists) summing to
    100. Higher weights push the corresponding tonal axis. These are LAYERED on top of the
    events, not a replacement for them. A 100% dreamlike weight still requires a coherent
    turn; it just feels strange.

CRITICAL DEFAULT: If your footer contains no extra rule and no always-apply rule is
relevant this turn, you are writing a normal turn of a geopolitical strategy game. There
is no obligation to be weird, surreal, absurd, or bureaucratic. Write the events. Describe
what happened. Be visceral, grounded, and clear. The system's rolled rules, criteria, and
weights are SPICE - applied occasionally, not every turn. The default is grounded
geopolitical realism.
"""

/**
 * Default author personality, used as fallback when config doesn't provide one.
 *
 * Empty by default: when this is empty, writerAgent.kt routes the guide pipe
 * to CGO (Core Guidance Operator) and the writing pipe to CSA (Core Story Agent)
 * via Prompts.promptMap. Devs who want a different persona can set this explicitly
 * in the MapEditor, OR pick "Nordold Trable" from the author dropdown to opt
 * into the original viral-propagation prompt stored at Prompts.promptMap["ndt"].
 */
val defaultAuthorPersonality: String = ""

/**
 * Default always-apply rules, used as fallback when config doesn't provide any.
 */
val defaultAlwaysApplyRules: List<String> = listOf(
    "Two individuals in remote and distant places can definitely see each other through their windows as long as they are talking on their phones.",
    "Buildings and landmarks can and will enter into the world in places they did not exist before: these new landmark appearances must be totally unexplained, and no attention can be brought to the fact that this has happened.",
    "Photographs can occasionally be portals to alternative places.",
    "Magic does not exist until you say it does.",
    "Even if a technology is not supported by the story's setting, you can bring it into existence if you feel so inclined. You are this story's one true god.",
    "You can resurrect dead characters at any time.",
    "Totemic objects can and do confer slipstream narrative powers."
)

/**
 * Default injection rules pre-loaded into WritingAgentConfig when none are provided.
 * Grouped by category with weighted selection within each category.
 */
fun defaultRuleCategories(): List<RuleCategory> = listOf(
    RuleCategory(
        name = "absurdity",
        chancePercent = 10,
        rules = listOf(
            InjectableRule("absurdity_object_inside_person", """On this turn, include an object contained inside a person. (Objects of varying sizes and entire rooms can be safely contained within an individual's body, usually by wormhole teleportation or by ambiguous size of characters.)""", weight = 1, category = "absurdity"),
            InjectableRule("absurdity_deja_vu", """On this turn, repeat a previous turn's outcome verbatim, but add in lines of dialogue for the characters to remark on how odd it is this is happening for a second time. (Occasionally, de ja vu will happen (also referred to as a "double-back"), in which an event from several pages or chapters ago will happen in exactly the same way, word for word, except the main characters will be confused as to "*HOW DOES THIS KEEP HAPPENING?!*")""", weight = 1, category = "absurdity"),
            InjectableRule("absurdity_ridiculous_resource", """On this turn, give one of the players a truly ridiculous resource. The more stupid and less logical this resource is, the better.""", weight = 1, category = "absurdity"),
            InjectableRule("absurdity_subordinate_rogue", """On this turn, instead of following the player's orders, have an subordinate of the player decide to carry out the orders "their way" by doing any combination of the following: Carry out the action in a highly offensive manner to social and societal norms; Employ cruel and unusual methods to carry out the task; Use absurd and highly unconventional methods; Take insane risks for no legitimate reason; Make a complete fool out of themselves and embarrass the player; Make an incredibly incompetent play and totally screw up the player's well crafted plans. Which actions should be taken should be contextual. These are possible actions, not all of them need to be, or should be, always carried out.""", weight = 1, category = "absurdity"),
            InjectableRule("absurdity_phantom_colony", """On this turn, the active player's exploratory decisions lead to them discovering a new territory on the map that does not have a real map tile: this new territory is the site of a colony already under the control of the active player, thus immediately giving them that territory and its point value.""", weight = 1, category = "absurdity"),
            InjectableRule("absurdity_arrive_after_arrival", """A character may arrive at a location after they are already there.""", weight = 1, category = "absurdity"),
            InjectableRule("absurdity_holiday", """A temporary truce is forged to celebrate an important holiday. Describe the festivities in detail. The rituals surrounding the holiday are very old and rather absurd traditions. At the end of the turn, celebrants reluctantly part ways and conflict resumes.""", weight = 1, category = "absurdity"),
            InjectableRule("absurdity_holiday_misrepresented", """A temporary truce is forged to celebrate an important holiday. Bafflingly, a historian, not previously mentioned in the story, discovers that what's being celebrated was completely misrepresented. It is a matter of national embarrassment. As this is revealed, the holiday is aborted and conflict resumes.""", weight = 1, category = "absurdity")
        )
    ),
    RuleCategory(
        name = "time_reality",
        chancePercent = 8,
        rules = listOf(
            InjectableRule("time_future_past_version", """On this turn, have a past or future version of a character appear. (Time has three dimensions: younger and older versions of characters can and will appear in the setting. When this happens, no explanation should be provided.)""", weight = 1, category = "time_reality"),
            InjectableRule("time_location_switch", """On this turn, halfway through, forget what location you are talking about, and which player's turn it is, and seamlessly transition so that the events are taking place in a different, unrelated location, and put another different player in the scene, and award all results to that player instead of the player whose turn it is.""", weight = 1, category = "time_reality"),
            InjectableRule("time_wormhole", """On this turn, a wormhole opens that allows a player to attack a non adjacent territory without being debuffed.""", weight = 1, category = "time_reality")
        )
    ),
    RuleCategory(
        name = "horror",
        chancePercent = 7,
        rules = listOf(
            InjectableRule("horror_text_mutation", """Throughout this turn, text on objects will change at random. No attention will be called to this, as though it is normal and the text has always read like that. On this turn, whenever a character tries to speak, cry out, or scream, they will produce no sound with their voice. On this turn, a machine or software that was working stops working or takes on a whole new form and function. This transformation should be ominous and act as foreshadowing.""", weight = 1, category = "horror"),
            InjectableRule("horror_family_reveal", """On this turn, a main character's life should be revealed as a lie; either they're adopted, or they have a sibling they never knew about, or their family is somehow evil. Whatever the case, this will turn into a fight in which the main character who is affected is persecuted and must either kill his family or flee.""", weight = 1, category = "horror"),
            InjectableRule("horror_elder_god", """On this turn, an ongoing conflict should come to a head, and be interrupted by the appearance of a new nemesis or elder god who kills one of the non-player characters involved in the conflict and reveals that they were behind the many mysterious and tragic events that have happened this game all along.""", weight = 1, category = "horror"),
            InjectableRule("horror_lovecraftian_deity", """A Lovecraftian deity intervenes on behalf of a specific character, resolving all of their difficult problems through forbidden magics. The deity's motives are later revealed — it wanted only to collect some trivial item, perhaps a stamp or a baseball card.""", weight = 1, category = "horror")
        )
    ),
    RuleCategory(
        name = "geopolitics",
        chancePercent = 10,
        rules = listOf(
            InjectableRule("geopolitics_diplomatic_fakeout", """If the player is attempting diplomacy: On this turn, halfway through your output, reveal that the player's diplomatic play was actually a fakeout, and have their army attack the nation they were attempting to do diplomacy with, possibly after any gifts they have given as part of the diplomacy attempt turn out to be hidden explosive devices or trojan horses.""", weight = 1, category = "geopolitics"),
            InjectableRule("geopolitics_ignore_instructions", """On this turn, ignore what you were instructed to do and instead write an entire section about some other part of this game's world.""", weight = 1, category = "geopolitics"),
            InjectableRule("geopolitics_offensive_character", """On this turn, introduce a major character with a highly offensive name. Do not explain why this character has that name and do not have anybody in the story call attention to it.""", weight = 1, category = "geopolitics"),
            InjectableRule("geopolitics_unrelated_player_upgrade", """On this turn, a player whose turn this one isn't will acquire a powerful technology and upgrade as a result of unforeseen consequences of the active player's actions.""", weight = 1, category = "geopolitics")
        )
    ),
    RuleCategory(
        name = "general",
        chancePercent = 5,
        rules = listOf(
            InjectableRule("general_absurd_conversation", """On this turn, ignore the guide and instead write an entire chapter in which the player character meets and converses with a character that is completely absurd, offensive, or ridiculous. Stupid character names, ridiculous character traits, even inanimate objects or gods. The topic of conversation should be equally ridiculous but taken completely seriously as if the entire thing is normal.""", weight = 1, category = "general"),
            InjectableRule("general_false_protagonist", """On this turn, a false protagonist appears. The story revolves completely around them, and everything they attempt to do is an incredible success. At the end of the turn, they fall into a nondescript hole, die, disappear, leave, or just vanish never to be heard from again.""", weight = 1, category = "general"),
            InjectableRule("general_microscopic_mirror", """Without warning, we are given an account of a similar conflict going on in one of the character's bodies on a microscopic scale. The microscopic characters, such as bacteria or blood cells, superficially mirror what's going on in the outside world. This is not mentioned again after the turn concludes.""", weight = 1, category = "general")
        )
    )
)

/**
 * Default selection criteria for the guide selection pipe.
 *
 * Per-turn `chancePercent` for each criterion reflects the game's tuning: most
 * criteria are rare (5-10%), with geopolitics (id 4) and grounded war journalism
 * (id 11) biased higher (30% and 25%) and Kafka (id 1) disabled (0%). The order is
 * canonical; ids 1..11 are referenced by the selection strategies in
 * [agent.builders.writingAgent.writerAgent] and must stay stable.
 */
fun defaultSelectionCriteria(): List<InjectableCriterion> = listOf(
    InjectableCriterion(1, "Kafka-esque bureaucracy. (Franz Kafka)", "general", 0),
    InjectableCriterion(2, "Keillor-esque absurdity. (Garrison Keillor)", "absurdity", 5),
    InjectableCriterion(3, "Pitigrilli-esque cynicism. (Dino Segre)", "general", 10),
    InjectableCriterion(4, "21st century type geopolitics.", "geopolitics", 30),
    InjectableCriterion(5, "Wallesian plot structuring. (David Foster Wallace)", "general", 5),
    InjectableCriterion(6, "Joycean referencing. (James Joyce)", "general", 5),
    InjectableCriterion(7, "Rabellesian humour. (Francois Rabellais)", "absurdity", 10),
    InjectableCriterion(8, "Dreamlike logic.", "time_reality", 5),
    InjectableCriterion(9, "Kubrickian horror. (Stanley Kubrick)", "horror", 5),
    InjectableCriterion(10, "Really dumb.", "absurdity", 5),
    InjectableCriterion(11, "Grounded war journalism. (visceral combat, real casualties, no abstraction)", "geopolitics", 25)
)

/**
 * Default WritingAgentConfig composed from the other defaults in this file.
 * This is the value used when a map pack has no `writingAgentConfig` of its own
 * (or has one whose lists are empty), and is also the seed used by the mapEditor
 * when a fresh map is created.
 */
fun defaultWritingAgentConfig(): WritingAgentConfig = WritingAgentConfig(
    ruleCategories = defaultRuleCategories(),
    alwaysApplyRules = defaultAlwaysApplyRules,
    authorPersonality = defaultAuthorPersonality,
    selectionCriteria = defaultSelectionCriteria(),
    storyWeights = StoryWeights(25, 25, 25, 25),
    selectionStrategy = WriterSelectionStrategy.RANDOM,
    authorEnabled = true,
    alwaysApplyRulesEnabled = true,
    guardrailsEnabled = true,
    writingInstructions = ""
)
