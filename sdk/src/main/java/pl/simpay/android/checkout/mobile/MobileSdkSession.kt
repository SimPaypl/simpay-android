package pl.simpay.android.checkout.mobile

/**
 * Mobile SDK access session.
 *
 * Observed on the verified environment: the token is short lived (about 15 minutes) and
 * is bound to a single service, so a long checkout may need a fresh session.
 */
data class MobileSdkSession(
    /** Full token as issued by the API, including the "mobsdk_" prefix. */
    val token: String,
    val expiresAt: String? = null,
    /**
     * Public IP address of the payer as observed by the merchant backend when the app
     * requested the session.
     *
     * The API requires `customer.ip` for direct channels. A device cannot determine its
     * public IP reliably and must not send a local interface address, so the backend
     * supplies it here.
     */
    val payerIp: String? = null
) {
    /**
     * Authorization header value. The scheme is always "Bearer" and any `token_type`
     * reported by the server is ignored. The SDK never generates or signs tokens.
     */
    val authorizationHeaderValue: String
        get() = "Bearer $token"
}
