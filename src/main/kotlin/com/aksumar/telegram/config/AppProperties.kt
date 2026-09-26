package com.aksumar.telegram.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("app")
class AppProperties {
    var bootstrapServers: String = ""
    var topic: String = "berlin-flat-listings-v1"
    var groupId: String = "berlin-flat-telegram-v1"
    var geoapifyApiKey: String = ""
    var botToken: String = ""
    var chatIds: List<String> = emptyList()

    fun chats(): List<String> = chatIds.also {
        require(it.isNotEmpty()) { "Telegram destination is required" }
    }
}
