package org.ttt.autogenesis.kvisionapp

import globals.KEnv
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import org.khronos.webgl.Uint8Array
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import structs.push.PushSubscriptionDto
import kotlin.js.Promise

/**
 * Browser-side Web Push subscription manager.
 *
 * The actual subscription happens via [subscribeIfPermitted] which MUST be
 * called from within a user gesture (Play button click, Resume click, etc.)
 * because the Push API rejects `subscribe()` calls outside a gesture.
 *
 * **Boot sequence**: [registerServiceWorker] is called from Main.kt on app
 * start. The Play button handler invokes [subscribeIfPermitted]. The actual
 * subscription result is forwarded to the server via the
 * `client.registerPushSubscription` RPC over the existing WebSocket bridge.
 *
 * **Implementation note**: Kotlin/JS DOM bindings ship with the Service
 * Worker API but not the modern Push API (`PushManager`, `PushSubscription`,
 * `PushSubscriptionOptionsInit`). We call these via `js()` and `unsafeCast`
 * rather than waiting for the standard library to catch up.
 */
object PushNotificationService
{
    private const val SW_PATH = "/sw.js"
    private const val VAPID_PUBLIC_JSON_PATH = "/vapid_public.json"
    private val json = Json { ignoreUnknownKeys = true }
    private val mainScope = MainScope()

    /**
     * Registers the service worker at /sw.js. Idempotent — safe to call on
     * every page load.
     */
    fun registerServiceWorker()
    {
        mainScope.launch {
            try
            {
                val nav: dynamic = window.navigator
                if (nav.serviceWorker == null)
                {
                    Logger.warn(LogCategory.NETWORK, "PushNotificationService: Service Worker API unavailable in this browser")
                    return@launch
                }
                // nav.serviceWorker.register returns a Promise<ServiceWorkerRegistration>;
                // typed as `dynamic` because the Kotlin/JS dom bindings don't surface
                // ServiceWorkerContainer. unsafeCast to kotlin.js.Promise to enable
                // the kotlinx.coroutines.await extension.
                val registerPromise: Promise<dynamic> = nav.serviceWorker.register(SW_PATH).unsafeCast<Promise<dynamic>>()
                val reg = registerPromise.await()
                Logger.info(LogCategory.NETWORK, "PushNotificationService: Service Worker registered, scope=${reg.scope as String}")
            }
            catch (e: Throwable)
            {
                Logger.warn(LogCategory.NETWORK, "PushNotificationService: SW registration failed: ${e.message}")
            }
        }
    }

    /**
     * Subscribes to push if the browser supports it AND the user has granted
     * notification permission. MUST be called inside a user gesture.
     */
    fun subscribeIfPermitted()
    {
        mainScope.launch {
            try
            {
                val nav: dynamic = window.navigator
                if (nav.serviceWorker == null)
                {
                    Logger.warn(LogCategory.NETWORK, "PushNotificationService: Service Worker API unavailable — push disabled")
                    return@launch
                }
                val ready: dynamic = nav.serviceWorker.ready
                val registration: dynamic = ready.unsafeCast<Promise<dynamic>>().await()
                val notification: dynamic = window.asDynamic().Notification
                val permission: String = notification.permission as String
                if (permission != "granted")
                {
                    val requested: dynamic = notification.requestPermission().unsafeCast<Promise<dynamic>>().await()
                    val granted: String = requested as String
                    if (granted != "granted")
                    {
                        Logger.info(LogCategory.NETWORK, "PushNotificationService: user denied notification permission — push disabled")
                        return@launch
                    }
                }
                val vapidKeyBytes = loadVapidPublicKey() ?: run {
                    Logger.warn(LogCategory.NETWORK, "PushNotificationService: VAPID public key not available — push disabled")
                    return@launch
                }
                val subscribeOptions = js("({userVisibleOnly: true, applicationServerKey: vapidKeyBytes})")
                val subscribePromise: Promise<dynamic> = registration.pushManager.subscribe(subscribeOptions).unsafeCast<Promise<dynamic>>()
                val subscription: dynamic = subscribePromise.await()
                sendSubscriptionToServer(subscription)
            }
            catch (e: Throwable)
            {
                Logger.warn(LogCategory.NETWORK, "PushNotificationService: subscribe failed: ${e.message}")
            }
        }
    }

    /**
     * Loads the VAPID public key (Base64URL-encoded) from the webpack-built
     * `vapid_public.json` and decodes it into the raw 65-byte uncompressed
     * point that `pushManager.subscribe` expects as `applicationServerKey`.
     */
    private suspend fun loadVapidPublicKey(): Uint8Array?
    {
        return try
        {
            val response = window.fetch(VAPID_PUBLIC_JSON_PATH).await()
            if (!response.ok) return null
            val text = response.text().await()
            val obj = json.parseToJsonElement(text) as? JsonObject ?: return null
            val base64Url = (obj["applicationServerKey"] as? JsonPrimitive)?.content ?: return null
            base64UrlToUint8Array(base64Url)
        }
        catch (e: Throwable)
        {
            Logger.warn(LogCategory.NETWORK, "PushNotificationService: failed to load VAPID public key: ${e.message}")
            null
        }
    }

    /**
     * Decodes a Base64URL string (no padding) to a `Uint8Array` of raw bytes.
     */
    private fun base64UrlToUint8Array(base64Url: String): Uint8Array
    {
        val standard = base64Url.replace("-", "+").replace("_", "/")
        val padding = (4 - standard.length % 4) % 4
        val padded = standard + "=".repeat(padding)
        // Kotlin/JS template strings get unstable inside `js("""...""")` due
        // to multiline handling, so keep the JS expression inline.
        val result: dynamic = js("(function(){var b=atob(padded),n=b.length,a=new Uint8Array(n);for(var i=0;i<n;i++)a[i]=b.charCodeAt(i);return a;})()")
        return result as Uint8Array
    }

    /**
     * Extracts the subscription fields into a [PushSubscriptionDto] and
     * forwards it to the server via the `client.registerPushSubscription`
     * RPC on the WebSocket bridge.
     */
    private suspend fun sendSubscriptionToServer(subscription: dynamic)
    {
        val endpoint: String = subscription.endpoint as String
        // Convert the subscription keys (ArrayBuffers) to Base64URL via js().
        // Kotlin/JS doesn't bridge ArrayBuffer cleanly into typed Uint8Array.
        // Inline each call (js() requires a constant string literal — no
        // string interpolation allowed).
        val p256dhB64: String = js("(function(){var u=new Uint8Array(subscription.getKey('p256dh')),b='';for(var i=0;i<u.length;i++)b+=String.fromCharCode(u[i]);return btoa(b).replace(/\\+/g,'-').replace(/\\//g,'_').replace(/=/g,'');})()") as String
        val authB64: String = js("(function(){var u=new Uint8Array(subscription.getKey('auth')),b='';for(var i=0;i<u.length;i++)b+=String.fromCharCode(u[i]);return btoa(b).replace(/\\+/g,'-').replace(/\\//g,'_').replace(/=/g,'');})()") as String
        val dto = PushSubscriptionDto(endpoint = endpoint, p256dh = p256dhB64, auth = authB64)
        try
        {
            val invoker = WebSocketRpcBridge.rpcInvoker
            if (invoker == null)
            {
                Logger.warn(LogCategory.NETWORK, "PushNotificationService: WebSocketRpcBridge.rpcInvoker is null — cannot register subscription server-side yet")
                return
            }
            val payload: JsonElement = Json.encodeToJsonElement(PushSubscriptionDto.serializer(), dto)
            invoker.invoke(method = "client.registerPushSubscription", params = payload, timeoutMillis = 5_000L)
            Logger.info(LogCategory.NETWORK, "PushNotificationService: subscription registered for endpoint=${endpoint.take(60)}")
        }
        catch (e: Throwable)
        {
            Logger.warn(LogCategory.NETWORK, "PushNotificationService: failed to register subscription server-side: ${e.message}")
        }
    }

    /**
     * Hook for the `autogenesis.resumeTurn` postMessage from the service
     * worker (fired when the user taps a push notification). Wires up the
     * server.restoreRunningGame RPC so the focused tab rehydrates the saved
     * turn — same path the Resume dialog uses.
     */
    fun handleResumeTurnMessage()
    {
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            try
            {
                val invoker = WebSocketRpcBridge.rpcInvoker
                if (invoker == null)
                {
                    Logger.warn(LogCategory.NETWORK, "PushNotificationService: handleResumeTurnMessage: WS not ready — cannot restore turn")
                    return@launch
                }
                invoker.invoke(method = "server.restoreRunningGame", params = null, timeoutMillis = 10_000L)
                Logger.info(LogCategory.NETWORK, "PushNotificationService: handleResumeTurnMessage: server.restoreRunningGame invoked")
            }
            catch (e: Throwable)
            {
                Logger.warn(LogCategory.NETWORK, "PushNotificationService: handleResumeTurnMessage failed: ${e.message}")
            }
        }
    }

    /**
     * Hook for the `autogenesis.subscriptionChanged` postMessage from the
     * service worker (fired on `pushsubscriptionchange` per RFC 8030).
     *
     * Browsers rotate push subscriptions on a regular cadence (Firefox
     * notably). The SW receives the lifecycle event, re-subscribes
     * internally, and forwards the new subscription here. If we did not
     * forward it to the server, `client.registerPushSubscription` would
     * never see the new endpoint, and the server would keep sending
     * pushes to the dead endpoint until it 410'd on the next attempt.
     *
     * The SW's payload is the JSON form of a `PushSubscription`:
     *   { endpoint: string, keys: { p256dh: string, auth: string } }
     * (see https://developer.mozilla.org/en-US/docs/Web/API/PushSubscription/toJSON)
     *
     * This function unwraps the nested `keys` into the flat
     * [PushSubscriptionDto] shape the server expects, then re-invokes
     * `client.registerPushSubscription` via the same WS bridge the
     * initial Play-button registration uses.
     */
    fun handleSubscriptionChanged(subscription: dynamic)
    {
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            try
            {
                val endpoint: String? = subscription?.endpoint as? String
                val p256dh: String? = subscription?.keys?.p256dh as? String
                val auth: String? = subscription?.keys?.auth as? String
                if (endpoint.isNullOrBlank() || p256dh.isNullOrBlank() || auth.isNullOrBlank())
                {
                    Logger.warn(LogCategory.NETWORK, "PushNotificationService: handleSubscriptionChanged: missing endpoint/keys — dropping (endpoint=${endpoint?.take(60)}, p256dh=${!p256dh.isNullOrBlank()}, auth=${!auth.isNullOrBlank()})")
                    return@launch
                }
                val dto = PushSubscriptionDto(endpoint = endpoint, p256dh = p256dh, auth = auth)
                val invoker = WebSocketRpcBridge.rpcInvoker
                if (invoker == null)
                {
                    Logger.warn(LogCategory.NETWORK, "PushNotificationService: handleSubscriptionChanged: WS not ready — cannot re-register subscription")
                    return@launch
                }
                val payload: JsonElement = Json.encodeToJsonElement(PushSubscriptionDto.serializer(), dto)
                invoker.invoke(method = "client.registerPushSubscription", params = payload, timeoutMillis = 5_000L)
                Logger.info(LogCategory.NETWORK, "PushNotificationService: handleSubscriptionChanged: re-registered subscription for endpoint=${endpoint.take(60)}")
            }
            catch (e: Throwable)
            {
                Logger.warn(LogCategory.NETWORK, "PushNotificationService: handleSubscriptionChanged failed: ${e.message}")
            }
        }
    }
}
