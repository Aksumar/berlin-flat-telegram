package com.aksumar.telegram.support

import com.aksumar.telegram.config.AppProperties
import com.aksumar.telegram.subscriptions.SubscriptionStore
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import java.util.UUID

fun subscriptionStore(chats: List<String> = emptyList(), url: String = "jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1"): SubscriptionStore {
    val datasource = DriverManagerDataSource(url, "sa", "")
    ResourceDatabasePopulator(ClassPathResource("schema.sql")).execute(datasource)
    return SubscriptionStore(JdbcTemplate(datasource), AppProperties().apply { chatIds = chats })
}
