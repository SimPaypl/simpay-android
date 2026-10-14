package pl.simpay.android.checkout.mobile

/**
 * Mobile SDK API used by the checkout flow.
 *
 * The default implementation performs real HTTP calls. A fake implementation may be
 * supplied explicitly by an integrator or demo app, but the SDK never falls back to a
 * fake implementation when the real API fails.
 */
interface SimPayMobileApi {

    /** Obtains a session, normally from the merchant backend. */
    suspend fun createSession(): MobileSdkSession

    /** GET /payment/{serviceId}/mobile-sdk/channels */
    suspend fun getChannels(session: MobileSdkSession): List<PaymentChannel>

    /** POST /payment/{serviceId}/transactions */
    suspend fun createTransaction(
        session: MobileSdkSession,
        request: CreateTransactionRequest
    ): CreatedTransaction

    /**
     * POST /payment/{serviceId}/blik/level0/{transactionId}
     *
     * A successful call means only that a confirmation request was pushed to the
     * banking app. It never means the transaction was paid.
     */
    suspend fun submitBlikCode(
        session: MobileSdkSession,
        transactionId: TransactionId,
        code: BlikCode
    )

    /** GET /payment/{serviceId}/mobile-sdk/transactions/{transactionId} */
    suspend fun getTransactionStatus(
        session: MobileSdkSession,
        transactionId: TransactionId
    ): TransactionSnapshot
}
