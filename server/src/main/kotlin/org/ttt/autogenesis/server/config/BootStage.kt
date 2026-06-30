package org.ttt.autogenesis.server.config

enum class SystemStartupStage
{
    EMPTY,
    WAITFORPLAYERS,
    LOADINGMAP,
    GAMESTARTED,
    GAMEOVER
}


/**
 * Global var to track the state the server is in. This allows us to know if it has a running session, if it has a
 * running game, and if it is now ended its task and is allowed to be destroyed.
 */
object BootStage
{
    var bootStage = SystemStartupStage.EMPTY
}