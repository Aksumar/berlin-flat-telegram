package com.aksumar.telegram.subscriptions

import com.aksumar.telegram.model.Listing
import java.math.BigDecimal

enum class WbsFilter(val label: String) {
    ANY("все"), REQUIRED("только с WBS"), NOT_REQUIRED("только без WBS")
}

data class ListingFilter(
    val wbs: WbsFilter = WbsFilter.ANY,
    val minArea: BigDecimal? = null,
    val maxWarm: BigDecimal? = null,
) {
    init {
        require(minArea == null || minArea.signum() >= 0)
        require(maxWarm == null || maxWarm.signum() >= 0)
    }

    fun matches(item: Listing): Boolean =
        (wbs == WbsFilter.ANY || item.wbs.required == (wbs == WbsFilter.REQUIRED)) &&
            (minArea == null || item.areaM2?.let { it >= minArea } == true) &&
            (maxWarm == null || (item.rent.currency == "EUR" && item.rent.warm?.let { it <= maxWarm } == true))
}

data class Subscription(
    val chatId: String,
    val active: Boolean = true,
    val filter: ListingFilter = ListingFilter(),
)

enum class SearchStep { WBS, AREA, WARM, CONFIRM }

data class SearchDraft(val step: SearchStep, val filter: ListingFilter)

data class CommandReply(val chat: String, val text: String, val buttons: List<List<String>>)
