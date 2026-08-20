package structs.storage

/**
 * Data class that holds the master record for a player's account. This allows us to know exactly what keys belong
 * to what kinds of records for any record that are dynamically generated at runtime based on the actions of the user
 * instead of known, developer defined values.
 */
@kotlinx.serialization.Serializable
data class MasterRecord(
    var commanderKeys: MutableList<String> = mutableListOf(),
    var storyKeys: MutableList<String> = mutableListOf()
)