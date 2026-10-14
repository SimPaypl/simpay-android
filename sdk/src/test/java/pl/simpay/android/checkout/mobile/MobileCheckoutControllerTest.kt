package pl.simpay.android.checkout.mobile

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import pl.simpay.android.checkout.mobile.internal.flow.BlikEntryError
import pl.simpay.android.checkout.mobile.internal.flow.BlikLevel0
import pl.simpay.android.checkout.mobile.internal.flow.MobileCheckoutController
import pl.simpay.android.checkout.mobile.internal.flow.MobileCheckoutState
import pl.simpay.android.checkout.mobile.internal.flow.PollingConfig

import pl.simpay.android.checkout.mobile.internal.flow.TimeSource
import org.junit.Test
import java.math.BigDecimal

/**
 * Covers the rules that protect money: a transaction is created at most once, a BLIK
 * code is never resent after an unknown outcome, and no state other than
 * `transaction_paid` is reported as success.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MobileCheckoutControllerTest {

    private val blikChannel = PaymentChannel(
        id = "blik",
        name = "BLIK",
        type = "blik",
        img = null,
        currencies = listOf("PLN"),
        amount = ChannelAmountRange(min = "1.00", max = "500.00")
    )

    private val transferChannel = PaymentChannel(
        id = "mbank",
        name = "mBank",
        type = "transfer",
        img = null,
        currencies = listOf("PLN"),
        amount = ChannelAmountRange(min = "1.00", max = "5000.00")
    )

    private val order = TransactionOrder(
        amount = BigDecimal("1.00"),
        currency = "PLN",
        description = "Order 123",
        control = "order-123",
        customerEmail = "payer@example.com",
        returns = TransactionReturns(
            success = "https://example.com/ok",
            failure = "https://example.com/fail"
        ),
        payerIpOverride = PAYER_IP
    )

    /** Advances virtual time, so the polling loop never waits in real time. */
    private class SteppingTime(private val stepMs: Long) : TimeSource {
        private var now = 0L
        override fun nowMs(): Long {
            val value = now
            now += stepMs
            return value
        }
    }

    private class FakeApi(
        var channels: List<PaymentChannel> = emptyList(),
        var payerIp: String? = null,
        var createError: Throwable? = null,
        var blikError: Throwable? = null,
        /** Consumed in order; the last entry is repeated once exhausted. */
        var statuses: MutableList<TransactionStatus> = mutableListOf()
    ) : SimPayMobileApi {

        var sessionCalls = 0
        var createCalls = 0
        var blikCalls = 0
        var statusCalls = 0
        var lastRequest: CreateTransactionRequest? = null

        override suspend fun createSession(): MobileSdkSession {
            sessionCalls++
            return MobileSdkSession("mobsdk_test", null, payerIp)
        }

        override suspend fun getChannels(session: MobileSdkSession): List<PaymentChannel> = channels

        override suspend fun createTransaction(
            session: MobileSdkSession,
            request: CreateTransactionRequest
        ): CreatedTransaction {
            createCalls++
            lastRequest = request
            createError?.let { throw it }
            // A rejected BLIK code forces a new transaction, so ids must differ.
            val id = if (createCalls == 1) TX_ID else OTHER_TX_ID
            return CreatedTransaction(TransactionId(id), REDIRECT_URL)
        }

        override suspend fun submitBlikCode(
            session: MobileSdkSession,
            transactionId: TransactionId,
            code: BlikCode
        ) {
            blikCalls++
            blikError?.let { throw it }
        }

        override suspend fun getTransactionStatus(
            session: MobileSdkSession,
            transactionId: TransactionId
        ): TransactionSnapshot {
            statusCalls++
            val status = when {
                statuses.isEmpty() -> TransactionStatus.fromRaw("transaction_new")
                statuses.size == 1 -> statuses.first()
                else -> statuses.removeAt(0)
            }
            return TransactionSnapshot(transactionId, status)
        }
    }

    private fun controller(
        api: FakeApi,
        scope: kotlinx.coroutines.CoroutineScope,
        order: TransactionOrder = this.order,
        polling: PollingConfig = PollingConfig(budgetMs = 60_000),
        timeSource: TimeSource = SteppingTime(1_000)
    ) = MobileCheckoutController(
        api = api,
        order = order,
        scope = scope,
        polling = polling,
        timeSource = timeSource
    )

    @Test
    fun `selecting blik asks for the code before creating a transaction`() = runTest {
        val api = FakeApi()
        val controller = controller(api, this)

        assertTrue(controller.selectChannel(blikChannel))
        advanceUntilIdle()

        val state = controller.state.value as MobileCheckoutState.EnteringBlikCode
        assertNull(state.transactionId)
        assertEquals(0, api.createCalls)
        assertNull(controller.transactionId)
    }

    @Test
    fun `invalid blik code is rejected locally and reaches no api`() = runTest {
        val api = FakeApi()
        val controller = controller(api, this)
        controller.selectChannel(blikChannel)
        advanceUntilIdle()

        assertFalse(controller.submitBlikCode("12345"))
        advanceUntilIdle()

        val state = controller.state.value as MobileCheckoutState.EnteringBlikCode
        // The flow reports a reason; the localised text lives in the UI layer.
        assertEquals(BlikEntryError.InvalidFormat, state.error)
        assertEquals(0, api.createCalls)
        assertEquals(0, api.blikCalls)
    }

    @Test
    fun `blik payment succeeds only on transaction_paid`() = runTest {
        val api = FakeApi(
            statuses = mutableListOf(
                TransactionStatus.fromRaw("transaction_new"),
                TransactionStatus.fromRaw("transaction_confirmed"),
                TransactionStatus.fromRaw("transaction_paid")
            )
        )
        val controller = controller(api, this)

        controller.selectChannel(blikChannel)
        advanceUntilIdle()
        assertTrue(controller.submitBlikCode("123456"))
        advanceUntilIdle()

        val state = controller.state.value as MobileCheckoutState.Succeeded
        assertEquals(TX_ID, state.transactionId.value)
        assertEquals("transaction_paid", state.rawStatus)
        assertEquals(1, api.createCalls)
        assertEquals(1, api.blikCalls)

        val result = controller.currentResult() as CheckoutResult.Succeeded
        assertEquals("transaction_paid", result.rawStatus)
    }

    @Test
    fun `the bank confirmation prompt stays on screen while polling`() = runTest {
        // No terminal status: the payer is still deciding in the banking app.
        val api = FakeApi()
        val controller = controller(api, this)

        controller.selectChannel(blikChannel)
        advanceUntilIdle()
        assertTrue(controller.submitBlikCode("123456"))

        // Several polling rounds pass while the bank push sits on the payer's screen.
        advanceTimeBy(10_000)
        runCurrent()

        assertTrue(api.statusCalls > 1)
        // BLIK Level 0 certification requires "Confirm the payment in your banking app"
        // to stay put for the whole wait, so polling must not replace it with a status
        // line two seconds in.
        assertTrue(controller.state.value is MobileCheckoutState.AwaitingBankConfirmation)
    }

    @Test
    fun `order data is sent as given and blik is the direct channel`() = runTest {
        val api = FakeApi(statuses = mutableListOf(TransactionStatus.fromRaw("transaction_paid")))
        val controller = controller(api, this)

        controller.selectChannel(blikChannel)
        advanceUntilIdle()
        controller.submitBlikCode("123456")
        advanceUntilIdle()

        val request = requireNotNull(api.lastRequest)
        assertEquals(0, BigDecimal("1.00").compareTo(request.amount))
        assertEquals("PLN", request.currency)
        assertEquals("order-123", request.control)
        assertEquals(BlikLevel0.DIRECT_CHANNEL, request.directChannel)
        assertEquals(PAYER_IP, request.customer?.ip)
        assertEquals("payer@example.com", request.customer?.email)
        assertEquals("https://example.com/ok", request.returns?.success)
    }

    @Test
    fun `blik is recognised by the id and the type reported by the channels endpoint`() {
        assertTrue(BlikLevel0.supports(blikChannel))
        assertTrue(BlikLevel0.supports(blikChannel.copy(id = "BLIK", type = null)))
        assertTrue(BlikLevel0.supports(blikChannel.copy(id = "something", type = "blik")))
        assertFalse(BlikLevel0.supports(transferChannel))
    }

    @Test
    fun `a direct channel carries the country code and the user agent`() = runTest {
        val api = FakeApi()
        val controller = MobileCheckoutController(
            api = api,
            order = order,
            scope = this,
            polling = PollingConfig(budgetMs = 60_000),
            timeSource = SteppingTime(1_000),
            userAgent = DEVICE_USER_AGENT
        )

        controller.selectChannel(transferChannel)
        advanceUntilIdle()

        val request = requireNotNull(api.lastRequest)
        // Derived from PLN, because the order states no country explicitly.
        assertEquals("PL", request.customer?.countryCode)
        assertEquals(DEVICE_USER_AGENT, request.antifraud?.userAgent)
    }

    @Test
    fun `an explicit country code wins over the one derived from the currency`() = runTest {
        val api = FakeApi()
        val controller = controller(
            api = api,
            scope = this,
            order = order.copy(customerCountryCode = "GB")
        )

        controller.selectChannel(transferChannel)
        advanceUntilIdle()

        assertEquals("GB", api.lastRequest?.customer?.countryCode)
    }

    @Test
    fun `a currency without a single country fails before any transaction is created`() = runTest {
        val api = FakeApi()
        val controller = controller(
            api = api,
            scope = this,
            // EUR is shared by many countries, so the SDK must not guess one.
            order = order.copy(currency = "EUR", customerCountryCode = null)
        )

        assertFalse(controller.selectChannel(transferChannel))
        advanceUntilIdle()

        val state = controller.state.value as MobileCheckoutState.Failed
        assertEquals(MobileCheckoutError.MissingCountryCode, state.error)
        assertFalse(state.retryable)
        assertEquals(0, api.createCalls)
    }

    @Test
    fun `a rejected code abandons the transaction and the retry creates a new one`() = runTest {
        val api = FakeApi(
            blikError = SimPayApiException(
                error = MobileCheckoutError.BlikRejected,
                httpCode = 400,
                message = "Invalid code"
            ),
            statuses = mutableListOf(TransactionStatus.fromRaw("transaction_paid"))
        )
        val controller = controller(api, this)

        controller.selectChannel(blikChannel)
        advanceUntilIdle()
        controller.submitBlikCode("123456")
        advanceUntilIdle()

        val rejected = controller.state.value as MobileCheckoutState.EnteringBlikCode
        assertTrue(rejected.canRetryCode)
        // The rejected ticket closes the transaction on the SimPay side.
        assertNull(rejected.transactionId)
        assertNull(controller.transactionId)
        assertEquals(1, api.createCalls)

        api.blikError = null
        controller.submitBlikCode("654321")
        advanceUntilIdle()

        val state = controller.state.value as MobileCheckoutState.Succeeded
        // A second transaction was created, and it is the one that got paid.
        assertEquals(2, api.createCalls)
        assertEquals(2, api.blikCalls)
        assertEquals(OTHER_TX_ID, state.transactionId.value)
    }

    @Test
    fun `repeated rejections stop after the allowed number of attempts`() = runTest {
        val api = FakeApi(
            blikError = SimPayApiException(
                error = MobileCheckoutError.BlikRejected,
                httpCode = 409,
                message = "Invalid code"
            )
        )
        val controller = controller(api, this)

        controller.selectChannel(blikChannel)
        advanceUntilIdle()

        repeat(MobileCheckoutController.MAX_BLIK_REJECTIONS) {
            controller.submitBlikCode("123456")
            advanceUntilIdle()
        }

        val state = controller.state.value as MobileCheckoutState.Failed
        assertEquals(MobileCheckoutError.BlikRejected, state.error)
        assertFalse(state.retryable)
        assertEquals(MobileCheckoutController.MAX_BLIK_REJECTIONS, api.createCalls)
    }

    @Test
    fun `an abandoned transaction is still reported when the sheet is dismissed`() = runTest {
        val api = FakeApi(
            blikError = SimPayApiException(
                error = MobileCheckoutError.BlikRejected,
                httpCode = 422,
                message = "Invalid code"
            )
        )
        val controller = controller(api, this)

        controller.selectChannel(blikChannel)
        advanceUntilIdle()
        controller.submitBlikCode("123456")
        advanceUntilIdle()

        // Cancelled would hide a transaction the API already knows about.
        val result = controller.currentResult() as CheckoutResult.Pending
        assertEquals(TX_ID, result.transactionId?.value)
    }

    @Test
    fun `an indeterminate blik outcome does not invite another attempt`() = runTest {
        val api = FakeApi(
            blikError = SimPayApiException(
                error = MobileCheckoutError.Indeterminate,
                httpCode = null,
                message = "timeout"
            )
        )
        val controller = controller(api, this)

        controller.selectChannel(blikChannel)
        advanceUntilIdle()
        controller.submitBlikCode("123456")
        advanceUntilIdle()

        val state = controller.state.value as MobileCheckoutState.Failed
        assertEquals(MobileCheckoutError.Indeterminate, state.error)
        assertFalse(state.retryable)
        assertEquals(1, api.blikCalls)
    }

    @Test
    fun `a redirect channel moves to the gateway and does not poll yet`() = runTest {
        val api = FakeApi()
        val controller = controller(api, this)

        assertTrue(controller.selectChannel(transferChannel))
        advanceUntilIdle()

        val state = controller.state.value as MobileCheckoutState.Redirecting
        assertEquals(REDIRECT_URL, state.redirectUrl)
        assertEquals("mbank", api.lastRequest?.directChannel)
        assertEquals(0, api.statusCalls)
    }

    @Test
    fun `returning from the browser is not a payment`() = runTest {
        val api = FakeApi(
            statuses = mutableListOf(TransactionStatus.fromRaw("transaction_failure"))
        )
        val controller = controller(api, this)

        controller.selectChannel(transferChannel)
        advanceUntilIdle()

        controller.onReturnedFromBrowser()
        advanceUntilIdle()

        val state = controller.state.value as MobileCheckoutState.Failed
        assertEquals(MobileCheckoutError.TransactionFailed, state.error)
        assertEquals("transaction_failure", state.rawStatus)
        assertFalse(state.retryable)
    }

    @Test
    fun `selecting a channel twice never creates a second transaction`() = runTest {
        val api = FakeApi()
        val controller = controller(api, this)

        controller.selectChannel(transferChannel)
        advanceUntilIdle()

        assertFalse(controller.selectChannel(transferChannel))
        advanceUntilIdle()

        assertEquals(1, api.createCalls)
        assertTrue(controller.state.value is MobileCheckoutState.Redirecting)
    }

    @Test
    fun `an unknown status is reported as pending, never as success`() = runTest {
        val api = FakeApi(
            statuses = mutableListOf(TransactionStatus.fromRaw("transaction_something_new"))
        )
        val controller = controller(api, this)

        controller.selectChannel(transferChannel)
        advanceUntilIdle()
        controller.onReturnedFromBrowser()
        advanceUntilIdle()

        val state = controller.state.value as MobileCheckoutState.Pending
        assertEquals("transaction_something_new", state.rawStatus)
        assertTrue(controller.currentResult() is CheckoutResult.Pending)
    }

    @Test
    fun `fraud verification is neither success nor failure`() = runTest {
        val api = FakeApi(
            statuses = mutableListOf(TransactionStatus.fromRaw("transaction_fraud_possibility"))
        )
        val controller = controller(api, this)

        controller.selectChannel(transferChannel)
        advanceUntilIdle()
        controller.onReturnedFromBrowser()
        advanceUntilIdle()

        val state = controller.state.value as MobileCheckoutState.VerificationRequired
        assertEquals("transaction_fraud_possibility", state.rawStatus)

        val result = controller.currentResult() as CheckoutResult.Pending
        assertEquals(TX_ID, result.transactionId?.value)
    }

    @Test
    fun `an elapsed polling budget ends as pending`() = runTest {
        val api = FakeApi(
            statuses = mutableListOf(TransactionStatus.fromRaw("transaction_new"))
        )
        val controller = controller(
            api = api,
            scope = this,
            polling = PollingConfig(budgetMs = 10_000),
            timeSource = SteppingTime(4_000)
        )

        controller.selectChannel(transferChannel)
        advanceUntilIdle()
        controller.onReturnedFromBrowser()
        advanceUntilIdle()

        val state = controller.state.value as MobileCheckoutState.Pending
        assertEquals("transaction_new", state.rawStatus)
    }

    @Test
    fun `a direct channel without a payer ip fails before any transaction is created`() = runTest {
        val api = FakeApi()
        val controller = controller(
            api = api,
            scope = this,
            order = order.copy(payerIpOverride = null)
        )

        assertFalse(controller.selectChannel(transferChannel))
        advanceUntilIdle()

        val state = controller.state.value as MobileCheckoutState.Failed
        assertEquals(MobileCheckoutError.MissingPayerIp, state.error)
        assertFalse(state.retryable)
        assertEquals(0, api.createCalls)
    }

    @Test
    fun `the payer ip from the session takes precedence over the override`() = runTest {
        val api = FakeApi(channels = listOf(transferChannel), payerIp = "198.51.100.7")
        val controller = controller(api, this)

        controller.loadChannels()
        advanceUntilIdle()

        controller.selectChannel(transferChannel)
        advanceUntilIdle()

        assertEquals("198.51.100.7", api.lastRequest?.customer?.ip)
    }

    @Test
    fun `a failed creation is reported and leaves no transaction behind`() = runTest {
        val api = FakeApi(
            createError = SimPayApiException(
                error = MobileCheckoutError.ValidationFailed,
                httpCode = 422,
                message = "amount too low"
            )
        )
        val controller = controller(api, this)

        controller.selectChannel(transferChannel)
        advanceUntilIdle()

        val state = controller.state.value as MobileCheckoutState.Failed
        assertEquals(MobileCheckoutError.ValidationFailed, state.error)
        assertNull(controller.transactionId)
    }

    @Test
    fun `cancelling before a transaction exists reports cancelled`() = runTest {
        val controller = controller(FakeApi(channels = listOf(blikChannel)), this)

        controller.loadChannels()
        advanceUntilIdle()

        assertEquals(CheckoutResult.Cancelled, controller.currentResult())
    }

    @Test
    fun `cancelling after a transaction exists reports pending, never cancelled`() = runTest {
        val api = FakeApi()
        val controller = controller(api, this)

        controller.selectChannel(blikChannel)
        advanceUntilIdle()
        controller.submitBlikCode("123456")
        advanceUntilIdle()

        // The sheet is dismissed while the bank confirmation is still open.
        val result = controller.currentResult()
        assertTrue(result is CheckoutResult.Pending)
    }

    @Test
    fun `a restored transaction is never overwritten`() = runTest {
        val controller = controller(FakeApi(), this)

        controller.restoreTransaction(TransactionId(TX_ID))
        controller.restoreTransaction(TransactionId(OTHER_TX_ID))

        assertEquals(TX_ID, controller.transactionId?.value)
    }

    @Test
    fun `resuming a status check does not start a second polling loop`() = runTest {
        val api = FakeApi(
            statuses = mutableListOf(TransactionStatus.fromRaw("transaction_paid"))
        )
        val controller = controller(api, this)

        controller.selectChannel(transferChannel)
        advanceUntilIdle()

        assertTrue(controller.resumeStatusCheckIfNeeded())
        advanceUntilIdle()

        // The flow is finished, so nothing may be resumed again.
        assertFalse(controller.resumeStatusCheckIfNeeded())
        advanceUntilIdle()

        assertEquals(1, api.statusCalls)
    }

    private companion object {
        const val TX_ID = "11111111-2222-3333-4444-555555555555"
        const val OTHER_TX_ID = "99999999-8888-7777-6666-555555555555"
        const val REDIRECT_URL = "https://pay.example/tx/11111111"
        const val PAYER_IP = "203.0.113.10"
        const val DEVICE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}

