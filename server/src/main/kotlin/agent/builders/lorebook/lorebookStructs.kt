package agent.builders.lorebook

import kotlinx.serialization.Serializable

@Serializable
data class CharacterEntry(
    var name: String = "",
    var description: String = "",
    var aliases: List<String> = listOf(),
    var affiliations: List<String> = listOf(),
    var status: String = "",
    var lastSeen: String = ""
)

@Serializable
data class EventEntry(
    var name: String = "",
    var description: String = "",
    var participants: List<String> = listOf(),
    var location: String = "",
    var aliases: List<String> = listOf()
)

@Serializable
data class LocationEntry(
    var name: String = "",
    var description: String = "",
    var controller: String = "",
    var aliases: List<String> = listOf()
)

@Serializable
data class ItemEntry(
    var name: String = "",
    var description: String = "",
    var owner: String = "",
    var aliases: List<String> = listOf()
)

@Serializable
data class FactionEntry(
    var name: String = "",
    var description: String = "",
    var leader: String = "",
    var aliases: List<String> = listOf()
)

@Serializable
data class RelationshipEntry(
    var entity1: String = "",
    var entity2: String = "",
    var type: String = "",
    var description: String = ""
)

@Serializable
data class LorebookExtraction(
    var characters: List<CharacterEntry> = listOf(),
    var events: List<EventEntry> = listOf(),
    var locations: List<LocationEntry> = listOf(),
    var items: List<ItemEntry> = listOf(),
    var factions: List<FactionEntry> = listOf(),
    var relationships: List<RelationshipEntry> = listOf()
)