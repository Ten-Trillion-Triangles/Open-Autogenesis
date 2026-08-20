package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json
import kotlin.js.json

/**
 * Optional namespace list parameters exposed by `BasicFacade#listNamespaces`.
 * @property activeOnly when true, filters to active namespaces.
 */
data class NamespaceQueryParams(val activeOnly : Boolean? = null) : AccelByteRequest
{
    override fun toJson() : Json = jsonOf("activeOnly" to activeOnly)
}

/**
 * Represents file upload creation metadata for user/folder endpoints.
 * @property fileType MIME hint such as `application/octet-stream`.
 */
data class FileUploadRequest(val fileType : String) : AccelByteRequest
{
    override fun toJson() : Json = json("fileType" to fileType)
}

/**
 * Request payload for retrieving user statistics/profile data by IDs.
 * @property userIds comma-delimited list of user identifiers.
 */
data class UserProfileQueryParams(val userIds : List<String>) : AccelByteRequest
{
    override fun toJson() : Json = jsonOf("userIds" to userIds.joinToString(","))
}