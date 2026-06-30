package structs.account

import kotlinx.serialization.Serializable

/**
 * Subscription plan tier for a player's account.
 *
 * @see AccountSettings
 */
@Serializable
enum class AccountPlan
{
    /** Free tier with limited inference time */
    FREE,
    /** Casual tier with moderate inference time */
    CASUAL,
    /** Pro tier with maximum inference time */
    PRO
}

/**
 * Billing and subscription status for a player's account.
 *
 * @param credits Current credit balance (1 credit per 1000 tokens)
 * @param plan The subscription plan tier
 * @param remainingInferenceMinutes Inference minutes remaining in current billing cycle
 * @param planResetDate Epoch milliseconds when the plan resets (0 = no reset scheduled)
 * @param autoRenew Whether the plan auto-renews at the end of the cycle
 */
@Serializable
data class BillingStatus(
    var credits: Double = 0.0,
    var plan: AccountPlan = AccountPlan.FREE,
    var remainingInferenceMinutes: Long = 0L,
    var planResetDate: Long = 0L,
    var autoRenew: Boolean = false
)

/**
 * Core account data for a player, combining identity and billing information.
 * Saved to AccelByte cloud save as an admin-only record accessible only by
 * server-extend and the main server module.
 *
 * @param accelByteUserId The player's AccelByte user ID
 * @param displayName The player's display name
 * @param billingStatus The player's billing and subscription status
 * @param bringYourOwnApiKey Whether the player has supplied their own LLM API key
 *                           (BYO mode). When true, the matchmaking algorithm treats
 *                           the player as the cheapest possible cost class because
 *                           the operator does not pay for their inference.
 * @param byoKeyItemId Optional analytic tag describing the BYO key (provider + last
 *                     four chars, etc.). Not used by the algorithm; useful for
 *                     logging and operator dashboards.
 * @see BillingStatus
 * @see AccountPlan
 */
@Serializable
data class AccountSettings(
    var accelByteUserId: String = "",
    var displayName: String = "",
    var billingStatus: BillingStatus = BillingStatus(),
    var bringYourOwnApiKey: Boolean = false,
    var byoKeyItemId: String = ""
)
