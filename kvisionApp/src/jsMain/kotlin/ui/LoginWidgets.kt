package ui

import io.kvision.form.check.checkBox
import io.kvision.html.span
import kotlinx.browser.localStorage
import accelbyte.iam.buildSdk
import accelbyte.user.getUserSdkInstance
import accelbyte.user.sendPasswordResetCode
import accelbyte.user.sendRegisterCode
import globals.AccelByteEnv
import globals.KEnv
import globals.World
import io.kvision.core.AlignItems
import io.kvision.core.Background
import io.kvision.core.Color
import io.kvision.core.Col
import io.kvision.core.CssSize
import io.kvision.core.JustifyContent
import io.kvision.core.JustifyItems
import io.kvision.core.Position
import io.kvision.core.Style
import io.kvision.core.TextAlign
import io.kvision.core.UNIT
import io.kvision.core.onInput
import io.kvision.form.text.TextInput
import io.kvision.form.text.textInput
import io.kvision.html.Button
import io.kvision.html.InputType
import io.kvision.html.button
import io.kvision.html.link
import io.kvision.html.div
import io.kvision.html.p
import io.kvision.panel.StackPanel
import io.kvision.panel.VPanel
import io.kvision.panel.hPanel
import io.kvision.panel.stackPanel
import io.kvision.panel.vPanel
import io.kvision.utils.perc
import io.kvision.utils.px
import io.kvision.window.Window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.await
import kotlinx.coroutines.cancel
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.promise
import kotlinx.coroutines.withTimeout
import org.ttt.autogenesis.accelbyte.AccelByteSdkInstance
import org.ttt.autogenesis.accelbyte.facades.UsersFacade
import org.ttt.autogenesis.accelbyte.models.OAuthTokenResponse
import org.ttt.autogenesis.accelbyte.models.RegisterUserRequest
import org.ttt.autogenesis.accelbyte.models.ResetPasswordRequestV3
import org.ttt.autogenesis.accelbyte.models.SendPasswordResetCodeRequest
import org.ttt.autogenesis.accelbyte.util.parseAccelByteErrorCode
import org.ttt.autogenesis.kvisionapp.RestRpcBridge
import org.ttt.autogenesis.kvisionapp.WebSocketRpcBridge
import org.ttt.autogenesis.kvisionapp.audio.AudioEngine
import org.ttt.autogenesis.logging.LogCategory
import org.ttt.autogenesis.logging.Logger

// Guest credentials were intentionally removed for the source-available
// release. The hardcoded email + password previously assigned here
// (the developer’s own AccelByte test account) made the “Login As Guest”
// button a real OAuth login as that user — anyone forking this repo
// would have hit the developer’s namespace on click. The button now
// routes to the synthetic `?skipLogin=true` guest path (accelbyteId =
// "guest-user") instead, which is the canonical guest mode used by
// the e2e probes, the debugger observer, and the controller.

enum class LoginState {
    LOGIN,
    REGISTER,
    VERIFY,
    RECOVER
}

/**
 * Applies login input styling to a TextInput component.
 *
 * @return The styled TextInput component.
 */
private fun TextInput.applyLoginInputStyle(): TextInput
{
    addCssClass("login-widget-input")
    return this
}

/**
 * Applies login button styling to a Button component.
 *
 * @return The styled Button component.
 */
private fun Button.applyLoginButtonStyle(): Button
{
    addCssClass("login-widget-button")
    return this
}

private const val REGISTER_REQUEST_TIMEOUT_MS = 60_000L

/**
 * Login page window. Supports caching username and password, as well as checking if the user is already logged in.
 */
class LoginPage() : Window(
    caption = "Login",
    contentWidth = 800.px,
    contentHeight = 680.px,
    isResizable = false,
    isDraggable = false
)
{
    var userName = ""
    var displayName = ""
    var email = ""
    var password = ""
    var pageState = LoginState.LOGIN
    var inputCode = ""
    var rememberMe = false
    var widgetSwitcherRef: StackPanel? = null
    private var disableButtons = false

    var loginPageRef: VPanel? = null
    var codePageRef: VPanel? = null
    var registerPageRef: VPanel? = null
    var recoverPageRef: VPanel? = null

    var emailBoxRef: TextInput? = null //Reference to the email box so we auto assign it's value based on the prior register.

    init
    {
        val savedEmail = localStorage.getItem("rememberedEmail")
        val savedPassword = localStorage.getItem("rememberedPassword")
        
        if (savedEmail != null && savedPassword != null) {
            email = savedEmail
            password = savedPassword
            rememberMe = true
        }

        position = Position.FIXED
        left = 50.perc
        top = 50.perc
        setStyle("transform", "translate(-50%, -50%)")
        zIndex = 2000
        addCssClass("login-widget-window")
        color = Color("#eeeeee")
        fontSize = CssSize(64, UNIT.px)
        textAlign = TextAlign.CENTER




        /**
         * Main widget switcher. Holds all the pages for the login window, register account, code verify, and
         * backwards flipping.
         */
     val widgetSwitcher = stackPanel {
            width = CssSize(100, UNIT.perc)
            height = CssSize(100, UNIT.perc)
            widgetSwitcherRef = this //Store ref to our switcher. This allows us to flip pages using delegate functions.
            addCssClass("login-widget-content")

            /**
             * First page. This page holds the standard login menu and defined functions. Can flip to the register page
             * if the Register button is pressed.
             *
             * index 0
             */
          val loginPage = vPanel {
                width = CssSize(100, UNIT.perc)
                height = CssSize(100, UNIT.perc) // Ensure panel takes full height
                padding = CssSize(40, UNIT.px) // More padding
                justifySelf = JustifyItems.STRETCH
                alignItems = AlignItems.CENTER
                spacing = 20 // More spacing

                //Box title. Can't be edited at runtime.
                p("Email") {
                    fontSize = CssSize(30, UNIT.px)
                }

                //Email textbox. Can be updated at runtime.
                textInput(type = InputType.EMAIL, value = email) {
                    width = CssSize(80, UNIT.perc)
                    maxWidth = CssSize(680, UNIT.px)
                    height = CssSize(72, UNIT.px)
                    fontSize = CssSize(24, UNIT.px)
                    setAttribute("maxlength", "256")
                    alignSelf = AlignItems.CENTER
                    padding = CssSize(12, UNIT.px)

                    onInput {
                        email = this.value ?: ""
                    }
                }.applyLoginInputStyle()

                //Password text input box title.
                p("Password") {
                    fontSize = CssSize(30, UNIT.px)
                    paddingTop = CssSize(10, UNIT.px)
                }

                //Password input box.
                textInput(type = InputType.PASSWORD, value = password) {
                    width = CssSize(80, UNIT.perc)
                    maxWidth = CssSize(680, UNIT.px)
                    height = CssSize(72, UNIT.px)
                    fontSize = CssSize(24, UNIT.px)
                    setAttribute("maxlength", "128")
                    alignSelf = AlignItems.CENTER
                    padding = CssSize(12, UNIT.px)

                    onInput {
                        password = this.value ?: ""
                    }
                }.applyLoginInputStyle()

                // Remember Me Checkbox
                hPanel(alignItems = AlignItems.CENTER, spacing = 10) {
                    marginTop = CssSize(0, UNIT.px)
                    checkBox(value = rememberMe) {
                        setStyle("cursor", "pointer")
                        setStyle("transform", "scale(1.5)")
                        setStyle("margin-right", "8px")
                        onClick {
                            rememberMe = this.value ?: false
                        }
                    }
                    span("Remember me") {
                        fontSize = CssSize(22, UNIT.px)
                        color = Color.name(Col.LIGHTGRAY)
                    }
                }

                // Spacer to push buttons to bottom
                div {
                    flexGrow = 1
                }

                //Button panel.
                hPanel {
                    width = CssSize(100, UNIT.perc)
                    height  = CssSize(60, UNIT.px)
                    spacing = 40
                    alignItems = AlignItems.CENTER
                    justifyContent = JustifyContent.CENTER

                    //Login button
                    button("Login") {
                        width =  CssSize(220, UNIT.px)
                        height = CssSize(60, UNIT.px)
                        fontSize = CssSize(28, UNIT.px)

                        onClick {
                            if(disableButtons) return@onClick //Safety lock for async events.
                            startLogin()
                        }
                    }.applyLoginButtonStyle()

                    //Guest Login button
                    button("Login As Guest") {
                        width =  CssSize(220, UNIT.px)
                        height = CssSize(60, UNIT.px)
                        fontSize = CssSize(22, UNIT.px)
                        // Stable selector for e2e probes — see
                        // kvisionApp-e2e/probes/guest-login.mjs. The probe
                        // navigates WITHOUT ?skipLogin so the LoginPage is
                        // mounted, then clicks this button to drive the real
                        // AccelByte guest OAuth flow (vs. the synthetic
                        // skipLogin path that sets accelbyteId="guest-user").
                        setAttribute("data-testid", "login-as-guest")

                        onClick {
                            if(disableButtons) return@onClick //Safety lock for async events.
                            guestLogin()
                        }
                    }.applyLoginButtonStyle()

                    //Register button
                    button("Register") {
                        width =  CssSize(220, UNIT.px)
                        height = CssSize(60, UNIT.px)
                        fontSize = CssSize(28, UNIT.px)

                        onClick {
                            if(disableButtons) return@onClick //Safety lock for async events.
                            openRegisterWindow(true)
                        }
                    }.applyLoginButtonStyle()
                }

                //Recover account link.
                link("Need help?", "#") {
                    fontSize = CssSize(20, UNIT.px)
                    marginTop = CssSize(20, UNIT.px)

                    onClick {

                        //Guardrail for preventing obviously invalid email addresses.
                        if(email.isEmpty() || !email.contains("@"))
                        {
                            KEnv.mainRoot?.add(MessageBox(
                                boxTitle = "Error",
                                message = "Please enter a valid email address",
                                showOk = true
                            ))

                            return@onClick
                        }

                        pageState = LoginState.RECOVER
                        openRegisterWindow()
                    }
                }
            }

            //Bind and save our login page so we can access this from outside this widget.
            loginPageRef = loginPage

            /**
             * Second page containing a title, code input box, forward, and back buttons.
             *
             * index 1
             */
            val codePage = vPanel {
                width = CssSize(100, UNIT.perc)
                height = CssSize(100, UNIT.perc) // Ensure panel takes full height
                padding = CssSize(40, UNIT.px) // More padding
                justifySelf = JustifyItems.STRETCH
                alignItems = AlignItems.CENTER
                spacing = 20 // More spacing

                //Text input box title for the code box.
                p("Email Code:") {
                    fontSize = CssSize(24, UNIT.px)
                    alignSelf = AlignItems.CENTER
                    paddingTop = CssSize(40, UNIT.px)
                }
            
            //Code input box to place register, or password reset code into.    
            val codeBox = textInput(
                    type = InputType.TEXT,
                    maxlength = 64) {
                    width = CssSize(80, UNIT.perc)
                    maxWidth = CssSize(680, UNIT.px)
                    height = CssSize(80, UNIT.px)
                    fontSize = CssSize(24, UNIT.px)
                    
                    onInput {
                        inputCode = this.value ?: ""
                    }
                }.applyLoginInputStyle()

                /**
                 * Panel to house the forward and back buttons. Downward padded, and aligned to the end. Content
                 * is centered and justified to center. Spacing is provided for each buttonn present.
                 */
                hPanel { 
                    width = CssSize(100, UNIT.perc)
                    height  = CssSize(60, UNIT.px)
                    spacing = 40
                    alignItems = AlignItems.CENTER
                    justifyContent = JustifyContent.CENTER
                    alignSelf = AlignItems.END

                    //Back button, clears the contents of this screen, then flips back to the login page.
                    button(text = "Back") {
                        fontSize = CssSize(24, UNIT.px)
                        width = CssSize(200, UNIT.px)
                        height = CssSize(60, UNIT.px)
                        
                        onClick {
                            if(disableButtons) return@onClick //Safety lock for async events.
                            codeBox.value = ""
                            pageState = LoginState.LOGIN
                            this@stackPanel.activeIndex = 0 //Push our page backwards.
                        }
                    }.applyLoginButtonStyle()

                    //Next button, issues async call to verify code.
                    button(text = "Next") {
                        fontSize = CssSize(24, UNIT.px)
                        width = CssSize(200, UNIT.px)
                        height = CssSize(60, UNIT.px)

                        onClick {
                            if(disableButtons) return@onClick //Safety lock for async events.
                            verifyCodeRequest(pageState == LoginState.REGISTER) //Verify code in register mode.
                        }
                    }.applyLoginButtonStyle()
                }
            }

            //Bind so we can access this outside our widget.
            codePageRef = codePage

            /**
             * Third and final register page. This page allows the user to assign required information like their
             * username, display name, and password. This page is flipped after a code is validated correctly.
             *
             * index 2
             */
            val registerPage = vPanel {
                width = CssSize(100, UNIT.perc)
                height = CssSize(100, UNIT.perc) // Ensure panel takes full height
                padding = CssSize(40, UNIT.px)
                alignItems = AlignItems.CENTER
                spacing = 10

                //Page Title
                p("Finish Registration") {
                    fontSize = CssSize(30, UNIT.px)
                    textAlign = TextAlign.CENTER
                    marginTop = CssSize(0, UNIT.px)
                }

                //Email title
                p("Email") {
                    fontSize = CssSize(24, UNIT.px)
                    textAlign = TextAlign.CENTER
                    justifySelf = JustifyItems.CENTER
                }

                //Email text box. Defaults to what was supplied in the username box prior when you register.
                val emailBox = textInput {
                    value = email
                    width = CssSize(60, UNIT.perc)
                    maxWidth = CssSize(600, UNIT.px)
                    height = CssSize(60, UNIT.px)
                    fontSize = CssSize(24, UNIT.px)
                    alignSelf = AlignItems.CENTER

                    onInput { email = this.value ?: "" }
                }.applyLoginInputStyle()

                emailBoxRef = emailBox //Bind so this can be written into on register page switch for sane defaults.

                //Username title
                p("Username") {
                    fontSize = CssSize(24, UNIT.px)
                    textAlign = TextAlign.CENTER
                    justifySelf = JustifyItems.CENTER
                }

                //Username input box. This needs to differ from the email that was used prior.
                val usernameBox = textInput {
                    width = CssSize(60, UNIT.perc)
                    maxWidth = CssSize(600, UNIT.px)
                    height = CssSize(60, UNIT.px)
                    fontSize = CssSize(24, UNIT.px)
                    alignSelf = AlignItems.CENTER

                    onInput { userName = this.value ?: "" }
                }.applyLoginInputStyle()

                //Display name title
                p("Display Name") {
                    fontSize = CssSize(24, UNIT.px)
                    textAlign = TextAlign.CENTER
                    justifySelf = JustifyItems.CENTER
                }

                //Display name box. Allows illegal chars that the username does not.
                val displayNameBox = textInput {
                    width = CssSize(60, UNIT.perc)
                    maxWidth = CssSize(600, UNIT.px)
                    height = CssSize(60, UNIT.px)
                    fontSize = CssSize(24, UNIT.px)
                    alignSelf = AlignItems.CENTER

                    onInput { displayName = this.value ?: "" }
                }.applyLoginInputStyle()

                //Password title
                p("Password") {
                    fontSize = CssSize(24, UNIT.px)
                    textAlign = TextAlign.CENTER
                    justifySelf = JustifyItems.CENTER
                }

                //Password input box.
                val passwordBox = textInput {
                    width = CssSize(60, UNIT.perc)
                    maxWidth = CssSize(600, UNIT.px)
                    height = CssSize(60, UNIT.px)
                    fontSize = CssSize(24, UNIT.px)
                    type = InputType.PASSWORD
                    alignSelf = AlignItems.CENTER

                    onInput { password = this.value ?: "" }
                }.applyLoginInputStyle()


                //Bottom button row.
                hPanel {
                    width = CssSize(100, UNIT.perc)
                    height  = CssSize(60, UNIT.px)
                    spacing = 40
                    alignSelf = AlignItems.FLEXEND
                    alignItems = AlignItems.CENTER
                    justifyContent = JustifyContent.CENTER

                    //Back button, clears this screen then goes back to the initial login menu.
                    button("Back") {
                        width = CssSize(200, UNIT.px)
                        height = CssSize(60, UNIT.px)
                        fontSize = CssSize(20, UNIT.px)

                        onClick {
                            emailBox.value = ""
                            email = ""
                            usernameBox.value = ""
                            userName = ""
                            displayNameBox.value = ""
                            displayName = ""
                            passwordBox.value = ""
                            password = ""

                            this@LoginPage.widgetSwitcherRef?.activeIndex = 0 //Switch back to the login menu page.
                        }
                    }.applyLoginButtonStyle()

                    //Register button. Triggers final steps of account creation.
                    button("Register") {
                        width = CssSize(200, UNIT.px)
                        height = CssSize(60, UNIT.px)
                        fontSize = CssSize(20, UNIT.px)

                        onClick {
                            if(disableButtons) return@onClick //Safety lock for async events.
                            registerAccount()
                        }
                    }.applyLoginButtonStyle()


                }
            }

            //Bind for outside access in static functions.
            registerPageRef = registerPage

            /**
             * Recovery page. Most of the details of the account we need to recover will be coming from the initial page
             * so we only need to request the new password at this stage.
             *
             * index 3
             */
            val recoverPage = vPanel {
                width = CssSize(100, UNIT.perc)
                height = CssSize(100, UNIT.perc) // Ensure panel takes full height
                padding = CssSize(40, UNIT.px)
                alignItems = AlignItems.CENTER
                spacing = 10

                //Page Title
                p("Recover Account") {
                    fontSize = CssSize(30, UNIT.px)
                    textAlign = TextAlign.CENTER
                    marginTop = CssSize(0, UNIT.px)
                }

                //Password Title
                p("New Password") {
                    fontSize = CssSize(24, UNIT.px)
                    textAlign = TextAlign.CENTER
                    justifySelf = JustifyItems.CENTER
                }

                //Email text box. Defaults to what was supplied in the username box prior when you register.
                val passwordBox = textInput {
                    value = ""
                    width = CssSize(60, UNIT.perc)
                    maxWidth = CssSize(600, UNIT.px)
                    height = CssSize(60, UNIT.px)
                    fontSize = CssSize(24, UNIT.px)
                    alignSelf = AlignItems.CENTER
                    type = InputType.PASSWORD

                    onInput { password = this.value ?: "" }
                }.applyLoginInputStyle()

                //Bottom button row.
                hPanel {
                    width = CssSize(100, UNIT.perc)
                    height  = CssSize(60, UNIT.px)
                    spacing = 40
                    alignSelf = AlignItems.FLEXEND
                    alignItems = AlignItems.CENTER
                    justifyContent = JustifyContent.CENTER

                    //Back button, clears this screen then goes back to the initial login menu.
                    button("Back") {
                        width = CssSize(200, UNIT.px)
                        height = CssSize(60, UNIT.px)
                        fontSize = CssSize(20, UNIT.px)

                        //todo: Implement logic to issue the required calls once we clear this stage.
                        onClick {
                            widgetSwitcherRef?.activeIndex = 0
                        }
                    }.applyLoginButtonStyle()

                    button("Next") {
                        width = CssSize(200, UNIT.px)
                        height = CssSize(60, UNIT.px)
                        fontSize = CssSize(20, UNIT.px)

                        onClick {
                            resetPassword()
                        }
                    }.applyLoginButtonStyle()
                }
            }

            //Bind for external static access.
            recoverPageRef = recoverPage
        }

        show()
        toFront()
        widgetSwitcher.activeIndex = 0
    }


    /**
     * Trigger a guest login sequence.
     *
     * In the source-available release the legacy hardcoded test
     * credentials have been removed; clicking “Login As Guest” now
     * routes the browser to the synthetic `?skipLogin=true` guest path
     * (`accelbyteId="guest-user"`), matching the same guest mode the
     * e2e probes, the debugger observer, and the controller already use.
     * Setting `location.href` triggers a full reload so Main.kt’s URL
     * parameter parsing picks up `skipLogin=true` and skips the login
     * page on the next pass.
     */
    fun guestLogin() {
        if (disableButtons) return
        kotlinx.browser.window.location.href =
            "${kotlinx.browser.window.location.origin}${kotlinx.browser.window.location.pathname}?skipLogin=true"
    }

    /**
     * Trip off a login sequence. This will make an async call to AccelByte, resolve the state, and  login the user.
     * After completion the message box will release and we can proceed forward out of this menu and onward to the rest
     * of the game.
     */
    fun startLogin()
    {
        if (disableButtons) return
        disableButtons = true
        val scope = MainScope() //Required to issue the async calls that need returns from AccelByte.
        val messageBox = MessageBox(
            boxTitle = "Login",
            message = "Please Wait",
            showThrobber = true
        )

        KEnv.mainRoot?.add(messageBox)

        val promise = scope.promise {
            val sdk = getUserSdkInstance()

            try
            {
                val oAuthTokenResponse = withTimeout(REGISTER_REQUEST_TIMEOUT_MS) {
                    UsersFacade(sdk).loginWithUsernameSuspend(email, password)
                }

                if(oAuthTokenResponse.accessToken.isEmpty())
                {
                    messageBox.setTitle("Error")
                        .setMessage("Login failed. Check your username and password and try again.")
                        .setButtons(
                            ok = true,
                            cancel = false)
                }
                else
                {
                    if (rememberMe) {
                        localStorage.setItem("rememberedEmail", email)
                        localStorage.setItem("rememberedPassword", password)
                    } else {
                        localStorage.removeItem("rememberedEmail")
                        localStorage.removeItem("rememberedPassword")
                    }

                    messageBox.setTitle("Login Complete")
                        .setMessage("Login Successful")
                        .setButtons(ok = false, cancel = false)
                }


                //Get user info, if we can save it, tear this down, and open the main menu next.
                try {
                    val userInfo = UsersFacade(sdk).getCurrentUserInfo().await()
                    AccelByteEnv.userName = userInfo.username!!
                    AccelByteEnv.userId = userInfo.userId
                    AccelByteEnv.displayName = userInfo.displayName

                    // Reconnect both bridges with accelbyteId now that we have it.
                    // - The WebSocket bridge lets the autogenesis server identify
                    //   this player connection.
                    // - The REST bridge rebinds the SSE inbound to the authenticated
                    //   user, so the upcoming `loadSavedCommanders` master-record
                    //   request runs against a stable, user-bound client. Doing the
                    //   rebind here (before the request fires) prevents the bridge
                    //   from being torn down mid-flight, which used to drop the
                    //   response and leave the messageBox stuck on
                    //   "Loading master record for ...".
                    // AWAIT both bridge connects inline — DO NOT wrap in `launch { ... }`.
                    //
                    // Bug fixed: previously this was a `launch { ... }` which made the
                    // rebind fire-and-forget. The very next line calls `loadSavedCommanders`
                    // which reads `ServerExtendBridge.rpcInvoker` — when the rebind was
                    // async the read sometimes returned the OLD invoker, whose pending
                    // `getMasterRecord` was then cancelled by the old client's close()
                    // (which calls drainInFlightRequests() and surfaces a
                    // `RestRpcClientClosedException`). The visible symptom was
                    // "Unable to load saved commanders: RestRpcClient closed before
                    // response (playerId=...)" on every login attempt.
                    //
                    // Awaiting inline makes the rebind atomic from the caller's
                    // perspective: by the time `loadSavedCommanders` runs, the new
                    // SSE channel is established and `RestRpcBridge.rpcInvoker` (and
                    // `ServerExtendBridge.rpcInvoker`) point at the user-bound client.
                    //
                    // We're already inside `scope.promise { ... }`, so calling suspend
                    // functions directly is fine.
                    WebSocketRpcBridge.connect(accelbyteId = userInfo.userId)
                    RestRpcBridge.connect(accelbyteId = userInfo.userId)

                    messageBox.setMessage("Fetching saved commanders for ${AccelByteEnv.userId}...")
                    messageBox.setThrobber(true, hideButtons = true)
                    Logger.debug(LogCategory.DATABASE, "LoginPage: starting master record fetch for ${AccelByteEnv.userId}")

                    val loadResult = runCatching {
                        loadSavedCommanders(messageBox)
                    }

                    if(loadResult.isSuccess)
                    {
                        messageBox.setMessage("Loaded ${World.availableCommanders.size} saved commanders")
                        messageBox.setThrobber(false)
                        messageBox.setButtons(ok = true, cancel = false)
                    }
                    else
                    {
                        Logger.error(
                            LogCategory.DATABASE,
                            "LoginPage: failed to hydrate commanders for ${AccelByteEnv.userId}: ${loadResult.exceptionOrNull()?.message}"
                        )
                        messageBox.setTitle("Warning")
                            .setMessage("Unable to load saved commanders: ${loadResult.exceptionOrNull()?.message}")
                            .setButtons(ok = true, cancel = false)
                            .setThrobber(false)
                    }

                    messageBox.onConfirm = {
                        KEnv.mainRoot?.remove(messageBox)
                        // Initialize AudioContext from user gesture context (login success).
                        // B1 fix: initContext() must run SYNCHRONOUSLY in the click handler
                        // so AudioContext.resume() is on the user-gesture call stack.
                        AudioEngine.initContext()
                        GlobalScope.launch { AudioEngine.initChannels() }
                        KEnv.appStack?.add(MainMenu())
                        KEnv.appStack?.activeIndex = 1
                    }

                }

                catch (e: Throwable)
                {
                    messageBox.setTitle("Error")
                        .setMessage("$e")
                        .setButtons(ok = true, cancel = false)
                    return@promise
                }

            }

            catch (e: Throwable)
            {
                messageBox.setTitle("Error")
                    .setMessage(e.message ?: "Unknown Error")
                    .setButtons(ok = true, cancel = false)
                    .setThrobber(false)
            }
        }
        promise.then {
    // Success
}.catch { cause ->
    console.error("Async operation failed:", cause)
            disableButtons = false
            scope.cancel()
        }
    }

    /**
     * Sends register code, and flips over to the register page to await the user's code input.
     */
    fun openRegisterWindow(isRegistering: Boolean = false)
    {
        /**
         * Throw error and exit if user didn't provide an email address with a @ or left it empty.
         * Wasting calls to AccelByte because the user didn't provide a valid string is problematic. So we should
         * take some basic precautions to prevent obviously incorrect email strings.
         */
        if(email.isEmpty() || !email.contains("@") && isRegistering)
        {
            Logger.warn(
                LogCategory.AUTH,
                "LoginWidgets: rejecting registration with invalid email (len=${email.length}, containsAt=${email.contains("@")})"
            )
            KEnv.mainRoot?.add(MessageBox(
                boxTitle = "Error",
                message = "A valid/non-empty email address must be provided.",
                showOk = true))

            return
        }

        /**
         * Send register code, and flip the page. Now handles error 10133 (email already used).
         */
        if(isRegistering)
        {
            MainScope().launch {
                try {
                    sendRegisterCode(email).await()
                    pageState = LoginState.REGISTER
                    this@LoginPage.widgetSwitcherRef?.activeIndex = 1
                } catch (e: Throwable) {
                    val errorCode = parseAccelByteErrorCode(e)
                    if (errorCode == 10133) {
                        KEnv.mainRoot?.add(MessageBox(
                            boxTitle = "Email Already Registered",
                            message = "This email address is already registered. Please use the password recovery option if you've forgotten your password.",
                            showOk = true))
                    } else {
                        KEnv.mainRoot?.add(MessageBox(
                            boxTitle = "Error",
                            message = "Failed to send verification code: ${e.message}",
                            showOk = true))
                    }
                }
            }
            return
        }

        else
        {
            //Send password reset code if we aren't in register mode.
            sendPasswordResetCode(email)
            pageState = LoginState.RECOVER
        }

        this@LoginPage.widgetSwitcherRef?.activeIndex = 1
    }

    /**
     * Verifies the code entered by the user for registration or password recovery.
     *
     * @param isRegister True if this is for registration, false for password recovery.
     */
    fun verifyCodeRequest(isRegister: Boolean)
    {
        if(inputCode.isEmpty())
        {
            KEnv.mainRoot?.add(MessageBox(
                boxTitle = "Error",
                message = "Please enter the secure code that was sent to your email address.",
                showOk = true
            ))

            return
        }

        if(isRegister)
        {
            widgetSwitcherRef?.activeIndex = 2
            emailBoxRef?.value = email //Write into since this default was assigned when the user hit the register prior.
            return
        }

        else
        {
            widgetSwitcherRef?.activeIndex = 3
            return
        }
    }

    /**
     * Finishes the account registration process.
     */
    fun registerAccount()
    {
        if (disableButtons) return

        val trimmedCode = inputCode.trim()
        val emailField = emailBoxRef?.value?.trim()
        val resolvedEmail = emailField?.takeUnless { it.isBlank() } ?: email.trim()
        val trimmedUserName = userName.trim()
        val resolvedDisplayName = displayName.trim().ifBlank { trimmedUserName }
        val passwordBlank = password.isBlank()

        if (trimmedCode.isEmpty() || resolvedEmail.isBlank() || trimmedUserName.isEmpty() || passwordBlank)
        {
            KEnv.mainRoot?.add(MessageBox(
                boxTitle = "Missing information",
                message = "Enter your email, username, password, and verification code before continuing.",
                showOk = true
            ))

            return
        }

        val scope = MainScope() //Required to issue the async calls that need returns from AccelByte.
        val messageBox = MessageBox(
            boxTitle = "Registering Account",
            message = "Please Wait",
            showThrobber = true
        )

        KEnv.mainRoot?.add(messageBox)

        val promise = scope.promise {
            val sdk = buildSdk()

            try
            {
                messageBox.setMessage("Connecting to register service")

                val request = RegisterUserRequest(
                    code = trimmedCode,
                    dateOfBirth = "1999-01-01",
                    emailAddress = resolvedEmail,
                    displayName = resolvedDisplayName,
                    username = trimmedUserName,
                    uniqueDisplayName = trimmedUserName,
                    password = password,
                    country = "US"
                )

                val result = withTimeout(REGISTER_REQUEST_TIMEOUT_MS)
                {
                    UsersFacade(sdk).registerUserSuspend(request)
                }

                if(result.userId.isEmpty())
                {
                    messageBox.setTitle("Error")
                        .setMessage("Unknown registration error")
                        .setButtons(ok = true, cancel = false)
                        .setThrobber(false)
                    return@promise
                }

                KEnv.mainRoot?.remove(messageBox)
                startLogin()
                return@promise
            }

            catch (e: TimeoutCancellationException)
            {
                messageBox.setTitle("Registration Timeout")
                    .setMessage("The registration service did not respond in time. Please try again.")
                    .setButtons(ok = true, cancel = false)
                    .setThrobber(false)
            }

            catch (e: Throwable)
            {
                messageBox.setTitle("Registration Failed")
                    .setMessage(e.message ?: "Unknown Error")
                    .setButtons(
                        ok = true,
                        cancel = false)
                    .setThrobber(false)
            }
        }
        promise.then {
    // Success
}.catch { cause ->
    console.error("Async operation failed:", cause)
            scope.cancel()
        }
    }

    /**
     * Trigger the sequence to reset the user's password.
     */
    fun resetPassword()
    {
        //Declare scope and sdk which we'll be needing
        val sdk = buildSdk()
        val scope = MainScope()

        scope.launch {

            //Declare ui message box.
            val message = MessageBox(
                boxTitle = "Reset Password",
                message = "Please wait",
                showThrobber = true
            )

            //Render ui message box.
            KEnv.mainRoot?.add(message)

            try
            {
                val result = UsersFacade(sdk).resetPassword(ResetPasswordRequestV3(
                    code = inputCode,
                    emailAddress = email,
                    newPassword = password,
                    )).await()

                KEnv.mainRoot?.remove(message)
                startLogin()
                return@launch
            }

            catch (e: Throwable)
            {
                message.setMessage(e.message ?: "Unknown Error")
                    .setButtons(ok = true, cancel = false)
                    .setThrobber(false)
                    .setTitle("Error")
            }

        }
    }

}