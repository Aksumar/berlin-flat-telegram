package com.aksumar.telegram.client.exceptions

import com.aksumar.telegram.exception.DeliveryException

class TelegramDeliveryException(
    message: String,
    val retryable: Boolean
) : DeliveryException(message)
