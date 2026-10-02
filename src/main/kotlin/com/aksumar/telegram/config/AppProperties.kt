package com.aksumar.telegram.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("app")
class AppProperties {
    var bootstrapServers: String = ""
    var groupId: String = "berlin-flat-telegram-v1"
    var geoapifyApiKey: String = ""
    var telegramBaseUrl: String = "https://api.telegram.org"
    var botToken: String = ""
    var chatIds: List<String> = emptyList()

    fun chats(): List<String> = chatIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}
