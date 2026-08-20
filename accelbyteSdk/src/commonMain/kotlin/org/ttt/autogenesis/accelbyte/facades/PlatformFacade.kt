package org.ttt.autogenesis.accelbyte.facades

import kotlin.js.Json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSdkInstance
import org.ttt.autogenesis.accelbyte.models.ItemInfoResponse
import org.ttt.autogenesis.accelbyte.models.StoreInfoResponse
import org.ttt.autogenesis.accelbyte.modules.PlatformModulePackage
import org.ttt.autogenesis.accelbyte.modules.ItemApi
import org.ttt.autogenesis.accelbyte.modules.StoreApi
import org.ttt.autogenesis.accelbyte.util.mapJson
import org.ttt.autogenesis.accelbyte.util.propagateJsErrors

/**
 * A thin facade over the AccelByte Platform module. Exposes marketplace and storefront
 * operations: browsing stores, retrieving item details (including loot-box items), querying
 * payment methods, entitlement listings, and currency wallet information for the current user.
 *
 * @property sdk the AccelByte SDK instance used to make API calls
 */
class PlatformFacade(private val sdk : AccelByteSdkInstance)
{
    private val storeApi : StoreApi
        get() = PlatformModulePackage.Platform.StoreApi(sdk.rawSdk)

    private val itemApi : ItemApi
        get() = PlatformModulePackage.Platform.ItemApi(sdk.rawSdk)

    /**
     * Lists all stores visible within the current namespace.
     *
     * @return a [Promise] containing a list of [StoreInfoResponse] entries for the current
     *         namespace via propagateJsErrors
     * @throws Throwable propagates any JS errors encountered during the API call
     */
    fun listStores() : Promise<List<StoreInfoResponse>> =
        storeApi.getStores()
            .propagateJsErrors()
            .mapJson { response ->
            val storesArray = response.unsafeCast<Array<Json>>()
            storesArray.map(StoreInfoResponse::fromJson)
        }

    /**
     * Retrieves an item by its SKU. Returns either a basic item or a loot-box (box) item
     * depending on the item type stored in the response.
     *
     * @param sku the SKU of the item to retrieve
     * @return a [Promise] containing the item as [ItemInfoResponse] (basic item or loot-box item)
     *         via propagateJsErrors
     * @throws Throwable propagates any JS errors encountered during the API call
     */
    fun getItem(sku : String) : Promise<ItemInfoResponse> =
        itemApi.getItemBySku(sku)
            .propagateJsErrors()
            .mapJson(ItemInfoResponse::fromJson)
}