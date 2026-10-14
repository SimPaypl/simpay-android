package pl.simpay.android.checkout.mobile.internal.network

import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * HttpURLConnection based transport. Chosen to avoid adding a new networking
 * dependency for this stage. Request and response bodies are never logged.
 */
internal class UrlConnectionTransport(
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS
) : HttpTransport {

    override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse {
        return execute(url = url, method = "POST", headers = headers, body = body)
    }

    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        return execute(url = url, method = "GET", headers = headers, body = null)
    }

    private fun execute(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?
    ): HttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            // Explicit agent: some edge protections reject default runtime agents.
            setRequestProperty("User-Agent", USER_AGENT)
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }

        return try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { stream ->
                    stream.write(body.toByteArray(Charsets.UTF_8))
                }
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val payload = stream?.bufferedReader(Charsets.UTF_8)?.use(BufferedReader::readText).orEmpty()

            HttpResponse(code = code, body = payload)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000
        const val DEFAULT_READ_TIMEOUT_MS = 20_000
        const val USER_AGENT = "SimPayAndroidSDK"
    }
}
