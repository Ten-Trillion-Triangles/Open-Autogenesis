@file:JsModule("@accelbyte/sdk-chat")
@file:JsNonModule

package org.ttt.autogenesis.accelbyte.modules

import kotlin.js.Json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSDK
import org.ttt.autogenesis.accelbyte.SdkSetConfigParam

external object ChatModulePackage {
    val Chat: ChatNamespace
}

external interface ChatNamespace {
    val TopicApi: TopicApiFactory
}

external interface TopicApiFactory {
    operator fun invoke(sdk: AccelByteSDK, args: SdkSetConfigParam = definedExternally): TopicApi
}

external interface TopicApi {
    fun getMuted(): Promise<Json>
    fun getTopic(queryParams: Json = definedExternally): Promise<Json>
    fun getChats_ByTopic(topic: String, queryParams: Json = definedExternally): Promise<Json>
    fun updateMute_ByTopic(topic: String, data: Json): Promise<Json>
    fun updateUnmute_ByTopic(topic: String, data: Json): Promise<Json>
    fun updateBanMember_ByTopic(topic: String, data: Json): Promise<Json>
    fun updateUnbanMember_ByTopic(topic: String, data: Json): Promise<Json>
    fun deleteChat_ByTopic_ByChatId(topic: String, chatId: String): Promise<Json>
}