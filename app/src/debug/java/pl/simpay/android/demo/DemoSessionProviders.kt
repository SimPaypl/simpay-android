package pl.simpay.android.demo

import pl.simpay.android.checkout.mobile.MobileSessionProvider
import pl.simpay.android.checkout.mobile.SimPayMobileConfiguration
import pl.simpay.android.demo.debug.DebugApiKeySessionProvider

/**
 * Debug variant: allows creating the session in the app with a merchant API key,
 * purely so the flow can be exercised against a test environment.
 */
object DemoSessionProviders {

    const val SUPPORTS_API_KEY_MODE = true

    fun apiKeySessionProvider(
        baseUrl: String,
        serviceId: String,
        apiKey: String
    ): MobileSessionProvider = DebugApiKeySessionProvider(
        baseUrl = baseUrl,
        serviceId = serviceId,
        apiKey = apiKey
    )

    fun configuration(
        serviceId: String,
        merchantDisplayName: String
    ): SimPayMobileConfiguration = SimPayMobileConfiguration(
        serviceId = serviceId,
        merchantDisplayName = merchantDisplayName
    )
}
