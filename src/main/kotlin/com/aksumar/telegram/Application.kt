package com.aksumar.telegram

import com.aksumar.telegram.config.AppProperties
import com.aksumar.telegram.kafka.DeliveryRuntime
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import kotlin.system.exitProcess

@SpringBootApplication(exclude = [KafkaAutoConfiguration::class])
@EnableConfigurationProperties(AppProperties::class)
class Application

fun main(args: Array<String>) {
    val result = try {
        SpringApplication.run(Application::class.java, *args).use { context ->
            context.getBean(DeliveryRuntime::class.java).run()
        }
    } catch (_: Exception) {
        System.err.println("Delivery failed. Check configuration and connectivity.")
        1
    }

    exitProcess(result)
}
