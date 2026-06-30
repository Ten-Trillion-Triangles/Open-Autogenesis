package accelbyte.user

import accelbyte.iam.buildGlobalSdk
import accelbyte.iam.buildSdk
import kotlin.js.Promise
import org.ttt.autogenesis.accelbyte.AccelByteSdkInstance
import org.ttt.autogenesis.accelbyte.facades.UserAuthFacade
import org.ttt.autogenesis.accelbyte.facades.UsersFacade
import org.ttt.autogenesis.accelbyte.models.OAuthTokenRequest
import org.ttt.autogenesis.accelbyte.models.OAuthTokenResponse
import org.ttt.autogenesis.accelbyte.models.RegisterUserRequest
import org.ttt.autogenesis.accelbyte.models.RegisterUserResponse
import org.ttt.autogenesis.accelbyte.models.SendRegisterVerificationCodeRequest
import org.ttt.autogenesis.accelbyte.models.SendPasswordResetCodeRequest
import org.ttt.autogenesis.accelbyte.models.VerifyRegistrationCodeRequest
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

// Singleton SDK instance to ensure only one is created
private var _sdk: AccelByteSdkInstance? = null

private val sdk: AccelByteSdkInstance
    get() {
        if (_sdk == null) {
            Logger.debug(LogCategory.AUTH, "DEBUG: [Login] Creating singleton SDK instance via buildSdk()")
            _sdk = buildSdk()
            Logger.debug(LogCategory.AUTH, "DEBUG: [Login] Created singleton SDK instance: $_sdk")
        } else {
            Logger.debug(LogCategory.AUTH, "DEBUG: [Login] Reusing existing singleton SDK instance: $_sdk")
        }
        return _sdk!!
    }
private val globalSdk by lazy { buildGlobalSdk() }
private val usersFacade by lazy { UsersFacade(sdk) }
private val globalUsersFacade by lazy { UsersFacade(globalSdk) }
private val userAuthFacade by lazy { UserAuthFacade(sdk) }
private val globalUserAuthFacade by lazy { UserAuthFacade(globalSdk) }

fun sendRegisterCode(email : String) : Promise<Unit> =
    usersFacade.sendRegisterCode(SendRegisterVerificationCodeRequest(email, "en-US"))

fun sendPasswordResetCode(email : String) : Promise<Unit> =
    usersFacade.sendPasswordResetCode(SendPasswordResetCodeRequest(email, "en-US"))

fun verifyRegistrationCode(email : String, code : String) : Promise<Unit> =
    usersFacade.verifyRegistrationCode(VerifyRegistrationCodeRequest(email, code))

fun registerUser(request : RegisterUserRequest) : Promise<RegisterUserResponse> =
    usersFacade.registerUser(request)

fun loginUser(request : OAuthTokenRequest) : Promise<OAuthTokenResponse> =
    userAuthFacade.loginUser(request)

fun logoutUser() : Promise<Unit> =
    userAuthFacade.logoutUser()

fun isUserLoggedIn() : Boolean =
    userAuthFacade.isLoggedIn()

/**
 * Provides access to the shared SDK instance that stores token state.
 */
fun getUserSdkInstance() : AccelByteSdkInstance
{
    Logger.debug(LogCategory.AUTH, "DEBUG: [Login] getUserSdkInstance() called, returning: $sdk")
    return sdk
}
