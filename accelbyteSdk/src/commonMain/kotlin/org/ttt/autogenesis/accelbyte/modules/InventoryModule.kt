@file:JsModule("@accelbyte/sdk-inventory")
@file:JsNonModule

package org.ttt.autogenesis.accelbyte.modules

import kotlin.js.Json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSDK
import org.ttt.autogenesis.accelbyte.SdkSetConfigParam

external val Inventory : InventoryNamespace

external object InventoryModulePackage {
    val Inventory: InventoryNamespace
}

external interface InventoryNamespace
{
    fun TagsAdminApi(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : TagsAdminApi
    fun ItemTypesAdminApi(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : ItemTypesAdminApi
    fun InventoriesAdminApi(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : InventoriesAdminApi
    fun ChainingOperationsAdminApi(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : ChainingOperationsAdminApi
    fun ItemsAdminApi(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : ItemsAdminApi
    fun InventoryConfigurationsAdminApi(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : InventoryConfigurationsAdminApi
    fun IntegrationConfigurationsAdminApi(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : IntegrationConfigurationsAdminApi
    fun PublicTagsApi(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : PublicTagsApi
    fun PublicItemTypesApi(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : PublicItemTypesApi
    fun PublicInventoriesApi(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : PublicInventoriesApi
    fun PublicInventoryConfigurationsApi(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : PublicInventoryConfigurationsApi
    fun PublicItemsApi(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : PublicItemsApi
}

external interface TagsAdminApi
{
    fun createTag(data : Json) : Promise<Json>
    fun deleteTag(tagId : String) : Promise<Json>
}

external interface ItemTypesAdminApi
{
    fun createItemtype(data : Json) : Promise<Json>
    fun deleteItemtype(code : String) : Promise<Json>
    fun updateItemtype(code : String, data : Json) : Promise<Json>
}

external interface InventoriesAdminApi
{
    fun createInventory(data : Json) : Promise<Json>
    fun deleteInventory(inventoryId : String) : Promise<Json>
    fun updateInventory(inventoryId : String, data : Json) : Promise<Json>
    fun getInventory(inventoryId : String) : Promise<Json>
    fun getUserInventories(userId : String, queryParams : Json = definedExternally) : Promise<Json>
}

external interface ChainingOperationsAdminApi
{
    fun createChainingOperation(data : Json) : Promise<Json>
}

external interface ItemsAdminApi
{
    fun createItem(data : Json) : Promise<Json>
    fun deleteItem(inventoryId : String, data : Json) : Promise<Json>
    fun updateItem(inventoryId : String, data : Json) : Promise<Json>
    fun getItem(inventoryId : String, slotId : String, sourceItemId : String) : Promise<Json>
    fun getItems(inventoryId : String, queryParams : Json = definedExternally) : Promise<Json>
    fun bulkSaveItem(data : Json) : Promise<Json>
}

external interface InventoryConfigurationsAdminApi
{
    fun createInventoryConfiguration(data : Json) : Promise<Json>
    fun deleteInventoryConfiguration(code : String) : Promise<Json>
    fun updateInventoryConfiguration(code : String, data : Json) : Promise<Json>
    fun getInventoryConfiguration(code : String) : Promise<Json>
}

external interface IntegrationConfigurationsAdminApi
{
    fun createIntegrationConfiguration(data : Json) : Promise<Json>
    fun deleteIntegrationConfiguration(code : String) : Promise<Json>
    fun updateIntegrationConfiguration(code : String, data : Json) : Promise<Json>
    fun updateStatusIntegrationConfiguration(code : String, data : Json) : Promise<Json>
    fun getIntegrationConfiguration(code : String) : Promise<Json>
}

external interface PublicTagsApi
{
    fun getTags(queryParams : Json = definedExternally) : Promise<Json>
}

external interface PublicItemTypesApi
{
    fun getItemtypes(queryParams : Json = definedExternally) : Promise<Json>
}

external interface PublicInventoriesApi
{
    fun getUsersMeInventories(queryParams : Json = definedExternally) : Promise<Json>
}

external interface PublicInventoryConfigurationsApi
{
    fun getInventoryConfigurations(queryParams : Json = definedExternally) : Promise<Json>
}

external interface PublicItemsApi
{
    fun deleteItemMeUser_ByInventoryId(inventoryId : String, data : Json) : Promise<Json>
    fun getItemsMeUsers_ByInventoryId(inventoryId : String, queryParams : Json = definedExternally) : Promise<Json>
    fun updateItemMeUser_ByInventoryId(inventoryId : String, data : Json) : Promise<Json>
    fun createConsumeUser_ByInventoryId(inventoryId : String, data : Json) : Promise<Json>
    fun createItemMovementUser_ByInventoryId(inventoryId : String, data : Json) : Promise<Json>
    fun getSourceItemMeUser_ByInventoryId_BySlotId_BySourceItemId(inventoryId : String, slotId : String, sourceItemId : String) : Promise<Json>
}