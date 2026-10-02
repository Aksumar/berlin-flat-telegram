package com.aksumar.telegram.subscriptions

import com.aksumar.telegram.client.TelegramSender
import com.aksumar.telegram.client.TelegramTransport
import com.aksumar.telegram.client.exceptions.TelegramDeliveryException
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.ExecutionException

@Component
@ConditionalOnProperty(name = ["app.telegram-updates-enabled"], havingValue = "true", matchIfMissing = true)
class TelegramUpdates(
    private val transport: TelegramTransport,
    private val sender: TelegramSender,
    private val commands: FilterCommands,
    private val store: SubscriptionStore,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 1000)
    fun poll() {
        try {
            val updates = transport.getUpdates(store.nextOffset()).get()
            for (update in updates) {
                check(update.path("update_id").isIntegralNumber) { "Invalid update ID" }
                update.path("callback_query").path("id").takeIf { it.isTextual }?.let {
                    try {
                        transport.answerCallback(it.asText()).get()
                    } catch (error: ExecutionException) {
                        // An expired button click can still be processed; dismissing the spinner is best effort.
                        log.debug("Could not acknowledge Telegram button click")
                    }
                }
                commands.handle(update)?.let { (chat, text, buttons, menuId) ->
                    try {
                        sender.sendMenu(chat, text, buttons, menuId ?: update.path("update_id").asLong()).get()
                        update.path("callback_query").path("message").path("message_id")
                            .takeIf { it.isIntegralNumber }?.let {
                                try {
                                    transport.clearMenu(chat, it.asLong()).get()
                                } catch (_: ExecutionException) {
                                    // Old messages may no longer be editable. Their callbacks are still rejected.
                                    log.debug("Could not remove old Telegram menu buttons")
                                }
                            }
                    } catch (error: ExecutionException) {
                        val cause = error.cause
                        if (cause !is TelegramDeliveryException || cause.retryable) throw error
                        log.warn("Telegram rejected command reply permanently")
                    }
                }
                // Confirm only after saving settings and handling the reply. Commands are idempotent on retry.
                store.advanceOffset(update.path("update_id").asLong() + 1)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
            log.warn("Telegram command processing failed; will retry. Check database, token, webhook and other polling instances.")
        }
    }
}
