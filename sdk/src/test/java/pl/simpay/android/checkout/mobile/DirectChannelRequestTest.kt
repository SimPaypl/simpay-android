package pl.simpay.android.checkout.mobile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.simpay.android.checkout.mobile.internal.flow.PayerCountry
import pl.simpay.android.checkout.mobile.internal.network.TransactionJsonFactory
import java.math.BigDecimal

/**
 * Covers the payload required by direct channels.
 *
 * A `directChannel` transaction skips the SimPay picker, so the provider's antifraud
 * systems rely entirely on the payer data sent with the request. Missing or malformed
 * values must fail locally, never reach the API as a half-built transaction.
 */
class DirectChannelRequestTest {

    private val customer = TransactionCustomer(
        email = "jan.kowalski@example.com",
        ip = "203.0.113.10",
        countryCode = "PL"
    )

    private val antifraud = TransactionAntifraud(
        userAgent = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile"
    )

    private fun request(
        customer: TransactionCustomer? = this.customer,
        antifraud: TransactionAntifraud? = this.antifraud,
        directChannel: String? = "blik",
        currency: String = "PLN"
    ) = CreateTransactionRequest(
        amount = BigDecimal("100.00"),
        currency = currency,
        control = "Order 12345",
        directChannel = directChannel,
        customer = customer,
        antifraud = antifraud
    )

    @Test
    fun `direct channel body carries every field required by the api`() {
        val body = JSONObject(TransactionJsonFactory.createTransactionBody(request()))

        assertEquals("PLN", body.getString("currency"))
        assertEquals("blik", body.getString("directChannel"))
        assertEquals("Order 12345", body.getString("control"))

        val payer = body.getJSONObject("customer")
        assertEquals("jan.kowalski@example.com", payer.getString("email"))
        assertEquals("203.0.113.10", payer.getString("ip"))
        assertEquals("PL", payer.getString("countryCode"))

        // Lowercase key, exactly as the API documents it.
        assertEquals(
            antifraud.userAgent,
            body.getJSONObject("antifraud").getString("useragent")
        )
    }

    @Test
    fun `amount stays an exact json number`() {
        val body = JSONObject(TransactionJsonFactory.createTransactionBody(request()))

        assertEquals(0, BigDecimal("100.00").compareTo(BigDecimal(body.get("amount").toString())))
    }

    @Test
    fun `a direct channel without customer data is rejected locally`() {
        val failure = runCatching { request(customer = null) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `a direct channel without a country code is rejected locally`() {
        val failure = runCatching {
            request(customer = customer.copy(countryCode = null))
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `a direct channel without a user agent is rejected locally`() {
        val failure = runCatching { request(antifraud = null) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `a blank user agent is rejected locally`() {
        val failure = runCatching { TransactionAntifraud(userAgent = " ") }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `a malformed country code is rejected locally`() {
        listOf("pl", "POL", "P", "").forEach { value ->
            val failure = runCatching { customer.copy(countryCode = value) }.exceptionOrNull()
            assertTrue("countryCode '$value' should be rejected", failure is IllegalArgumentException)
        }
    }

    @Test
    fun `a payer ip is required for direct channels`() {
        val failure = runCatching { customer.copy(ip = "") }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `country is derived only for currencies bound to a single country`() {
        assertEquals("PL", PayerCountry.forCurrency("PLN"))
        assertEquals("CZ", PayerCountry.forCurrency("czk"))
        assertEquals("GB", PayerCountry.forCurrency(" GBP "))
        // Shared or unknown currencies must be stated explicitly by the integrator.
        assertEquals(null, PayerCountry.forCurrency("EUR"))
        assertEquals(null, PayerCountry.forCurrency("XYZ"))
    }

    @Test
    fun `a transaction order validates the country code format`() {
        val failure = runCatching {
            TransactionOrder(
                amount = BigDecimal("1.00"),
                customerEmail = "payer@example.com",
                customerCountryCode = "pl"
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }
}

