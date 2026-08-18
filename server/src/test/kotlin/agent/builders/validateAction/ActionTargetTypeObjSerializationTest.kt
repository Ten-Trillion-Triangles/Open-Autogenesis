package agent.builders.validateAction

import com.TTT.Util.extractJson
import com.TTT.Util.serialize
import kotlin.test.Test
import kotlin.test.assertEquals

class ActionTargetTypeObjSerializationTest
{
    @Test
    fun serializesAndDeserializesTargetPlayTypesMap()
    {
        val original = ActionTargetTypeObj(
            type = ActionTargetType.Territory,
            targets = listOf("Sudan", "Ethiopia"),
            targetPlayTypes = mapOf("Sudan" to PlayType.Military, "Ethiopia" to PlayType.Diplomatic)
        )
        val json = serialize(original)
        val restored = extractJson<ActionTargetTypeObj>(json)
        assertEquals(
            mapOf("Sudan" to PlayType.Military, "Ethiopia" to PlayType.Diplomatic),
            restored?.targetPlayTypes
        )
    }

    @Test
    fun defaultsToEmptyMapWhenAbsent()
    {
        val json = """{"type":"Territory","targets":["Sudan"]}"""
        val restored = extractJson<ActionTargetTypeObj>(json)
        assertEquals(emptyMap(), restored?.targetPlayTypes)
    }

    @Test
    fun extractsTargetPlayTypesFromLlmShapedJson()
    {
        val llmJson = """
            {"type":"Territory","targets":["Sudan","Ethiopia"],
             "actionIntent":"Hostile",
             "targetPlayTypes":{"Sudan":"Military","Ethiopia":"Diplomatic"}}
        """.trimIndent()
        val restored = extractJson<ActionTargetTypeObj>(llmJson)
        assertEquals(
            mapOf("Sudan" to PlayType.Military, "Ethiopia" to PlayType.Diplomatic),
            restored?.targetPlayTypes
        )
    }
}