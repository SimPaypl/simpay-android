package pl.simpay.android.checkout.mobile.internal.flow

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pl.simpay.android.checkout.mobile.BlikCode
import pl.simpay.android.checkout.mobile.CheckoutResult
import pl.simpay.android.checkout.mobile.CreateTransactionRequest
import pl.simpay.android.checkout.mobile.CreatedTransaction
import pl.simpay.android.checkout.mobile.MobileCheckoutError
import pl.simpay.android.checkout.mobile.PaymentChannel
import pl.simpay.android.checkout.mobile.SimPayApiException
import pl.simpay.android.checkout.mobile.SimPayMobileApi
import pl.simpay.android.checkout.mobile.TransactionAntifraud
import pl.simpay.android.checkout.mobile.TransactionCustomer
import pl.simpay.android.checkout.mobile.TransactionId
import pl.simpay.android.checkout.mobile.TransactionOrder
import pl.simpay.android.checkout.mobile.TransactionStatus
import pl.simpay.android.checkout.mobile.internal.network.SessionHolder

/** Timing of the status polling loop. Overridable so tests need no real waiting. */
internal data class PollingConfig(
    val initialIntervalMs: Long = 2_000,
    val maxIntervalMs: Long = 5_000,
    val budgetMs: Long = 120_000
)

/** Abstraction over elapsed time so polling can be tested deterministically. */
internal fun interface TimeSource {
    fun nowMs(): Long
}

/**
 * Owns the whole checkout flow.
 *
 * Responsibilities kept here rather than in the UI:
 * loading channels, creating the transaction exactly once, BLIK submission,
 * and status polling. Returning from a browser never completes the flow by itself.
 */
internal class MobileCheckoutController(
    private val api: SimPayMobileApi,
    private val order: TransactionOrder,
    private val scope: CoroutineScope,
    private val polling: PollingConfig = PollingConfig(),
    private val timeSource: TimeSource = TimeSource { System.currentTimeMillis() },
    private val sessionHolder: SessionHolder = SessionHolder(api),
    private val userAgent: String = DEFAULT_USER_AGENT
) {

    private val _state = MutableStateFlow<MobileCheckoutState>(MobileCheckoutState.LoadingChannels)
    val state: StateFlow<MobileCheckoutState> = _state.asStateFlow()

    private var channelsJob: Job? = null
    private var createJob: Job? = null
    private var blikJob: Job? = null
    private var pollJob: Job? = null

    private var selectedChannel: PaymentChannel? = null

    /** Set once a transaction exists; guards against ever creating a second one. */
    var transactionId: TransactionId? = null
        private set

    /**
     * Last transaction created in this checkout, kept even after a rejected BLIK code
     * clears [transactionId], so a dismissed sheet never reports "cancelled" for a
     * transaction the API already knows about.
     */
    private var lastTransactionId: TransactionId? = null

    /** Counts explicitly rejected BLIK codes, to bound how many retries are allowed. */
    private var blikRejections = 0

    private var redirectUrl: String? = null

    /** Restores a known transaction, for example after process death. */
    fun restoreTransaction(id: TransactionId) {
        if (transactionId == null) {
            transactionId = id
            lastTransactionId = id
        }
    }

    fun loadChannels(force: Boolean = false): Boolean {
        if (channelsJob?.isActive == true) {
            return false
        }
        if (!force && _state.value is MobileCheckoutState.Channels) {
            return false
        }

        _state.value = MobileCheckoutState.LoadingChannels
        channelsJob = scope.launch {
            runCatchingApi {
                val channels = sessionHolder.withSession { session -> api.getChannels(session) }
                // The API lists BLIK twice; the payer must be offered one entry.
                _state.value = MobileCheckoutState.Channels(BlikLevel0.collapseDuplicates(channels))
            }
        }
        return true
    }

    /** Loads channels only when nothing has been resolved yet, used after recreation. */
    fun loadChannelsIfNeeded(): Boolean {
        return when (_state.value) {
            is MobileCheckoutState.LoadingChannels -> loadChannels(force = true)
            else -> false
        }
    }

    fun selectChannel(channel: PaymentChannel): Boolean {
        if (createJob?.isActive == true) {
            return false
        }
        // A transaction must never be created twice for one checkout.
        if (transactionId != null) {
            resumeAfterExistingTransaction(channel)
            return false
        }

        selectedChannel = channel

        return if (BlikLevel0.supports(channel)) {
            _state.value = MobileCheckoutState.EnteringBlikCode(channel, transactionId = null)
            true
        } else {
            createTransaction(channel, directChannel = channel.id)
        }
    }

    /** Called when the user submits a six digit code for BLIK. */
    fun submitBlikCode(rawCode: String): Boolean {
        val channel = selectedChannel ?: return false
        if (blikJob?.isActive == true || createJob?.isActive == true) {
            return false
        }

        val code = BlikCode.parseOrNull(rawCode)
        if (code == null) {
            _state.value = MobileCheckoutState.EnteringBlikCode(
                channel = channel,
                transactionId = transactionId,
                error = BlikEntryError.InvalidFormat
            )
            return false
        }

        val existing = transactionId
        return if (existing == null) {
            createTransaction(channel, directChannel = BlikLevel0.DIRECT_CHANNEL, blikCode = code)
        } else {
            sendBlikCode(channel, existing, code)
        }
    }

    private fun createTransaction(
        channel: PaymentChannel,
        directChannel: String,
        blikCode: BlikCode? = null
    ): Boolean {
        // Every channel offered by the sheet is created as a direct channel, so the API
        // requires the full payer context before anything is sent.
        val customer = when (val payer = buildCustomer()) {
            is PayerData.Ready -> payer.customer
            is PayerData.Missing -> {
                _state.value = MobileCheckoutState.Failed(
                    error = payer.error,
                    message = payer.message,
                    retryable = false
                )
                return false
            }
        }

        _state.value = MobileCheckoutState.CreatingTransaction(channel)
        createJob = scope.launch {
            runCatchingApi {
                val request = CreateTransactionRequest(
                    amount = order.amount,
                    currency = order.currency,
                    description = order.description,
                    control = order.control,
                    directChannel = directChannel,
                    customer = customer,
                    antifraud = TransactionAntifraud(userAgent),
                    returns = order.returns
                )

                // Creating a transaction is not idempotent, so it is never retried
                // automatically after an indeterminate failure.
                val created = sessionHolder.withSession(retryAfterRefresh = false) { session ->
                    api.createTransaction(session, request)
                }

                onTransactionCreated(channel, created, blikCode)
            }
        }
        return true
    }

    private suspend fun onTransactionCreated(
        channel: PaymentChannel,
        created: CreatedTransaction,
        blikCode: BlikCode?
    ) {
        transactionId = created.transactionId
        lastTransactionId = created.transactionId
        redirectUrl = created.redirectUrl

        if (BlikLevel0.supports(channel)) {
            if (blikCode != null) {
                performBlikSubmission(channel, created.transactionId, blikCode)
            } else {
                _state.value = MobileCheckoutState.EnteringBlikCode(channel, created.transactionId)
            }
        } else {
            _state.value = MobileCheckoutState.Redirecting(created.transactionId, created.redirectUrl)
        }
    }

    private fun sendBlikCode(
        channel: PaymentChannel,
        id: TransactionId,
        code: BlikCode
    ): Boolean {
        blikJob = scope.launch { performBlikSubmission(channel, id, code) }
        return true
    }

    private suspend fun performBlikSubmission(
        channel: PaymentChannel,
        id: TransactionId,
        code: BlikCode
    ) {
        _state.value = MobileCheckoutState.SubmittingBlikCode(channel, id)
        try {
            // Not idempotent: never repeated automatically after an unknown outcome.
            sessionHolder.withSession(retryAfterRefresh = false) { session ->
                api.submitBlikCode(session, id, code)
            }
            // HTTP 204 only means the bank was asked to confirm.
            _state.value = MobileCheckoutState.AwaitingBankConfirmation(id)
            startPolling(id)
        } catch (exception: SimPayApiException) {
            _state.value = handleBlikFailure(channel, id, exception)
        } catch (exception: Throwable) {
            _state.value = MobileCheckoutState.Failed(
                error = MobileCheckoutError.Internal,
                message = exception.message,
                transactionId = id
            )
        }
    }

    /**
     * Decides what happens after a failed BLIK submission, and clears the transaction
     * when the code was explicitly rejected.
     *
     * A rejected ticket ends the transaction on the SimPay side: the same
     * `transactionId` cannot accept another code, so the next attempt must create a new
     * transaction. Clearing [transactionId] is therefore the only way a retry can work,
     * and it is allowed exclusively here, after an explicit rejection. An unknown
     * outcome keeps the transaction, so the SDK never starts a second payment for money
     * that may already be on its way.
     */
    private fun handleBlikFailure(
        channel: PaymentChannel,
        id: TransactionId,
        exception: SimPayApiException
    ): MobileCheckoutState {
        val codeWasRejected = when (exception.httpCode) {
            400, 409, 422 -> true
            else -> false
        }

        if (!codeWasRejected) {
            return MobileCheckoutState.Failed(
                error = exception.error,
                message = exception.message,
                transactionId = id,
                retryable = exception.error != MobileCheckoutError.Indeterminate
            )
        }

        // The rejected transaction is abandoned; lastTransactionId still remembers it.
        transactionId = null
        redirectUrl = null
        selectedChannel = channel
        blikRejections++

        if (blikRejections >= MAX_BLIK_REJECTIONS) {
            return MobileCheckoutState.Failed(
                error = MobileCheckoutError.BlikRejected,
                message = "The BLIK code was rejected too many times",
                transactionId = id,
                retryable = false
            )
        }

        return MobileCheckoutState.EnteringBlikCode(
            channel = channel,
            // No transaction: the next code starts a fresh one.
            transactionId = null,
            error = BlikEntryError.CodeRejected,
            canRetryCode = true
        )
    }

    /** Called after returning from the browser. Never treated as success by itself. */
    fun onReturnedFromBrowser() {
        val id = transactionId ?: return
        startPolling(id)
    }

    /**
     * Publishes the polling state without disturbing the BLIK confirmation screen.
     *
     * BLIK Level 0 certification requires "Confirm the payment in your banking app" to
     * stay visible for the whole time the payer is deciding in the bank, with no other
     * action and no countdown. Polling starts immediately after the code is accepted, so
     * overwriting the state here would replace that message with a status line after
     * about two seconds - exactly when the push notification from the bank arrives.
     *
     * Status checking still runs underneath; only the wording on screen is kept.
     */
    private fun publishPollingState(id: TransactionId, lastRaw: String?) {
        if (_state.value is MobileCheckoutState.AwaitingBankConfirmation) {
            return
        }
        _state.value = MobileCheckoutState.CheckingStatus(id, lastRaw)
    }

    /** Resumes polling, for example when the app comes back to the foreground. */
    fun resumeStatusCheckIfNeeded(): Boolean {
        val id = transactionId ?: return false
        if (pollJob?.isActive == true) {
            return false
        }
        return when (_state.value) {
            is MobileCheckoutState.AwaitingBankConfirmation,
            is MobileCheckoutState.CheckingStatus,
            is MobileCheckoutState.Redirecting -> {
                startPolling(id)
                true
            }

            else -> false
        }
    }

    fun startPolling(id: TransactionId): Boolean {
        if (pollJob?.isActive == true) {
            return false
        }

        pollJob = scope.launch {
            val deadline = timeSource.nowMs() + polling.budgetMs
            var interval = polling.initialIntervalMs
            var lastRaw: String? = null

            publishPollingState(id, lastRaw)

            while (true) {
                val snapshot = try {
                    sessionHolder.withSession { session -> api.getTransactionStatus(session, id) }
                } catch (exception: SimPayApiException) {
                    _state.value = MobileCheckoutState.Failed(
                        error = exception.error,
                        message = exception.message,
                        transactionId = id,
                        rawStatus = lastRaw
                    )
                    return@launch
                } catch (exception: Throwable) {
                    _state.value = MobileCheckoutState.Failed(
                        error = MobileCheckoutError.Internal,
                        message = exception.message,
                        transactionId = id,
                        rawStatus = lastRaw
                    )
                    return@launch
                }

                lastRaw = snapshot.status.rawStatus

                val terminal = terminalStateFor(id, snapshot.status)
                if (terminal != null) {
                    _state.value = terminal
                    return@launch
                }

                if (timeSource.nowMs() >= deadline) {
                    // A timeout means the result is still open, never a failure.
                    _state.value = MobileCheckoutState.Pending(id, lastRaw)
                    return@launch
                }

                publishPollingState(id, lastRaw)
                delay(interval)
                interval = minOf(interval * 2, polling.maxIntervalMs)
            }
        }
        return true
    }

    private fun terminalStateFor(
        id: TransactionId,
        status: TransactionStatus
    ): MobileCheckoutState? = when (status) {
        is TransactionStatus.Paid -> MobileCheckoutState.Succeeded(id, status.rawStatus)
        is TransactionStatus.Failed -> MobileCheckoutState.Failed(
            error = MobileCheckoutError.TransactionFailed,
            message = "Payment was not completed",
            transactionId = id,
            rawStatus = status.rawStatus,
            retryable = false
        )

        is TransactionStatus.Expired -> MobileCheckoutState.Expired(id, status.rawStatus)
        is TransactionStatus.Refunded -> MobileCheckoutState.Refunded(id, status.rawStatus)
        is TransactionStatus.Fraud -> MobileCheckoutState.Failed(
            error = MobileCheckoutError.TransactionFailed,
            message = "Transaction was rejected",
            transactionId = id,
            rawStatus = status.rawStatus,
            retryable = false
        )

        is TransactionStatus.Verification -> MobileCheckoutState.VerificationRequired(id, status.rawStatus)

        // Processing keeps polling; an unknown status is never a success and is
        // reported as pending so the backend decides based on the webhook.
        is TransactionStatus.Processing -> null
        is TransactionStatus.Unknown -> MobileCheckoutState.Pending(id, status.rawStatus)
    }

    private fun resumeAfterExistingTransaction(channel: PaymentChannel) {
        val id = transactionId ?: return
        if (BlikLevel0.supports(channel)) {
            _state.value = MobileCheckoutState.EnteringBlikCode(channel, id)
        } else {
            redirectUrl?.let { url -> _state.value = MobileCheckoutState.Redirecting(id, url) }
        }
    }

    /** Payer context required by direct channels, or the reason it cannot be built. */
    private sealed interface PayerData {
        data class Ready(val customer: TransactionCustomer) : PayerData
        data class Missing(val error: MobileCheckoutError, val message: String) : PayerData
    }

    private fun buildCustomer(): PayerData {
        val ip = sessionHolder.peek()?.payerIp ?: order.payerIpOverride
        // The SDK never invents an IP and never sends a local interface address.
        if (ip.isNullOrBlank()) {
            return PayerData.Missing(
                error = MobileCheckoutError.MissingPayerIp,
                message = "The session provider did not supply the payer IP required for direct channels"
            )
        }

        val countryCode = order.customerCountryCode ?: PayerCountry.forCurrency(order.currency)
        if (countryCode == null) {
            return PayerData.Missing(
                error = MobileCheckoutError.MissingCountryCode,
                message = "Direct channels require customer.countryCode matching " +
                    "${order.currency}; set TransactionOrder.customerCountryCode"
            )
        }

        return PayerData.Ready(
            TransactionCustomer(
                email = order.customerEmail,
                ip = ip,
                countryCode = countryCode
            )
        )
    }

    /** Maps the current state to the public result. */
    fun currentResult(): CheckoutResult = when (val state = _state.value) {
        is MobileCheckoutState.Succeeded -> CheckoutResult.Succeeded(state.transactionId, state.rawStatus)
        is MobileCheckoutState.Pending -> CheckoutResult.Pending(state.transactionId, state.rawStatus)
        is MobileCheckoutState.VerificationRequired ->
            CheckoutResult.Pending(state.transactionId, state.rawStatus)

        is MobileCheckoutState.Expired -> CheckoutResult.Failed(
            error = MobileCheckoutError.TransactionFailed,
            transactionId = state.transactionId,
            rawStatus = state.rawStatus,
            message = "Transaction expired"
        )

        is MobileCheckoutState.Refunded -> CheckoutResult.Failed(
            error = MobileCheckoutError.TransactionFailed,
            transactionId = state.transactionId,
            rawStatus = state.rawStatus,
            message = "Transaction refunded"
        )

        is MobileCheckoutState.Failed -> CheckoutResult.Failed(
            error = state.error,
            transactionId = state.transactionId,
            rawStatus = state.rawStatus,
            message = state.message
        )

        else -> (transactionId ?: lastTransactionId)
            ?.let { id -> CheckoutResult.Pending(id, null) }
            ?: CheckoutResult.Cancelled
    }

    private suspend fun runCatchingApi(block: suspend () -> Unit) {
        try {
            block()
        } catch (exception: SimPayApiException) {
            _state.value = MobileCheckoutState.Failed(
                error = exception.error,
                message = exception.message,
                transactionId = transactionId,
                retryable = exception.error != MobileCheckoutError.Indeterminate
            )
        } catch (exception: Throwable) {
            _state.value = MobileCheckoutState.Failed(
                error = MobileCheckoutError.Internal,
                message = exception.message,
                transactionId = transactionId
            )
        }
    }

    internal companion object {
        /**
         * Used only when the device agent cannot be read. The API expects the agent of
         * the client that continues the payment, so the real value is resolved from the
         * device and passed in by the sheet. Contains no personal data.
         */
        const val DEFAULT_USER_AGENT = "SimPayAndroidSDK/1.0 (Android)"

        /**
         * Each rejected code creates a new transaction, so retries are bounded to keep
         * a mistyping user from filling the merchant account with dead transactions.
         */
        const val MAX_BLIK_REJECTIONS = 5
    }
}

