package org.ttt.autogenesis.accelbyte.models

import kotlin.js.Json

/**
 * Summary of an item available in the platform marketplace, without pricing-specific details.
 *
 * @property id unique identifier for this item.
 * @property name optional human-readable item name.
 * @property description optional descriptive text.
 * @property tags optional list of free-form tags associated with this item.
 * @property status optional status string (e.g., "ACTIVE", "INACTIVE").
 * @property price optional price in the default currency. Null when price is dynamic or uninitialized.
 * @property sku optional SKU string used in inventory and order systems.
 */
data class BasicItem(
    val id: String,
    val name: String?,
    val description: String?,
    val tags: List<String>?,
    val status: String?,
    val price: Double?,
    val sku: String?
) : AccelByteResponse {
    companion object {
        /**
         * Parses a [BasicItem] from the API response JSON.
         *
         * @param json the raw JSON returned by the platform service.
         * @return a fully populated [BasicItem].
         * @throws IllegalArgumentException if [id][BasicItem.id] is absent.
         */
        fun fromJson(json: Json): BasicItem {
            val actualData = json.asDynamic().data?.unsafeCast<Json>() ?: json
            return BasicItem(
                id = actualData.unsafeCast<Json>().requireString("id"),
                name = actualData.unsafeCast<Json>().optString("name"),
                description = actualData.unsafeCast<Json>().optString("description"),
                tags = actualData.unsafeCast<Json>().optStringList("tags"),
                status = actualData.unsafeCast<Json>().optString("status"),
                price = actualData.unsafeCast<Json>().optDouble("price"),
                sku = actualData.unsafeCast<Json>().optString("sku")
            )
        }
    }
}

/**
 * A loot-box type item paired with its configuration, including the reward tables.
 *
 * @property itemId the unique identifier of this loot box item.
 * @property name optional human-readable loot box name.
 * @property lootBoxConfig the loot box configuration, including display type and reward list.
 */
data class BoxItem(
    val itemId: String,
    val name: String?,
    val lootBoxConfig: LootBoxConfig?
) : AccelByteResponse {
    companion object {
        /**
         * Parses a [BoxItem] from the API response JSON.
         *
         * @param json the raw JSON returned by the platform service.
         * @return a fully populated [BoxItem].
         * @throws IllegalArgumentException if [itemId][BoxItem.itemId] is absent.
         */
        fun fromJson(json: Json): BoxItem {
            val actualData = json.asDynamic().data?.unsafeCast<Json>() ?: json
            return BoxItem(
                itemId = actualData.unsafeCast<Json>().requireString("itemId"),
                name = actualData.unsafeCast<Json>().optString("name"),
                lootBoxConfig = actualData.unsafeCast<Json>()
                    .optJson("lootBoxConfig")?.let { LootBoxConfig.fromJson(it) }
            )
        }
    }
}

/**
 * Configuration data for a loot box item — defines how the box is displayed and what it can reward.
 *
 * @property displayType how the loot box UI is to render the opening experience
 *                        (e.g., "STANDARD", "ANIMATED").
 * @property rewards the list of possible reward entries for this loot box.
 */
data class LootBoxConfig(
    val displayType: String?,
    val rewards: List<LootBoxReward>?
) : AccelByteResponse {
    companion object {
        /**
         * Parses a [LootBoxConfig] from the API response JSON.
         *
         * @param json the raw JSON returned by the platform service.
         * @return a fully populated [LootBoxConfig].
         */
        fun fromJson(json: Json): LootBoxConfig {
            val actualData = json.asDynamic().data?.unsafeCast<Json>() ?: json
            return LootBoxConfig(
                displayType = actualData.unsafeCast<Json>().optString("displayType"),
                rewards = actualData.unsafeCast<Json>()
                    .optJsonList("rewards")
                    .map { LootBoxReward.fromJson(it) }
            )
        }
    }
}

/**
 * A single reward entry within a [LootBoxConfig]'s reward table.
 *
 * @property itemId the item identifier granted when this reward entry is selected.
 * @property itemType optional item type string (distinguishes consumables, cosmetics, currency, etc.).
 * @property quantity the number of units of the item granted (null for unique/one-of items).
 * @property rarity optional rarity tier for this reward (e.g., "COMMON", "RARE", "LEGENDARY").
 */
data class LootBoxReward(
    val itemId: String,
    val itemType: String?,
    val quantity: Int?,
    val rarity: String?
) : AccelByteResponse {
    companion object {
        /**
         * Parses a [LootBoxReward] from the API response JSON.
         *
         * @param json the raw JSON returned by the platform service.
         * @return a fully populated [LootBoxReward].
         * @throws IllegalArgumentException if [itemId][LootBoxReward.itemId] is absent.
         */
        fun fromJson(json: Json): LootBoxReward {
            val actualData = json.asDynamic().data?.unsafeCast<Json>() ?: json
            return LootBoxReward(
                itemId = actualData.unsafeCast<Json>().requireString("itemId"),
                itemType = actualData.unsafeCast<Json>().optString("itemType"),
                quantity = actualData.unsafeCast<Json>().optInt("quantity"),
                rarity = actualData.unsafeCast<Json>().optString("rarity")
            )
        }
    }
}

/**
 * A purchase or redemption order placed by a user in the platform marketplace.
 *
 * @property orderId unique identifier for this order.
 * @property userId the user who placed the order.
 * @property namespace the namespace in which the order was placed.
 * @property currency the currency code used for payment (e.g., "USD", "EUR"). Null for free orders.
 * @property totalPrice the total price charged, in [currency]. Null for free orders.
 * @property status optional order status string (e.g., "PENDING", "FULFILLED", "CHARGEBACK").
 * @property createdAt ISO-8601 timestamp when the order was created.
 */
data class Order(
    val orderId: String,
    val userId: String,
    val namespace: String,
    val currency: String?,
    val totalPrice: Double?,
    val status: String?,
    val createdAt: String?
) : AccelByteResponse {
    companion object {
        /**
         * Parses an [Order] from the API response JSON.
         *
         * @param json the raw JSON returned by the platform service.
         * @return a fully populated [Order].
         * @throws IllegalArgumentException if [orderId][Order.orderId], [userId][Order.userId],
         *         or [namespace][Order.namespace] is absent.
         */
        fun fromJson(json: Json): Order {
            val actualData = json.asDynamic().data?.unsafeCast<Json>() ?: json
            return Order(
                orderId = actualData.unsafeCast<Json>().requireString("orderId"),
                userId = actualData.unsafeCast<Json>().requireString("userId"),
                namespace = actualData.unsafeCast<Json>().requireString("namespace"),
                currency = actualData.unsafeCast<Json>().optString("currency"),
                totalPrice = actualData.unsafeCast<Json>().optDouble("totalPrice"),
                status = actualData.unsafeCast<Json>().optString("status"),
                createdAt = actualData.unsafeCast<Json>().optString("createdAt")
            )
        }
    }
}

/**
 * A stored payment instrument for a user, associated with a particular payment provider.
 *
 * @property paymentProvider the payment provider name (e.g., "STEAM", "XBOX", "PAYPAL").
 * @property paymentMethodId the provider-assigned identifier for this payment instrument.
 * @property status optional status of the payment method (e.g., "ACTIVE", "EXPIRED").
 */
data class PaymentMethod(
    val paymentProvider: String?,
    val paymentMethodId: String?,
    val status: String?
) : AccelByteResponse {
    companion object {
        /**
         * Parses a [PaymentMethod] from the API response JSON.
         *
         * @param json the raw JSON returned by the platform service.
         * @return a fully populated [PaymentMethod].
         */
        fun fromJson(json: Json): PaymentMethod {
            val actualData = json.asDynamic().data?.unsafeCast<Json>() ?: json
            return PaymentMethod(
                paymentProvider = actualData.unsafeCast<Json>().optString("paymentProvider"),
                paymentMethodId = actualData.unsafeCast<Json>().optString("paymentMethodId"),
                status = actualData.unsafeCast<Json>().optString("status")
            )
        }
    }
}

/**
 * Summary record for a single entitlement held by a user.
 *
 * An entitlement grants a user the right to access or consume a platform item.
 *
 * @property id unique identifier for this entitlement.
 * @property userId the user who holds this entitlement.
 * @property namespace the namespace in which the entitlement was granted.
 * @property itemId the item this entitlement covers.
 * @property entitlementType optional type label (e.g., "PERMANENT", "SUBSCRIPTION", "CONSUMABLE").
 * @property name optional human-readable entitlement name.
 * @property sku optional SKU string for the entitled item.
 * @property owned true if the entitlement is currently active (not revoked, not expired).
 * @property createdAt ISO-8601 timestamp when the entitlement was created.
 */
data class EntitlementSummary(
    val id: String,
    val userId: String,
    val namespace: String,
    val itemId: String,
    val entitlementType: String?,
    val name: String?,
    val sku: String?,
    val owned: Boolean?,
    val createdAt: String?
) : AccelByteResponse {
    companion object {
        /**
         * Parses an [EntitlementSummary] from the API response JSON.
         *
         * @param json the raw JSON returned by the platform service.
         * @return a fully populated [EntitlementSummary].
         * @throws IllegalArgumentException if [id][EntitlementSummary.id],
         *         [userId][EntitlementSummary.userId], [namespace][EntitlementSummary.namespace],
         *         or [itemId][EntitlementSummary.itemId] is absent.
         */
        fun fromJson(json: Json): EntitlementSummary {
            val actualData = json.asDynamic().data?.unsafeCast<Json>() ?: json
            return EntitlementSummary(
                id = actualData.unsafeCast<Json>().requireString("id"),
                userId = actualData.unsafeCast<Json>().requireString("userId"),
                namespace = actualData.unsafeCast<Json>().requireString("namespace"),
                itemId = actualData.unsafeCast<Json>().requireString("itemId"),
                entitlementType = actualData.unsafeCast<Json>().optString("entitlementType"),
                name = actualData.unsafeCast<Json>().optString("name"),
                sku = actualData.unsafeCast<Json>().optString("sku"),
                owned = actualData.unsafeCast<Json>().optBoolean("owned"),
                createdAt = actualData.unsafeCast<Json>().optString("createdAt")
            )
        }
    }
}

/**
 * Ownership check result for a single item.
 *
 * @property itemId the item identifier that was checked.
 * @property owned true if the user currently owns the item; false otherwise.
 */
data class EntitlementOwnership(
    val itemId: String,
    val owned: Boolean
) : AccelByteResponse {
    companion object {
        /**
         * Parses an [EntitlementOwnership] from the API response JSON.
         *
         * @param json the raw JSON returned by the platform service.
         * @return a fully populated [EntitlementOwnership].
         * @throws IllegalArgumentException if [itemId][EntitlementOwnership.itemId] or
         *         [owned][EntitlementOwnership.owned] is absent.
         */
        fun fromJson(json: Json): EntitlementOwnership {
            val actualData = json.asDynamic().data?.unsafeCast<Json>() ?: json
            return EntitlementOwnership(
                itemId = actualData.unsafeCast<Json>().requireString("itemId"),
                owned = actualData.unsafeCast<Json>().requireBoolean("owned")
            )
        }
    }
}

/**
 * Summary of a user's wallet balance for a particular virtual currency.
 *
 * @property currencyCode the ISO 4217 currency code (e.g., "GC", "VIP_COINS").
 * @property balance the current wallet balance for this currency.
 * @property status optional wallet status (e.g., "ACTIVE", "SUSPENDED").
 */
data class CurrencyWallet(
    val currencyCode: String,
    val balance: Double?,
    val status: String?
) : AccelByteResponse {
    companion object {
        /**
         * Parses a [CurrencyWallet] from the API response JSON.
         *
         * @param json the raw JSON returned by the platform service.
         * @return a fully populated [CurrencyWallet].
         * @throws IllegalArgumentException if [currencyCode][CurrencyWallet.currencyCode] is absent.
         */
        fun fromJson(json: Json): CurrencyWallet {
            val actualData = json.asDynamic().data?.unsafeCast<Json>() ?: json
            return CurrencyWallet(
                currencyCode = actualData.unsafeCast<Json>().requireString("currencyCode"),
                balance = actualData.unsafeCast<Json>().optDouble("balance"),
                status = actualData.unsafeCast<Json>().optString("status")
            )
        }
    }
}

/**
 * Summary information for a promotional or pricing campaign.
 *
 * @property campaignId unique identifier for the campaign.
 * @property name optional human-readable campaign name.
 * @property startDate ISO-8601 campaign start timestamp. Null if not yet scheduled.
 * @property endDate ISO-8601 campaign end timestamp. Null if open-ended.
 * @property status optional campaign status (e.g., "ACTIVE", "EXPIRED", "SCHEDULED").
 */
data class CampaignInfo(
    val campaignId: String,
    val name: String?,
    val startDate: String?,
    val endDate: String?,
    val status: String?
) : AccelByteResponse {
    companion object {
        /**
         * Parses a [CampaignInfo] from the API response JSON.
         *
         * @param json the raw JSON returned by the platform service.
         * @return a fully populated [CampaignInfo].
         * @throws IllegalArgumentException if [campaignId][CampaignInfo.campaignId] is absent.
         */
        fun fromJson(json: Json): CampaignInfo {
            val actualData = json.asDynamic().data?.unsafeCast<Json>() ?: json
            return CampaignInfo(
                campaignId = actualData.unsafeCast<Json>().requireString("campaignId"),
                name = actualData.unsafeCast<Json>().optString("name"),
                startDate = actualData.unsafeCast<Json>().optString("startDate"),
                endDate = actualData.unsafeCast<Json>().optString("endDate"),
                status = actualData.unsafeCast<Json>().optString("status")
            )
        }
    }
}

/**
 * Metadata for a platform storefront.
 *
 * @property id unique identifier for this store.
 * @property name optional human-readable store name.
 * @property description optional store description.
 * @property enabled true if the store is currently open; false if disabled.
 */
data class StoreInfo(
    val id: String,
    val name: String?,
    val description: String?,
    val enabled: Boolean?
) : AccelByteResponse {
    companion object {
        /**
         * Parses a [StoreInfo] from the API response JSON.
         *
         * @param json the raw JSON returned by the platform service.
         * @return a fully populated [StoreInfo].
         * @throws IllegalArgumentException if [id][StoreInfo.id] is absent.
         */
        fun fromJson(json: Json): StoreInfo {
            val actualData = json.asDynamic().data?.unsafeCast<Json>() ?: json
            return StoreInfo(
                id = actualData.unsafeCast<Json>().requireString("id"),
                name = actualData.unsafeCast<Json>().optString("name"),
                description = actualData.unsafeCast<Json>().optString("description"),
                enabled = actualData.unsafeCast<Json>().optBoolean("enabled")
            )
        }
    }
}

/**
 * Subscription state for a user's recurring purchase.
 *
 * @property subscriptionId unique identifier for this subscription.
 * @property userId the user who holds this subscription.
 * @property itemId the subscription item this record relates to.
 * @property status optional subscription status (e.g., "ACTIVE", "CANCELLED", "PAUSED").
 * @property nextBillingDate ISO-8601 timestamp of the next scheduled billing event. Null if inactive.
 */
data class SubscriptionInfo(
    val subscriptionId: String,
    val userId: String,
    val itemId: String,
    val status: String?,
    val nextBillingDate: String?
) : AccelByteResponse {
    companion object {
        /**
         * Parses a [SubscriptionInfo] from the API response JSON.
         *
         * @param json the raw JSON returned by the platform service.
         * @return a fully populated [SubscriptionInfo].
         * @throws IllegalArgumentException if [subscriptionId][SubscriptionInfo.subscriptionId],
         *         [userId][SubscriptionInfo.userId], or [itemId][SubscriptionInfo.itemId] is absent.
         */
        fun fromJson(json: Json): SubscriptionInfo {
            val actualData = json.asDynamic().data?.unsafeCast<Json>() ?: json
            return SubscriptionInfo(
                subscriptionId = actualData.unsafeCast<Json>().requireString("subscriptionId"),
                userId = actualData.unsafeCast<Json>().requireString("userId"),
                itemId = actualData.unsafeCast<Json>().requireString("itemId"),
                status = actualData.unsafeCast<Json>().optString("status"),
                nextBillingDate = actualData.unsafeCast<Json>().optString("nextBillingDate")
            )
        }
    }
}

/**
 * Pagination cursor structure for platform service list responses.
 *
 * @property next URL string for the next page, or null if already on the last page.
 * @property previous URL string for the previous page, or null if already on the first page.
 */
data class PlatformPaging(
    val next: String?,
    val previous: String?
) {
    companion object {
        /**
         * Parses a [PlatformPaging] from the raw pagination object returned by the API.
         *
         * @param json the JSON object carrying pagination cursors.
         * @return an [PlatformPaging]. Both cursors may be null on single-page responses.
         */
        fun fromJson(json: Json): PlatformPaging = PlatformPaging(
            next = json.optString("next"),
            previous = json.optString("previous")
        )
    }
}
