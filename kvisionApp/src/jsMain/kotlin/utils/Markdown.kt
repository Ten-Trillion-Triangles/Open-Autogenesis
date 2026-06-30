package utils

import kotlin.js.JsModule
import kotlin.js.JsNonModule

@JsModule("marked")
@JsNonModule
external object marked {
    fun parse(markdown: String): String
}
