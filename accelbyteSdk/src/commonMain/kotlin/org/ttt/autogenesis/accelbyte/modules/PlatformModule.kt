@file:JsModule("@accelbyte/sdk-platform")
@file:JsNonModule

package org.ttt.autogenesis.accelbyte.modules

import kotlin.js.Json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSDK
import org.ttt.autogenesis.accelbyte.SdkSetConfigParam

/**
 * External object representing the Platform module package from AccelByte SDK.
 * Provides access to platform and commerce functionality.
 */
external object PlatformModulePackage
{
    val Platform : PlatformNamespace
}

/**
 * Namespace interface for Platform-related APIs and services.
 * Contains factories for store and item operations.
 */
external interface PlatformNamespace
{
    val StoreApi : StoreApiFactory
    val ItemApi : ItemApiFactory
}

/**
 * Factory interface for creating Store API instances.
 * Handles store management operations.
 */
external interface StoreApiFactory
{
    /**
     * Creates a Store API instance.
     * @param sdk The AccelByte SDK instance
     * @param args Optional configuration parameters
     * @return Store API instance
     */
    operator fun invoke(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : StoreApi
}

/**
 * Store API interface for store operations.
 * Provides methods for store management.
 */
external interface StoreApi
{
    /**
     * Retrieves available stores.
     * @return Promise resolving to stores data
     */
    fun getStores() : Promise<Json>
}

/**
 * Factory interface for creating Item API instances.
 * Handles item management operations.
 */
external interface ItemApiFactory
{
    /**
     * Creates an Item API instance.
     * @param sdk The AccelByte SDK instance
     * @param args Optional configuration parameters
     * @return Item API instance
     */
    operator fun invoke(sdk : AccelByteSDK, args : SdkSetConfigParam = definedExternally) : ItemApi
}

/**
 * Item API interface for item operations.
 * Provides methods for item retrieval and management.
 */
external interface ItemApi
{
    /**
     * Retrieves item by SKU.
     * @param sku The item SKU to retrieve
     * @return Promise resolving to item data
     */
    fun getItemBySku(sku : String) : Promise<Json>
}
