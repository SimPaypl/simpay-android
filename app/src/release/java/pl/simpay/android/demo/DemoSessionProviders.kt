package pl.simpay.android.demo

import pl.simpay.android.checkout.mobile.MobileSessionProvider
import pl.simpay.android.checkout.mobile.SimPayMobileConfiguration

/**
 * Release variant: the demo never accepts a merchant API key.
 * The session must come from the merchant backend.
 */
object DemoSessionProviders {

    const val SUPPORTS_API_KEY_MODE = false

    fun apiKeySessionProvider(
        baseUrl: String,
        serviceId: String,
        apiKey: String
    ): MobileSessionProvider = throw UnsupportedOperationException(
        "API key mode is not available in release builds"
    )

    fun configuration(
        serviceId: String,
        merchantDisplayName: String
    ): SimPayMobileConfiguration = SimPayMobileConfiguration(
        serviceId = serviceId,
        merchantDisplayName = merchantDisplayName
    )
}
