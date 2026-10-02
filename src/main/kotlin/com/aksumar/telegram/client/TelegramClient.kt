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

    override fun sendMenu(chat: String, text: String, buttons: List<List<String>>, menuId: Long): CompletableFuture<Void> =
        sendText(chat, text, keyboard = mapOf("inline_keyboard" to buttons.mapIndexed { rowIndex, row ->
            row.mapIndexed { column, label -> mapOf("text" to label, "callback_data" to "menu:$menuId:$rowIndex:$column") }
        }), boldHeading = true)

    private fun sendText(
        chatId: String,
        text: String,
        silent: Boolean = false,
        keyboard: Map<String, Any>? = null,
        boldHeading: Boolean = false,
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
                ) + (if (keyboard == null) emptyMap() else mapOf("reply_markup" to keyboard)) +
                    (if (boldHeading) mapOf("entities" to headingEntities(text)) else emptyMap())
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
        if (map == null) return sendText(chat, text, keyboard = keyboard, boldHeading = true)

        val locationNote = if (map.approximate) "\n📍 Примерное расположение" else ""
        val fullTextFitsCaption = text.length + locationNote.length <= 1024
        val caption = createMapCaption(text, locationNote, fullTextFitsCaption)

        return sendMapPhoto(chat, caption, keyboard, map)
            .handle { _, failure ->
                if (failure != null) handlePhotoDeliveryFailure(chat, text, keyboard, failure)
                else if (fullTextFitsCaption) CompletableFuture.completedFuture<Void>(null)
                else sendText(chat, text, silent = true, boldHeading = true)
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
                            mapOf("text" to "Объявление", "url" to listingUrl),
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
            mapOf(
                "chat_id" to chat,
                "caption" to caption,
                "caption_entities" to mapper.writeValueAsString(headingEntities(caption)),
                "reply_markup" to keyboard,
            )) {
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
        return if (cause is TelegramDeliveryException) sendText(chat, text, keyboard = keyboard, boldHeading = true)
        else CompletableFuture.failedFuture(cause)
    }

    private fun unwrap(error: Throwable): Throwable =
        if (error is CompletionException && error.cause != null) error.cause!! else error

    private fun headingEntities(text: String): List<Map<String, Any>> {
        val length = text.substringBefore('\n').length
        return if (length == 0) emptyList()
        else listOf(mapOf("type" to "bold", "offset" to 0, "length" to length))
    }
}
