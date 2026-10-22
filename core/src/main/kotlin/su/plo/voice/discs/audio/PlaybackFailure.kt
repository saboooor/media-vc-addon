package su.plo.voice.discs.audio

/** Authentication challenges say nothing about whether the saved media URL is still valid. */
object PlaybackFailure {
    private val authentication = Regex(
        "sign[ -]?in|log[ -]?in|oauth|authenticat|captcha|not a bot|bot verification|bot detect|bot check|detected.{0,30}bot|confirm.{0,60}bot|too many requests|rate[ -]?limit|http.?429",
        RegexOption.IGNORE_CASE
    )
    private const val AUTH_MESSAGE = "Sign-in or bot verification required; check the media provider's authentication"

    fun keepDisc(message: String?): Boolean = message != null && authentication.containsMatchIn(message)

    fun keepDisc(error: Throwable): Boolean {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        fun inspect(cause: Throwable): Boolean {
            if (!seen.add(cause)) return false
            return keepDisc(cause.message) || cause.javaClass.simpleName == "AllClientsFailedException" ||
                cause.cause?.let(::inspect) == true || cause.suppressed.any(::inspect)
        }
        return inspect(error)
    }

    /** Preserve the classification when a provider wraps the useful error in a generic exception. */
    fun message(error: Throwable, fallback: String): String =
        if (keepDisc(error)) AUTH_MESSAGE else error.message ?: fallback
}
