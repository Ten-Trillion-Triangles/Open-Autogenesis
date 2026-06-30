package serverStructs

@kotlinx.serialization.Serializable
data class TrueFalse(
    var isTrue: Boolean = false,
    var reason: String = ""
)