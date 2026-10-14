package pl.simpay.android.checkout.mobile

/**
 * Supplies a mobile SDK access session.
 *
 * The SDK deliberately provides no implementation that accepts a merchant API key.
 */
interface MobileSessionProvider {

    /** Returns a usable session. Implementations should return a fresh token when needed. */
    suspend fun provideSession(): MobileSdkSession
}
