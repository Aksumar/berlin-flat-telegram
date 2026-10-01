package com.aksumar.telegram.maps

/** User-facing reason is separate from diagnostics, whose request URLs exclude API keys. */
class MapGenerationException(
    val reason: String,
    val details: String = "",
    val outcome: String = "error",
) : RuntimeException(reason)

/** Exception messages and causes may contain API keys, request URLs or provider response bodies. */
internal fun Exception.mapFailureDetails(): String =
    if (this is MapGenerationException && details.isNotBlank()) details
    else "exception=${javaClass.name}, cause=${cause?.javaClass?.name ?: "none"}, at=${stackTrace.take(5).joinToString(" <- ")}"
