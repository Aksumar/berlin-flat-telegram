package com.aksumar.telegram.subscriptions

import com.aksumar.telegram.support.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SubscriptionsTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `unknown WBS passes every filter while known values respect all three modes`() {
        for (required in listOf(true, false, null)) {
            val item = event().copy(wbs = event().wbs.copy(required = required))
            assertTrue(ListingFilter().matches(item))
            assertEquals(required != false, ListingFilter(WbsFilter.REQUIRED).matches(item))
            assertEquals(required != true, ListingFilter(WbsFilter.NOT_REQUIRED).matches(item))
        }
    }

    @Test
    fun `area and warm bounds are inclusive combined and never substitute cold rent`() {
        val item = event()
        val filter = ListingFilter(WbsFilter.NOT_REQUIRED, "64.5".toBigDecimal(), "890".toBigDecimal())
        assertTrue(filter.matches(item))
        assertFalse(filter.matches(item.copy(areaM2 = "64.49".toBigDecimal())))
        assertFalse(filter.matches(item.copy(rent = item.rent.copy(warm = "890.01".toBigDecimal()))))
        assertTrue(filter.matches(item.copy(areaM2 = null)))
        assertTrue(filter.matches(item.copy(rent = item.rent.copy(warm = null))))
        assertTrue(filter.matches(item.copy(areaM2 = null, rent = item.rent.copy(warm = null),
            wbs = item.wbs.copy(required = null))))
        assertTrue(filter.matches(item.copy(rent = item.rent.copy(currency = "USD", warm = "999999".toBigDecimal()))))
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
        assertTrue(command("/filters").contains("64,5 м²"))
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
        assertEquals(listOf(listOf(FilterCommands.MODIFY), listOf(FilterCommands.PAUSE)), savedReply.buttons)
        val original = store.get("123")!!
        assertEquals(listOf("123"), store.recipients(event()))
        command(FilterCommands.MODIFY)
        command(FilterCommands.EDIT_WBS)
        command("Только с WBS")
        command(FilterCommands.EDIT_AREA)
        command("100")
        command(FilterCommands.EDIT_WARM)
        command("2000")
        assertEquals(original, store.get("123"))
        command(FilterCommands.CANCEL)
        assertEquals(original, store.get("123"))
        command(FilterCommands.MODIFY)
        command(FilterCommands.EDIT_AREA)
        command(FilterCommands.KEEP)
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

    @Test
    fun `old and double clicked buttons never affect the next step even after restart`() {
        val url = "jdbc:h2:file:${directory.resolve("buttons")}"
        val store = subscriptionStore(url = url)
        var commands = FilterCommands(store, testMapper)
        fun command(text: String) = commands.handle(update(text))!!
        val wbs = command("/setup")
        val keep = callback(wbs, FilterCommands.KEEP)
        val area = commands.handle(keep)!!
        assertEquals(area, commands.handle(keep)) // Transport redelivery repeats the same response.
        assertEquals(SearchStep.AREA, store.draft("123")!!.step)
        val stale = commands.handle(callback(wbs, FilterCommands.KEEP))!! // A second actual click is a new update.
        assertTrue(stale.text.contains("устарела"))
        assertEquals(SearchStep.AREA, store.draft("123")!!.step)
        command("50")
        val before = store.draft("123")
        commands = FilterCommands(subscriptionStore(url = url), testMapper)
        assertTrue(commands.handle(callback(area, FilterCommands.NO_LIMIT))!!.text.contains("устарела"))
        assertEquals(before, store.draft("123"))
        val confirm = command("1 000 €")
        commands.handle(callback(confirm, FilterCommands.SAVE))
        val saved = store.get("123")
        command(FilterCommands.MODIFY)
        command(FilterCommands.EDIT_WARM)
        command("900")
        val newDraft = store.draft("123")
        assertTrue(commands.handle(callback(confirm, FilterCommands.SAVE))!!.text.contains("устарела"))
        assertEquals(newDraft, store.draft("123"))
        assertEquals(saved, store.get("123"))
        val legacy = testMapper.readTree("""{"update_id": ${++updateId}, "callback_query": {
            "data": "Сохранить", "from": {"id": 123},
            "message": {"chat": {"id": 123, "type": "private"}}
        }}""")
        assertTrue(commands.handle(legacy)!!.text.contains("устарела"))
        assertEquals(saved, store.get("123"))
    }

    @Test
    fun `menu pause resume and help keep draft while cancel discards only unsaved changes`() {
        val store = subscriptionStore(listOf("123"))
        val commands = FilterCommands(store, testMapper)
        fun command(text: String) = commands.handle(update(text))!!
        command("/modify")
        command(FilterCommands.EDIT_AREA)
        val before = store.draft("123")
        for (action in listOf("/start", "/filters", "/stop", "/resume", "/help", "/setup", "/modify")) {
            command(action)
            assertEquals(before, store.draft("123"), action)
        }
        val paused = command("/stop")
        assertTrue(paused.buttons.flatten().contains(FilterCommands.CONTINUE))
        assertFalse(paused.buttons.flatten().contains(FilterCommands.PAUSE))
        assertTrue(paused.buttons.flatten().contains(FilterCommands.RESUME_DRAFT))
        command(FilterCommands.RESUME_DRAFT)
        command("60")
        command(FilterCommands.SAVE)
        assertFalse(store.get("123")!!.active)
        assertEquals("60.00".toBigDecimal(), store.get("123")!!.filter.minArea)
        command("/modify")
        command(FilterCommands.EDIT_AREA)
        command("100")
        command(FilterCommands.CANCEL)
        assertNull(store.draft("123"))
        assertEquals("60.00".toBigDecimal(), store.get("123")!!.filter.minArea)
    }

    @Test
    fun `back preserves entered values and individual edit returns straight to confirmation`() {
        val url = "jdbc:h2:file:${directory.resolve("edit")}"
        val store = subscriptionStore(url = url)
        var commands = FilterCommands(store, testMapper)
        fun command(text: String) = commands.handle(update(text))!!
        command("/setup")
        command("Только без WBS")
        command("50 м²")
        command(FilterCommands.BACK)
        assertEquals(SearchStep.AREA, store.draft("123")!!.step)
        assertTrue(command(FilterCommands.BACK).text.contains("только без WBS"))
        command(FilterCommands.KEEP)
        command(FilterCommands.KEEP)
        command("1000")
        command(FilterCommands.EDIT_AREA)
        commands = FilterCommands(subscriptionStore(url = url), testMapper)
        assertTrue(store.draft("123")!!.singleField)
        command(FilterCommands.BACK)
        assertEquals(SearchStep.CONFIRM, store.draft("123")!!.step)
        command(FilterCommands.EDIT_AREA)
        command("60")
        assertEquals(SearchStep.CONFIRM, store.draft("123")!!.step)
        assertNull(store.get("123"))
        command(FilterCommands.SAVE)
        assertEquals(ListingFilter(WbsFilter.NOT_REQUIRED, "60.00".toBigDecimal(), "1000.00".toBigDecimal()),
            store.get("123")!!.filter)
    }

    @Test
    fun `commands during setup edit draft without changing live subscription or losing step`() {
        val store = subscriptionStore(listOf("123"))
        val commands = FilterCommands(store, testMapper)
        fun command(text: String) = commands.handle(update(text))!!
        command("/modify")
        command(FilterCommands.EDIT_AREA)
        command("/warm 1 000 €")
        command("/area 50,5 м²")
        command("/wbs no")
        val before = store.draft("123")!!
        assertEquals(SearchStep.AREA, before.step)
        assertEquals(ListingFilter(WbsFilter.NOT_REQUIRED, "50.50".toBigDecimal(), "1000.00".toBigDecimal()), before.filter)
        assertEquals(ListingFilter(), store.get("123")!!.filter)
        assertTrue(command("/filters").text.contains("несохранённые"))
        assertTrue(command("/help").text.contains("только черновик"))
        assertTrue(command("/unknown").text.contains("Неизвестная команда"))
        assertTrue(command("/save").text.contains("Сначала завершите"))
        assertEquals(before, store.draft("123"))
        command("/reset")
        assertEquals(ListingFilter(), store.draft("123")!!.filter)
        assertEquals(SearchStep.AREA, store.draft("123")!!.step)
    }

    @Test
    fun `numeric inputs accept units and grouped thousands but reject malformed and wrong units`() {
        val store = subscriptionStore(listOf("123"))
        val commands = FilterCommands(store, testMapper)
        fun command(text: String) = commands.handle(update(text))!!
        for (value in listOf("1 000 €", "1\u00a0000 €", "1\u202f000 €", "1000.00", "1000,00€")) {
            command("/warm $value")
            assertEquals("1000.00".toBigDecimal(), store.get("123")!!.filter.maxWarm)
        }
        command("/area 50,5 м²")
        assertEquals("50.50".toBigDecimal(), store.get("123")!!.filter.minArea)
        val saved = store.get("123")
        for (value in listOf("10 00", "1 00 000", "1000 м²", "-1", "1e3", "1,000", "10000000000", "50 € extra")) {
            assertTrue(command("/warm $value").text.contains("Введите бюджет"), value)
            assertEquals(saved, store.get("123"), value)
        }
        assertTrue(command("/area 50 €").text.contains("Введите площадь"))
        assertEquals(saved, store.get("123"))
    }

    @Test
    fun `schema upgrade preserves drafts created by the previous version`() {
        val url = "jdbc:h2:file:${directory.resolve("legacy")}"
        java.sql.DriverManager.getConnection(url, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("""CREATE TABLE search_drafts (
                    chat_id VARCHAR(64) PRIMARY KEY, step VARCHAR(16) NOT NULL, wbs VARCHAR(16) NOT NULL,
                    min_area DECIMAL(12, 2), max_warm DECIMAL(12, 2))""")
                statement.execute("INSERT INTO search_drafts VALUES ('123', 'WARM', 'NOT_REQUIRED', 50.5, NULL)")
            }
        }
        val store = subscriptionStore(url = url)
        assertEquals(SearchDraft(SearchStep.WARM, ListingFilter(WbsFilter.NOT_REQUIRED, "50.50".toBigDecimal())),
            store.draft("123"))
        val commands = FilterCommands(store, testMapper)
        commands.handle(update("1000"))
        commands.handle(update(FilterCommands.SAVE))
        assertEquals("1000.00".toBigDecimal(), store.get("123")!!.filter.maxWarm)
    }

    private fun callback(reply: CommandReply, label: String): com.fasterxml.jackson.databind.JsonNode {
        val row = reply.buttons.indexOfFirst { label in it }
        require(row >= 0) { "Missing button: $label" }
        val column = reply.buttons[row].indexOf(label)
        return testMapper.readTree("""{"update_id": ${++updateId}, "callback_query": {
            "data": "menu:${reply.menuId}:$row:$column", "from": {"id": 123},
            "message": {"chat": {"id": 123, "type": "private"}}
        }}""")
    }

    private var updateId = 0L

    @Test
    fun `help menu command works during setup without losing the current step`() {
        val store = subscriptionStore()
        val commands = FilterCommands(store, testMapper)
        commands.handle(update("/setup"))
        commands.handle(update("Только без WBS"))
        val before = store.draft("123")
        val help = commands.handle(update("/help"))!!
        assertTrue(help.text.contains("/start — открыть свой поиск"))
        assertTrue(help.text.contains("2/3."))
        assertEquals(before, store.draft("123"))
        commands.handle(update("50"))
        assertEquals(SearchStep.WARM, store.draft("123")!!.step)
        assertEquals("50.00".toBigDecimal(), store.draft("123")!!.filter.minArea)
    }

    private fun update(text: String, chat: Long = 123, type: String = "private", sender: Long = chat) =
        testMapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(mapOf(
            "update_id" to ++updateId,
            "message" to mapOf("text" to text, "chat" to mapOf("id" to chat, "type" to type),
                "from" to mapOf("id" to sender, "is_bot" to false)),
        ))
}
