package pl.simpay.android.checkout.mobile.internal.flow

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import pl.simpay.android.checkout.mobile.SimPayMobileApi
import pl.simpay.android.checkout.mobile.TransactionId
import pl.simpay.android.checkout.mobile.TransactionOrder

/**
 * Keeps the checkout alive across configuration changes and remembers the transaction
 * id across process death, so a known transaction can be re-checked with a new session.
 */
internal class MobileCheckoutViewModel(
    api: SimPayMobileApi,
    order: TransactionOrder,
    userAgent: String,
    private val savedState: SavedStateHandle
) : ViewModel() {

    val controller = MobileCheckoutController(
        api = api,
        order = order,
        scope = viewModelScope,
        userAgent = userAgent
    )

    init {
        // After process death the transaction id is the only thing worth restoring:
        // the session is short lived and will be fetched again.
        savedState.get<String>(KEY_TRANSACTION_ID)
            ?.let(TransactionId::parseOrNull)
            ?.let { restored ->
                controller.restoreTransaction(restored)
                controller.startPolling(restored)
            }
    }

    /**
     * Must be called whenever a transaction becomes known, and also when it is dropped
     * after a rejected BLIK code, so a restarted process never polls a transaction the
     * flow has already abandoned.
     */
    fun rememberTransaction() {
        val id = controller.transactionId
        if (id == null) {
            savedState.remove<String>(KEY_TRANSACTION_ID)
        } else {
            savedState[KEY_TRANSACTION_ID] = id.value
        }
    }

    companion object {
        private const val KEY_TRANSACTION_ID = "simpay_transaction_id"

        fun factory(
            api: SimPayMobileApi,
            order: TransactionOrder,
            userAgent: String
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MobileCheckoutViewModel(
                    api = api,
                    order = order,
                    userAgent = userAgent,
                    savedState = this.createSavedStateHandle()
                )
            }
        }
    }
}

