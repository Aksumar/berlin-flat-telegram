package com.aksumar.telegram.maps

/** Contains only a safe, user-facing reason, never provider URLs or response bodies. */
class MapGenerationException(val reason: String, val details: String = "") : RuntimeException(reason)

/** Exception messages and causes may contain API keys, request URLs or provider response bodies. */
internal fun Exception.mapFailureDetails(): String =
    if (this is MapGenerationException && details.isNotBlank()) details
    else "exception=${javaClass.name}, cause=${cause?.javaClass?.name ?: "none"}, at=${stackTrace.take(5).joinToString(" <- ")}"
