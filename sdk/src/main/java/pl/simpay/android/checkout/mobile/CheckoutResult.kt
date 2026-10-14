package pl.simpay.android.checkout.mobile

/**
 * Outcome reported to the integrating application.
 *
 * Deliberately never collapses to a single "returned" value: returning from a browser
 * is not a payment, and a pending payment is not a failure. No value here is proof of
 * payment; the merchant backend decides based on the SimPay webhook.
 */
sealed interface CheckoutResult {

    data class Succeeded(
        val transactionId: TransactionId,
        val rawStatus: String
    ) : CheckoutResult

    /** The payment may still complete. */
    data class Pending(
        val transactionId: TransactionId?,
        val rawStatus: String?
    ) : CheckoutResult

    data class Failed(
        val error: MobileCheckoutError,
        val transactionId: TransactionId? = null,
        val rawStatus: String? = null,
        val message: String? = null
    ) : CheckoutResult

    data object Cancelled : CheckoutResult
}
