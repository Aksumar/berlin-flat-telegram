package com.aksumar.telegram.kafka

import com.aksumar.telegram.client.TelegramSender
import com.aksumar.telegram.config.AppProperties
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

fun interface FatalErrorReporter {
    fun report(message: String)
}

@Component
class TelegramFatalErrorReporter(
    private val sender: TelegramSender,
    private val properties: AppProperties
) : FatalErrorReporter {
    private val alerted = AtomicBoolean(false)

    override fun report(message: String) {
        if (!alerted.compareAndSet(false, true)) {
            return
        }

        try {
            CompletableFuture.allOf(
                *properties.chats()
                    .map { sender.send(it, "⚠️ Berlin Flat Telegram: $message") }
                    .toTypedArray()
            )
                .orTimeout(10, TimeUnit.SECONDS)
                .join()
        } catch (_: Exception) {
            // Fatal shutdown must continue even if the alert cannot be delivered.
        }
    }
}
