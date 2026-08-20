package structs.rpcRequests

import kotlinx.serialization.Serializable

/**
 * Request types for the server-extend BinaryRecordProxy RPC surface.
 *
 * Each request wraps the parameters a single binary-record endpoint needs.
 * The naming follows the existing pattern (`Get*Request`, `Save*Request`).
 *
 * The web client calls these RPCs to bypass CORS for the AGS Cloud Save
 * binary endpoints. The proxy on server-extend handles metadata only —
 * byte uploads/downloads happen directly between the client and the
 * AGS-issued presigned URL.
 */

// ---- Game Binary (public) ----------------------------------------------------

@Serializable
data class ListGameBinaryRequest(
    val limit: Int? = null,
    val offset: Int? = null,
    val query: String? = null,
    val tags: List<String>? = null
)

@Serializable
data class GetGameBinaryRequest(val key: String)

@Serializable
data class CreateGameBinaryRequest(
    val key: String,
    val fileType: String,
    val setBy: String? = null
)

@Serializable
data class ReplaceGameBinaryRequest(
    val key: String,
    val contentType: String,
    val fileLocation: String
)

@Serializable
data class DeleteGameBinaryRequest(val key: String)

@Serializable
data class BulkGetGameBinaryRequest(val keys: List<String>)

@Serializable
data class GameBinaryPresignedRequest(
    val key: String,
    val fileType: String
)

// ---- Game Binary (admin) -----------------------------------------------------

@Serializable
data class AdminListGameBinaryRequest(
    val limit: Int,
    val offset: Int,
    val query: String? = null,
    val tags: List<String>? = null
)

@Serializable
data class AdminGetGameBinaryRequest(val key: String)

@Serializable
data class AdminCreateGameBinaryRequest(
    val key: String,
    val fileType: String,
    val setBy: String? = null
)

@Serializable
data class AdminReplaceGameBinaryRequest(
    val key: String,
    val contentType: String,
    val fileLocation: String
)

@Serializable
data class AdminDeleteGameBinaryRequest(val key: String)

@Serializable
data class AdminGameBinaryPresignedRequest(
    val key: String,
    val fileType: String
)

@Serializable
data class AdminGameBinaryMetadataRequest(
    val key: String,
    val setBy: String? = null,
    val tags: List<String>? = null
)

// ---- Player Binary (public, /users/me/) -------------------------------------

@Serializable
data class ListMyBinaryRequest(
    val limit: Int? = null,
    val offset: Int? = null,
    val query: String? = null,
    val tags: List<String>? = null
)

@Serializable
data class GetMyBinaryRequest(val key: String)

@Serializable
data class CreateMyBinaryRequest(
    val key: String,
    val fileType: String,
    val setBy: String? = null
)

@Serializable
data class ReplaceMyBinaryRequest(
    val key: String,
    val contentType: String,
    val fileLocation: String
)

@Serializable
data class DeleteMyBinaryRequest(val key: String)

@Serializable
data class BulkGetMyBinaryRequest(val keys: List<String>)

@Serializable
data class MyBinaryPresignedRequest(
    val key: String,
    val fileType: String
)

// ---- Player Binary (admin) ---------------------------------------------------

@Serializable
data class AdminListPlayerBinaryRequest(
    val userId: String,
    val limit: Int? = null,
    val offset: Int? = null,
    val query: String? = null,
    val tags: List<String>? = null
)

@Serializable
data class AdminGetPlayerBinaryRequest(
    val userId: String,
    val key: String
)

@Serializable
data class AdminCreatePlayerBinaryRequest(
    val userId: String,
    val key: String,
    val fileType: String,
    val setBy: String? = null
)

@Serializable
data class AdminReplacePlayerBinaryRequest(
    val userId: String,
    val key: String,
    val contentType: String,
    val fileLocation: String
)

@Serializable
data class AdminDeletePlayerBinaryRequest(
    val userId: String,
    val key: String
)

@Serializable
data class AdminPlayerBinaryPresignedRequest(
    val userId: String,
    val key: String,
    val fileType: String
)

@Serializable
data class AdminPlayerBinaryMetadataRequest(
    val userId: String,
    val key: String,
    val setBy: String? = null,
    val tags: List<String>? = null
)
