package com.aksumar.telegram.subscriptions

import com.aksumar.telegram.support.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SubscriptionsTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `WBS distinguishes unknown from no and applies all three modes`() {
        for (required in listOf(true, false, null)) {
            val item = event().copy(wbs = event().wbs.copy(required = required))
            assertTrue(ListingFilter().matches(item))
            assertEquals(required == true, ListingFilter(WbsFilter.REQUIRED).matches(item))
            assertEquals(required == false, ListingFilter(WbsFilter.NOT_REQUIRED).matches(item))
        }
    }

    @Test
    fun `area and warm bounds are inclusive combined and never substitute cold rent`() {
        val item = event()
        val filter = ListingFilter(WbsFilter.NOT_REQUIRED, "64.5".toBigDecimal(), "890".toBigDecimal())
        assertTrue(filter.matches(item))
        assertFalse(filter.matches(item.copy(areaM2 = "64.49".toBigDecimal())))
        assertFalse(filter.matches(item.copy(rent = item.rent.copy(warm = "890.01".toBigDecimal()))))
        assertFalse(filter.matches(item.copy(areaM2 = null)))
        assertFalse(filter.matches(item.copy(rent = item.rent.copy(warm = null))))
        assertTrue(ListingFilter().matches(item.copy(areaM2 = null, rent = item.rent.copy(warm = null))))
    }

    @Test
    fun `settings and stopped subscriptions survive reopening database without duplicate recipients`() {
        val url = "jdbc:h2:file:${directory.resolve("subscriptions")}"
        val store = subscriptionStore(listOf("123", "456"), url)
        store.save(Subscription("123", filter = ListingFilter(maxWarm = "900".toBigDecimal())))
        store.save(Subscription("456", active = false))
        store.save(Subscription("789", filter = ListingFilter(minArea = "70".toBigDecimal())))
        store.advanceOffset(321)
        val reopened = subscriptionStore(listOf("123", "456"), url)
        assertEquals(listOf("123"), reopened.recipients(event()))
        assertEquals(321L, reopened.nextOffset())
        assertEquals("900.00".toBigDecimal(), reopened.get("123")!!.filter.maxWarm)
    }

    @Test
    fun `commands isolate users validate input and preserve filters when restarting subscription`() {
        val store = subscriptionStore()
        val commands = FilterCommands(store, testMapper)
        fun command(text: String, chat: Long = 123) = commands.handle(update(text, chat))!!.text

        command("/warm 800")
        assertNull(store.get("123"))
        command("/start")
        assertNull(store.get("123"))
        for (chat in listOf(123L, 456L)) {
            command("/setup", chat)
            command("Все", chat)
            command("Без ограничения", chat)
            command("Без ограничения", chat)
            command("Сохранить", chat)
        }
        command("/area 64,5")
        command("/warm 890")
        command("/wbs no")
        assertTrue(command("/filters").contains("64.5 м²"))
        val saved = store.get("123")!!
        for (invalid in listOf("-1", "NaN", "1e3", "1.234", "10000000000", "", "any extra")) {
            assertTrue(command("/warm $invalid").contains("Введите"))
            assertEquals(saved, store.get("123"))
        }
        command("/wbs invalid")
        assertEquals(saved, store.get("123"))
        command("/stop")
        assertEquals(listOf("456"), store.recipients(event()))
        command("/resume")
        assertEquals(saved, store.get("123"))
        command("/area any")
        assertNull(store.get("123")!!.filter.minArea)
        command("/reset")
        assertEquals(ListingFilter(), store.get("123")!!.filter)
        assertEquals(ListingFilter(), store.get("456")!!.filter)
    }

    @Test
    fun `groups channels and mismatched senders cannot change personal filters`() {
        val store = subscriptionStore()
        val commands = FilterCommands(store, testMapper)
        for (type in listOf("group", "supergroup", "channel")) {
            assertNull(commands.handle(update("/start", type = type)))
        }
        assertNull(commands.handle(update("/start", sender = 456)))
        assertNull(store.get("123"))
    }

    @Test
    fun `start displays one search and changes apply only after saving`() {
        val store = subscriptionStore()
        val commands = FilterCommands(store, testMapper)
        fun command(text: String) = commands.handle(update(text))!!
        assertEquals(listOf(listOf(FilterCommands.SETUP)), command("/start").buttons)
        assertTrue(store.recipients(event()).isEmpty())
        command(FilterCommands.SETUP)
        command("Только без WBS")
        assertTrue(command("oops").text.contains("Введите"))
        command("60")
        command("900")
        assertNull(store.get("123"))
        val savedReply = command(FilterCommands.SAVE)
        assertEquals(listOf(listOf(FilterCommands.CONTINUE), listOf(FilterCommands.MODIFY), listOf(FilterCommands.PAUSE)), savedReply.buttons)
        val original = store.get("123")!!
        assertEquals(listOf("123"), store.recipients(event()))
        command(FilterCommands.MODIFY)
        command("Только с WBS")
        command("100")
        command("2000")
        assertEquals(original, store.get("123"))
        command(FilterCommands.CANCEL)
        assertEquals(original, store.get("123"))
        command(FilterCommands.MODIFY)
        repeat(3) { command(FilterCommands.KEEP) }
        command(FilterCommands.SAVE)
        assertEquals(original, store.get("123"))
        command(FilterCommands.PAUSE)
        command("/start")
        assertFalse(store.get("123")!!.active)
        command(FilterCommands.CONTINUE)
        assertEquals(original, store.get("123"))
        assertEquals(listOf("123"), store.recipients(event()))
    }

    @Test
    fun `wizard survives restart and duplicate numeric answer never advances twice`() {
        val url = "jdbc:h2:file:${directory.resolve("draft")}"
        val store = subscriptionStore(url = url)
        val commands = FilterCommands(store, testMapper)
        commands.handle(update(FilterCommands.SETUP))
        commands.handle(update("Все"))
        val areaUpdate = update("50,5")
        val reply = commands.handle(areaUpdate)
        val reopened = subscriptionStore(url = url)
        val restarted = FilterCommands(reopened, testMapper)
        assertEquals(reply, restarted.handle(areaUpdate))
        assertEquals(SearchStep.WARM, reopened.draft("123")!!.step)
        assertNull(reopened.draft("123")!!.filter.maxWarm)
        restarted.handle(update("1000"))
        restarted.handle(update(FilterCommands.SAVE))
        assertEquals("50.50".toBigDecimal(), reopened.get("123")!!.filter.minArea)
        assertEquals("1000.00".toBigDecimal(), reopened.get("123")!!.filter.maxWarm)
    }

    @Test
    fun `another user cannot operate private search buttons`() {
        val store = subscriptionStore()
        val commands = FilterCommands(store, testMapper)
        val callback = testMapper.readTree("""{"update_id": 10, "callback_query": {
            "id": "test", "data": "Setup Search", "from": {"id": 456},
            "message": {"chat": {"id": 123, "type": "private"}}
        }}""")
        assertNull(commands.handle(callback))
        assertNull(store.draft("123"))
    }

    private var updateId = 0L

    private fun update(text: String, chat: Long = 123, type: String = "private", sender: Long = chat) =
        testMapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(mapOf(
            "update_id" to ++updateId,
            "message" to mapOf("text" to text, "chat" to mapOf("id" to chat, "type" to type),
                "from" to mapOf("id" to sender, "is_bot" to false)),
        ))
}
