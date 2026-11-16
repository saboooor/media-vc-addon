package ca.saboor.discs.audio

/** Authentication challenges say nothing about whether the saved media URL is still valid. */
object PlaybackFailure {
    private val authentication = Regex(
        "sign[ -]?in|log[ -]?in|oauth|authenticat|captcha|not a bot|bot verification|bot detect|bot check|detected.{0,30}bot|confirm.{0,60}bot|too many requests|rate[ -]?limit|http.?429",
        RegexOption.IGNORE_CASE
    )
    enum class YoutubeIssue { RATE_LIMIT, BOT_CHECK, TOKEN_REJECTED, SIGN_IN, CLIENT_FAILURE }
    private val rateLimit = Regex("rate[ -]?limit|too many requests|429", RegexOption.IGNORE_CASE)
    private val botCheck = Regex("captcha|not a bot|bot verification|bot detect|bot check|detected.{0,30}bot|confirm.{0,60}bot", RegexOption.IGNORE_CASE)
    private val rejectedToken = Regex("invalid_grant|(?:oauth|refresh[ _-]?token|access[ _-]?token|credentials?).{0,60}(?:invalid|expired|revoked|reject)|(?:invalid|expired|revoked|reject).{0,60}(?:oauth|refresh[ _-]?token|access[ _-]?token|credentials?)", RegexOption.IGNORE_CASE)
    private val extractorFailure = Regex("YouTube extract(?:ion|or)|Could not run yt-dlp|yt-dlp returned", RegexOption.IGNORE_CASE)
    private const val CLIENT_MESSAGE = "All YouTube clients failed; check client compatibility and connectivity"
    private const val AUTH_MESSAGE = "Sign-in or bot verification required; check the media provider's authentication"

    fun keepDisc(message: String?): Boolean = message != null && (authentication.containsMatchIn(message) || rejectedToken.containsMatchIn(message) || extractorFailure.containsMatchIn(message) || message == CLIENT_MESSAGE)

    fun keepDisc(error: Throwable): Boolean = inspect(error) { cause ->
        keepDisc(cause.message) || cause.javaClass.simpleName == "AllClientsFailedException"
    }

    private fun inspect(error: Throwable, matches: (Throwable) -> Boolean): Boolean {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        fun inspect(cause: Throwable): Boolean {
            if (!seen.add(cause)) return false
            return matches(cause) || cause.cause?.let(::inspect) == true || cause.suppressed.any(::inspect)
        }
        return inspect(error)
    }

    fun youtubeIssue(message: String?): YoutubeIssue? = when {
        message == null -> null
        rateLimit.containsMatchIn(message) -> YoutubeIssue.RATE_LIMIT
        botCheck.containsMatchIn(message) -> YoutubeIssue.BOT_CHECK
        rejectedToken.containsMatchIn(message) -> YoutubeIssue.TOKEN_REJECTED
        authentication.containsMatchIn(message) -> YoutubeIssue.SIGN_IN
        message == CLIENT_MESSAGE || extractorFailure.containsMatchIn(message) -> YoutubeIssue.CLIENT_FAILURE
        else -> null
    }

    fun youtubeIssue(error: Throwable): YoutubeIssue? {
        // Inspect all nested/suppressed errors before deciding a token needs replacement.
        for (issue in listOf(YoutubeIssue.RATE_LIMIT, YoutubeIssue.BOT_CHECK,
            YoutubeIssue.TOKEN_REJECTED, YoutubeIssue.SIGN_IN)) {
            if (inspect(error) { youtubeIssue(it.message) == issue }) return issue
        }
        return if (inspect(error) { it.javaClass.simpleName == "AllClientsFailedException" || youtubeIssue(it.message) == YoutubeIssue.CLIENT_FAILURE })
            YoutubeIssue.CLIENT_FAILURE else null
    }

    fun youtubeAdvice(issue: YoutubeIssue): String = when (issue) {
        YoutubeIssue.RATE_LIMIT -> "YouTube rate-limited requests. Wait before retrying; reauthorization does not fix rate limits."
        YoutubeIssue.BOT_CHECK -> "YouTube requested bot verification. Check the YouTube client/PO-token setup and server connectivity; this does not prove the OAuth token is invalid."
        YoutubeIssue.TOKEN_REJECTED -> "YouTube rejected an authentication token. Check the console; use /disc oauth force if the saved OAuth credential needs replacement."
        YoutubeIssue.SIGN_IN -> "YouTube requires sign-in. Check the configured clients and authentication; a sign-in request alone does not prove the saved token expired."
        YoutubeIssue.CLIENT_FAILURE -> "All YouTube clients failed. Check the underlying console errors, client compatibility, remote cipher or yt-dlp/provider configuration, and connectivity before reauthorizing."
    }

    /** Preserve the classification when a provider wraps the useful error in a generic exception. */
    fun message(error: Throwable, fallback: String): String = when (youtubeIssue(error)) {
        YoutubeIssue.RATE_LIMIT -> "Provider rate limit exceeded; retry later"
        YoutubeIssue.BOT_CHECK -> "Bot verification required; check the media provider's client and PO-token setup"
        YoutubeIssue.TOKEN_REJECTED -> "OAuth token rejected or expired; check the media provider's authentication"
        YoutubeIssue.SIGN_IN -> AUTH_MESSAGE
        YoutubeIssue.CLIENT_FAILURE -> CLIENT_MESSAGE
        null -> error.message ?: fallback
    }
}
