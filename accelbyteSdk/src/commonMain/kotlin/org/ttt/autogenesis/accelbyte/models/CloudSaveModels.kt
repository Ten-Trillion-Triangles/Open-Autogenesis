package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json
import kotlin.js.json

/**
 * Request for creating/updating a cloud save record.
 * @property data JSON payload stored in the record.
 * @property updateIfExists when true will patch an existing save instead of failing.
 */
data class GameRecordRequest(val data : Json, val updateIfExists : Boolean = true) : AccelByteRequest
{
    override fun toJson() : Json = json("data" to data, "updateIfExists" to updateIfExists)
}

/**
 * Bulk fetch request returning multiple records in one call.
 * @property keys keys identifying the records to fetch.
 */
data class BulkGameRecordRequest(val keys : List<String>) : AccelByteRequest
{
    override fun toJson() : Json = json("keys" to keys)
}
