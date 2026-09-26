package com.aksumar.telegram.client.exceptions

import com.aksumar.telegram.contract.exceptions.DeliveryException

class TelegramDeliveryException(
    message: String,
    val retryable: Boolean
) : DeliveryException(message)
