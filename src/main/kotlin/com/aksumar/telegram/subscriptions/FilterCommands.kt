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
        // Polling may redeliver an update after a failed reply. Never apply an answer twice.
        store.reply(chatId, updateId)?.let { return mapper.readValue(it, CommandReply::class.java) }
        val action = if (callback.isObject) callbackAction(chatId, text) else text
        val reply = if (action == null) {
            currentScreen(chatId).let { it.copy(text = "Эта кнопка устарела. Используйте кнопки ниже.\n\n${it.text}") }
        } else respond(chatId, action)
        return reply.copy(menuId = updateId).also {
            store.saveReply(chatId, updateId, mapper.writeValueAsString(it))
        }
    }

    private fun callbackAction(chatId: String, data: String): String? {
        val parts = data.split(':')
        if (parts.size != 4 || parts[0] != "menu") return null
        val menuId = parts[1].toLongOrNull() ?: return null
        val row = parts[2].toIntOrNull() ?: return null
        val column = parts[3].toIntOrNull() ?: return null
        val reply = store.reply(chatId, menuId)?.let { mapper.readValue(it, CommandReply::class.java) } ?: return null
        if (reply.menuId != menuId) return null
        return reply.buttons.getOrNull(row)?.getOrNull(column)
    }

    private fun currentScreen(chatId: String): CommandReply =
        store.draft(chatId)?.let { prompt(chatId, it) } ?: home(chatId, store.get(chatId))

    private fun respond(chatId: String, text: String): CommandReply {
        val parts = text.split(Regex("\\s+"), limit = 2)
        val command = parts[0].substringBefore('@').lowercase()
        val argument = parts.getOrNull(1)?.lowercase()
        val current = store.get(chatId)
        val draft = store.draft(chatId)
        if (command in listOf("/start", "/filters") || text == HOME) return home(chatId, current)
        if (command == "/cancel" || text == CANCEL) {
            if (draft != null) return applyTransition(chatId,
                SearchSetup.transition(draft, SearchAction.Cancel), draft, current)
            store.clearDraft(chatId)
            return home(chatId, current).let { it.copy(text = "Изменения отменены.\n\n${it.text}") }
        }
        if (text in listOf(SETUP, MODIFY, RESUME_DRAFT) || command in listOf("/setup", "/modify")) {
            val next = draft ?: SearchDraft(if (current == null) SearchStep.WBS else SearchStep.CONFIRM,
                current?.filter ?: ListingFilter())
            store.saveDraft(chatId, next)
            return prompt(chatId, next)
        }
        if (command == "/stop" || command == "/resume" || text == PAUSE || text == CONTINUE) {
            if (current == null) return home(chatId, null)
            val updated = current.copy(active = command == "/resume" || text == CONTINUE)
            store.save(updated)
            return home(chatId, updated)
        }
        if (command == "/help") {
            val reply = draft?.let { prompt(chatId, it) } ?: home(chatId, current)
            val note = if (draft == null) "Команды параметров сразу меняют сохранённый поиск."
                else "Сейчас команды параметров меняют только черновик. Примените его кнопкой «Сохранить»."
            return reply.copy(text = "$HELP\n\n$note\n\n${reply.text}")
        }
        if (command in listOf("/reset", "/wbs", "/area", "/warm")) {
            if (draft == null && current == null) return home(chatId, null)
            val filter = draft?.filter ?: current!!.filter
            val updated = try {
                when (command) {
                    "/reset" -> ListingFilter()
                    "/wbs" -> filter.copy(wbs = wbs(argument))
                    "/area" -> filter.copy(minArea = number(argument, "м²"))
                    else -> filter.copy(maxWarm = number(argument, "€"))
                }
            } catch (_: IllegalArgumentException) {
                return currentScreen(chatId).let { it.copy(text = "${inputHint(command)}\n\n${it.text}") }
            }
            if (draft != null) {
                val next = draft.copy(filter = updated)
                store.saveDraft(chatId, next)
                return prompt(chatId, next).let { it.copy(text = "Черновик обновлён. Изменения ещё не сохранены.\n\n${it.text}") }
            }
            val saved = current!!.copy(filter = updated)
            store.save(saved)
            return home(chatId, saved).let { it.copy(text = "Параметры сохранены.\n\n${it.text}") }
        }
        if (command.startsWith('/') && command != "/save") {
            return currentScreen(chatId).let { it.copy(text = "Неизвестная команда. Справка: /help.\n\n${it.text}") }
        }
        if (draft != null) return advance(chatId, text, draft, current)
        return home(chatId, current)
    }

    private fun advance(chatId: String, text: String, draft: SearchDraft, current: Subscription?): CommandReply {
        val action = try {
            when {
                text == BACK -> SearchAction.Back
                text == SAVE || text == "/save" -> SearchAction.Save
                draft.step == SearchStep.CONFIRM -> when (text) {
                    EDIT_WBS -> SearchAction.Edit(SearchStep.WBS)
                    EDIT_AREA -> SearchAction.Edit(SearchStep.AREA)
                    EDIT_WARM -> SearchAction.Edit(SearchStep.WARM)
                    else -> return prompt(chatId, draft)
                }
                text == KEEP -> SearchAction.Keep
                else -> when (draft.step) {
                    SearchStep.WBS -> SearchAction.Wbs(wbs(text.lowercase()))
                    SearchStep.AREA -> SearchAction.Area(number(text.lowercase(), "м²"))
                    SearchStep.WARM -> SearchAction.Warm(number(text.lowercase(), "€"))
                    SearchStep.CONFIRM -> error("Confirmation already handled")
                }
            }
        } catch (_: IllegalArgumentException) {
            val hint = when (draft.step) {
                SearchStep.WBS -> "Выберите вариант WBS кнопкой."
                SearchStep.AREA -> inputHint("/area")
                else -> inputHint("/warm")
            }
            return prompt(chatId, draft).let { it.copy(text = "$hint\n\n${it.text}") }
        }
        return applyTransition(chatId, SearchSetup.transition(draft, action), draft, current)
    }

    private fun applyTransition(
        chatId: String, transition: SearchTransition, draft: SearchDraft, current: Subscription?,
    ): CommandReply = when (transition) {
        is SearchTransition.Draft -> saveDraft(chatId, transition.value)
        is SearchTransition.Saved -> {
            val saved = Subscription(chatId, current?.active ?: true, transition.filter)
            store.save(saved)
            store.clearDraft(chatId)
            home(chatId, saved).let { it.copy(text = "Поиск сохранён.\n\n${it.text}") }
        }
        SearchTransition.Cancelled -> {
            store.clearDraft(chatId)
            home(chatId, current).let { it.copy(text = "Изменения отменены.\n\n${it.text}") }
        }
        SearchTransition.Home -> home(chatId, current)
        SearchTransition.Incomplete -> prompt(chatId, draft).let {
            it.copy(text = "Сначала завершите настройку и проверьте параметры.\n\n${it.text}")
        }
    }

    private fun saveDraft(chatId: String, draft: SearchDraft): CommandReply {
        store.saveDraft(chatId, draft)
        return prompt(chatId, draft)
    }

    private fun home(chatId: String, subscription: Subscription?): CommandReply {
        val hasDraft = store.draft(chatId) != null
        val text = if (subscription == null) "Настройте поиск квартиры: WBS, площадь и бюджет.\nУведомления начнут приходить после сохранения."
            else "Ваш текущий поиск\n\n${summary(subscription.filter)}\n\n$UNKNOWN_VALUES\n\n" +
                if (subscription.active) "Уведомления включены." else "Уведомления приостановлены."
        val buttons = if (hasDraft) listOf(listOf(RESUME_DRAFT), listOf(CANCEL))
            else listOf(listOf(if (subscription == null) SETUP else MODIFY))
        return CommandReply(chatId, text + if (hasDraft) "\n\nЕсть несохранённые изменения. Можно продолжить настройку." else "",
            buttons + if (subscription == null) emptyList() else listOf(listOf(if (subscription.active) PAUSE else CONTINUE)))
    }

    private fun prompt(chatId: String, draft: SearchDraft): CommandReply {
        val navigation = listOf(listOf(BACK, HOME), listOf(CANCEL))
        val prefix = if (draft.singleField) "" else when (draft.step) {
            SearchStep.WBS -> "1/3. "
            SearchStep.AREA -> "2/3. "
            SearchStep.WARM -> "3/3. "
            SearchStep.CONFIRM -> ""
        }
        return when (draft.step) {
            SearchStep.WBS -> CommandReply(chatId, "${prefix}Какие квартиры показывать по WBS?\n" +
                "WBS — документ, подтверждающий право на социальное жильё.\n" +
                "Объявления с неизвестным статусом WBS проходят любой вариант.\nСейчас: ${draft.filter.wbs.label}",
                listOf(listOf("Все"), listOf("Только с WBS"), listOf("Только без WBS"), listOf(KEEP)) + navigation)
            SearchStep.AREA -> CommandReply(chatId, "${prefix}Минимальная площадь в м²?\nВведите, например: 50 или 50,5 м².\n" +
                "Объявления без указанной площади тоже будут приходить.\nСейчас: ${amount(draft.filter.minArea, "м²")}",
                listOf(listOf(NO_LIMIT, KEEP)) + navigation)
            SearchStep.WARM -> CommandReply(chatId, "${prefix}Максимальная Warmmiete в € за месяц?\nВведите, например: 1 000 €.\n" +
                "Warmmiete — аренда с коммунальными платежами по объявлению.\n" +
                "Объявления без Warmmiete тоже будут приходить.\nСейчас: ${amount(draft.filter.maxWarm, "€ / месяц")}",
                listOf(listOf(NO_LIMIT, KEEP)) + navigation)
            SearchStep.CONFIRM -> CommandReply(chatId, "Проверьте параметры поиска\n\n${summary(draft.filter)}\n\n" +
                "$UNKNOWN_VALUES\nВсе условия действуют одновременно, границы включены.\n\n" +
                "Изменения применятся после сохранения." +
                if (store.get(chatId)?.active == false) " Уведомления останутся на паузе." else "",
                listOf(listOf(SAVE), listOf(EDIT_WBS), listOf(EDIT_AREA), listOf(EDIT_WARM), listOf(HOME, CANCEL)))
        }
    }

    private fun wbs(value: String?): WbsFilter = when (value) {
        "any", "все" -> WbsFilter.ANY
        "yes", "только с wbs" -> WbsFilter.REQUIRED
        "no", "только без wbs" -> WbsFilter.NOT_REQUIRED
        else -> throw IllegalArgumentException("Invalid WBS choice")
    }

    private fun number(value: String?, unit: String): BigDecimal? {
        if (value == "any" || value == NO_LIMIT.lowercase()) return null
        require(value != null)
        val normalized = value.removeSuffix(unit).trim().replace('\u00a0', ' ').replace('\u202f', ' ')
        require(Regex("(?:[0-9]{1,10}|[0-9]{1,3}(?: [0-9]{3}){1,3})([.,][0-9]{1,2})?").matches(normalized))
        val digits = normalized.replace(" ", "").replace(',', '.')
        require(digits.substringBefore('.').length <= 10)
        return digits.toBigDecimal()
    }

    private fun inputHint(command: String) = when (command) {
        "/wbs" -> "Используйте /wbs any, /wbs yes или /wbs no."
        "/area" -> "Введите площадь не меньше нуля, например 50,5 м² (до 10 цифр и 2 знаков после запятой). Без ограничения: /area any."
        else -> "Введите бюджет не меньше нуля, например 1 000 € (до 10 цифр и 2 знаков после запятой). Без ограничения: /warm any."
    }

    private fun amount(value: BigDecimal?, unit: String) = value?.stripTrailingZeros()?.toPlainString()?.replace('.', ',')?.let { "$it $unit" } ?: "без ограничения"

    private fun summary(filter: ListingFilter): String =
        "📋 WBS: ${filter.wbs.label}\n📏 Площадь: ${filter.minArea?.let { "от " } ?: ""}${amount(filter.minArea, "м²")}\n" +
            "💰 Warmmiete: ${filter.maxWarm?.let { "до " } ?: ""}${amount(filter.maxWarm, "€ / месяц")}"

    companion object {
        const val SETUP = "Настроить поиск"
        const val MODIFY = "⚙️ Изменить фильтры"
        const val CONTINUE = "▶️ Возобновить уведомления"
        const val PAUSE = "⏸ Приостановить уведомления"
        const val RESUME_DRAFT = "Продолжить настройку"
        const val HOME = "Мой поиск"
        const val BACK = "Назад"
        const val EDIT_WBS = "Изменить WBS"
        const val EDIT_AREA = "Изменить площадь"
        const val EDIT_WARM = "Изменить бюджет"
        const val KEEP = "Оставить как есть"
        const val CANCEL = "Отмена"
        const val SAVE = "Сохранить"
        const val NO_LIMIT = "Без ограничения"
        const val UNKNOWN_VALUES = "Объявления без данных о WBS, площади или Warmmiete проходят соответствующий фильтр. Цена в другой валюте не проверяется."
        val HELP = """
            /start — открыть свой поиск, сохранив черновик
            /setup или /modify — настроить параметры или продолжить черновик
            /cancel — отменить несохранённые изменения
            /filters — показать сохранённый поиск
            /stop — приостановить рассылку
            /resume — возобновить рассылку

            /wbs any|yes|no — WBS; /area 50 — площадь; /warm 1000 — бюджет.
            /area any и /warm any — снять ограничение; /reset — сбросить параметры.
        """.trimIndent()
    }
}
