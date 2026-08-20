@file:JsModule("@accelbyte/sdk-audit")
@file:JsNonModule

package org.ttt.autogenesis.accelbyte.modules

import kotlin.js.Json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSDK
import org.ttt.autogenesis.accelbyte.SdkSetConfigParam

external val Audit : AuditNamespace

external object AuditModulePackage {
    val Audit: AuditNamespace
}

external interface AuditNamespace
{
    fun AccountEventsAdminApi(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : AccountEventsAdminApi
    fun AccountHistoryAdminApi(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : AccountHistoryAdminApi
    fun AuditLogCommentAdminApi(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : AuditLogCommentAdminApi
    fun AuditLogsAdminApi(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : AuditLogsAdminApi
    fun AccountHistoryApi(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : AccountHistoryApi
}

external interface AccountEventsAdminApi
{
    fun getEventsCritical_ByUserId(userId : String, queryParams : Json = definedExternally) : Promise<Json>
    fun getEventsCriticalCategories_ByUserId(userId : String, queryParams : Json = definedExternally) : Promise<Json>
}

external interface AccountHistoryAdminApi
{
    fun getUsersMeAccountHistories(queryParams : Json = definedExternally) : Promise<Json>
    fun deleteAccountHistory_ByUserId(userId : String) : Promise<Json>
    fun getAccountHistories_ByUserId(userId : String, queryParams : Json = definedExternally) : Promise<Json>
}

external interface AuditLogCommentAdminApi
{
    fun getComments(queryParams : Json) : Promise<Json>
    fun createComment(data : Json) : Promise<Json>
    fun deleteComment_ByCommentId(commentId : String) : Promise<Json>
    fun patchComment_ByCommentId(commentId : String, data : Json) : Promise<Json>
    fun deleteComment_ByUserId(userId : String) : Promise<Json>
}

external interface AuditLogsAdminApi
{
    fun getLogs(queryParams : Json = definedExternally) : Promise<Json>
    fun getLogsExport(queryParams : Json = definedExternally) : Promise<Json>
    fun getConfigCategories(queryParams : Json = definedExternally) : Promise<Json>
    fun getConfigTimeRange() : Promise<Json>
    fun getLog_ByLogId(logId : String) : Promise<Json>
}

external interface AccountHistoryApi
{
    fun getUsersMeAccountHistories(queryParams : Json = definedExternally) : Promise<Json>
}