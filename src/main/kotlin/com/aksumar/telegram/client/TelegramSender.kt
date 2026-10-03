package com.aksumar.telegram.client

import com.aksumar.telegram.maps.ListingMap
import java.util.concurrent.CompletableFuture

fun interface TelegramSender {
    fun sendMenu(chat: String, text: String, buttons: List<List<String>>, menuId: Long): CompletableFuture<Void> = send(chat, text)

    fun sendListing(
        chat: String,
        text: String,
        listingUrl: String,
        map: ListingMap?,
        mapUrl: String?,
    ): CompletableFuture<Void> = send(chat, text)

    fun sendQueuedListing(
        chat: String,
        text: String,
        listingUrl: String,
        map: ListingMap?,
        mapUrl: String?,
    ): CompletableFuture<Void> = sendListing(chat, text, listingUrl, map, mapUrl)

    fun send(chat: String, text: String): CompletableFuture<Void>
}
