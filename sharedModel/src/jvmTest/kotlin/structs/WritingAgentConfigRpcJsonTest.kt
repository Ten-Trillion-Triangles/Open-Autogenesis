package structs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.ttt.autogenesis.network.RpcJson

/**
 * Verifies that the production `RpcJson` (with `encodeDefaults = true`) emits the `procedure` key
 * for a `WritingAgentConfig` whose `procedure` is the default empty string. This protects against
 * future drift in `RpcJson`'s `encodeDefaults` setting: if someone flips it to `false`, this test
 * fails and points at the exact regression that would silently drop the override on the pack side.
 *
 * The test-local `Json` in `MapDataWritingFieldsTest` does not set `encodeDefaults`, so it cannot
 * surface this regression — this test is the canonical coverage.
 */
class WritingAgentConfigRpcJsonTest
{
    @Test
    fun `should encode procedure key with default empty value under RpcJson`()
    {
        val config = WritingAgentConfig(procedure = "")
        val encoded = RpcJson.encodeToString(WritingAgentConfig.serializer(), config)
        assertTrue(
            encoded.contains("\"procedure\""),
            "RpcJson should encode the procedure key for default-empty values (encodeDefaults = true); got: $encoded"
        )
    }

    @Test
    fun `should roundtrip default procedure through RpcJson`()
    {
        val config = WritingAgentConfig(procedure = "")
        val encoded = RpcJson.encodeToString(WritingAgentConfig.serializer(), config)
        val decoded = RpcJson.decodeFromString(WritingAgentConfig.serializer(), encoded)
        assertEquals(config.procedure, decoded.procedure, "default-empty procedure must roundtrip cleanly")
    }
}