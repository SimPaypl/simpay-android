package pl.simpay.android.checkout.mobile.internal.ui

import android.content.Context
import android.os.Build
import android.webkit.WebSettings
import pl.simpay.android.checkout.mobile.internal.flow.MobileCheckoutController

/**
 * Resolves the User-Agent sent as `antifraud.useragent`.
 *
 * Direct channels are continued either in a Custom Tab or in the banking app, and the
 * provider's antifraud systems compare the declared agent with the client that actually
 * shows up. The WebView default agent is the closest match available on the device and
 * is the same string a browser on this device would report. It carries no account or
 * device identifier.
 */
internal object DeviceUserAgent {

    fun resolve(context: Context): String {
        // getDefaultUserAgent can throw when the WebView package is missing or being
        // updated, which must never block a payment.
        val agent = runCatching { WebSettings.getDefaultUserAgent(context) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

        return agent ?: fallback()
    }

    private fun fallback(): String =
        "${MobileCheckoutController.DEFAULT_USER_AGENT} Android/${Build.VERSION.RELEASE}"
}

