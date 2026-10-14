package pl.simpay.android.checkout.mobile.internal.network

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pl.simpay.android.checkout.mobile.MobileCheckoutError
import pl.simpay.android.checkout.mobile.MobileSdkSession
import pl.simpay.android.checkout.mobile.SimPayApiException
import pl.simpay.android.checkout.mobile.SimPayMobileApi

/**
 * Holds the current session and refreshes it at most once per failed call.
 *
 * The session token is short lived (about 15 minutes on the verified environment), so a
 * long checkout can outlive it. Refreshing is limited to a single attempt so a broken
 * provider cannot cause a refresh loop.
 */
internal class SessionHolder(private val api: SimPayMobileApi) {

    private val mutex = Mutex()
    private var current: MobileSdkSession? = null

    suspend fun session(): MobileSdkSession = mutex.withLock {
        current ?: api.createSession().also { current = it }
    }

    suspend fun refresh(): MobileSdkSession = mutex.withLock {
        api.createSession().also { current = it }
    }

    fun peek(): MobileSdkSession? = current

    /**
     * Runs [block] with a valid session. On HTTP 401 the session is refreshed once and
     * the call is repeated.
     *
     * [retryAfterRefresh] must be false for requests whose outcome cannot be determined
     * after a failure, so the SDK never repeats a request the server may have accepted.
     */
    suspend fun <T> withSession(
        retryAfterRefresh: Boolean = true,
        block: suspend (MobileSdkSession) -> T
    ): T {
        val session = session()
        return try {
            block(session)
        } catch (exception: SimPayApiException) {
            val canRetry = retryAfterRefresh &&
                exception.error == MobileCheckoutError.Unauthorized &&
                exception.httpCode == 401

            if (!canRetry) {
                throw exception
            }
            // Exactly one refresh attempt.
            block(refresh())
        }
    }
}

