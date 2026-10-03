package com.aksumar.telegram.client

import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Shared by commands, listing messages, and HTTP retries within this bot process. */
@Component
class TelegramRateLimiter {
    private var nextGlobal: Long? = null
    private val nextByChat = mutableMapOf<String, Long>()

    @Synchronized
    internal fun reserve(chatId: String?, now: Long): Long {
        nextByChat.entries.removeIf { it.value <= now }
        val slot = maxOf(now, nextGlobal ?: now, nextByChat[chatId] ?: now)
        nextGlobal = slot + 50_000_000 // At most 20 outbound requests per second.
        if (chatId != null) nextByChat[chatId] = slot + 1_100_000_000
        return slot - now
    }

    fun <T> schedule(chatId: String?, request: () -> CompletableFuture<T>): CompletableFuture<T> {
        val delay = reserve(chatId, System.nanoTime())
        return CompletableFuture.runAsync({}, CompletableFuture.delayedExecutor(delay, TimeUnit.NANOSECONDS))
            .thenCompose { request() }
    }
}
