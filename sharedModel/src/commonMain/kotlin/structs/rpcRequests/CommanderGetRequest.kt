package structs.rpcRequests

/**
 * Request to read a saved commander record for a given user. Wraps the user id the extend proxy needs and the key.
 * The extend-server proxy will then bypass cors and run the record request. Provided the record is publicly listed on
 * the master-record for the user, it will retrieve, convert, and return it back through the rpc pipe.
 */
@kotlinx.serialization.Serializable
data class CommanderRecordRequest(val userId: String, val key: String)