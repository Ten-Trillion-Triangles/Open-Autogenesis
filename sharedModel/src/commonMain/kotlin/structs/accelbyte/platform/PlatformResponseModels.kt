package structs.accelbyte.platform

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import structs.accelbyte.common.AccelByteJson
import structs.accelbyte.common.AccelByteSerializable
import structs.accelbyte.common.ImageResponse
import structs.accelbyte.common.OffsetPagination
import structs.accelbyte.common.PagingInfo

@Serializable
data class StoreInfoResponse(
    val storeId: String,
    val title: String,
    val namespace: String,
    val defaultLanguage: String,
    val defaultRegion: String,
    val supportedLanguages: List<String>,
    val supportedRegions: List<String>,
    val published: Boolean,
    val createdAt: String,
    val updatedAt: String,
    val description: String? = null,
    val publishedTime: String? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class StoreListResponse(val stores: List<StoreInfoResponse>) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class ItemPagingResponse(
    val data: List<ItemInfoResponse>,
    val paging: OffsetPagination? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class AvailablePriceResponse(
    val currencyCode: String,
    val currencyNamespace: String,
    val price: Int,
    val discountedPrice: Int,
    val discountPurchaseAt: String? = null,
    val discountExpireAt: String? = null,
    val purchaseAt: String? = null,
    val expireAt: String? = null,
    val priceDetails: List<JsonElement>? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class EstimatedPriceInfoResponse(
    val itemId: String,
    val region: String? = null,
    val estimatedPrices: List<AvailablePriceResponse>? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class EstimatedPriceInfoListResponse(val infos: List<EstimatedPriceInfoResponse>) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class ItemInfoResponse(
    val itemId: String,
    val name: String,
    val namespace: String,
    val status: String,
    val itemType: String,
    val entitlementType: String,
    val description: String? = null,
    val longDescription: String? = null,
    val sku: String? = null,
    val title: String? = null,
    val categoryPath: String,
    val createdAt: String,
    val updatedAt: String,
    val region: String? = null,
    val language: String? = null,
    val images: List<ImageResponse>? = null,
    val ext: JsonElement? = null,
    val features: List<String> = emptyList(),
    val regionData: JsonElement? = null,
    val saleConfig: JsonElement? = null,
    val inventoryConfig: JsonElement? = null,
    val lootBoxConfig: JsonElement? = null,
    val optionBoxConfig: JsonElement? = null,
    val purchaseCondition: JsonElement? = null
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}

@Serializable
data class OrderStatistics(
    val statusCount: Map<String, Long>,
    val total: Long
) : AccelByteSerializable {
    override fun toJsonString(): String = AccelByteJson.encodeToString(serializer(), this)
}
