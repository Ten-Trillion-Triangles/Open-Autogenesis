package ui.billing

import kotlin.math.abs
import kotlin.math.floor

/**
 * Formatting helpers used by the Shop and Usage overlays.
 *
 * All functions are pure and side-effect-free so they can be called from any
 * KVision render path without touching the project Logger. JS-only target —
 * no `String.format` (which would pull in `java.util.Formatter`).
 */
object BillingFormatting
{
    /**
     * Formats a credit balance for display in the top-bar pill and Shop header.
     * Rounds half-up to the nearest integer when the value is large or whole;
     * otherwise shows up to two decimal places. Negative balances are clamped
     * to 0 to match the [structs.account.BillingStatus.coerceAtLeast] guard
     * on the server.
     *
     * @param credits Raw credit balance.
     * @return Human-readable string such as "1,250" or "0.50".
     */
    fun formatCredits(credits: Double): String
    {
        if (credits.isNaN() || credits.isInfinite()) return "0"
        val safe = credits.coerceAtLeast(0.0)
        return if (safe >= 1000.0 || safe == floor(safe))
        {
            val asLong = safe.toLong()
            groupThousands(asLong)
        }
        else
        {
            "%.2f".formatSafe(safe)
        }
    }

    /**
     * Formats a credit delta for display in the deduction history rows.
     * Returns a signed string such as "-12.50" or "+5.00".
     *
     * @param creditsDelta Raw delta; negatives are deductions, positives are refills/purchases.
     * @return Signed string with no grouping.
     */
    fun formatCreditsDelta(creditsDelta: Double): String
    {
        if (creditsDelta == 0.0) return "0"
        val sign = if (creditsDelta < 0) "-" else "+"
        return sign + formatCredits(abs(creditsDelta))
    }

    /**
     * Renders a short, relative-time string for a past epoch-millis timestamp.
     * Returns "just now" for under 60s, "Xm ago" for under an hour, "Xh ago" for
     * under a day, and "Xd ago" beyond that.
     *
     * @param timestampMillis Event timestamp.
     * @param nowMillis Reference "now" timestamp; defaults to the system clock.
     * @return Short relative-time string.
     */
    fun formatRelativeTime(timestampMillis: Long, nowMillis: Long = currentMillis()): String
    {
        val deltaMs = (nowMillis - timestampMillis).coerceAtLeast(0L)
        val seconds = deltaMs / 1000L
        if (seconds < 60L) return "just now"
        val minutes = seconds / 60L
        if (minutes < 60L) return "${minutes}m ago"
        val hours = minutes / 60L
        if (hours < 24L) return "${hours}h ago"
        val days = hours / 24L
        return "${days}d ago"
    }

    /**
     * Inserts thousands separators into an integer. Pure JS-portable loop —
     * no `String.format` (which is missing on Kotlin/JS).
     */
    private fun groupThousands(value: Long): String
    {
        val negative = value < 0
        val digits = abs(value).toString()
        val out = StringBuilder(digits.length + digits.length / 3)
        for ((index, ch) in digits.withIndex())
        {
            if (index != 0 && (digits.length - index) % 3 == 0) out.append(',')
            out.append(ch)
        }
        return if (negative) "-${out}" else out.toString()
    }

    /**
     * Best-effort portable "now". Delegates to the system clock on JS.
     */
    private fun currentMillis(): Long = kotlin.js.Date.now().toLong()

    /**
     * Wrapper around Kotlin's JS-friendly `String.format`. Avoids the JVM-only
     * `java.util.Formatter` while keeping the same call site for floats.
     */
    private fun String.formatSafe(value: Double): String
    {
        val parts = this.split(".")
        if (parts.size != 2) return this
        val intPart = value.toLong()
        val frac = ((value - intPart) * 100).toLong().let { if (it < 0) -it else it }
        val fracStr = if (frac < 10) "0$frac" else frac.toString()
        return "${groupThousands(intPart)}.$fracStr"
    }
}