package structs.rpcRequests

import kotlinx.serialization.Serializable

/**
 * Request to retrieve a player's account settings from cloud save.
 *
 * @param userId The AccelByte user ID
 */
@Serializable
data class GetAccountSettingsRequest(val userId: String)

/**
 * Request to save a player's account settings to cloud save.
 *
 * @param userId The AccelByte user ID
 * @param accountSettingsJson JSON string of AccountSettings
 */
@Serializable
data class SaveAccountSettingsRequest(val userId: String, val accountSettingsJson: String)
