package pl.simpay.android.checkout.mobile.internal.flow

import pl.simpay.android.checkout.mobile.CheckoutResult
import pl.simpay.android.checkout.mobile.SimPayMobileApi
import pl.simpay.android.checkout.mobile.TransactionOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * Holds non-parcelable collaborators of an in-flight mobile checkout.
 *
 * Entries live only while the sheet is presented. Nothing here is persisted, so no
 * token or order data survives the process.
 */
internal object MobileCheckoutRegistry {

    /** Everything the sheet needs that cannot be passed through a Bundle. */
    data class PendingCheckout(
        val api: SimPayMobileApi,
        val order: TransactionOrder,
        val merchantDisplayName: String,
        val callback: (CheckoutResult) -> Unit
    )

    private val pendingCheckouts = ConcurrentHashMap<String, PendingCheckout>()

    fun put(checkoutId: String, pendingCheckout: PendingCheckout) {
        pendingCheckouts[checkoutId] = pendingCheckout
    }

    fun find(checkoutId: String): PendingCheckout? = pendingCheckouts[checkoutId]

    /** Removes the entry and reports the result exactly once. */
    fun finish(checkoutId: String, result: CheckoutResult) {
        pendingCheckouts.remove(checkoutId)?.callback?.invoke(result)
    }
}
