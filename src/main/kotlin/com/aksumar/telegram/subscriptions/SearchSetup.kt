package com.aksumar.telegram.subscriptions

import java.math.BigDecimal

sealed interface SearchAction {
    data object Back : SearchAction
    data object Save : SearchAction
    data object Cancel : SearchAction
    data object Keep : SearchAction
    data class Edit(val step: SearchStep) : SearchAction
    data class Wbs(val value: WbsFilter) : SearchAction
    data class Area(val value: BigDecimal?) : SearchAction
    data class Warm(val value: BigDecimal?) : SearchAction
}

sealed interface SearchTransition {
    data class Draft(val value: SearchDraft) : SearchTransition
    data class Saved(val filter: ListingFilter) : SearchTransition
    data object Cancelled : SearchTransition
    data object Home : SearchTransition
    data object Incomplete : SearchTransition
}

/** Search transitions are independent of Telegram, persistence, and screen text. */
object SearchSetup {
    fun transition(draft: SearchDraft, action: SearchAction): SearchTransition {
        if (action == SearchAction.Cancel) return SearchTransition.Cancelled
        if (action == SearchAction.Save) {
            return if (draft.step == SearchStep.CONFIRM) SearchTransition.Saved(draft.filter)
            else SearchTransition.Incomplete
        }
        if (action == SearchAction.Back) {
            val step = if (draft.singleField) SearchStep.CONFIRM else when (draft.step) {
                SearchStep.WBS -> return SearchTransition.Home
                SearchStep.AREA -> SearchStep.WBS
                SearchStep.WARM -> SearchStep.AREA
                SearchStep.CONFIRM -> SearchStep.WARM
            }
            return SearchTransition.Draft(draft.copy(step = step, singleField = false))
        }
        if (action is SearchAction.Edit) {
            require(action.step != SearchStep.CONFIRM)
            return SearchTransition.Draft(if (draft.step == SearchStep.CONFIRM)
                draft.copy(step = action.step, singleField = true) else draft)
        }
        if (draft.step == SearchStep.CONFIRM) return SearchTransition.Draft(draft)
        val filter = when (action) {
            SearchAction.Keep -> draft.filter
            is SearchAction.Wbs -> {
                require(draft.step == SearchStep.WBS)
                draft.filter.copy(wbs = action.value)
            }
            is SearchAction.Area -> {
                require(draft.step == SearchStep.AREA)
                draft.filter.copy(minArea = action.value)
            }
            is SearchAction.Warm -> {
                require(draft.step == SearchStep.WARM)
                draft.filter.copy(maxWarm = action.value)
            }
            else -> error("Action already handled")
        }
        val step = if (draft.singleField) SearchStep.CONFIRM else when (draft.step) {
            SearchStep.WBS -> SearchStep.AREA
            SearchStep.AREA -> SearchStep.WARM
            SearchStep.WARM -> SearchStep.CONFIRM
            SearchStep.CONFIRM -> error("Confirmation already handled")
        }
        return SearchTransition.Draft(SearchDraft(step, filter))
    }
}
