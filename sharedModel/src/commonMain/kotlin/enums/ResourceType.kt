package enums

import kotlinx.serialization.Serializable

/**
 * Defines the type of thing a resource is.
 */
@Serializable
enum class ResourceType
{
    Military,
    Diplomatic,
    Economic,
    Scientific,
    Technological,
    Supernatural,
    Magical,
    Subordinate;

    companion object
    {
        fun options(): List<Pair<String, String>> = values().map { it.name to it.name }
    }
}
