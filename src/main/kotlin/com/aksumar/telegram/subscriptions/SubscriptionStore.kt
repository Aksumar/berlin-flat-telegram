package com.aksumar.telegram.subscriptions

import com.aksumar.telegram.config.AppProperties
import com.aksumar.telegram.model.Listing
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository

@Repository
class SubscriptionStore(private val jdbc: JdbcTemplate, private val properties: AppProperties) {
    private val rowMapper = RowMapper { rs, _ ->
        Subscription(rs.getString("chat_id"), rs.getBoolean("active"), ListingFilter(
            WbsFilter.valueOf(rs.getString("wbs")), rs.getBigDecimal("min_area"), rs.getBigDecimal("max_warm"),
        ))
    }

    fun get(chatId: String): Subscription? =
        jdbc.query("SELECT * FROM subscriptions WHERE chat_id = ?", rowMapper, chatId).firstOrNull()
            ?: chatId.takeIf { it in properties.chats() }?.let(::Subscription)

    fun save(subscription: Subscription) {
        jdbc.update(
            "MERGE INTO subscriptions (chat_id, active, wbs, min_area, max_warm) KEY(chat_id) VALUES (?, ?, ?, ?, ?)",
            subscription.chatId, subscription.active, subscription.filter.wbs.name,
            subscription.filter.minArea, subscription.filter.maxWarm,
        )
    }

    fun recipients(item: Listing): List<String> {
        val subscriptions = properties.chats().associateWith { Subscription(it) }.toMutableMap()
        jdbc.query("SELECT * FROM subscriptions", rowMapper).forEach { subscriptions[it.chatId] = it }
        return subscriptions.values.filter { it.active && it.filter.matches(item) }.map { it.chatId }
    }

    fun nextOffset(): Long = jdbc.queryForList(
        "SELECT next_offset FROM telegram_update_position WHERE id = 1", Long::class.java,
    ).firstOrNull() ?: 0L

    fun draft(chatId: String): SearchDraft? = jdbc.query(
        "SELECT * FROM search_drafts WHERE chat_id = ?", RowMapper { rs, _ ->
            SearchDraft(SearchStep.valueOf(rs.getString("step")), ListingFilter(
                WbsFilter.valueOf(rs.getString("wbs")), rs.getBigDecimal("min_area"), rs.getBigDecimal("max_warm"),
            ), rs.getBoolean("single_field"))
        }, chatId,
    ).firstOrNull()

    fun saveDraft(chatId: String, draft: SearchDraft) {
        jdbc.update("MERGE INTO search_drafts (chat_id, step, wbs, min_area, max_warm, single_field) KEY(chat_id) VALUES (?, ?, ?, ?, ?, ?)",
            chatId, draft.step.name, draft.filter.wbs.name, draft.filter.minArea, draft.filter.maxWarm, draft.singleField)
    }

    fun clearDraft(chatId: String) {
        jdbc.update("DELETE FROM search_drafts WHERE chat_id = ?", chatId)
    }

    fun reply(chatId: String, updateId: Long): String? = jdbc.queryForList(
        "SELECT reply FROM command_replies WHERE chat_id = ? AND update_id = ?", String::class.java, chatId, updateId,
    ).firstOrNull()

    fun saveReply(chatId: String, updateId: Long, reply: String) {
        jdbc.update("MERGE INTO command_replies (chat_id, update_id, reply) KEY(chat_id) VALUES (?, ?, ?)", chatId, updateId, reply)
    }

    fun advanceOffset(nextOffset: Long) {
        jdbc.update("MERGE INTO telegram_update_position (id, next_offset) KEY(id) VALUES (1, ?)", nextOffset)
    }
}
