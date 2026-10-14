package pl.simpay.android.checkout.mobile

import java.math.BigDecimal

/**
 * Order data passed to the checkout.
 *
 * SECURITY: these values are only what the application claims. The verified API does
 * not bind amount, currency or order id to the session token, so a modified app can
 * create a transaction for any amount. The merchant backend must therefore verify the
 * webhook (status, amount, currency, control, service id) against its own order record
 * before releasing goods. Never treat these values, or any SDK result, as proof of
 * payment.
 */
data class TransactionOrder(
    val amount: BigDecimal,
    val currency: String = "PLN",
    /** Shown on the SimPay gateway. Max 64 characters. */
    val description: String? = null,
    /** Merchant side reference, typically the order id. Max 255 characters. */
    val control: String? = null,
    /** Required by the API for direct channels. */
    val customerEmail: String,
    /**
     * Payer country as ISO 3166-1 alpha-2, required by the API for direct channels and
     * expected to match [currency]. When null the SDK derives it from the currency, and
     * fails before creating a transaction if the currency maps to no single country.
     */
    val customerCountryCode: String? = null,
    /** Return URLs used by the gateway. */
    val returns: TransactionReturns? = null,
    /**
     * Payer IP fallback, used only when the session provider does not report one.
     * Must be a public address observed by the merchant backend, never a device
     * interface address.
     */
    val payerIpOverride: String? = null
) {
    init {
        require(amount >= BigDecimal.ONE) { "amount must be at least 1" }
        require(customerEmail.isNotBlank()) { "customerEmail must not be blank" }
        require(description == null || description.length <= 64) { "description max length is 64" }
        require(control == null || control.length <= 255) { "control max length is 255" }
        require(
            customerCountryCode == null || COUNTRY_CODE_PATTERN.matches(customerCountryCode)
        ) {
            "customerCountryCode must be two uppercase letters (ISO 3166-1 alpha-2)"
        }
    }


    private companion object {
        val COUNTRY_CODE_PATTERN = Regex("^[A-Z]{2}$")
    }
}
