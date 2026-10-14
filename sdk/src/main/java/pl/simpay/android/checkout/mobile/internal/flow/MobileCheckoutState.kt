package pl.simpay.android.checkout.mobile.internal.flow

import pl.simpay.android.checkout.mobile.MobileCheckoutError
import pl.simpay.android.checkout.mobile.PaymentChannel
import pl.simpay.android.checkout.mobile.TransactionId

/**
 * Why the BLIK entry shows an error.
 *
 * The flow reports a reason rather than a message, so every user facing text lives in
 * the UI layer and can be translated. API messages are kept in [MobileCheckoutState.Failed]
 * for the integrator, not shown to the payer.
 */
internal enum class BlikEntryError {
    InvalidFormat,
    CodeRejected
}

/**
 * States of the mobile checkout flow.
 *
 * The state always keeps the exact SimPay status where one is known, so the UI never
 * has to guess and an unknown status is never rendered as success.
 */
internal sealed interface MobileCheckoutState {

    data object LoadingChannels : MobileCheckoutState

    data class Channels(
        val channels: List<PaymentChannel>
    ) : MobileCheckoutState

    data class CreatingTransaction(
        val channel: PaymentChannel
    ) : MobileCheckoutState

    data class EnteringBlikCode(
        val channel: PaymentChannel,
        val transactionId: TransactionId?,
        val error: BlikEntryError? = null,
        val canRetryCode: Boolean = true
    ) : MobileCheckoutState


    data class SubmittingBlikCode(
        val channel: PaymentChannel,
        val transactionId: TransactionId
    ) : MobileCheckoutState

    /** BLIK confirmation was pushed to the banking app. Not a payment yet. */
    data class AwaitingBankConfirmation(
        val transactionId: TransactionId
    ) : MobileCheckoutState

    data class Redirecting(
        val transactionId: TransactionId,
        val redirectUrl: String
    ) : MobileCheckoutState

    data class CheckingStatus(
        val transactionId: TransactionId,
        val lastRawStatus: String? = null
    ) : MobileCheckoutState

    data class Succeeded(
        val transactionId: TransactionId,
        val rawStatus: String
    ) : MobileCheckoutState

    /** Outcome still open, for example after the polling budget elapsed. */
    data class Pending(
        val transactionId: TransactionId,
        val rawStatus: String?
    ) : MobileCheckoutState

    data class Failed(
        val error: MobileCheckoutError,
        val message: String?,
        val transactionId: TransactionId? = null,
        val rawStatus: String? = null,
        val retryable: Boolean = true
    ) : MobileCheckoutState

    data class Expired(
        val transactionId: TransactionId,
        val rawStatus: String
    ) : MobileCheckoutState

    data class Refunded(
        val transactionId: TransactionId,
        val rawStatus: String
    ) : MobileCheckoutState

    /** transaction_fraud_possibility: neither a success nor a failure. */
    data class VerificationRequired(
        val transactionId: TransactionId,
        val rawStatus: String
    ) : MobileCheckoutState
}

