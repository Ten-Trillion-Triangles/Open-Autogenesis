@file:JsModule("@accelbyte/sdk-cloudsave")
@file:JsNonModule

package org.ttt.autogenesis.accelbyte.modules

import kotlin.js.Json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSDK
import org.ttt.autogenesis.accelbyte.SdkSetConfigParam

typealias PublicGameRecordApiFactory = (sdk: AccelByteSDK, args: SdkSetConfigParam?) -> PublicGameRecordApi
typealias RecordAdminApiFactory = (sdk: AccelByteSDK, args: SdkSetConfigParam?) -> RecordAdminApi
typealias PlayerRecordAdminApiFactory = (sdk: AccelByteSDK, args: SdkSetConfigParam?) -> PlayerRecordAdminApi
typealias PublicPlayerRecordApiFactory = (sdk: AccelByteSDK, args: SdkSetConfigParam?) -> PublicPlayerRecordApi

external val Cloudsave: CloudsaveNamespace

external interface CloudsaveNamespace {
    val PublicGameRecordApi: PublicGameRecordApiFactory
    val RecordAdminApi: RecordAdminApiFactory
    val PlayerRecordAdminApi: PlayerRecordAdminApiFactory
    val PublicPlayerRecordApi: PublicPlayerRecordApiFactory
}

external interface PublicGameRecordApi {
    fun getRecord_ByKey(key: String): Promise<Json>
    fun createRecord_ByKey(key: String, data: Json): Promise<Json>
    fun updateRecord_ByKey(key: String, data: Json): Promise<Json>
    fun fetchRecordBulk(data: Json): Promise<Json>
}

external interface RecordAdminApi {
    fun getAdminrecord_ByUserId_ByKey(userId: String, key: String): Promise<Json>
    fun createAdminrecord_ByUserId_ByKey(userId: String, key: String, data: Json): Promise<Json>
    fun updateAdminrecord_ByUserId_ByKey(userId: String, key: String, data: Json): Promise<Json>
}

external interface PlayerRecordAdminApi {
    fun getRecord_ByUserId_ByKey(userId: String, key: String): Promise<Json>
    fun createRecord_ByUserId_ByKey(userId: String, key: String, data: Json): Promise<Json>
    fun updateRecord_ByUserId_ByKey(userId: String, key: String, data: Json): Promise<Json>
    fun getPublic_ByUserId_ByKey(userId: String, key: String): Promise<Json>
}

external interface PublicPlayerRecordApi {
    fun getRecord_ByUserId_ByKey(userId: String, key: String): Promise<Json>
    fun createRecord_ByUserId_ByKey(userId: String, key: String, data: Json): Promise<Json>
    fun updateRecord_ByUserId_ByKey(userId: String, key: String, data: Json): Promise<Json>
}
