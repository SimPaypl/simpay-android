package pl.simpay.android.checkout.mobile.debug

import pl.simpay.android.checkout.mobile.SimPayMobileConfiguration

/**
 * Points the SDK at a non production address.
 *
 * This file lives in the `debug` source set of the library, so it is compiled only into
 * the debug variant and is physically absent from the published release AAR. Nothing
 * here changes how requests are authorized: the header is always `Bearer <token>` built
 * from the session supplied by the integrator.
 */
object  SimPayDebugEnvironment {

    fun configuration(
        serviceId: String,
        merchantDisplayName: String,
        baseUrl: String
    ): SimPayMobileConfiguration = SimPayMobileConfiguration(
        serviceId = serviceId,
        merchantDisplayName = merchantDisplayName,
        baseUrl = baseUrl
    )
}

