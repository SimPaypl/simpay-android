package pl.simpay.android.checkout.mobile.internal.network

import pl.simpay.android.checkout.mobile.MobileSdkSession

/**
 * URLs of the mobile SDK HTTP surface.
 *
 * Kept free of Android APIs so they can be unit tested. The session endpoint is absent
 * on purpose: it needs the merchant API key and is called by the merchant backend.
 */
internal object MobileSdkEndpoints {


    fun channelsUrl(baseUrl: String, serviceId: String): String =
        "${baseUrl.trimEnd('/')}/payment/$serviceId/mobile-sdk/channels"

    fun transactionsUrl(baseUrl: String, serviceId: String): String =
        "${baseUrl.trimEnd('/')}/payment/$serviceId/transactions"

    fun blikLevel0Url(baseUrl: String, serviceId: String, transactionId: String): String =
        "${baseUrl.trimEnd('/')}/payment/$serviceId/blik/level0/$transactionId"

    fun transactionStatusUrl(baseUrl: String, serviceId: String, transactionId: String): String =
        "${baseUrl.trimEnd('/')}/payment/$serviceId/mobile-sdk/transactions/$transactionId"

    /** The token is forwarded in full, exactly as issued by the API. */
    fun authorizationHeader(session: MobileSdkSession): Pair<String, String> =
        "Authorization" to session.authorizationHeaderValue
}
