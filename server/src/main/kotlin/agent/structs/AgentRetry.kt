package agent.structs

import agent.enums.BranchCase

/**
 *
 */
@kotlinx.serialization.Serializable
data class AgentRetry(
    var failureReason: BranchCase = BranchCase.AgentIsWorkingAsIntended,
    var retryUserPrompt: String = ""
) {
    fun isEmpty() : Boolean
    {
        return failureReason == BranchCase.AgentIsWorkingAsIntended && retryUserPrompt.isEmpty()
    }
}