package com.aksumar.telegram.delivery

import com.aksumar.telegram.maps.ListingMap
import com.aksumar.telegram.model.Listing
import com.aksumar.telegram.subscriptions.SubscriptionStore
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration

/** One Spring worker processes this file-backed H2 queue. Network calls never hold a transaction. */
@Repository
class DeliveryQueue(
    private val jdbc: JdbcTemplate,
    private val subscriptions: SubscriptionStore,
    private val mapper: ObjectMapper,
    private val transactions: TransactionTemplate,
) {
    data class Job(
        val eventKey: String,
        val chatId: String,
        val attempts: Int,
        val listing: Listing,
        val prepared: PreparedListing?,
    )

    fun enqueue(eventKey: String, listing: Listing, now: Long = System.currentTimeMillis()): Boolean =
        transactions.execute {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM delivery_events WHERE event_key = ?",
                    Long::class.java, eventKey) != 0L) return@execute false
            val recipients = subscriptions.recipients(listing)
            jdbc.update("INSERT INTO delivery_events(event_key, payload, created_at, completed_at) VALUES (?, ?, ?, ?)",
                eventKey, listing.takeUnless { recipients.isEmpty() }?.let(mapper::writeValueAsString),
                now, if (recipients.isEmpty()) now else null)
            recipients.forEach { chat ->
                jdbc.update("INSERT INTO delivery_jobs(event_key, chat_id, status, available_at) VALUES (?, ?, 'PENDING', ?)",
                    eventKey, chat, now)
            }
            true
        }!!

    fun next(now: Long = System.currentTimeMillis()): Job? = jdbc.query(
        """SELECT j.*, e.payload, e.message_text, e.map_png, e.map_approximate, e.map_url
           FROM delivery_jobs j JOIN delivery_events e ON e.event_key = j.event_key
           WHERE j.status = 'PENDING' AND j.available_at <= ?
           ORDER BY j.available_at, e.created_at, j.event_key, j.chat_id LIMIT 1""",
        { rs, _ ->
            val listing = mapper.readValue(rs.getString("payload"), Listing::class.java)
            val prepared = rs.getString("message_text")?.let {
                PreparedListing(it, listing.url, rs.getBytes("map_png")?.let { bytes ->
                    ListingMap(bytes, rs.getBoolean("map_approximate"))
                }, rs.getString("map_url"))
            }
            Job(rs.getString("event_key"), rs.getString("chat_id"), rs.getInt("attempts"), listing, prepared)
        }, now,
    ).firstOrNull()

    fun savePrepared(eventKey: String, content: PreparedListing) {
        jdbc.update("UPDATE delivery_events SET message_text = ?, map_png = ?, map_approximate = ?, map_url = ? WHERE event_key = ?",
            content.text, content.map?.png, content.map?.approximate ?: false, content.mapUrl, eventKey)
    }

    fun finish(job: Job, rejected: Boolean, now: Long = System.currentTimeMillis()) {
        transactions.executeWithoutResult {
            jdbc.update("UPDATE delivery_jobs SET status = ?, attempts = attempts + 1, finished_at = ? WHERE event_key = ? AND chat_id = ?",
                if (rejected) "REJECTED" else "SENT", now, job.eventKey, job.chatId)
            jdbc.update("""UPDATE delivery_events SET completed_at = ?, payload = NULL, message_text = NULL,
                map_png = NULL, map_url = NULL WHERE event_key = ?
                AND NOT EXISTS (SELECT 1 FROM delivery_jobs WHERE event_key = ? AND status = 'PENDING')""",
                now, job.eventKey, job.eventKey)
        }
    }

    fun retry(job: Job, retryAfterSeconds: Long? = null, now: Long = System.currentTimeMillis()) {
        val seconds = maxOf((30L shl job.attempts.coerceAtMost(7)).coerceAtMost(3600), retryAfterSeconds ?: 0)
        jdbc.update("UPDATE delivery_jobs SET attempts = attempts + 1, available_at = ? WHERE event_key = ? AND chat_id = ?",
            Math.addExact(now, Math.multiplyExact(seconds, 1000)), job.eventKey, job.chatId)
    }

    fun pendingCount(): Long = jdbc.queryForObject(
        "SELECT COUNT(*) FROM delivery_jobs WHERE status = 'PENDING'", Long::class.java)!!

    fun oldestPendingAgeSeconds(now: Long = System.currentTimeMillis()): Double {
        val oldest = jdbc.queryForObject("""SELECT MIN(e.created_at) FROM delivery_events e
            WHERE EXISTS (SELECT 1 FROM delivery_jobs j WHERE j.event_key = e.event_key AND j.status = 'PENDING')""",
            Long::class.java) ?: return 0.0
        return (now - oldest).coerceAtLeast(0) / 1000.0
    }

    fun cleanup(now: Long = System.currentTimeMillis()) {
        transactions.executeWithoutResult {
            jdbc.update("DELETE FROM delivery_jobs WHERE status <> 'PENDING' AND finished_at < ?",
                now - Duration.ofDays(7).toMillis())
            // Longer than the configured 30-day Kafka retention; pending events are never removed.
            jdbc.update("""DELETE FROM delivery_events WHERE completed_at < ?
                AND NOT EXISTS (SELECT 1 FROM delivery_jobs WHERE delivery_jobs.event_key = delivery_events.event_key)""",
                now - Duration.ofDays(35).toMillis())
        }
    }
}
