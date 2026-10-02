package com.aksumar.telegram

import com.aksumar.telegram.config.AppProperties
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication(exclude = [KafkaAutoConfiguration::class])
@EnableConfigurationProperties(AppProperties::class)
@EnableScheduling
class Application

fun main(args: Array<String>) {
    SpringApplication.run(Application::class.java, *args)
}
