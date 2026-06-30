package org.ttt.autogenesis.accelbyte.facades

import kotlin.js.Json
import kotlin.js.json
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSdkInstance
import org.ttt.autogenesis.accelbyte.modules.ExtendAppUiModulePackage
import org.ttt.autogenesis.accelbyte.util.propagateJsErrors

/**
 * Facade for AccelByte EHS (Extensible Hosted Services) service.
 * Provides gRPC reflection and token access utilities.
 */
class EhsFacade(private val sdk : AccelByteSdkInstance)
{
    private val utilityAdminApi = ExtendAppUiModulePackage.ExtendAppUi.UtilityAdminApi(sdk.rawSdk)
    private val accessApi = ExtendAppUiModulePackage.ExtendAppUi.AccessApi(sdk.rawSdk)

    /** Gets gRPC reflection information for the service. */
    fun getReflection(queryParams : Json = json()) : Promise<Json> = utilityAdminApi.getReflection(queryParams).propagateJsErrors()

    /** Gets an access token by application name. */
    fun getTokenByApp(app : String) : Promise<Json> = accessApi.getToken_ByApp(app).propagateJsErrors()
}
