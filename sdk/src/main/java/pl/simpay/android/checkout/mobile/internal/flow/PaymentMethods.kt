package pl.simpay.android.checkout.mobile.internal.flow

import pl.simpay.android.checkout.mobile.PaymentChannel

/**
 * How a channel is completed.
 *
 * BLIK Level 0 is the only method finished inside the sheet: the six digit code is sent
 * to POST /payment/{serviceId}/blik/level0/{transactionId}. Every other channel is
 * completed on the SimPay gateway opened in a Custom Tab.
 */
internal object BlikLevel0 {

    /**
     * Value sent as `directChannel` when creating the transaction.
     *
     * Deliberately separate from [BLIK_IDENTIFIERS]: the channels endpoint lists the
     * channel under its own id, which is not necessarily the identifier the transaction
     * endpoint expects.
     */
    const val DIRECT_CHANNEL = "blik-level0"

    /** Ids and types under which the channels endpoint reports BLIK. */
    private val BLIK_IDENTIFIERS = setOf("blik", "blik-level0", "blik_level_0")

    /** Ids of the in-app variant, preferred when the API lists BLIK more than once. */
    private val LEVEL0_IDENTIFIERS = setOf("blik-level0", "blik_level_0", "bliklevel0")

    /**
     * Recognises a BLIK channel. This only classifies a single channel; it cannot
     * remove duplicates from a list — see [collapseDuplicates] for that.
     */
    fun supports(channel: PaymentChannel): Boolean =
        channel.id.trim().lowercase() in BLIK_IDENTIFIERS ||
            channel.type?.trim()?.lowercase() in BLIK_IDENTIFIERS

    private fun isLevel0(channel: PaymentChannel): Boolean =
        channel.id.trim().lowercase() in LEVEL0_IDENTIFIERS

    /**
     * Reduces the BLIK entries to a single one.
     *
     * The channels endpoint reports BLIK twice: as a redirect channel and as Level 0.
     * Both match [supports], so without this step the sheet renders two identical
     * looking rows that both lead to the in-app code entry — one of them lying about
     * what it does. Level 0 wins because that is the flow the sheet implements; when a
     * service exposes no Level 0 variant, the remaining entry is kept so BLIK does not
     * disappear.
     *
     * Other channels keep their order, and BLIK stays at the index of its first
     * occurrence.
     */
    fun collapseDuplicates(channels: List<PaymentChannel>): List<PaymentChannel> {
        val blikEntries = channels.filter(::supports)
        if (blikEntries.size <= 1) {
            return channels
        }

        val preferred = blikEntries.firstOrNull(::isLevel0) ?: blikEntries.first()
        val firstBlikIndex = channels.indexOfFirst(::supports)

        return channels
            .filterIndexed { index, channel -> !supports(channel) || index == firstBlikIndex }
            .map { channel -> if (supports(channel)) preferred else channel }
    }
}

