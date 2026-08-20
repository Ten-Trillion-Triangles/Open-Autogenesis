package agent.builders

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

object AgentCoroutineScope
{
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
