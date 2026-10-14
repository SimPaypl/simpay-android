package pl.simpay.android.checkout.mobile.internal.flow

/**
 * Derives `customer.countryCode` from the transaction currency.
 *
 * Direct channels require a country code that matches the currency. Only currencies
 * with exactly one issuing country are mapped; shared currencies such as EUR resolve to
 * null, so the integrator must state the country explicitly instead of the SDK guessing
 * one and risking a rejection by the provider.
 */
internal object PayerCountry {

    private val BY_CURRENCY = mapOf(
        "PLN" to "PL",
        "CZK" to "CZ",
        "HUF" to "HU",
        "RON" to "RO",
        "BGN" to "BG",
        "GBP" to "GB",
        "USD" to "US",
        "SEK" to "SE",
        "NOK" to "NO",
        "DKK" to "DK",
        "CHF" to "CH",
        "UAH" to "UA"
    )

    fun forCurrency(currency: String): String? = BY_CURRENCY[currency.trim().uppercase()]
}

