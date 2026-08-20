package structs.push

import kotlinx.serialization.Serializable

/**
 * Web Push subscription registered by the browser.
 *
 * Sent from the KVision client to the server via the `client.registerPushSubscription`
 * RPC after a successful `pushManager.subscribe()` call. Stored server-side
 * in the VFS under key `push-subscription` per accelByteId, identical to the
 * `running-game` pattern used by [structs.resume.ResumeAvailabilityNotification].
 *
 * Fields:
 *   - endpoint: the browser's push service endpoint URL (FCM, Mozilla Push, APNs, etc.)
 *   - p256dh: Base64URL-encoded ECDH public key from the browser — used to encrypt
 *     the payload so only the user's browser can decrypt it (RFC 8291).
 *   - auth: Base64URL-encoded 16-byte secret — feeds the HKDF used in encryption
 *     (RFC 8291). Both p256dh and auth are required for the server to encrypt
 *     a push message to this subscription.
 */
@Serializable
data class PushSubscriptionDto(
    val endpoint: String,
    val p256dh: String,
    val auth: String,
)