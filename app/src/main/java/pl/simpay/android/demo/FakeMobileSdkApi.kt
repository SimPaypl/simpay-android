package pl.simpay.android.demo

import kotlinx.coroutines.delay
import pl.simpay.android.checkout.mobile.BlikCode
import pl.simpay.android.checkout.mobile.ChannelAmountRange
import pl.simpay.android.checkout.mobile.CreateTransactionRequest
import pl.simpay.android.checkout.mobile.CreatedTransaction
import pl.simpay.android.checkout.mobile.MobileSdkSession
import pl.simpay.android.checkout.mobile.MobileSessionProvider
import pl.simpay.android.checkout.mobile.PaymentChannel
import pl.simpay.android.checkout.mobile.SimPayMobileApi
import pl.simpay.android.checkout.mobile.TransactionId
import pl.simpay.android.checkout.mobile.TransactionSnapshot
import pl.simpay.android.checkout.mobile.TransactionStatus
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * DEMO ONLY. Fully local stand-in so the demo runs without any credentials.
 *
 * It always walks the happy path. The SDK never activates it as a fallback when the
 * real API fails, so a successful run here proves nothing about a real integration.
 * It holds no API key and performs no network calls.
 */
class FakeMobileSdkApi(
    private val delayMs: Long = 700
) : SimPayMobileApi, MobileSessionProvider {

    private val statusCalls = AtomicInteger(0)

    override suspend fun provideSession(): MobileSdkSession = createSession()

    override suspend fun createSession(): MobileSdkSession {
        delay(delayMs)
        return MobileSdkSession(
            token = "mobsdk_demo_local_token",
            expiresAt = null,
            // A real backend reports the payer IP it observed.
            payerIp = "203.0.113.10"
        )
    }

    override suspend fun getChannels(session: MobileSdkSession): List<PaymentChannel> {
        delay(delayMs)
        return CHANNELS
    }

    override suspend fun createTransaction(
        session: MobileSdkSession,
        request: CreateTransactionRequest
    ): CreatedTransaction {
        delay(delayMs)
        val id = TransactionId(UUID.randomUUID().toString())
        return CreatedTransaction(
            transactionId = id,
            // Kept in-app so the demo never leaves for an external site.
            redirectUrl = "simpay-demo://payment-return?transactionId=${id.value}"
        )
    }

    override suspend fun submitBlikCode(
        session: MobileSdkSession,
        transactionId: TransactionId,
        code: BlikCode
    ) {
        delay(delayMs)
        // Success here only means the bank was asked to confirm.
    }

    override suspend fun getTransactionStatus(
        session: MobileSdkSession,
        transactionId: TransactionId
    ): TransactionSnapshot {
        delay(delayMs / 2)
        // Pending for the first polls, so the waiting state stays visible.
        val raw = if (statusCalls.incrementAndGet() >= 3) {
            "transaction_paid"
        } else {
            "transaction_confirmed"
        }
        return TransactionSnapshot(
            transactionId = transactionId,
            status = TransactionStatus.fromRaw(raw)
        )
    }

    private companion object {
        private const val BANKS = "https://img.simpay.pl/transfer/banks"
        private const val PROVIDERS = "https://cdn.simpay.pl/payment_providers"

        private val CHANNELS = listOf(
            channel("blik", "BLIK", "blik", "$PROVIDERS/blik/blik-normal.svg", "20000.00"),
            channel("mbank", "mBank", "transfer", "$BANKS/mbank.png"),
            channel("pko_bp", "PKO BP", "transfer", "$BANKS/pkobp.png"),
            channel("ing", "ING", "transfer", "$BANKS/ing.png"),
            channel("pekao_sa", "Pekao SA", "transfer", "$BANKS/pekao_sa.png"),
            channel("santander", "Erste Bank Polska", "transfer", "$PROVIDERS/pbl/erste.png"),
            channel("alior", "Alior Bank", "transfer", "$BANKS/alior.png"),
            channel("millennium", "Bank Millennium", "transfer", "$BANKS/millennium.png"),
            channel("bnp", "BNP Paribas", "transfer", "$BANKS/bnpp.png"),
            channel("pocztowy", "Bank Pocztowy", "transfer", "$BANKS/pocztowy.png"),
            channel("citi", "Citi Bank Handlowy", "transfer", "$BANKS/citi.png"),
            channel("agricole", "Crédit Agricole", "transfer", "$BANKS/agricole.png"),
            channel("nest", "Nest Bank", "transfer", "$BANKS/nest.png"),
            channel("plus", "Plus Bank", "transfer", "$BANKS/plus.png"),
            channel("velo", "VeloBank", "transfer", "$BANKS/velo.png"),
            channel("bos", "Bank Ochrony Środowiska", "transfer", "$BANKS/bos.png"),
            channel("bfg", "Bank Nowy BFG", "transfer", "$BANKS/bfg.png"),
            channel("inteligo", "Inteligo", "transfer", "$BANKS/inteligo.png"),
            channel("spoldzielcze", "Bank Spółdzielczy", "transfer", "$BANKS/spoldzielcze.png"),
            channel("blik-level0", "BLIK Level 0", "blik", "$BANKS/blik.png", "20000.00")
        )

        private fun channel(
            id: String,
            name: String,
            type: String,
            img: String,
            max: String = "50000.00"
        ) = PaymentChannel(
            id = id,
            name = name,
            type = type,
            img = img,
            currencies = listOf("PLN"),
            amount = ChannelAmountRange(min = "1.00", max = max)
        )
    }
}

