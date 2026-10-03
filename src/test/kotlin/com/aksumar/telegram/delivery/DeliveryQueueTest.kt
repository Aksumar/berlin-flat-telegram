package com.aksumar.telegram.delivery

import com.aksumar.telegram.config.AppProperties
import com.aksumar.telegram.subscriptions.Subscription
import com.aksumar.telegram.maps.ListingMap
import com.aksumar.telegram.model.Listing
import com.aksumar.telegram.subscriptions.SubscriptionStore
import com.aksumar.telegram.support.fixture
import com.aksumar.telegram.support.testMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Path
import java.time.Duration

class DeliveryQueueTest {
    @TempDir lateinit var directory: Path

    private fun queue(chats: List<String> = listOf("123", "456"), url: String = "jdbc:h2:mem:q${System.nanoTime()};DB_CLOSE_DELAY=-1"):
        Pair<DeliveryQueue, JdbcTemplate> {
        val source = DriverManagerDataSource(url, "sa", "")
        ResourceDatabasePopulator(ClassPathResource("schema.sql")).execute(source)
        val jdbc = JdbcTemplate(source)
        val subscriptions = SubscriptionStore(jdbc, AppProperties().apply { chatIds = chats })
        return DeliveryQueue(jdbc, subscriptions, testMapper, TransactionTemplate(JdbcTransactionManager(source))) to jdbc
    }

    private fun listing(): Listing = testMapper.readValue(fixture(), Listing::class.java)

    @Test
    fun `enqueue is idempotent and snapshots recipients for each Kafka record`() {
        val (queue, jdbc) = queue(listOf("123", "456"))
        assertTrue(queue.enqueue("topic:0:5", listing(), 1000))
        assertFalse(queue.enqueue("topic:0:5", listing(), 2000))
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM delivery_jobs", Int::class.java))
        SubscriptionStore(jdbc, AppProperties()).save(Subscription("456", active = false))
        assertTrue(queue.enqueue("topic:0:6", listing(), 3000))
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM delivery_jobs", Int::class.java))
        assertEquals(3L, queue.pendingCount())
    }

    @Test
    fun `prepared text and map survive reopening H2 and completing one recipient`() {
        val file = directory.resolve("queue")
        val url = "jdbc:h2:file:$file"
        val (first, _) = queue(url = url)
        first.enqueue("topic:0:8", listing(), 1000)
        val job = first.next(1000)!!
        first.savePrepared(job.eventKey, PreparedListing("listing body", listing().url,
            ListingMap(byteArrayOf(1, 2, 3), true), "https://maps.example/"))
        first.finish(job, rejected = false, now = 2000)
        val (reopened, _) = queue(url = url)
        val next = reopened.next(3000)!!
        assertEquals("456", next.chatId)
        assertEquals("listing body", next.prepared!!.text)
        assertArrayEquals(byteArrayOf(1, 2, 3), next.prepared.map!!.png)
        reopened.finish(next, rejected = false, now = 4000)
        assertEquals(0, reopened.pendingCount())
        assertEquals(0.0, reopened.oldestPendingAgeSeconds(5000))
    }

    @Test
    fun `retries retain the job with bounded exponential delay and Telegram cooldown`() {
        val (queue, _) = queue(listOf("123"))
        queue.enqueue("topic:0:9", listing(), 1_000)
        val job = queue.next(1_000)!!
        queue.retry(job, now = 1_000)
        assertNull(queue.next(30_999))
        assertNotNull(queue.next(31_000))
        val due = queue.next(31_000)!!
        queue.retry(due, retryAfterSeconds = 5000, now = 31_000)
        assertNull(queue.next(5_030_999))
        assertNotNull(queue.next(5_031_000))
        assertEquals(1L, queue.pendingCount())
    }

    @Test
    fun `permanent rejections finish events while cleanup preserves recent deduplication`() {
        val (queue, jdbc) = queue(listOf("123"))
        queue.enqueue("topic:0:11", listing(), 10_000)
        val job = queue.next(10_000)!!
        queue.finish(job, rejected = true, now = 20_000)
        assertEquals("REJECTED", jdbc.queryForObject("SELECT status FROM delivery_jobs", String::class.java))
        assertFalse(queue.enqueue("topic:0:11", listing(), 30_000))
        queue.cleanup(8 * Duration.ofDays(1).toMillis())
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM delivery_jobs", Int::class.java))
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM delivery_events", Int::class.java))
        queue.cleanup(36 * Duration.ofDays(1).toMillis())
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM delivery_events", Int::class.java))
    }
}
