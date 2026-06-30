package gameState

import kotlinx.datetime.Clock

object TimeProvider
{
    fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
}
