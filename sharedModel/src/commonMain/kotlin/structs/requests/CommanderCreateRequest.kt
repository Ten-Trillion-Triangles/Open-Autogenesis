package structs.requests

//Defines a request to create a new commander based on it's name, and description.
@kotlinx.serialization.Serializable
data class CommanderCreateRequest(
    var name: String = "",
    var description: String = "",
    var empireDescription: String = ""
)
