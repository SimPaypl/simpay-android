package pl.simpay.android.checkout.mobile

/** Amount limits of a payment channel, kept as raw text to avoid Float/Double. */
data class ChannelAmountRange(
    val min: String?,
    val max: String?
)

/** Payment channel returned by GET /payment/{serviceId}/mobile-sdk/channels. */
data class PaymentChannel(
    val id: String,
    val name: String,
    val type: String?,
    val img: String?,
    val currencies: List<String>,
    val amount: ChannelAmountRange
)
