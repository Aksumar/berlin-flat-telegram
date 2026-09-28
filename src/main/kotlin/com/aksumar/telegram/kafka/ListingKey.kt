package com.aksumar.telegram.kafka

import com.fasterxml.jackson.databind.ObjectMapper

fun ObjectMapper.listingKey(source: String, id: String): String =
    writeValueAsString(listOf(source, id))

fun ObjectMapper.matchesListingKey(key: String?, source: String, id: String): Boolean {
    if (key == null) {
        return false
    }

    return runCatching {
            val node = readTree(key)
            node.isArray &&
                node.size() == 2 &&
                node[0].isTextual &&
                node[1].isTextual &&
                node[0].asText() == source &&
                node[1].asText() == id
        }
        .getOrDefault(false)
}
