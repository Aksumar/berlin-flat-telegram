package com.aksumar.telegram.client

import com.aksumar.telegram.client.exceptions.TelegramDeliveryException
import com.aksumar.telegram.maps.ListingMap
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import org.springframework.stereotype.Component

@Component
class TelegramClient(private val transport: TelegramTransport, private val mapper: ObjectMapper) :
    TelegramSender {
    override fun send(chat: String, text: String): CompletableFuture<Void> = sendText(chat, text)

    private fun sendText(
        chatId: String,
        text: String,
        silent: Boolean = false,
    ): CompletableFuture<Void> =
        transport.deliver(
            "sendMessage",
            "application/json",
            mapper.writeValueAsBytes(
                mapOf(
                    "chat_id" to chatId,
                    "text" to text,
                    "disable_notification" to silent,
                    "link_preview_options" to mapOf("is_disabled" to true),
                )
            ),
        )

    override fun sendListing(
        chat: String,
        text: String,
        listingUrl: String,
        map: ListingMap?,
    ): CompletableFuture<Void> {
        if (map == null) return send(chat, text)

        val locationNote = if (map.approximate) "\n📍 Примерное расположение" else ""
        val fullTextFitsCaption = text.length + locationNote.length <= 1024
        val caption = createMapCaption(text, locationNote, fullTextFitsCaption)

        return sendMapPhoto(chat, caption, listingUrl, map)
            .handle { _, failure ->
                if (failure != null) handlePhotoDeliveryFailure(chat, text, failure)
                else if (fullTextFitsCaption) CompletableFuture.completedFuture<Void>(null)
                else sendText(chat, text, silent = true)
            }
            .thenCompose { it }
    }

    private fun createMapCaption(
        text: String,
        locationNote: String,
        fullTextFits: Boolean,
    ): String {
        val captionText =
            if (fullTextFits) text
            else text.substringBefore('\n').take(900).dropLastWhile { it.isHighSurrogate() }
        return captionText + locationNote
    }

    private fun sendMapPhoto(
        chat: String,
        caption: String,
        listingUrl: String,
        map: ListingMap,
    ): CompletableFuture<Void> {
        val boundary = "map-${UUID.randomUUID()}"
        val keyboard = createListingKeyboard(map.url, listingUrl)
        val body = buildMapPhotoBody(chat, caption, keyboard, map.png, boundary)
        return transport.deliver("sendPhoto", "multipart/form-data; boundary=$boundary", body)
    }

    private fun createListingKeyboard(mapUrl: String, listingUrl: String): String =
        mapper.writeValueAsString(
            mapOf(
                "inline_keyboard" to
                    listOf(
                        listOf(mapOf("text" to "📍 Открыть на карте", "url" to mapUrl)),
                        listOf(mapOf("text" to "Посмотреть объявление ↗", "url" to listingUrl)),
                    )
            )
        )

    private fun buildMapPhotoBody(
        chat: String,
        caption: String,
        keyboard: String,
        png: ByteArray,
        boundary: String,
    ): ByteArray =
        ByteArrayOutputStream().use { out ->
            fun write(value: String) {
                out.write(value.toByteArray(Charsets.UTF_8))
            }
            for ((name, value) in
                mapOf("chat_id" to chat, "caption" to caption, "reply_markup" to keyboard)) {
                write(
                    "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n"
                )
            }
            write(
                "--$boundary\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"map.png\"\r\nContent-Type: image/png\r\n\r\n"
            )
            out.write(png)
            write("\r\n--$boundary--\r\n")
            out.toByteArray()
        }

    private fun handlePhotoDeliveryFailure(
        chat: String,
        text: String,
        failure: Throwable,
    ): CompletableFuture<Void> {
        val cause = unwrap(failure)
        // Only permanent photo rejection falls back to text; transient failures remain uncommitted.
        return if (cause is TelegramDeliveryException && !cause.retryable) send(chat, text)
        else CompletableFuture.failedFuture(cause)
    }

    private fun unwrap(error: Throwable): Throwable =
        if (error is CompletionException && error.cause != null) error.cause!! else error
}
