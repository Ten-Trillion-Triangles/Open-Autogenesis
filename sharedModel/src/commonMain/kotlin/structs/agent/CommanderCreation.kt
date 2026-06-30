package structs.agent

import enums.CommanderTrait
import enums.CommanderType

@kotlinx.serialization.Serializable
data class AgentCommanderTypeResponse(
    var type: CommanderType? = null
)

@kotlinx.serialization.Serializable
data class AgentCommanderTraitResponse(
    var trait: CommanderTrait? = null
)