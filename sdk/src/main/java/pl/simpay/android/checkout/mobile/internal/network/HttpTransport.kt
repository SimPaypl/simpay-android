package pl.simpay.android.checkout.mobile.internal.network

/** Minimal HTTP response representation. */
internal data class HttpResponse(
    val code: Int,
    val body: String
)

/** Transport abstraction so the API client can be tested without real sockets. */
internal interface HttpTransport {
    fun post(url: String, headers: Map<String, String>, body: String): HttpResponse
    fun get(url: String, headers: Map<String, String>): HttpResponse
}
