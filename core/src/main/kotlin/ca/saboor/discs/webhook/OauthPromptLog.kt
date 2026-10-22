package ca.saboor.discs.webhook

import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.LoggerConfig
import su.plo.voice.lavaplayer.libs.dev.lavalink.youtube.YoutubeAudioSourceManager

/** The bundled provider exposes device pairing details only through its dedicated OAuth logger. */
class OauthPromptLog(private val receive: (OauthPrompt) -> Unit) : AutoCloseable {
    private val loggerName = YoutubeAudioSourceManager::class.java.packageName + ".http.YoutubeOauth2Handler"
    private var context: LoggerContext? = null
    private var logger: LoggerConfig? = null
    private var created = false
    private var originalLevel: Level? = null
    private val appender = object : AbstractAppender("DiscsOauthPrompt", null, null, true) {
        override fun append(event: LogEvent) {
            if (event.loggerName != loggerName) return
            OauthPrompt.parse(event.message.formattedMessage)?.let(receive)
        }
    }
    fun start() {
        if (context != null) return
        val ctx = LogManager.getContext(false) as? LoggerContext ?: return
        val config = ctx.configuration
        val existing = config.loggers[loggerName]
        val target = existing ?: LoggerConfig(loggerName, Level.INFO, true).also { config.addLogger(loggerName, it); created = true }
        originalLevel = target.level
        if (target.level.isMoreSpecificThan(Level.INFO)) target.level = Level.INFO
        appender.start()
        target.addAppender(appender, Level.INFO, null)
        logger = target; context = ctx
        ctx.updateLoggers()
    }
    override fun close() {
        val ctx = context ?: return
        logger?.removeAppender(appender.name)
        if (created) ctx.configuration.removeLogger(loggerName) else originalLevel?.let { logger?.level = it }
        appender.stop(); ctx.updateLoggers()
        context = null
    }
}
