package com.aksumar.telegram.exception

open class DeliveryException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
