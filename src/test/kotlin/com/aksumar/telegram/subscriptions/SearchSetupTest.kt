package com.aksumar.telegram.subscriptions

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SearchSetupTest {
    private fun advance(draft: SearchDraft, action: SearchAction) =
        (SearchSetup.transition(draft, action) as SearchTransition.Draft).value

    @Test
    fun `new search is saved only after all steps`() {
        val start = SearchDraft(SearchStep.WBS, ListingFilter())
        assertEquals(SearchTransition.Incomplete, SearchSetup.transition(start, SearchAction.Save))
        val area = advance(start, SearchAction.Wbs(WbsFilter.NOT_REQUIRED))
        assertEquals(SearchStep.AREA, area.step)
        val warm = advance(area, SearchAction.Area("50.5".toBigDecimal()))
        assertEquals(SearchStep.WARM, warm.step)
        val confirm = advance(warm, SearchAction.Warm("900".toBigDecimal()))
        assertEquals(SearchStep.CONFIRM, confirm.step)
        assertEquals(SearchTransition.Saved(ListingFilter(WbsFilter.NOT_REQUIRED,
            "50.5".toBigDecimal(), "900".toBigDecimal())), SearchSetup.transition(confirm, SearchAction.Save))
    }

    @Test
    fun `editing one field preserves the rest and returns to confirmation`() {
        val filter = ListingFilter(WbsFilter.REQUIRED, "50".toBigDecimal(), "1000".toBigDecimal())
        val confirm = SearchDraft(SearchStep.CONFIRM, filter)
        val edit = advance(confirm, SearchAction.Edit(SearchStep.AREA))
        assertTrue(edit.singleField)
        val result = advance(edit, SearchAction.Area(null))
        assertEquals(SearchDraft(SearchStep.CONFIRM, filter.copy(minArea = null)), result)
        assertEquals(confirm, advance(edit, SearchAction.Back))
    }

    @Test
    fun `back keep and cancel preserve the entered values`() {
        val filter = ListingFilter(maxWarm = "900".toBigDecimal())
        for ((step, previous) in listOf(SearchStep.AREA to SearchStep.WBS,
            SearchStep.WARM to SearchStep.AREA, SearchStep.CONFIRM to SearchStep.WARM)) {
            val draft = SearchDraft(step, filter)
            assertEquals(SearchDraft(previous, filter), advance(draft, SearchAction.Back))
            assertEquals(SearchTransition.Cancelled, SearchSetup.transition(draft, SearchAction.Cancel))
        }
        val start = SearchDraft(SearchStep.WBS, filter)
        assertEquals(SearchTransition.Home, SearchSetup.transition(start, SearchAction.Back))
        assertEquals(filter, advance(start, SearchAction.Keep).filter)
        assertEquals(SearchTransition.Cancelled, SearchSetup.transition(start, SearchAction.Cancel))
    }

    @Test
    fun `answer cannot change a different field or introduce a negative limit`() {
        val draft = SearchDraft(SearchStep.AREA, ListingFilter())
        assertThrows(IllegalArgumentException::class.java) {
            SearchSetup.transition(draft, SearchAction.Warm("900".toBigDecimal()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SearchSetup.transition(draft, SearchAction.Area("-1".toBigDecimal()))
        }
    }
}
