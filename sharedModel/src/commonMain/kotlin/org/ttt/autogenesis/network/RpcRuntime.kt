package org.ttt.autogenesis.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.yield
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger
import kotlin.math.absoluteValue
import kotlin.random.Random
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Defines the direction of RPC method calls.
 */
enum class RpcDirection
{
    SERVER,
    CLIENT,
    BOTH;

    /**
     * Checks if this direction permits the given direction.
     *
     * @param direction Direction to check
     * @return True if permitted, false otherwise
     */
    fun permits(direction : RpcDirection) : Boolean =
        this == BOTH || direction == BOTH || this == direction
}

/**
 * Annotation for marking functions as RPC methods.
 *
 * @property name RPC method name for remote calls
 * @property direction Which endpoints can handle this method
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RpcMethod(val name : String, val direction : RpcDirection = RpcDirection.BOTH)

/**
 * Context provided to RPC handlers during execution.
 *
 * @property connectionId Unique identifier for the connection
 * @property metadata Additional connection metadata
 * @property sender Function for sending RPC messages back to the caller
 */
data class RpcCallContext(
    val connectionId : String,
    val metadata : Map<String, String> = emptyMap(),
    private val sender : suspend (RpcMessage) -> Unit
)
{
    /**
     * Sends a notification to the remote endpoint.
     *
     * @param method Notification method name
     * @param params Optional parameters
     */
    suspend fun notify(method : String, params : JsonElement?)
    {
        sender(RpcMessage.Notification(method, params))
    }

    /**
     * Sends a typed notification to the remote endpoint.
     *
     * @param method Notification method name
     * @param params Typed parameters
     * @param serializer Serializer for parameters
     */
    suspend fun <P> notify(method : String, params : P, serializer : KSerializer<P>)
    {
        notify(method, RpcJson.encodeToJsonElement(serializer, params))
    }

    /**
     * Sends a typed notification using reified serializer.
     *
     * @param method Notification method name
     * @param params Typed parameters
     */
    suspend inline fun <reified P> notify(method : String, params : P) : Unit =
        notify(method, params, serializer())

    internal suspend fun sendResponse(response : RpcMessage.Response)
    {
        sender(response)
    }

    internal suspend fun sendStreamChunk(id : String, payload : JsonElement)
    {
        sender(RpcMessage.StreamChunk(id = id, payload = payload))
    }
}

/**
 * Type alias for RPC handler functions.
 */
typealias RpcHandlerBlock = suspend (RpcCallContext, JsonElement?) -> JsonElement?

/**
 * Overloads the default decode behavior of the [Json] class and provides the default serializer for kotlinx.
 */
inline fun <reified T> Json.decode(jsonElement: JsonElement?): T =
    decodeFromJsonElement(serializer(), jsonElement ?: throw IllegalArgumentException("Missing json"))

/**
 * Platform-specific RPC registration initialization.
 */
expect fun initializeRpcRegistrationsPlatform()

/**
 * Registry for RPC method handlers.
 *
 * @property localDirection Direction this registry serves
 */
class RpcRegistry(private val localDirection : RpcDirection)
{
    private val handlers = mutableMapOf<String, RegisteredHandler>()
    
    init
    {
        // Initialize all RPC registrations
        initializeRpcRegistrations()
        RpcRegistrationCollector.registerAll(this)
        
        // Log the number of registered handlers for verification
        Logger.info(LogCategory.NETWORK, "RpcRegistry initialized with ${handlers.size} handlers from ${RpcRegistrationCollector.getProviderCount()} providers")
    }
    
    private fun initializeRpcRegistrations()
    {
        // Platform-specific initialization will be implemented in actual classes
        initializeRpcRegistrationsPlatform()
    }

    private sealed class RegisteredHandler(val direction : RpcDirection)
    {
        class Unary(direction : RpcDirection, val handler : RpcHandlerBlock) : RegisteredHandler(direction)
        class Stream(
            direction : RpcDirection,
            val handler : suspend (RpcCallContext, JsonElement?) -> Flow<JsonElement>
        ) : RegisteredHandler(direction)
    }

    private val streamJobs = mutableMapOf<String, Job>()
    private val streamJobsMutex = Mutex()
    private val streamScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Registers an RPC method handler.
     *
     * @param method Method name
     * @param direction Method direction
     * @param handler Handler function
     */
    fun register(
        method : String,
        direction : RpcDirection,
        handler : RpcHandlerBlock
    )
    {
        Logger.debug(LogCategory.NETWORK, "RpcRegistry: Registering handler for method='$method', direction=$direction")
        handlers[method] = RegisteredHandler.Unary(direction, handler)
    }

    /**
     * Registers a typed RPC method handler.
     *
     * @param method Method name
     * @param direction Method direction
     * @param serializer Parameter serializer
     * @param resultSerializer Result serializer
     * @param block Typed handler function
     */
    fun <P, R> registerTyped(
        method : String,
        direction : RpcDirection,
        serializer : KSerializer<P>,
        resultSerializer : KSerializer<R>,
        block : suspend (RpcCallContext, P) -> R?
    )
    {
        register(method, direction) { ctx, params ->
            val payload = params?.let { RpcJson.decodeFromJsonElement(serializer, it) }
            val result = block(ctx, payload ?: throw IllegalArgumentException("Missing params"))
            result?.let { RpcJson.encodeToJsonElement(resultSerializer, it) }
        }
    }

    /**
     * Registers a streaming RPC method handler.
     *
     * @param method Method name
     * @param direction Method direction
     * @param handler Streaming handler block
     */
    fun registerStream(
        method : String,
        direction : RpcDirection,
        handler : suspend (RpcCallContext, JsonElement?) -> Flow<JsonElement>
    )
    {
        handlers[method] = RegisteredHandler.Stream(direction, handler)
    }

    /**
     * Registers a typed streaming RPC method handler.
     *
     * @param method Method name
     * @param direction Method direction
     * @param serializer Parameter serializer
     * @param resultSerializer Result serializer
     * @param block Typed streaming handler function
     */
    fun <P, R> registerStream(
        method : String,
        direction : RpcDirection,
        serializer : KSerializer<P>,
        resultSerializer : KSerializer<R>,
        block : suspend (RpcCallContext, P) -> Flow<R>
    )
    {
        registerStream(method, direction) { ctx, params ->
            val payload = params?.let { RpcJson.decodeFromJsonElement(serializer, it) }
            val flow = block(ctx, payload ?: throw IllegalArgumentException("Missing params"))
            flow.map { RpcJson.encodeToJsonElement(resultSerializer, it) }
        }
    }

    fun <R> registerStream(
        method : String,
        direction : RpcDirection,
        resultSerializer : KSerializer<R>,
        block : suspend (RpcCallContext) -> Flow<R>
    )
    {
        registerStream(method, direction) { ctx, _ ->
            block(ctx).map { RpcJson.encodeToJsonElement(resultSerializer, it) }
        }
    }

    /**
     * Dispatches an RPC request to the appropriate handler.
     *
     * @param request RPC request message
     * @param context Call context
     * @return RPC response message or null if the response is sent asynchronously
     */
    suspend fun dispatch(
        request : RpcMessage.Request,
        context : RpcCallContext
    ) : RpcMessage.Response?
    {
        val entry = handlers[request.method]
        if (entry == null || !localDirection.permits(entry.direction)) {
            return RpcMessage.Response(
                id = request.id,
                error = RpcError(code = 404, message = "Method ${request.method} not found")
            )
        }

        return when (entry) {
            is RegisteredHandler.Unary -> {
                try {
                    val result = entry.handler(context, request.params)
                    RpcMessage.Response(id = request.id, result = result)
                } catch (err : Throwable) {
                    RpcMessage.Response(
                        id = request.id,
                        error = RpcError(code = 500, message = err.message ?: "Internal error")
                    )
                }
            }
            is RegisteredHandler.Stream -> {
                val job = streamScope.launch {
                    try {
                        entry.handler(context, request.params).collect { chunk ->
                            context.sendStreamChunk(request.id, chunk)
                        }
                        context.sendResponse(RpcMessage.Response(id = request.id))
                    } catch (err : Throwable) {
                        val error = when (err) {
                            is CancellationException -> RpcError(
                                code = 499,
                                message = err.message ?: "Stream cancelled"
                            )
                            else -> RpcError(
                                code = 500,
                                message = err.message ?: "Stream error"
                            )
                        }
                        context.sendResponse(RpcMessage.Response(id = request.id, error = error))
                    } finally {
                        streamJobsMutex.withLock { streamJobs.remove(request.id) }
                    }
                }
                streamJobsMutex.withLock { streamJobs[request.id] = job }
                null
            }
        }
    }

    /**
     * Dispatches an RPC notification to the appropriate handler.
     *
     * @param notification RPC notification message
     * @param context Call context
     */
    suspend fun dispatchNotification(
        notification : RpcMessage.Notification,
        context : RpcCallContext
    )
    {
        val entry = handlers[notification.method] as? RegisteredHandler.Unary ?: return
        if (!localDirection.permits(entry.direction)) return
        entry.handler(context, notification.params)
    }

    /**
     * Cancels an active streaming request if it exists.
     *
     * @param streamId RPC request identifier for the stream
     */
    suspend fun cancelStream(streamId : String)
    {
        streamJobsMutex.withLock { streamJobs.remove(streamId) }?.cancel()
    }
}

/**
 * Invoker for making RPC calls to remote endpoints.
 *
 * @property sender Function for sending messages
 */
class RpcRequestHandle internal constructor(
    val id : String,
    private val invoker : RpcInvoker,
    private val deferred : CompletableDeferred<RpcMessage.Response>,
    private val streamFlow : SharedFlow<JsonElement>?
)
{
    val isActive : Boolean
        get() = !deferred.isCompleted

    val stream : SharedFlow<JsonElement>?
        get() = streamFlow

    suspend fun await() : RpcMessage.Response = deferred.await()

    suspend fun cancel(cause : CancellationException = CancellationException("RPC request $id cancelled")) : Boolean =
        invoker.cancelRequest(id, cause)
}

interface RpcTelemetrySink
{
    fun onRequestSent(id : String, method : String)
    fun onRequestCompleted(id : String, method : String, durationMillis : Long, error : RpcError?)
    fun onRequestCancelled(id : String, method : String, reason : String?)

    companion object
    {
        val NoOp : RpcTelemetrySink = object : RpcTelemetrySink {
            override fun onRequestSent(id: String, method: String) = Unit
            override fun onRequestCompleted(id: String, method: String, durationMillis: Long, error: RpcError?) = Unit
            override fun onRequestCancelled(id: String, method: String, reason: String?) = Unit
        }
    }
}

class RpcInvoker(
    private val sender : suspend (RpcMessage) -> Unit,
    private val telemetry : RpcTelemetrySink = RpcTelemetrySink.NoOp
)
{
    private val pending = mutableMapOf<String, PendingEntry>()
    private val mutex = Mutex()

    /**
     * Returns the number of in-flight requests currently in the pending
     * map. Read under the same mutex the cancellation logic holds, so
     * the count is consistent with the next immediate `cancelAll` call.
     * Suspending (rather than synchronous with a spin) so the event
     * loop can run other coroutines while we wait for the mutex — the
     * previous spin-based version monopolized the event loop and
     * prevented SSE responses from being processed, so requests never
     * completed and the drain always timed out.
     */
    suspend fun pendingCount(): Int = mutex.withLock { pending.size }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private data class PendingEntry(
        val deferred : CompletableDeferred<RpcMessage.Response>,
        val timeoutJob : Job?,
        val startedMark : TimeMark,
        val method : String,
        val stream : MutableSharedFlow<JsonElement>?
    )

    private fun scheduleTimeout(id : String, timeoutMillis : Long) : Job =
        scope.launch {
            delay(timeoutMillis)
            val entry = mutex.withLock { pending.remove(id) }
            if (entry != null) {
                telemetry.onRequestCancelled(id, entry.method, "timeout")
                entry.deferred.completeExceptionally(
                    RpcTimeoutException("RPC request $id timed out after ${timeoutMillis}ms")
                )
            }
        }

    /**
     * Invokes an RPC method on the remote endpoint.
     *
     * @param method Method name
     * @param params Optional parameters
     * @return RPC response
     */
    suspend fun invoke(method : String, params : JsonElement?, timeoutMillis : Long? = null) : RpcMessage.Response
    {
        return request(method, params, timeoutMillis).await()
    }

    suspend fun stream(
        method : String,
        params : JsonElement?,
        timeoutMillis : Long? = null,
        bufferCapacity : Int = 8
    ) : RpcRequestHandle
    {
        return request(method, params, timeoutMillis, streamBufferCapacity = bufferCapacity)
    }

    suspend fun <P> stream(
        method : String,
        params : P,
        serializer : KSerializer<P>,
        timeoutMillis : Long? = null,
        bufferCapacity : Int = 8
    ) : RpcRequestHandle
    {
        return stream(method, RpcJson.encodeToJsonElement(serializer, params), timeoutMillis, bufferCapacity)
    }

    suspend inline fun <reified P> stream(
        method : String,
        params : P,
        timeoutMillis : Long? = null,
        bufferCapacity : Int = 8
    ) : RpcRequestHandle =
        stream(method, params, serializer(), timeoutMillis, bufferCapacity)

    suspend fun request(
        method : String,
        params : JsonElement?,
        timeoutMillis : Long? = null,
        streamBufferCapacity : Int? = null
    ) : RpcRequestHandle
    {
        val id = Random.nextLong().absoluteValue.toString()
        val deferred = CompletableDeferred<RpcMessage.Response>()
        val streamFlow = streamBufferCapacity?.let {
            MutableSharedFlow<JsonElement>(extraBufferCapacity = it)
        }
        val entry = PendingEntry(
            deferred = deferred,
            timeoutJob = null,
            startedMark = TimeSource.Monotonic.markNow(),
            method = method,
            stream = streamFlow
        )
        mutex.withLock {
            pending[id] = entry
        }
        val timeoutJob = timeoutMillis?.let { scheduleTimeout(id, it) }
        if (timeoutJob != null) {
            mutex.withLock {
                pending[id] = pending[id]?.copy(timeoutJob = timeoutJob) ?: entry.copy(timeoutJob = timeoutJob)
            }
        }
        telemetry.onRequestSent(id, method)
        sender(RpcMessage.Request(id = id, method = method, params = params))
        return RpcRequestHandle(
            id = id,
            invoker = this,
            deferred = deferred,
            streamFlow = streamFlow?.asSharedFlow()
        )
    }

    suspend fun cancelRequest(
        id : String,
        cause : CancellationException = CancellationException("RPC request $id cancelled"),
        notifyRemote : Boolean = true
    ) : Boolean
    {
        val entry = mutex.withLock { pending.remove(id) }
        if (entry != null) {
            entry.timeoutJob?.cancel()
            entry.deferred.completeExceptionally(cause)
            if (notifyRemote) {
                sender(RpcMessage.StreamCancel(id = id))
            }
            telemetry.onRequestCancelled(id, entry.method, cause.message)
            return true
        }
        return false
    }

    /**
     * Completes every pending request with [cause]. Used when the owning
     * transport is being torn down so callers do not hang on a request
     * whose response channel was just disposed.
     *
     * @param cause Failure surfaced to awaiting callers (defaults to a
     *              [RestRpcClientClosedException] so callers can distinguish
     *              a teardown from a real network failure).
     * @return List of (requestId, method) pairs that were dropped, in the
     *         order they were observed. Useful for logging.
     */
    suspend fun cancelAll(
        cause : Throwable = RestRpcClientClosedException("RPC client closed before response")
    ) : List<Pair<String, String>>
    {
        val snapshot = mutex.withLock { pending.toMap() }
        for((id, _) in snapshot)
        {
            mutex.withLock { pending.remove(id) }
        }
        val reason = cause.message ?: cause::class.simpleName ?: "cancelled"
        for((id, entry) in snapshot)
        {
            entry.timeoutJob?.cancel()
            entry.deferred.completeExceptionally(cause)
            telemetry.onRequestCancelled(id, entry.method, reason)
        }
        return snapshot.map { it.key to it.value.method }
    }

    /**
     * Invokes a typed RPC method on the remote endpoint.
     *
     * @param method Method name
     * @param params Typed parameters
     * @param serializer Parameter serializer
     * @return RPC response
     */
    suspend fun <P> invoke(method : String, params : P, serializer : KSerializer<P>) : RpcMessage.Response
    {
        return invoke(method, RpcJson.encodeToJsonElement(serializer, params))
    }

    /**
     * Invokes a typed RPC method using reified serializer.
     *
     * @param method Method name
     * @param params Typed parameters
     * @return RPC response
     */
    suspend inline fun <reified P> invoke(method : String, params : P) : RpcMessage.Response =
        invoke(method, params, serializer())

    suspend fun <P> request(method : String, params : P, serializer : KSerializer<P>, timeoutMillis : Long? = null) : RpcRequestHandle
    {
        return request(method, RpcJson.encodeToJsonElement(serializer, params), timeoutMillis)
    }

    suspend inline fun <reified P> request(method : String, params : P, timeoutMillis : Long? = null) : RpcRequestHandle =
        request(method, params, serializer(), timeoutMillis)

    /**
     * Handles an RPC response from the remote endpoint.
     *
     * @param response RPC response message
     */
    suspend fun handleResponse(response : RpcMessage.Response)
    {
        val entry = mutex.withLock { pending.remove(response.id) }
        if (entry != null) {
            entry.timeoutJob?.cancel()
            entry.deferred.complete(response)
            val duration = entry.startedMark.elapsedNow().inWholeMilliseconds
            telemetry.onRequestCompleted(
                response.id,
                entry.method,
                duration,
                response.error
            )
        }
    }

    suspend fun handleStreamChunk(chunk : RpcMessage.StreamChunk)
    {
        mutex.withLock { pending[chunk.id]?.stream }?.tryEmit(chunk.payload)
    }

    suspend fun handleStreamCancel(cancel : RpcMessage.StreamCancel) : Boolean
    {
        return cancelRequest(
            id = cancel.id,
            cause = CancellationException("Remote cancelled stream ${cancel.id}"),
            notifyRemote = false
        )
    }
}

class RpcTimeoutException(message : String) : CancellationException(message)

/**
 * Thrown when an RPC call is still in-flight when its underlying client is
 * closed (for example, when the REST/SSE transport is replaced mid-request).
 *
 * Callers awaiting an [org.ttt.autogenesis.network.RpcRequestHandle] will see
 * this exception instead of a permanent hang, and the transport can log a
 * breadcrumb identifying the stranded request.
 */
class RestRpcClientClosedException(message : String) : CancellationException(message)

/**
 * Decodes stream chunks emitted for a handle.
 *
 * @param serializer Serializer for each chunk
 * @return Flow over decoded chunks
 */
fun <R> RpcRequestHandle.streamFlow(serializer : KSerializer<R>) : Flow<R> =
    stream?.map { RpcJson.decodeFromJsonElement(serializer, it) } ?: emptyFlow()

/**
 * Decodes stream chunks emitted for a handle using a reified serializer.
 */
inline fun <reified R> RpcRequestHandle.streamFlow() : Flow<R> =
    streamFlow(serializer())