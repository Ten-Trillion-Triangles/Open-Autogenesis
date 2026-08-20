package org.ttt.autogenesis.server.push

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.martijndwars.webpush.Notification
import nl.martijndwars.webpush.PushService
import nl.martijndwars.webpush.Subscription
import org.apache.http.HttpResponse
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import structs.push.PushSubscriptionDto

/**
 * Sends a VAPID-signed Web Push notification to every stored subscription
 * for a given user.
 *
 * Multi-device support: the underlying [PushSubscriptionStore] holds a
 * list of subscriptions per accelByteId (one per browser/device). This
 * service fans the push out to every entry; if a device is offline or
 * the push service endpoint has been rotated, only that device's
 * subscription is pruned — the user's other devices continue to
 * receive notifications.
 *
 * HTTP 410 Gone and 404 Not Found are treated as "this endpoint is
 * dead" and trigger a surgical [PushSubscriptionStore.removeByEndpoint]
 * call. Other endpoints for the same user are left intact.
 *
 * If VAPID keypair is unavailable (null), the service silently no-ops.
 * Push-notification failures must not crash the game server, and a missing
 * keypair just means push is disabled until `~/.autogenesis/vapid_private.pem`
 * is provisioned via the `:kvisionApp:generateVapidKeys` Gradle task.
 */
class PushNotificationService(
    private val store: PushSubscriptionStore,
    private val keypair: PushVapidConfig.VapidKeypair?,
    /**
     * If non-null, every push's subscription.endpoint is rewritten to this
     * URL (preserving only the path component). Used in dev e2e tests so the
     * push service can be intercepted by a local HttpServer instead of
     * hitting FCM/Mozilla/APNs. Read by [resolveEndpoint].
     */
    private val endpointOverrideBase: String? = null,
)
{
    private val pushService: PushService? = keypair?.let { kp ->
        // PushService(KeyPair, subject) constructor — library accepts the
        // PKCS8-encoded private key as a Java PrivateKey and extracts the
        // public key from it via Java's KeyFactory.
        PushService(
            java.security.KeyPair(kp.publicKeyAsPublicKey(), kp.privateKey),
            PushVapidConfig.getContact()
        )
    }

    /**
     * Rewrites a subscription's endpoint URL so the push delivery can be
     * intercepted by a local mock during dev e2e tests. Preserves the path
     * component of the original endpoint (the push service's `topic` is
     * derived from the URL, and we want the mock to receive the same path
     * the real push service would have seen).
     *
     * Example: endpoint=`https://fcm.googleapis.com/fcm/send/abc`, override=`http://127.0.0.1:9099`
     * → `http://127.0.0.1:9099/fcm/send/abc`
     */
    private fun resolveEndpoint(originalEndpoint: String): String
    {
        val base = endpointOverrideBase ?: return originalEndpoint
        // Strip trailing slash from base so we don't get "//fcm/send/..."
        val trimmedBase = base.trimEnd('/')
        // Extract the path from the original endpoint (e.g. "/fcm/send/abc").
        val pathPart = try
        {
            java.net.URI(originalEndpoint).rawPath ?: ""
        }
        catch (e: Throwable)
        {
            ""
        }
        return trimmedBase + pathPart
    }

    /**
     * Sends a turn-start push notification to every stored subscription
     * for [accelByteId]. Returns true if at least one delivery succeeded
     * (HTTP 2xx). Returns false when push is disabled, no subscriptions
     * are registered, or every delivery failed.
     *
     * Failures on individual endpoints are logged but do not abort the
     * fan-out — the remaining devices still receive the notification.
     * 404 Not Found and 410 Gone trigger a surgical
     * [PushSubscriptionStore.removeByEndpoint] so the dead endpoint is
     * pruned without touching the user's other live devices.
     */
    suspend fun sendTurnStart(accelByteId: String, actorName: String, roundNumber: Int): Boolean
    {
        if (pushService == null)
        {
            Logger.debug(LogCategory.NETWORK, "PushNotificationService: VAPID not configured, skipping push for user=$accelByteId")
            return false
        }
        val subscriptions: List<PushSubscriptionDto> = store.getAll(accelByteId)
        if (subscriptions.isEmpty())
        {
            Logger.debug(LogCategory.NETWORK, "PushNotificationService: no subscriptions for user=$accelByteId")
            return false
        }
        val payload = """{"title":"Your turn","body":"Round $roundNumber — $actorName, ready when you are","tag":"turn-$roundNumber","url":"/"}"""
        var anyDelivered = false
        for (subscription in subscriptions)
        {
            val delivered: Boolean = sendOne(accelByteId, subscription, payload)
            if (delivered) anyDelivered = true
        }
        return anyDelivered
    }

    /**
     * Sends a single push delivery. On 404/410, surgically removes the
     * matching subscription from the store. Returns true on 2xx, false
     * otherwise (including transient failures and 410-pruned endpoints).
     */
    private suspend fun sendOne(
        accelByteId: String,
        subscription: PushSubscriptionDto,
        payload: String,
    ): Boolean
    {
        val effectiveEndpoint = resolveEndpoint(subscription.endpoint)
        if (effectiveEndpoint != subscription.endpoint)
        {
            Logger.debug(LogCategory.NETWORK, "PushNotificationService: endpoint rewritten for dev override: ${subscription.endpoint} -> $effectiveEndpoint")
        }
        val effectiveSubscription = subscription.copy(endpoint = effectiveEndpoint)
        return withContext(Dispatchers.IO) {
            try
            {
                val response: HttpResponse = pushService!!.send(buildWebPushNotification(effectiveSubscription, payload))
                val status = response.statusLine.statusCode
                when (status)
                {
                    in 200..299 -> true
                    404, 410 -> {
                        Logger.info(LogCategory.NETWORK, "PushNotificationService: subscription expired for user=$accelByteId (status=$status, endpoint=${subscription.endpoint.take(60)}), removing only this device")
                        store.removeByEndpoint(accelByteId, subscription.endpoint)
                        false
                    }
                    else -> {
                        Logger.warn(LogCategory.NETWORK, "PushNotificationService: push failed for user=$accelByteId endpoint=${subscription.endpoint.take(60)} with status $status")
                        false
                    }
                }
            }
            catch (e: Throwable)
            {
                Logger.warn(LogCategory.NETWORK, "PushNotificationService: exception sending push for user=$accelByteId endpoint=${subscription.endpoint.take(60)}: ${e.message}")
                false
            }
        }
    }

    private fun buildWebPushNotification(dto: PushSubscriptionDto, payload: String): Notification
    {
        val subscription = Subscription(dto.endpoint, Subscription.Keys(dto.p256dh, dto.auth))
        return Notification(subscription, payload)
    }
}