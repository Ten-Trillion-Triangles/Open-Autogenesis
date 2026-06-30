package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json

/**
 * Information about a single store in the platform.
 *
 * @property storeId The unique identifier of this store.
 * @property title The display title of the store.
 * @property namespace The namespace where this store exists.
 * @property defaultLanguage The default language code.
 * @property defaultRegion The default region code.
 * @property supportedLanguages List of supported language codes.
 * @property supportedRegions List of supported region codes.
 * @property published Whether the store is published.
 * @property createdAt Timestamp when the store was created.
 * @property updatedAt Timestamp when the store was last updated.
 * @property description The store description.
 * @property publishedTime Timestamp when the store was published.
 */
data class StoreInfoResponse(
    val storeId : String,
    val title : String,
    val namespace : String,
    val defaultLanguage : String,
    val defaultRegion : String,
    val supportedLanguages : List<String>,
    val supportedRegions : List<String>,
    val published : Boolean,
    val createdAt : String,
    val updatedAt : String,
    val description : String?,
    val publishedTime : String?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : StoreInfoResponse = StoreInfoResponse(
            storeId = json.requireString("storeId"),
            title = json.requireString("title"),
            namespace = json.requireString("namespace"),
            defaultLanguage = json.requireString("defaultLanguage"),
            defaultRegion = json.requireString("defaultRegion"),
            supportedLanguages = json.optStringList("supportedLanguages"),
            supportedRegions = json.optStringList("supportedRegions"),
            published = json.requireBoolean("published"),
            createdAt = json.requireString("createdAt"),
            updatedAt = json.requireString("updatedAt"),
            description = json.optString("description"),
            publishedTime = json.optString("publishedTime")
        )
    }
}

/**
 * A list of stores returned by store queries.
 *
 * @property stores The list of store information.
 */
data class StoreListResponse(
    val stores : List<StoreInfoResponse>
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : StoreListResponse = StoreListResponse(
            stores = json.optJsonList("data").takeIf { it.isNotEmpty() }?.map(StoreInfoResponse::fromJson)
                ?: json.optJsonList("stores").map(StoreInfoResponse::fromJson)
        )

        fun fromJsonArray(array : Array<Json>) : StoreListResponse =
            StoreListResponse(array.map(StoreInfoResponse::fromJson))
    }
}

/**
 * A paginated list of items from a store.
 *
 * @property data The list of items on this page.
 * @property paging Pagination metadata for navigating additional pages.
 */
data class ItemPagingResponse(
    val data : List<ItemInfoResponse>,
    val paging : OffsetPagination?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : ItemPagingResponse = ItemPagingResponse(
            data = json.optJsonList("data").map(ItemInfoResponse::fromJson),
            paging = json.optJson("paging")?.let(OffsetPagination::fromJson)
        )
    }
}

/**
 * Available price information for an item.
 *
 * @property currencyCode The currency code for this price.
 * @property currencyNamespace The namespace of the currency.
 * @property price The original price in the smallest currency unit.
 * @property discountedPrice The discounted price in the smallest currency unit.
 * @property discountPurchaseAt Timestamp when the discount started.
 * @property discountExpireAt Timestamp when the discount expires.
 * @property purchaseAt Timestamp when purchases are allowed from.
 * @property expireAt Timestamp when purchases expire.
 * @property priceDetails Additional price details as JSON.
 */
data class AvailablePriceResponse(
    val currencyCode : String,
    val currencyNamespace : String,
    val price : Int,
    val discountedPrice : Int,
    val discountPurchaseAt : String?,
    val discountExpireAt : String?,
    val purchaseAt : String?,
    val expireAt : String?,
    val priceDetails : List<Json>?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : AvailablePriceResponse = AvailablePriceResponse(
            currencyCode = json.requireString("currencyCode"),
            currencyNamespace = json.requireString("currencyNamespace"),
            price = json.requireInt("price"),
            discountedPrice = json.requireInt("discountedPrice"),
            discountPurchaseAt = json.optString("discountPurchaseAt"),
            discountExpireAt = json.optString("discountExpireAt"),
            purchaseAt = json.optString("purchaseAt"),
            expireAt = json.optString("expireAt"),
            priceDetails = json.optJsonArray("priceDetails")?.mapNotNull { it }
        )
    }
}

/**
 * Estimated price information for an item in a specific region.
 *
 * @property itemId The unique identifier of the item.
 * @property region The region for this estimate.
 * @property estimatedPrices List of available price responses for this item.
 */
data class EstimatedPriceInfoResponse(
    val itemId : String,
    val region : String?,
    val estimatedPrices : List<AvailablePriceResponse>?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : EstimatedPriceInfoResponse = EstimatedPriceInfoResponse(
            itemId = json.requireString("itemId"),
            region = json.optString("region"),
            estimatedPrices = json.optJsonList("estimatedPrices").map(AvailablePriceResponse::fromJson)
                .takeIf { it.isNotEmpty() }
        )
    }
}

/**
 * A list of estimated price information for multiple items.
 *
 * @property infos The list of estimated price information entries.
 */
data class EstimatedPriceInfoListResponse(
    val infos : List<EstimatedPriceInfoResponse>
) : AccelByteResponse {
    companion object
    {
        fun fromJson(jsonArray : Array<Json>) : EstimatedPriceInfoListResponse =
            EstimatedPriceInfoListResponse(jsonArray.map(EstimatedPriceInfoResponse::fromJson))
    }
}

/**
 * Detailed information about a store item.
 *
 * @property itemId The unique identifier of this item.
 * @property name The display name of the item.
 * @property namespace The namespace where this item exists.
 * @property status The current status of the item.
 * @property itemType The type of item (e.g., INGAMEITEM, SUBSCRIPTION).
 * @property entitlementType The entitlement type for this item.
 * @property description The item description.
 * @property longDescription The extended description of the item.
 * @property sku The stock keeping unit identifier.
 * @property title The localized title of the item.
 * @property categoryPath The category path for this item.
 * @property createdAt Timestamp when the item was created.
 * @property updatedAt Timestamp when the item was last updated.
 * @property region The region for this item.
 * @property language The language for this item.
 * @property images List of images associated with this item.
 * @property ext Extended attributes as JSON.
 * @property features List of feature flags for this item.
 * @property regionData Region-specific data as JSON.
 * @property saleConfig Sale configuration as JSON.
 * @property inventoryConfig Inventory configuration as JSON.
 * @property lootBoxConfig Loot box configuration as JSON.
 * @property optionBoxConfig Option box configuration as JSON.
 * @property purchaseCondition Purchase condition as JSON.
 */
data class ItemInfoResponse(
    val itemId : String,
    val name : String,
    val namespace : String,
    val status : String,
    val itemType : String,
    val entitlementType : String,
    val description : String?,
    val longDescription : String?,
    val sku : String?,
    val title : String?,
    val categoryPath : String,
    val createdAt : String,
    val updatedAt : String,
    val region : String?,
    val language : String?,
    val images : List<ImageResponse>?,
    val ext : Json?,
    val features : List<String>,
    val regionData : Json?,
    val saleConfig : Json?,
    val inventoryConfig : Json?,
    val lootBoxConfig : Json?,
    val optionBoxConfig : Json?,
    val purchaseCondition : Json?
) : AccelByteResponse {
    companion object
    {
        fun fromJson(json : Json) : ItemInfoResponse = ItemInfoResponse(
            itemId = json.requireString("itemId"),
            name = json.requireString("name"),
            namespace = json.requireString("namespace"),
            status = json.requireString("status"),
            itemType = json.requireString("itemType"),
            entitlementType = json.requireString("entitlementType"),
            description = json.optString("description"),
            longDescription = json.optString("longDescription"),
            sku = json.optString("sku"),
            title = json.optString("title"),
            categoryPath = json.requireString("categoryPath"),
            createdAt = json.requireString("createdAt"),
            updatedAt = json.requireString("updatedAt"),
            region = json.optString("region"),
            language = json.optString("language"),
            images = json.optJsonList("images").map(ImageResponse::fromJson).takeIf { it.isNotEmpty() },
            ext = json.optJson("ext"),
            features = json.optJsonArray("features")?.mapNotNull { it?.toString() } ?: emptyList(),
            regionData = json.optJson("regionData"),
            saleConfig = json.optJson("saleConfig"),
            inventoryConfig = json.optJson("inventoryConfig"),
            lootBoxConfig = json.optJson("lootBoxConfig"),
            optionBoxConfig = json.optJson("optionBoxConfig"),
            purchaseCondition = json.optJson("purchaseCondition")
        )
    }
}
