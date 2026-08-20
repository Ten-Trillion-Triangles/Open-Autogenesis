package agent.enums

/**
 * Enum class intended to be used by branch failure pipes. Allows the branch failure pipe to define how and why an
 * agent failed to carry out a task, and assist it in providing a new prompt for a retry attempt.
 */
enum class BranchCase
{
    AgentIsWorkingAsIntended,
    RefusedTask,
    DidNotFollowInstructions,
    IncorrectResult
}
