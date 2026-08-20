package org.ttt.autogenesis.server.config

import structs.matchmaking.GameSessionStatus

/**
 * Houses core startup data such as game mode, what players to expect, and other factors to
 * ensure that connecting players are the ones intended for this game, and that we know how to configure
 * the core systems for each distinct game mode.
 */
object RuleSet
{
    lateinit var data: GameSessionStatus
}
