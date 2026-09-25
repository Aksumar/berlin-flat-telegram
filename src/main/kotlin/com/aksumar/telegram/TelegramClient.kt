package com.aksumar.telegram

import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration

fun interface TelegramSender { fun send(chat: String, text: String) }

class TelegramClient(token: String, baseUrl: String = "https://api.telegram.org") : TelegramSender {
    private val mapper = jsonMapper()
    private val endpoint: String
    private val client: RestClient
    init {
        require(token.isNotBlank()) { "Telegram token is required" }
        endpoint = "$baseUrl/bot$token/sendMessage"
        val factory = JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build())
        factory.setReadTimeout(Duration.ofSeconds(30))
        // No observation/interceptor logs: the endpoint contains the bot token.
        client = RestClient.builder().requestFactory(factory).build()
    }
    override fun send(chat: String, text: String) {
        try {
            val response = client.post().uri(endpoint).contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("chat_id" to chat, "text" to text, "link_preview_options" to mapOf("is_disabled" to true)))
                .retrieve().body(String::class.java)
            val ok = mapper.readTree(response ?: "null")?.get("ok")
            if (ok?.isBoolean != true || !ok.booleanValue()) throw IllegalStateException()
        } catch (_: Exception) {
            // Never retain the original cause: HTTP exceptions may include the token in the URI.
            throw DeliveryException("Telegram delivery failed; listing remains pending")
        }
    }
}
