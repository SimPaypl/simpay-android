package pl.simpay.android.checkout.mobile

import androidx.fragment.app.FragmentActivity
import pl.simpay.android.checkout.mobile.internal.flow.MobileCheckoutRegistry
import pl.simpay.android.checkout.mobile.internal.network.HttpMobileSdkApi
import pl.simpay.android.checkout.mobile.internal.ui.SimPayCheckoutSheetFragment
import java.util.UUID

/**
 * Entry point of the mobile checkout.
 *
 * The SDK fetches channels, creates the transaction, handles BLIK Level 0 and redirect
 * channels, and always verifies the transaction status before reporting success.
 *
 * The access session is supplied by a [MobileSessionProvider]: the session endpoint
 * requires the merchant API key, which must stay on the merchant backend.
 */
class SimPayMobileCheckout private constructor(
    private val merchantDisplayName: String,
    private val api: SimPayMobileApi
) {

    constructor(
        configuration: SimPayMobileConfiguration,
        sessionProvider: MobileSessionProvider
    ) : this(
        merchantDisplayName = configuration.merchantDisplayName,
        api = HttpMobileSdkApi(configuration, sessionProvider)
    )

    /**
     * Presents the payment sheet.
     *
     * Requires a FragmentActivity because the flow keeps its state in a ViewModel and
     * remembers the transaction id in saved state.
     *
     * The result is never proof of payment. The merchant backend must confirm the
     * payment from the SimPay webhook before releasing goods.
     */
    fun present(
        activity: FragmentActivity,
        order: TransactionOrder,
        onResult: (CheckoutResult) -> Unit
    ) {
        if (activity.supportFragmentManager.isStateSaved) {
            onResult(
                CheckoutResult.Failed(
                    error = MobileCheckoutError.Internal,
                    message = "Cannot present the sheet while state is saved"
                )
            )
            return
        }

        // Guards against repeated taps opening several sheets.
        if (activity.supportFragmentManager.findFragmentByTag(FRAGMENT_TAG) != null) {
            return
        }

        val checkoutId = UUID.randomUUID().toString()
        MobileCheckoutRegistry.put(
            checkoutId = checkoutId,
            pendingCheckout = MobileCheckoutRegistry.PendingCheckout(
                api = api,
                order = order,
                merchantDisplayName = merchantDisplayName,
                callback = onResult
            )
        )

        SimPayCheckoutSheetFragment
            .newInstance(checkoutId)
            .show(activity.supportFragmentManager, FRAGMENT_TAG)
    }

    companion object {
        private const val FRAGMENT_TAG = "simpay_checkout_sheet"

        /**
         * Runs the checkout against a supplied [SimPayMobileApi], for demos and tests
         * that substitute a fake. The SDK never falls back to a fake on its own.
         */
        fun withApi(
            configuration: SimPayMobileConfiguration,
            api: SimPayMobileApi
        ): SimPayMobileCheckout = SimPayMobileCheckout(
            merchantDisplayName = configuration.merchantDisplayName,
            api = api
        )
    }
}
