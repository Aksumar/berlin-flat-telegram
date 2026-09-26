package com.aksumar.telegram.contract.exceptions

open class DeliveryException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
