package com.aksumar.telegram.kafka

import org.springframework.stereotype.Component
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

@Component
class RunControl(
    private val reporter: FatalErrorReporter
) {
    val failed = AtomicBoolean(false)
    val closing = AtomicBoolean(false)

    private val done = CountDownLatch(1)

    fun finish() {
        done.countDown()
    }

    fun fail(message: String = "Kafka consumer failed") {
        if (failed.compareAndSet(false, true)) {
            reporter.report(message)
        }
        done.countDown()
    }

    fun await() {
        done.await()
    }
}
