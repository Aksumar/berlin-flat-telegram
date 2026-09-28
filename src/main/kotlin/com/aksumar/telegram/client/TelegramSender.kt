package com.aksumar.telegram.client

import com.aksumar.telegram.maps.ListingMap
import java.util.concurrent.CompletableFuture

fun interface TelegramSender {
    fun sendListing(
        chat: String,
        text: String,
        listingUrl: String,
        map: ListingMap?,
    ): CompletableFuture<Void> = send(chat, text)

    fun send(chat: String, text: String): CompletableFuture<Void>
}
