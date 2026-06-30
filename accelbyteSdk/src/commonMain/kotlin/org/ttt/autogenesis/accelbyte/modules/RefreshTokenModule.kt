@file:JsModule("@accelbyte/sdk")
@file:JsNonModule

package org.ttt.autogenesis.accelbyte.modules

import kotlin.js.Json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.Interceptor

/**
 * Constructor arguments for the SDK-provided refresh token helper.
 * Matches the shape expected by `new RefreshToken({ config, interceptors })`.
 */
external interface RefreshTokenConstructor
{
    var config : RefreshArgs
    var interceptors : Array<Interceptor>?
}

/**
 * Arguments required to refresh a token.
 * Mirrors the type used inside the AccelByte SDK's `RefreshToken` helper.
 */
external interface RefreshArgs
{
    var axiosConfig : Json
    var refreshToken : String?
    var clientId : String
    var tokenUrl : String?
}

/**
 * Lightweight wrapper around the SDK's refresh helper.
 * The implementation exposes `refreshToken()` so Kotlin callers can await the result.
 */
external class RefreshToken(constructor : RefreshTokenConstructor)
{
    fun refreshToken() : Promise<Json>
}
