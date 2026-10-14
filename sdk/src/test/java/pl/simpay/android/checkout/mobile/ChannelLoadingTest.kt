package pl.simpay.android.checkout.mobile

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.simpay.android.checkout.mobile.internal.flow.BlikLevel0
import pl.simpay.android.checkout.mobile.internal.flow.MobileCheckoutController
import pl.simpay.android.checkout.mobile.internal.flow.MobileCheckoutState
import java.math.BigDecimal

/**
 * Channel loading rules: the session is fetched first, one load runs at a time, and a
 * failure is always surfaced instead of showing an empty list as if it were a result.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelLoadingTest {

    private val channel = PaymentChannel(
        id = "blik",
        name = "BLIK",
        type = "blik",
        img = null,
        currencies = listOf("PLN"),
        amount = ChannelAmountRange(min = "1.00", max = "500.00")
    )

    private val order = TransactionOrder(
        amount = BigDecimal("1.00"),
        customerEmail = "payer@example.com",
        payerIpOverride = "203.0.113.10"
    )

    private class FakeApi(
        var sessionError: Throwable? = null,
        var channelsError: Throwable? = null,
        var channels: List<PaymentChannel> = emptyList(),
        val gate: CompletableDeferred<Unit>? = null
    ) : SimPayMobileApi {
        var sessionCalls = 0
        var channelCalls = 0
        var lastSession: MobileSdkSession? = null

        override suspend fun createSession(): MobileSdkSession {
            sessionCalls++
            gate?.await()
            sessionError?.let { throw it }
            return MobileSdkSession("mobsdk_test")
        }

        override suspend fun getChannels(session: MobileSdkSession): List<PaymentChannel> {
            channelCalls++
            lastSession = session
            channelsError?.let { throw it }
            return channels
        }

        // Only channel loading is exercised here.
        override suspend fun createTransaction(
            session: MobileSdkSession,
            request: CreateTransactionRequest
        ): CreatedTransaction = throw UnsupportedOperationException("not used in this test")

        override suspend fun submitBlikCode(
            session: MobileSdkSession,
            transactionId: TransactionId,
            code: BlikCode
        ): Unit = throw UnsupportedOperationException("not used in this test")

        override suspend fun getTransactionStatus(
            session: MobileSdkSession,
            transactionId: TransactionId
        ): TransactionSnapshot = throw UnsupportedOperationException("not used in this test")
    }

    private fun controller(api: FakeApi, scope: kotlinx.coroutines.CoroutineScope) =
        MobileCheckoutController(api = api, order = order, scope = scope)

    @Test
    fun `loads channels with the full bearer token`() = runTest {
        val api = FakeApi(channels = listOf(channel))
        val controller = controller(api, this)

        controller.loadChannels()
        advanceUntilIdle()

        val state = controller.state.value as MobileCheckoutState.Channels
        assertEquals(listOf(channel), state.channels)
        assertEquals("Bearer mobsdk_test", api.lastSession?.authorizationHeaderValue)
    }

    @Test
    fun `an empty channel list is a result, not a failure`() = runTest {
        val api = FakeApi(channels = emptyList())
        val controller = controller(api, this)

        controller.loadChannels()
        advanceUntilIdle()

        val state = controller.state.value as MobileCheckoutState.Channels
        assertTrue(state.channels.isEmpty())
    }

    @Test
    fun `a session failure is surfaced without calling channels`() = runTest {
        val api = FakeApi(sessionError = SimPayApiException(MobileCheckoutError.SessionFailed))
        val controller = controller(api, this)

        controller.loadChannels()
        advanceUntilIdle()

        val state = controller.state.value as MobileCheckoutState.Failed
        assertEquals(MobileCheckoutError.SessionFailed, state.error)
        assertEquals(0, api.channelCalls)
    }

    @Test
    fun `a channels failure is surfaced`() = runTest {
        val api = FakeApi(channelsError = SimPayApiException(MobileCheckoutError.ChannelsFailed))
        val controller = controller(api, this)

        controller.loadChannels()
        advanceUntilIdle()

        val state = controller.state.value as MobileCheckoutState.Failed
        assertEquals(MobileCheckoutError.ChannelsFailed, state.error)
    }

    @Test
    fun `an unexpected error maps to internal`() = runTest {
        val api = FakeApi(sessionError = IllegalStateException("boom"))
        val controller = controller(api, this)

        controller.loadChannels()
        advanceUntilIdle()

        val state = controller.state.value as MobileCheckoutState.Failed
        assertEquals(MobileCheckoutError.Internal, state.error)
    }

    @Test
    fun `repeated taps do not start parallel loads`() {
        val scope = TestScope(StandardTestDispatcher())
        val gate = CompletableDeferred<Unit>()
        val api = FakeApi(channels = listOf(channel), gate = gate)
        val controller = MobileCheckoutController(api = api, order = order, scope = scope)

        assertTrue(controller.loadChannels())
        scope.advanceUntilIdle()

        assertFalse(controller.loadChannels())
        assertFalse(controller.loadChannels())
        scope.advanceUntilIdle()

        gate.complete(Unit)
        scope.advanceUntilIdle()

        assertEquals(1, api.sessionCalls)
        assertEquals(1, api.channelCalls)
        assertEquals(listOf(channel), (controller.state.value as MobileCheckoutState.Channels).channels)
    }

    @Test
    fun `retrying after a failure reloads`() = runTest {
        val api = FakeApi(sessionError = SimPayApiException(MobileCheckoutError.SessionFailed))
        val controller = controller(api, this)

        controller.loadChannels()
        advanceUntilIdle()
        assertTrue(controller.state.value is MobileCheckoutState.Failed)

        api.sessionError = null
        api.channels = listOf(channel)

        assertTrue(controller.loadChannels(force = true))
        advanceUntilIdle()

        assertTrue(controller.state.value is MobileCheckoutState.Channels)
        assertEquals(2, api.sessionCalls)
    }

    @Test
    fun `recreating the sheet does not refetch loaded channels`() = runTest {
        val api = FakeApi(channels = listOf(channel))
        val controller = controller(api, this)

        controller.loadChannelsIfNeeded()
        advanceUntilIdle()

        // Simulates the view being recreated after a rotation.
        assertFalse(controller.loadChannelsIfNeeded())
        advanceUntilIdle()

        assertEquals(1, api.sessionCalls)
        assertEquals(1, api.channelCalls)
    }

    @Test
    fun `the two blik entries are collapsed into the level0 one`() = runTest {
        val redirectBlik = channel.copy(id = "blik", name = "BLIK")
        val level0Blik = channel.copy(id = "blik-level0", name = "BLIK Level 0")
        val transfer = channel.copy(id = "mbank", name = "mBank", type = "transfer")

        val api = FakeApi(channels = listOf(redirectBlik, transfer, level0Blik))
        val controller = controller(api, this)

        controller.loadChannels()
        advanceUntilIdle()

        val shown = (controller.state.value as MobileCheckoutState.Channels).channels
        assertEquals(listOf("blik-level0", "mbank"), shown.map { it.id })
    }

    @Test
    fun `a service without level0 keeps its plain blik entry`() = runTest {
        val redirectBlik = channel.copy(id = "blik", name = "BLIK")
        val transfer = channel.copy(id = "mbank", name = "mBank", type = "transfer")

        val api = FakeApi(channels = listOf(redirectBlik, transfer))
        val controller = controller(api, this)

        controller.loadChannels()
        advanceUntilIdle()

        val shown = (controller.state.value as MobileCheckoutState.Channels).channels
        assertEquals(listOf("blik", "mbank"), shown.map { it.id })
    }

    @Test
    fun `collapsing keeps every non blik channel untouched`() {
        val channels = listOf(
            channel.copy(id = "blik", name = "BLIK"),
            channel.copy(id = "mbank", name = "mBank", type = "transfer"),
            channel.copy(id = "blik-level0", name = "BLIK Level 0"),
            channel.copy(id = "card", name = "Card", type = "cards")
        )

        val collapsed = BlikLevel0.collapseDuplicates(channels)

        assertEquals(listOf("mbank", "card"), collapsed.filterNot(BlikLevel0::supports).map { it.id })
        assertEquals(1, collapsed.count(BlikLevel0::supports))
    }
}
