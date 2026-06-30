package org.ttt.autogenesis.accelbyte.facades

import kotlin.js.Json
import kotlin.js.json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSdkInstance
import org.ttt.autogenesis.accelbyte.modules.InventoryModulePackage
import org.ttt.autogenesis.accelbyte.util.propagateJsErrors

/**
 * A facade over the AccelByte Inventory module. Manages player inventory storage: the containers (inventories) that hold items, the item slots within them, and the type/tag taxonomy that organizes them. Admin endpoints allow management of any user's inventory; public endpoints allow players to access their own.
 */
class InventoryFacade(private val sdk : AccelByteSdkInstance)
{
    private val inventoriesAdminApi = InventoryModulePackage.Inventory.InventoriesAdminApi(sdk.rawSdk)
    private val itemsAdminApi = InventoryModulePackage.Inventory.ItemsAdminApi(sdk.rawSdk)
    private val itemTypesAdminApi = InventoryModulePackage.Inventory.ItemTypesAdminApi(sdk.rawSdk)
    private val tagsAdminApi = InventoryModulePackage.Inventory.TagsAdminApi(sdk.rawSdk)
    private val inventoryConfigurationsAdminApi = InventoryModulePackage.Inventory.InventoryConfigurationsAdminApi(sdk.rawSdk)
    private val integrationConfigurationsAdminApi = InventoryModulePackage.Inventory.IntegrationConfigurationsAdminApi(sdk.rawSdk)
    private val chainingOperationsAdminApi = InventoryModulePackage.Inventory.ChainingOperationsAdminApi(sdk.rawSdk)
    private val publicInventoriesApi = InventoryModulePackage.Inventory.PublicInventoriesApi(sdk.rawSdk)
    private val publicItemsApi = InventoryModulePackage.Inventory.PublicItemsApi(sdk.rawSdk)
    private val publicItemTypesApi = InventoryModulePackage.Inventory.PublicItemTypesApi(sdk.rawSdk)
    private val publicTagsApi = InventoryModulePackage.Inventory.PublicTagsApi(sdk.rawSdk)
    private val publicInventoryConfigurationsApi = InventoryModulePackage.Inventory.PublicInventoryConfigurationsApi(sdk.rawSdk)

    /**
     * Creates a new inventory.
     *
     * @param data The JSON payload matching AccelByte inventory creation schema.
     * @return The created inventory record as raw JSON.
     * @throws network errors propagate via propagateJsErrors.
     */
    fun createInventory(data : Json) : Promise<Json> = inventoriesAdminApi.createInventory(data).propagateJsErrors()

    /**
     * Gets an inventory by ID.
     *
     * @param inventoryId The target inventory identifier.
     * @return Inventory record JSON.
     */
    fun getInventory(inventoryId : String) : Promise<Json> = inventoriesAdminApi.getInventory(inventoryId).propagateJsErrors()

    /**
     * Updates an existing inventory.
     *
     * @param inventoryId The target inventory identifier to update.
     * @param data The patch JSON containing updated inventory fields.
     * @return The updated inventory JSON.
     */
    fun updateInventory(inventoryId : String, data : Json) : Promise<Json> = inventoriesAdminApi.updateInventory(inventoryId, data).propagateJsErrors()

    /**
     * Deletes an inventory.
     *
     * @param inventoryId The target inventory identifier to delete.
     * @return The deleted inventory JSON.
     */
    fun deleteInventory(inventoryId : String) : Promise<Json> = inventoriesAdminApi.deleteInventory(inventoryId).propagateJsErrors()

    /**
     * Gets inventories for a specific user.
     *
     * @param userId The target user identifier.
     * @param queryParams Optional JSON filter (limit, offset, etc.).
     * @return List of inventory records JSON.
     */
    fun getUserInventories(userId : String, queryParams : Json = json()) : Promise<Json> = inventoriesAdminApi.getUserInventories(userId, queryParams).propagateJsErrors()

    /**
     * Gets all items in an inventory.
     *
     * @param inventoryId The inventory identifier.
     * @param queryParams Optional JSON filter.
     * @return Item list JSON.
     */
    fun getItems(inventoryId : String, queryParams : Json = json()) : Promise<Json> = itemsAdminApi.getItems(inventoryId, queryParams).propagateJsErrors()

    /**
     * Gets a specific item from an inventory.
     *
     * @param inventoryId The inventory identifier.
     * @param slotId The item slot identifier.
     * @param sourceItemId The item identifier.
     * @return Item JSON.
     */
    fun getItem(inventoryId : String, slotId : String, sourceItemId : String) : Promise<Json> = itemsAdminApi.getItem(inventoryId, slotId, sourceItemId).propagateJsErrors()

    /**
     * Creates a new item in an inventory.
     *
     * @param data The item creation JSON.
     * @return The created item JSON.
     */
    fun createItem(data : Json) : Promise<Json> = itemsAdminApi.createItem(data).propagateJsErrors()

    /**
     * Updates an item in an inventory.
     *
     * @param inventoryId The inventory identifier.
     * @param data The update JSON containing item fields to update.
     * @return The updated item JSON.
     */
    fun updateItem(inventoryId : String, data : Json) : Promise<Json> = itemsAdminApi.updateItem(inventoryId, data).propagateJsErrors()

    /**
     * Deletes an item from an inventory.
     *
     * @param inventoryId The inventory identifier.
     * @param data The deletion criteria JSON.
     * @return The deleted item JSON.
     */
    fun deleteItem(inventoryId : String, data : Json) : Promise<Json> = itemsAdminApi.deleteItem(inventoryId, data).propagateJsErrors()

    /**
     * Bulk saves items to an inventory.
     *
     * @param data The bulk save body JSON (array of items).
     * @return The save result JSON.
     */
    fun bulkSaveItem(data : Json) : Promise<Json> = itemsAdminApi.bulkSaveItem(data).propagateJsErrors()

    /**
     * Creates a chaining operation for inventory management.
     *
     * @param data The ChainingOperation JSON.
     * @return The operation result JSON.
     */
    fun createChainingOperation(data : Json) : Promise<Json> = chainingOperationsAdminApi.createChainingOperation(data).propagateJsErrors()

    /**
     * Gets the current user's inventories.
     *
     * @param queryParams Optional JSON filter.
     * @return Current user's inventories JSON.
     */
    fun getMyInventories(queryParams : Json = json()) : Promise<Json> = publicInventoriesApi.getUsersMeInventories(queryParams).propagateJsErrors()

    /**
     * Gets the current user's items from an inventory.
     *
     * @param inventoryId The inventory identifier.
     * @param queryParams Optional filter JSON.
     * @return Current user's items JSON.
     */
    fun getMyItems(inventoryId : String, queryParams : Json = json()) : Promise<Json> = publicItemsApi.getItemsMeUsers_ByInventoryId(inventoryId, queryParams).propagateJsErrors()

    /**
     * Updates a user's item in an inventory.
     *
     * @param inventoryId The inventory identifier.
     * @param data The update JSON containing item fields to update.
     * @return The updated item JSON.
     */
    fun updateMyItem(inventoryId : String, data : Json) : Promise<Json> = publicItemsApi.updateItemMeUser_ByInventoryId(inventoryId, data).propagateJsErrors()

    /**
     * Consumes an item from the user's inventory.
     *
     * @param inventoryId The inventory identifier.
     * @param data The consumption body JSON (quantity, etc.).
     * @return The consumption result JSON.
     */
    fun consumeItem(inventoryId : String, data : Json) : Promise<Json> = publicItemsApi.createConsumeUser_ByInventoryId(inventoryId, data).propagateJsErrors()

    /**
     * Moves items within the user's inventory.
     *
     * @param inventoryId The inventory identifier.
     * @param data The movement spec JSON.
     * @return The movement result JSON.
     */
    fun moveItems(inventoryId : String, data : Json) : Promise<Json> = publicItemsApi.createItemMovementUser_ByInventoryId(inventoryId, data).propagateJsErrors()

    /**
     * Gets available item types.
     *
     * @param queryParams Optional filter JSON.
     * @return Item types JSON.
     */
    fun getItemTypes(queryParams : Json = json()) : Promise<Json> = publicItemTypesApi.getItemtypes(queryParams).propagateJsErrors()

    /**
     * Gets available tags.
     *
     * @param queryParams Optional filter JSON.
     * @return Tags JSON.
     */
    fun getTags(queryParams : Json = json()) : Promise<Json> = publicTagsApi.getTags(queryParams).propagateJsErrors()

    /**
     * Gets inventory configuration.
     *
     * @param code The configuration code identifier.
     * @return Configuration JSON.
     */
    fun getInventoryConfiguration(code : String) : Promise<Json> = inventoryConfigurationsAdminApi.getInventoryConfiguration(code).propagateJsErrors()

    /**
     * Gets public inventory configurations.
     *
     * @param queryParams Optional filter JSON.
     * @return Configurations JSON.
     */
    fun getPublicConfigurations(queryParams : Json = json()) : Promise<Json> = publicInventoryConfigurationsApi.getInventoryConfigurations(queryParams).propagateJsErrors()
}
