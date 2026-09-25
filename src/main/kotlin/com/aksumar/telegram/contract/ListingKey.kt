package com.aksumar.telegram.contract

private val keyMapper = jsonMapper()

fun listingKey(source: String, id: String): String =
    keyMapper.writeValueAsString(listOf(source, id))

fun matchesListingKey(key: String?, source: String, id: String): Boolean {
    if (key == null) {
        return false
    }

    return runCatching {
        val node = keyMapper.readTree(key)
        node.isArray &&
            node.size() == 2 &&
            node[0].isTextual &&
            node[1].isTextual &&
            node[0].asText() == source &&
            node[1].asText() == id
    }.getOrDefault(false)
}
