package pl.simpay.android.checkout.mobile.internal.ui

import android.content.Context
import android.os.Build
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * Formats the amount due the way the payer's locale writes money.
 *
 * The sheet is already translated through resources, so the amount has to follow the
 * same locale; otherwise a Polish payer reads "1.00 PLN" next to Polish labels.
 *
 * The locale is taken from the configuration rather than [Locale.getDefault], so a
 * per-app language or a configuration override reaches the amount as well.
 */
internal object AmountFormatter {

    fun format(context: Context, amount: BigDecimal, currencyCode: String): String {
        val code = currencyCode.trim().uppercase(Locale.ROOT)
        val currency = runCatching { Currency.getInstance(code) }.getOrNull()
            // Unknown or malformed code: show the raw value rather than guess a symbol.
            ?: return "${amount.toPlainString()} $code".trim()

        val format = NumberFormat.getCurrencyInstance(localeOf(context)).apply {
            this.currency = currency
            // Never hide a digit of a payment amount: keep whatever precision the
            // order carries, even when the currency normally shows fewer decimals.
            val digits = maxOf(currency.defaultFractionDigits.coerceAtLeast(0), amount.scale())
            minimumFractionDigits = digits
            maximumFractionDigits = digits
        }
        return format.format(amount)
    }

    private fun localeOf(context: Context): Locale {
        val configuration = context.resources.configuration
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            configuration.locale
        }
    }
}

