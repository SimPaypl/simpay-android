package pl.simpay.android.demo

import androidx.fragment.app.FragmentActivity
import pl.simpay.android.checkout.mobile.CheckoutResult
import pl.simpay.android.checkout.mobile.SimPayMobileCheckout
import pl.simpay.android.checkout.mobile.SimPayMobileConfiguration
import pl.simpay.android.checkout.mobile.TransactionOrder
import pl.simpay.android.checkout.mobile.TransactionReturns
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

/** The single item the demo shop sells. */
object DemoCart {

    const val PRODUCT_NAME = "Słuchawki bezprzewodowe"
    const val ORDER_ID = "zamowienie-2451"

    val AMOUNT: BigDecimal = BigDecimal("1.00")

    /**
     * Display only. The backend must compare the webhook amount with the order before
     * releasing it; a value formatted here proves nothing.
     */
    fun formattedAmount(): String = NumberFormat
        .getCurrencyInstance(Locale.forLanguageTag("pl-PL"))
        .format(AMOUNT)
}

/**
 * Everything an integrator has to write to open the sheet.
 *
 * The demo runs offline by default. Adding `simpay.serviceId`, `simpay.apiKey` and
 * `simpay.payerIp` to `local.properties` switches a debug build to the real API without
 * touching the screen; the API key never belongs in a production app.
 */
object DemoCheckout {

    /**
     * Must match the intent filter of DemoReturnActivity in the manifest **and** the
     * deep link scheme registered in the merchant panel.
     * The gateway drops a redirect to a scheme it was not told about.
     */
    private const val RETURN_DEEP_LINK = "simpay-demo://payment-return"

    private val usesRealApi: Boolean
        get() = BuildConfig.SIMPAY_SERVICE_ID.isNotBlank() && BuildConfig.SIMPAY_API_KEY.isNotBlank() && BuildConfig.SIMPAY_PAYER_IP.isNotBlank()

    fun present(activity: FragmentActivity, onResult: (CheckoutResult) -> Unit) {
        val order = TransactionOrder(
            amount = DemoCart.AMOUNT,
            currency = "PLN",
            description = DemoCart.PRODUCT_NAME,
            control = DemoCart.ORDER_ID,
            customerEmail = "demo@example.com",
            // Direct channels require a country code matching the currency.
            customerCountryCode = "PL",
            // Handled by DemoReturnActivity, so the payer comes back to the app
            // instead of being left in a browser tab.
            returns = TransactionReturns(
                success = "$RETURN_DEEP_LINK?result=success",
                failure = "$RETURN_DEEP_LINK?result=failure"
            ),
            // A real backend reports the payer IP it observed; the SDK never invents one.
            payerIpOverride = BuildConfig.SIMPAY_PAYER_IP.takeIf { usesRealApi }
        )

        checkout().present(activity, order, onResult)
    }

    private fun checkout(): SimPayMobileCheckout {
        val configuration = DemoSessionProviders.configuration(
            serviceId = if (usesRealApi) BuildConfig.SIMPAY_SERVICE_ID else "00000000",
            merchantDisplayName = "Sklep SimPay"
        )

        // Explicit substitution of the API; the SDK never falls back to a fake itself.
        if (!usesRealApi) {
            return SimPayMobileCheckout.withApi(configuration, FakeMobileSdkApi())
        }

        return SimPayMobileCheckout(
            configuration = configuration,
            sessionProvider = DemoSessionProviders.apiKeySessionProvider(
                baseUrl = SimPayMobileConfiguration.PRODUCTION_BASE_URL,
                serviceId = BuildConfig.SIMPAY_SERVICE_ID,
                apiKey = BuildConfig.SIMPAY_API_KEY
            )
        )
    }
}
