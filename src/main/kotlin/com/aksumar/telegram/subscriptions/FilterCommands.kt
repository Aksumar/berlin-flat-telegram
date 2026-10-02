package com.aksumar.telegram.subscriptions

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

@Component
class FilterCommands(private val store: SubscriptionStore, private val mapper: ObjectMapper) {
    @Transactional
    fun handle(update: JsonNode): CommandReply? {
        val callback = update.path("callback_query")
        val message = if (callback.isObject) callback.path("message") else update.path("message")
        val from = if (callback.isObject) callback.path("from") else message.path("from")
        val chat = message.path("chat")
        if (chat.path("type").asText() != "private" || !chat.path("id").isIntegralNumber ||
            from.path("id") != chat.path("id") || from.path("is_bot").asBoolean()
        ) return null
        val text = (if (callback.isObject) callback.path("data") else message.path("text"))
            .takeIf { it.isTextual }?.asText()?.trim() ?: return null
        require(update.path("update_id").isIntegralNumber)
        val chatId = chat.path("id").asText()
        val updateId = update.path("update_id").asLong()
        // Polling may redeliver an update after a failed reply. Never apply a wizard answer twice.
        store.reply(chatId, updateId)?.let { return mapper.readValue(it, CommandReply::class.java) }
        return respond(chatId, text).also { store.saveReply(chatId, updateId, mapper.writeValueAsString(it)) }
    }

    private fun respond(chatId: String, text: String): CommandReply {
        val parts = text.split(Regex("\\s+"), limit = 2)
        val command = parts[0].substringBefore('@').lowercase()
        val argument = parts.getOrNull(1)?.lowercase()
        val current = store.get(chatId)
        if (command == "/start" || command == "/cancel" || text == CANCEL) {
            store.clearDraft(chatId)
            return home(chatId, current)
        }
        if (text in listOf(SETUP, MODIFY) || command in listOf("/setup", "/modify")) {
            val draft = SearchDraft(SearchStep.WBS, current?.filter ?: ListingFilter())
            store.saveDraft(chatId, draft)
            return prompt(chatId, draft)
        }
        if (command == "/stop" || command == "/resume" || text == PAUSE || text == CONTINUE) {
            store.clearDraft(chatId)
            if (current == null) return home(chatId, null)
            val updated = current.copy(active = command == "/resume" || text == CONTINUE)
            store.save(updated)
            return home(chatId, updated)
        }
        val draft = store.draft(chatId)
        if (command == "/help") {
            val reply = draft?.let { prompt(chatId, it) } ?: home(chatId, current)
            return reply.copy(text = "$HELP\n\n${reply.text}")
        }
        if (draft != null) return advance(chatId, text, draft, current)
        if (current == null || command == "/filters") return home(chatId, current)
        val updated = try {
            when (command) {
                "/reset" -> current.copy(filter = ListingFilter())
                "/wbs" -> current.copy(filter = current.filter.copy(wbs = wbs(argument)))
                "/area" -> current.copy(filter = current.filter.copy(minArea = number(argument)))
                "/warm" -> current.copy(filter = current.filter.copy(maxWarm = number(argument)))
                else -> return home(chatId, current)
            }
        } catch (_: IllegalArgumentException) {
            return home(chatId, current).copy(text = if (command == "/wbs")
                "Используйте /wbs any, /wbs yes или /wbs no."
            else "Введите неотрицательное число, например $command 50,5. Для снятия ограничения: $command any.")
        }
        store.save(updated)
        return home(chatId, updated)
    }

    private fun advance(chatId: String, text: String, draft: SearchDraft, current: Subscription?): CommandReply {
        if (draft.step == SearchStep.CONFIRM) {
            if (text != SAVE && text != "/save") return prompt(chatId, draft)
            val saved = Subscription(chatId, current?.active ?: true, draft.filter)
            store.save(saved)
            store.clearDraft(chatId)
            return home(chatId, saved).let { it.copy(text = "Поиск сохранён.\n\n${it.text}") }
        }
        val next = try {
            when (draft.step) {
                SearchStep.WBS -> SearchDraft(SearchStep.AREA, draft.filter.copy(
                    wbs = if (text == KEEP) draft.filter.wbs else wbs(text.lowercase())))
                SearchStep.AREA -> SearchDraft(SearchStep.WARM, draft.filter.copy(
                    minArea = if (text == KEEP) draft.filter.minArea else number(text.lowercase())))
                SearchStep.WARM -> SearchDraft(SearchStep.CONFIRM, draft.filter.copy(
                    maxWarm = if (text == KEEP) draft.filter.maxWarm else number(text.lowercase())))
                SearchStep.CONFIRM -> error("Already handled")
            }
        } catch (_: IllegalArgumentException) {
            val message = if (draft.step == SearchStep.WBS) "Выберите вариант WBS кнопкой."
                else "Введите неотрицательное число (до 10 цифр и 2 знаков после запятой), например 50,5."
            return prompt(chatId, draft).let { it.copy(text = "$message\n\n${it.text}") }
        }
        store.saveDraft(chatId, next)
        return prompt(chatId, next)
    }

    private fun home(chatId: String, subscription: Subscription?): CommandReply =
        if (subscription == null) CommandReply(chatId,
            "Настройте свой поиск квартиры. У вас будет один поиск с несколькими параметрами.", listOf(listOf(SETUP)))
        else CommandReply(chatId, "Ваш текущий поиск\n\n${summary(subscription.filter)}\n\n" +
            (if (subscription.active) "Уведомления включены." else "Уведомления приостановлены."),
            listOf(listOf(CONTINUE), listOf(MODIFY), listOf(PAUSE)))

    private fun prompt(chatId: String, draft: SearchDraft): CommandReply = when (draft.step) {
        SearchStep.WBS -> CommandReply(chatId, "1/3. Какие квартиры показывать по WBS?\nСейчас: ${draft.filter.wbs.label}",
            listOf(listOf("Все", "Только с WBS", "Только без WBS"), listOf(KEEP, CANCEL)))
        SearchStep.AREA -> CommandReply(chatId, "2/3. Минимальная площадь в м²? Например: 50 или 50,5.\nСейчас: ${amount(draft.filter.minArea, "м²")}",
            listOf(listOf(NO_LIMIT, KEEP), listOf(CANCEL)))
        SearchStep.WARM -> CommandReply(chatId, "3/3. Максимальная Warmmiete в € за месяц? Например: 1000.\nСейчас: ${amount(draft.filter.maxWarm, "€ / месяц")}",
            listOf(listOf(NO_LIMIT, KEEP), listOf(CANCEL)))
        SearchStep.CONFIRM -> CommandReply(chatId, "Проверьте параметры поиска:\n${summary(draft.filter)}\n\n" +
            "Все условия применяются одновременно, границы включены. Объявления с неизвестным значением заданного параметра не проходят фильтр.",
            listOf(listOf(SAVE, CANCEL)))
    }

    private fun wbs(value: String?): WbsFilter = when (value) {
        "any", "все" -> WbsFilter.ANY
        "yes", "только с wbs" -> WbsFilter.REQUIRED
        "no", "только без wbs" -> WbsFilter.NOT_REQUIRED
        else -> throw IllegalArgumentException("Invalid WBS choice")
    }

    private fun number(value: String?): BigDecimal? {
        if (value == "any" || value == NO_LIMIT.lowercase()) return null
        require(value != null && Regex("[0-9]{1,10}([.,][0-9]{1,2})?").matches(value))
        return value.replace(',', '.').toBigDecimal()
    }

    private fun amount(value: BigDecimal?, unit: String) = value?.stripTrailingZeros()?.toPlainString()?.let { "$it $unit" } ?: "без ограничения"

    private fun summary(filter: ListingFilter): String =
        "📋 WBS\n${filter.wbs.label}\n\n📏 Минимальная площадь\n${amount(filter.minArea, "м²")}\n\n💰 Максимальная Warmmiete\n${amount(filter.maxWarm, "€ / месяц")}"

    companion object {
        const val SETUP = "Setup Search"
        const val MODIFY = "⚙️ Modify Search"
        const val CONTINUE = "▶️ Continue Search"
        const val PAUSE = "⏸ Pause Alerts"
        const val KEEP = "Оставить как есть"
        const val CANCEL = "Отмена"
        const val SAVE = "Сохранить"
        const val NO_LIMIT = "Без ограничения"
        val HELP = """
            /start — открыть свой поиск
            /setup или /modify — настроить параметры пошагово
            /cancel — отменить изменения
            /filters — показать сохранённый поиск
            /stop — приостановить рассылку
            /resume — возобновить рассылку

            Параметры также можно изменить командами /wbs any|yes|no, /area 50, /warm 1000.
            /area any и /warm any — снять ограничение; /reset — сбросить параметры поиска.
        """.trimIndent()
    }
}
