package pl.simpay.android.demo.debug

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import pl.simpay.android.checkout.mobile.MobileCheckoutError
import pl.simpay.android.checkout.mobile.MobileSdkSession
import pl.simpay.android.checkout.mobile.MobileSessionProvider
import pl.simpay.android.checkout.mobile.SimPayApiException
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * DEBUG BUILDS ONLY.
 *
 * Creates a mobile SDK session straight from the app using the merchant API key.
 * It lives in the debug source set so it can never reach a release artifact, and it is
 * intentionally not part of the SDK.
 *
 * Shipping a merchant API key inside an application exposes it to anyone who inspects
 * the APK. Production apps must obtain the token from the merchant backend.
 *
 * Nothing here is logged: neither the API key nor the issued token.
 */
class DebugApiKeySessionProvider(
    private val baseUrl: String,
    private val serviceId: String,
    private val apiKey: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : MobileSessionProvider {

    override suspend fun provideSession(): MobileSdkSession = withContext(ioDispatcher) {
        val url = "${baseUrl.trimEnd('/')}/payment/$serviceId/mobile-sdk/session"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "SimPayAndroidSDK-demo")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }

        try {
            connection.outputStream.use { stream -> stream.write("{}".toByteArray()) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()

            if (code !in 200..299) {
                throw SimPayApiException(
                    error = if (code == 401 || code == 403) {
                        MobileCheckoutError.Unauthorized
                    } else {
                        MobileCheckoutError.SessionFailed
                    },
                    httpCode = code,
                    message = "Session endpoint responded with HTTP $code"
                )
            }

            val data = JSONObject(body).optJSONObject("data")
                ?: throw SimPayApiException(
                    error = MobileCheckoutError.InvalidResponse,
                    message = "Session response is missing data"
                )

            MobileSdkSession(
                token = data.optString("token"),
                expiresAt = data.optString("expires_at").takeIf { it.isNotBlank() }
            )
        } finally {
            connection.disconnect()
        }
    }
}
