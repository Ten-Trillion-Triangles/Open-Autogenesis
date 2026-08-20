package org.ttt.autogenesis.accelbyte.facades

import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSdkInstance
import org.ttt.autogenesis.accelbyte.models.LegacyRegisterUserRequest
import org.ttt.autogenesis.accelbyte.models.RegisterUserResponse
import org.ttt.autogenesis.accelbyte.modules.UsersApi
import org.ttt.autogenesis.accelbyte.util.propagateJsErrors

/**
 * Mirrors the old `/iam/v2/public/namespaces/{namespace}/users` contract when callers need
 * the simpler loginId/displayName payload instead of the v3+ schema.
 */
class LegacyUsersFacade(private val sdk: AccelByteSdkInstance)
{
    private val usersApi : UsersApi
        get() = UsersApi(sdk.rawSdk)

    fun registerUser(request : LegacyRegisterUserRequest) : Promise<RegisterUserResponse> =
        usersApi.createUser_v2(request.toJson())
            .propagateJsErrors()
            .then { RegisterUserResponse.fromJson(it.data) }
}