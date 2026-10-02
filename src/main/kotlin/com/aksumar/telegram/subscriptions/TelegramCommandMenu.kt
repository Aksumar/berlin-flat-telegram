package com.aksumar.telegram.subscriptions

import com.aksumar.telegram.client.TelegramTransport
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["app.telegram-updates-enabled"], havingValue = "true", matchIfMissing = true)
class TelegramCommandMenu(private val transport: TelegramTransport) {
    private val log = LoggerFactory.getLogger(javaClass)
    private var registered = false

    // Install once per application start, retrying later if Telegram is unavailable.
    @Scheduled(fixedDelay = 60_000)
    fun register() {
        if (registered) return
        try {
            transport.configureCommandMenu().get()
            registered = true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
            log.warn("Telegram command menu registration failed; will retry")
        }
    }
}
