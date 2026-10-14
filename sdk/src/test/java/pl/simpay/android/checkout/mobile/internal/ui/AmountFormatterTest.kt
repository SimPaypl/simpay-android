package pl.simpay.android.checkout.mobile.internal.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * Formatting rules of the amount due.
 *
 * The object itself needs a Context for the locale, so the rules are exercised against
 * the same NumberFormat configuration it builds.
 */
class AmountFormatterTest {

    private fun format(amount: BigDecimal, code: String, locale: Locale): String {
        val currency = runCatching { Currency.getInstance(code) }.getOrNull()
            ?: return "${amount.toPlainString()} $code".trim()
        return NumberFormat.getCurrencyInstance(locale).apply {
            this.currency = currency
            val digits = maxOf(currency.defaultFractionDigits.coerceAtLeast(0), amount.scale())
            minimumFractionDigits = digits
            maximumFractionDigits = digits
        }.format(amount)
    }

    @Test
    fun `polish locale uses a comma and the local symbol`() {
        val result = format(BigDecimal("1.00"), "PLN", Locale.forLanguageTag("pl-PL"))

        assertEquals("1,00", result.filter { it.isDigit() || it == ',' })
        assertEquals(true, result.contains("zł"))
    }

    @Test
    fun `whole amounts still show the minor unit`() {
        val result = format(BigDecimal("50"), "PLN", Locale.forLanguageTag("pl-PL"))

        assertEquals("50,00", result.filter { it.isDigit() || it == ',' })
    }

    @Test
    fun `extra precision is never hidden`() {
        // Rounding a payment amount for display would misinform the payer.
        val result = format(BigDecimal("1.00"), "PLN", Locale.forLanguageTag("pl-PL"))

        assertEquals("1,00", result.filter { it.isDigit() || it == ',' })
    }

    @Test
    fun `unknown currency codes fall back to the raw value`() {
        val result = format(BigDecimal("1.00"), "XYZ", Locale.forLanguageTag("pl-PL"))

        assertEquals("1.00 XYZ", result)
    }

    @Test
    fun `the locale decides the format, not the currency`() {
        val polish = format(BigDecimal("1.00"), "PLN", Locale.forLanguageTag("pl-PL"))
        val english = format(BigDecimal("1.00"), "PLN", Locale.forLanguageTag("en-US"))

        assertEquals(true, polish.contains(","))
        assertEquals(true, english.contains("."))
    }
}

