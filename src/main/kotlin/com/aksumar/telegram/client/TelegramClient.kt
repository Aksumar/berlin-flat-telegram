package com.aksumar.telegram.client

import com.aksumar.telegram.client.exceptions.TelegramDeliveryException
import com.aksumar.telegram.maps.ListingMap
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

@Component
class TelegramClient(private val transport: TelegramTransport, private val mapper: ObjectMapper) :
    TelegramSender {
    override fun send(chat: String, text: String): CompletableFuture<Void> = sendText(chat, text)

    private fun sendText(
        chatId: String,
        text: String,
        silent: Boolean = false,
        keyboard: Map<String, Any>? = null,
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
                ) + if (keyboard == null) emptyMap() else mapOf("reply_markup" to keyboard)
            ),
        )

    override fun sendListing(
        chat: String,
        text: String,
        listingUrl: String,
        map: ListingMap?,
        mapUrl: String?,
    ): CompletableFuture<Void> {
        val keyboard = createListingKeyboard(mapUrl, listingUrl)
        if (map == null) return sendText(chat, text, keyboard = keyboard)

        val locationNote = if (map.approximate) "\n📍 Примерное расположение" else ""
        val fullTextFitsCaption = text.length + locationNote.length <= 1024
        val caption = createMapCaption(text, locationNote, fullTextFitsCaption)

        return sendMapPhoto(chat, caption, keyboard, map)
            .handle { _, failure ->
                if (failure != null) handlePhotoDeliveryFailure(chat, text, keyboard, failure)
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
        keyboard: Map<String, Any>,
        map: ListingMap,
    ): CompletableFuture<Void> {
        val boundary = "map-${UUID.randomUUID()}"
        val body = buildMapPhotoBody(chat, caption, mapper.writeValueAsString(keyboard), map.png, boundary)
        return transport.deliver("sendPhoto", "multipart/form-data; boundary=$boundary", body)
    }

    private fun createListingKeyboard(mapUrl: String?, listingUrl: String): Map<String, Any> =
        mapOf(
            "inline_keyboard" to
                    listOf(
                        listOfNotNull(
                            mapOf("text" to "Объявление ↗", "url" to listingUrl),
                            mapUrl?.let { mapOf("text" to "📍 Карта", "url" to it) },
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
        keyboard: Map<String, Any>,
        failure: Throwable,
    ): CompletableFuture<Void> {
        val cause = unwrap(failure)
        // After photo delivery fails (including exhausted retries), try text with the same buttons.
        return if (cause is TelegramDeliveryException) sendText(chat, text, keyboard = keyboard)
        else CompletableFuture.failedFuture(cause)
    }

    private fun unwrap(error: Throwable): Throwable =
        if (error is CompletionException && error.cause != null) error.cause!! else error
}
