package pl.simpay.android.checkout.mobile.internal.network

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.simpay.android.checkout.mobile.BlikCode
import pl.simpay.android.checkout.mobile.CreateTransactionRequest
import pl.simpay.android.checkout.mobile.CreatedTransaction
import pl.simpay.android.checkout.mobile.MobileCheckoutError
import pl.simpay.android.checkout.mobile.MobileSdkSession
import pl.simpay.android.checkout.mobile.MobileSessionProvider
import pl.simpay.android.checkout.mobile.PaymentChannel
import pl.simpay.android.checkout.mobile.SimPayApiException
import pl.simpay.android.checkout.mobile.SimPayMobileApi
import pl.simpay.android.checkout.mobile.SimPayMobileConfiguration
import pl.simpay.android.checkout.mobile.TransactionId
import pl.simpay.android.checkout.mobile.TransactionSnapshot
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * HTTP implementation of the mobile SDK API.
 *
 * The session comes from a [MobileSessionProvider], because the session endpoint
 * requires the merchant API key and that key must not live inside the app.
 *
 * Nothing sensitive is logged: no tokens, no BLIK codes, no request bodies.
 */
internal class HttpMobileSdkApi(
    private val configuration: SimPayMobileConfiguration,
    private val sessionProvider: MobileSessionProvider,
    private val transport: HttpTransport = UrlConnectionTransport(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : SimPayMobileApi {

    override suspend fun createSession(): MobileSdkSession = sessionProvider.provideSession()

    override suspend fun getChannels(session: MobileSdkSession): List<PaymentChannel> =
        withContext(ioDispatcher) {
            val response = safeCall(MobileCheckoutError.ChannelsFailed, isMutating = false) {
                transport.get(
                    url = MobileSdkEndpoints.channelsUrl(baseUrl, serviceId),
                    headers = authHeaders(session)
                )
            }

            ensureSuccess(response, MobileCheckoutError.ChannelsFailed)
            MobileSdkResponseParser.parseChannels(response.body)
        }

    override suspend fun createTransaction(
        session: MobileSdkSession,
        request: CreateTransactionRequest
    ): CreatedTransaction = withContext(ioDispatcher) {
        // Mutating call: a timeout must never be retried automatically, because the
        // server may already have created the transaction.
        val response = safeCall(MobileCheckoutError.TransactionFailed, isMutating = true) {
            transport.post(
                url = MobileSdkEndpoints.transactionsUrl(baseUrl, serviceId),
                headers = authHeaders(session),
                body = TransactionJsonFactory.createTransactionBody(request)
            )
        }

        ensureSuccess(response, MobileCheckoutError.TransactionFailed)
        MobileSdkResponseParser.parseCreatedTransaction(response.body)
    }

    override suspend fun submitBlikCode(
        session: MobileSdkSession,
        transactionId: TransactionId,
        code: BlikCode
    ) {
        withContext(ioDispatcher) {
            val response = safeCall(MobileCheckoutError.BlikRejected, isMutating = true) {
                transport.post(
                    url = MobileSdkEndpoints.blikLevel0Url(baseUrl, serviceId, transactionId.value),
                    headers = authHeaders(session),
                    body = TransactionJsonFactory.blikLevel0Body(code)
                )
            }

            // 204 means the confirmation was pushed to the banking app, nothing more.
            ensureSuccess(response, MobileCheckoutError.BlikRejected)
        }
    }

    override suspend fun getTransactionStatus(
        session: MobileSdkSession,
        transactionId: TransactionId
    ): TransactionSnapshot = withContext(ioDispatcher) {
        val response = safeCall(MobileCheckoutError.TransactionFailed, isMutating = false) {
            transport.get(
                url = MobileSdkEndpoints.transactionStatusUrl(baseUrl, serviceId, transactionId.value),
                headers = authHeaders(session)
            )
        }

        ensureSuccess(response, MobileCheckoutError.TransactionFailed)
        MobileSdkResponseParser.parseTransactionStatus(response.body, transactionId)
    }

    private val baseUrl: String get() = configuration.baseUrl
    private val serviceId: String get() = configuration.serviceId

    private fun authHeaders(session: MobileSdkSession): Map<String, String> =
        mapOf(MobileSdkEndpoints.authorizationHeader(session))

    private inline fun safeCall(
        failureError: MobileCheckoutError,
        isMutating: Boolean,
        block: () -> HttpResponse
    ): HttpResponse {
        return try {
            block()
        } catch (exception: UnknownHostException) {
            throw SimPayApiException(MobileCheckoutError.NoInternet, message = "Host unreachable", cause = exception)
        } catch (exception: ConnectException) {
            // Connection refused means the request never reached the server.
            throw SimPayApiException(MobileCheckoutError.NoInternet, message = "Connection failed", cause = exception)
        } catch (exception: SocketTimeoutException) {
            throw timeoutException(isMutating, exception)
        } catch (exception: InterruptedIOException) {
            throw timeoutException(isMutating, exception)
        } catch (exception: IOException) {
            throw SimPayApiException(failureError, message = "Network failure", cause = exception)
        }
    }

    private fun timeoutException(isMutating: Boolean, cause: Throwable): SimPayApiException {
        // For mutating calls the outcome is unknown: the server may have accepted it.
        val error = if (isMutating) MobileCheckoutError.Indeterminate else MobileCheckoutError.Timeout
        return SimPayApiException(
            error = error,
            message = if (isMutating) "Request outcome is unknown after timeout" else "Request timed out",
            cause = cause
        )
    }

    private fun ensureSuccess(response: HttpResponse, failureError: MobileCheckoutError) {
        if (response.code in 200..299) {
            return
        }

        val error = when (response.code) {
            400 -> MobileCheckoutError.ValidationFailed
            401, 403 -> MobileCheckoutError.Unauthorized
            404 -> MobileCheckoutError.TransactionNotFound
            409 -> MobileCheckoutError.BlikRejected
            422 -> MobileCheckoutError.ValidationFailed
            429 -> MobileCheckoutError.RateLimited
            else -> failureError
        }

        throw SimPayApiException(
            error = error,
            httpCode = response.code,
            // Field names only, never submitted values.
            message = TransactionJsonFactory.validationSummary(response.body)
                ?: "API responded with HTTP ${response.code}"
        )
    }
}
