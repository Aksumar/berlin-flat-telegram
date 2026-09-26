package com.aksumar.telegram

import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import org.springframework.core.io.ByteArrayResource
import org.springframework.util.LinkedMultiValueMap
import java.net.http.HttpClient
import java.time.Duration

fun interface TelegramSender {
    fun send(chat: String, text: String)
    fun sendListing(chat: String, text: String, listingUrl: String, map: ListingMap?) { send(chat, text) }
}

class TelegramClient(token: String, baseUrl: String = "https://api.telegram.org") : TelegramSender {
    private val mapper = jsonMapper()
    private val endpoint: String
    private val client: RestClient
    init {
        require(token.isNotBlank()) { "Telegram token is required" }
        endpoint = "$baseUrl/bot$token"
        val factory = JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build())
        factory.setReadTimeout(Duration.ofSeconds(30))
        // No observation/interceptor logs: the endpoint contains the bot token.
        client = RestClient.builder().requestFactory(factory).build()
    }
    override fun send(chat: String, text: String) {
        sendText(chat, text)
    }

    override fun sendListing(chat: String, text: String, listingUrl: String, map: ListingMap?) {
        if (map == null) { send(chat, text); return }
        val note = if (map.approximate) "\n📍 Примерное расположение" else ""
        val fullCaption = text + note
        val fits = fullCaption.length <= 1024
        // Preserve every field for long listings; only the photo triggers a sound.
        val heading = text.substringBefore('\n').take(900).dropLastWhile { it.isHighSurrogate() }
        val caption = if (fits) fullCaption else heading + note
        val keyboard = mapOf("inline_keyboard" to listOf(
            listOf(mapOf("text" to "📍 Открыть на карте", "url" to map.url)),
            listOf(mapOf("text" to "Посмотреть объявление ↗", "url" to listingUrl))
        ))
        val form = LinkedMultiValueMap<String, Any>().apply {
            add("chat_id", chat)
            add("caption", caption)
            add("reply_markup", mapper.writeValueAsString(keyboard))
            add("photo", object : ByteArrayResource(map.png) {
                override fun getFilename() = "map.png"
            })
        }
        request("sendPhoto", MediaType.MULTIPART_FORM_DATA, form)
        if (!fits) sendText(chat, text, silent = true)
    }

    private fun sendText(chat: String, text: String, silent: Boolean = false) {
        request("sendMessage", MediaType.APPLICATION_JSON, mapOf("chat_id" to chat, "text" to text,
            "disable_notification" to silent, "link_preview_options" to mapOf("is_disabled" to true)))
    }

    private fun request(method: String, contentType: MediaType, body: Any) {
        try {
            val response = client.post().uri("$endpoint/$method").contentType(contentType)
                .body(body)
                .retrieve().body(String::class.java)
            val ok = mapper.readTree(response ?: "null")?.get("ok")
            if (ok?.isBoolean != true || !ok.booleanValue()) throw IllegalStateException()
        } catch (_: Exception) {
            // Never retain the original cause: HTTP exceptions may include the token in the URI.
            throw DeliveryException("Telegram delivery failed; listing remains pending")
        }
    }
}
