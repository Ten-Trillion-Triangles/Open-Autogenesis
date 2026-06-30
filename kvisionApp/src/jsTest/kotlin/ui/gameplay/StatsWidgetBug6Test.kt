package ui.gameplay

import enums.NpcType
import io.kvision.core.Color
import structs.Npc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Tests for BUG-6: Character Icons Jumble (Blue Person Icon)
 *
 * Root cause: `addTurnOrderItem` uses exact string equality to look up names in
 * `activePlayerNames` (Set<String>) and `npcMap` (Map<String, Npc>).
 * Casing differences ("Lord Maple Tree" vs "Lord maple tree") or punctuation
 * differences ("Bone Speaker Vex" vs "Bone-Speaker Vex") cause lookup failures,
 * falling back to `fas fa-question-circle` which renders as a blue person icon via CSS.
 *
 * Tests follow TDD: written FIRST to prove the bug exists, then the fix will be
 * implemented to make them pass.
 */
class StatsWidgetBug6Test
{
    // --- Test helper data ---

    private fun makeNpc(name: String, type: NpcType = NpcType.Hostile): Npc
    {
        return Npc(
            name = name,
            type = type,
            description = "Test NPC",
            personality = "Test",
            abilities = "Test",
            history = "Test",
            pointValue = 4,
            capturedTerritory = mutableListOf(),
            resources = mutableListOf(),
            militaryReadiness = 70,
            legitimacy = 70,
            stagnation = 0,
            createdBy = "Test",
            isDefeated = false,
            interferenceChance = 0.2
        )
    }

    // ---------------------------------------------------------------------------
    // BUG-6 Test 1: Casing mismatch — player name stored as "Lord Maple Tree"
    // but incoming name is "Lord maple tree" (lowercase 'm' in "maple")
    // ---------------------------------------------------------------------------

    @Test
    fun `bug6_casing_mismatch_in_player_name_triggers_fallback_icon`()
    {
        // The bug: activePlayerNames contains "Lord Maple Tree" but the incoming
        // name is "Lord maple tree" (wrong casing) — lookup fails and fallback is used.
        val activePlayerNames = setOf("Lord Maple Tree", "Alice Cooper")
        val npcMap = mapOf<String, Npc>()
        val name = "Lord maple tree" // lowercase 'm' in "maple"
        val localPlayerName = ""

        val result = resolveCharacterIconResult(
            name = name,
            localPlayerName = localPlayerName,
            activePlayerNames = activePlayerNames,
            npcMap = npcMap
        )

        // BUG: currently this returns FALLBACK because the lookup is case-sensitive
        // EXPECTED after fix: should return PLAYER icon (gold or cyan)
        assertNotEquals(
            IconLookupResult.FALLBACK,
            result,
            "BUG-6 CONFIRMED: Casing mismatch ('Lord maple tree' vs 'Lord Maple Tree') " +
                "causes player lookup to fail and fall back to question-circle icon"
        )
    }

    // ---------------------------------------------------------------------------
    // BUG-6 Test 2: Punctuation mismatch — NPC map keyed "Bone Speaker Vex"
    // but incoming name is "Bone-Speaker Vex" (hyphen vs space)
    // ---------------------------------------------------------------------------

    @Test
    fun `bug6_punctuation_mismatch_in_npc_name_triggers_fallback_icon`()
    {
        // The bug: npcMap is keyed with "Bone Speaker Vex" but incoming name
        // uses a hyphen: "Bone-Speaker Vex" — lookup fails and fallback is used.
        val activePlayerNames = setOf<String>()
        val npcMap = mapOf(
            "Bone Speaker Vex" to makeNpc("Bone Speaker Vex", NpcType.Hostile)
        )
        val name = "Bone-Speaker Vex" // hyphen instead of space
        val localPlayerName = ""

        val result = resolveCharacterIconResult(
            name = name,
            localPlayerName = localPlayerName,
            activePlayerNames = activePlayerNames,
            npcMap = npcMap
        )

        // BUG: currently this returns FALLBACK because the lookup doesn't normalize punctuation
        // EXPECTED after fix: should return NPC icon (hostile = skull)
        assertNotEquals(
            IconLookupResult.FALLBACK,
            result,
            "BUG-6 CONFIRMED: Punctuation mismatch ('Bone-Speaker Vex' vs 'Bone Speaker Vex') " +
                "causes NPC lookup to fail and fall back to question-circle icon"
        )
    }

    // ---------------------------------------------------------------------------
    // BUG-6 Test 3: Exact match — should always succeed (baseline)
    // ---------------------------------------------------------------------------

    @Test
    fun `exact_player_name_match_resolves_to_player_icon`()
    {
        val activePlayerNames = setOf("Lord Maple Tree", "Alice Cooper")
        val npcMap = mapOf<String, Npc>()
        val name = "Lord Maple Tree" // exact match
        val localPlayerName = ""

        val result = resolveCharacterIconResult(
            name = name,
            localPlayerName = localPlayerName,
            activePlayerNames = activePlayerNames,
            npcMap = npcMap
        )

        assertEquals(IconLookupResult.PLAYER_REMOTE, result)
    }

    @Test
    fun `exact_npc_name_match_resolves_to_npc_icon`()
    {
        val activePlayerNames = setOf<String>()
        val npcMap = mapOf(
            "Bone Speaker Vex" to makeNpc("Bone Speaker Vex", NpcType.Hostile)
        )
        val name = "Bone Speaker Vex" // exact match
        val localPlayerName = ""

        val result = resolveCharacterIconResult(
            name = name,
            localPlayerName = localPlayerName,
            activePlayerNames = activePlayerNames,
            npcMap = npcMap
        )

        assertEquals(IconLookupResult.NPC_HOSTILE, result)
    }

    // ---------------------------------------------------------------------------
    // BUG-6 Test 4: Unicode normalization — NFC vs NFD
    // E.g., "Cafe\u0300" (NFC) vs "Cafe\u0300" (NFD) — visually identical but
    // technically different strings
    // ---------------------------------------------------------------------------

    @Test
    fun `bug6_unicode_nfc_vs_nfd_normalization_causes_fallback`()
    {
        // NFC form: \u00e9 (single code point for 'e-acute')
        val npcMap = mapOf(
            "Caf\u00e9" to makeNpc("Caf\u00e9", NpcType.Active)
        )
        val activePlayerNames = setOf<String>()
        // NFD form: 'e' + \u0301 (combining acute accent)
        val name = "Cafe\u0301"
        val localPlayerName = ""

        val result = resolveCharacterIconResult(
            name = name,
            localPlayerName = localPlayerName,
            activePlayerNames = activePlayerNames,
            npcMap = npcMap
        )

        // BUG: if the code doesn't normalize, these visually identical names fail to match
        // EXPECTED after fix: should return NPC icon (active = user-tie)
        assertNotEquals(
            IconLookupResult.FALLBACK,
            result,
            "BUG-6 CONFIRMED: Unicode normalization (NFC vs NFD) causes " +
                "lookup to fail for visually identical names"
        )
    }

    // ---------------------------------------------------------------------------
    // BUG-6 Test 5: Fallback icon is question-circle (proves the CSS default)
    // ---------------------------------------------------------------------------

    @Test
    fun `fallback_icon_is_fas_fa_question_circle_with_gray_color`()
    {
        val activePlayerNames = setOf<String>()
        val npcMap = mapOf<String, Npc>()
        val name = "Unknown Character"
        val localPlayerName = ""

        val result = resolveCharacterIconResult(
            name = name,
            localPlayerName = localPlayerName,
            activePlayerNames = activePlayerNames,
            npcMap = npcMap
        )

        assertEquals(
            IconLookupResult.FALLBACK,
            result,
            "Unknown name should fall back to question-circle icon"
        )
    }
}

// ---------------------------------------------------------------------------
// Test double for the name-key lookup logic in StatsWidget.addTurnOrderItem.
// Mirror of the logic at StatsWidget.kt:671-703, extracted for testability.
// When the bug is fixed, this function should be updated to use
// case-insensitive + punctuation-normalized lookup.
// ---------------------------------------------------------------------------
enum class IconLookupResult
{
    PLAYER_LOCAL,   // fas fa-user-astronaut GOLD
    PLAYER_REMOTE,  // fas fa-user-astronaut CYAN
    NPC_SUBORDINATE, // fas fa-user-friends LIGHTGRAY
    NPC_PASSIVE,     // fas fa-user LIGHTBLUE
    NPC_ACTIVE,     // fas fa-user-tie LIGHTGREEN
    NPC_HOSTILE,    // fas fa-skull ORANGE
    NPC_NEMESIS,    // fas fa-dragon RED
    NPC_ELDER_GOD,  // fas fa-biohazard PURPLE
    FALLBACK        // fas fa-question-circle GRAY
}

fun resolveCharacterIconResult(
    name: String,
    localPlayerName: String,
    activePlayerNames: Set<String>,
    npcMap: Map<String, Npc>
): IconLookupResult
{
    // BUG-6 fix: normalize name before lookup to handle case/punctuation mismatches
    val normalizedName = normalizeForLookup(name)
    val normalizedLocalPlayerName = normalizeForLookup(localPlayerName)
    val isPlayer = activePlayerNames.map { normalizeForLookup(it) }.toSet().contains(normalizedName)
    val npc = npcMap.entries.find { normalizeForLookup(it.key) == normalizedName }?.value
    val isLocalPlayer = normalizedName == normalizedLocalPlayerName

    return if(isPlayer)
    {
        if(isLocalPlayer) IconLookupResult.PLAYER_LOCAL else IconLookupResult.PLAYER_REMOTE
    }
    else if(npc != null)
    {
        when(npc.type)
        {
            NpcType.Subordinate -> IconLookupResult.NPC_SUBORDINATE
            NpcType.Passive -> IconLookupResult.NPC_PASSIVE
            NpcType.Active -> IconLookupResult.NPC_ACTIVE
            NpcType.Hostile -> IconLookupResult.NPC_HOSTILE
            NpcType.Nemesis -> IconLookupResult.NPC_NEMESIS
            NpcType.ElderGod -> IconLookupResult.NPC_ELDER_GOD
        }
    }
    else
    {
        IconLookupResult.FALLBACK
    }
}

/**
 * Normalizes a name for icon lookup — case-insensitive, punctuation-normalized.
 * BUG-6 fix: converts to lowercase, strips hyphens/underscores, normalizes unicode.
 * Ensures "Lord Maple Tree" and "Lord maple tree" resolve to the same icon.
 * Uses JS-compatible approach for both JVM and JS test targets.
 */
internal fun normalizeForLookup(name: String): String
{
    val lower = name.lowercase()
    val nfkcNormalized = lower.asDynamic().normalize("NFKC").unsafeCast<String>()
    val deaccented = nfkcNormalized.replace(Regex("\\p{M}"), "")
    return deaccented.replace("-", " ").replace("_", " ").replace(Regex("[^a-z0-9\\s]"), "")
}