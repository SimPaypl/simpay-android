package pl.simpay.android.checkout.mobile

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.simpay.android.checkout.mobile.internal.network.HttpMobileSdkApi
import pl.simpay.android.checkout.mobile.internal.network.HttpResponse
import pl.simpay.android.checkout.mobile.internal.network.HttpTransport
import pl.simpay.android.checkout.mobile.internal.network.MobileSdkEndpoints

class HttpMobileSdkApiTest {

    private val configuration = SimPayMobileConfiguration(
        serviceId = "a1b2c3d4",
        merchantDisplayName = "Example Store",
        baseUrl = "https://api.simpay.pl"
    )

    private class RecordingTransport(
        private val postResponse: HttpResponse = HttpResponse(200, ""),
        private val getResponse: HttpResponse = HttpResponse(200, "")
    ) : HttpTransport {
        var lastGetUrl: String? = null
        var lastGetHeaders: Map<String, String> = emptyMap()
        var lastPostUrl: String? = null
        var lastPostHeaders: Map<String, String> = emptyMap()
        var lastPostBody: String? = null

        override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse {
            lastPostUrl = url
            lastPostHeaders = headers
            lastPostBody = body
            return postResponse
        }

        override fun get(url: String, headers: Map<String, String>): HttpResponse {
            lastGetUrl = url
            lastGetHeaders = headers
            return getResponse
        }
    }

    private fun staticSession(token: String = "mobsdk_full.token.value") =
        object : MobileSessionProvider {
            override suspend fun provideSession() =
                MobileSdkSession(token = token, expiresAt = null)
        }

    @Test
    fun `public configuration always targets the production address`() {
        val published = SimPayMobileConfiguration(
            serviceId = "a1b2c3d4",
            merchantDisplayName = "Example Store"
        )

        assertEquals("https://api.simpay.pl", SimPayMobileConfiguration.PRODUCTION_BASE_URL)
        assertEquals("https://api.simpay.pl", published.baseUrl)
        assertEquals(
            "https://api.simpay.pl/payment/a1b2c3d4/mobile-sdk/channels",
            MobileSdkEndpoints.channelsUrl(published.baseUrl, published.serviceId)
        )
    }

    @Test
    fun `authorization header is always bearer regardless of server token type`() {
        val session = MobileSdkSession(token = "mobsdk_abc.def.ghi")

        assertEquals("Bearer mobsdk_abc.def.ghi", session.authorizationHeaderValue)
    }

    @Test
    fun `builds documented endpoint urls`() {
        assertEquals(
            "https://example-host/payment/a1b2c3d4/mobile-sdk/channels",
            MobileSdkEndpoints.channelsUrl("https://example-host/", "a1b2c3d4")
        )
        assertEquals(
            "https://example-host/payment/a1b2c3d4/transactions",
            MobileSdkEndpoints.transactionsUrl("https://example-host", "a1b2c3d4")
        )
        assertEquals(
            "https://example-host/payment/a1b2c3d4/blik/level0/tx-1",
            MobileSdkEndpoints.blikLevel0Url("https://example-host", "a1b2c3d4", "tx-1")
        )
        assertEquals(
            "https://example-host/payment/a1b2c3d4/mobile-sdk/transactions/tx-1",
            MobileSdkEndpoints.transactionStatusUrl("https://example-host", "a1b2c3d4", "tx-1")
        )
    }

    @Test
    fun `sends full bearer token to channels endpoint`() = runTest {
        val transport = RecordingTransport(
            getResponse = HttpResponse(
                code = 200,
                body = """{"success":true,"data":[{"id":"blik","name":"BLIK"}]}"""
            )
        )
        val api = HttpMobileSdkApi(configuration, staticSession(), transport)

        val session = api.createSession()
        val channels = api.getChannels(session)

        assertEquals("Bearer mobsdk_full.token.value", transport.lastGetHeaders["Authorization"])
        assertEquals(
            "https://api.simpay.pl/payment/a1b2c3d4/mobile-sdk/channels",
            transport.lastGetUrl
        )
        assertEquals("blik", channels.single().id)
    }

    @Test
    fun `unauthorized channels response maps to unauthorized`() = runTest {
        val transport = RecordingTransport(getResponse = HttpResponse(code = 401, body = ""))
        val api = HttpMobileSdkApi(configuration, staticSession(), transport)
        val session = api.createSession()

        val exception = runCatching { api.getChannels(session) }.exceptionOrNull() as SimPayApiException

        assertEquals(MobileCheckoutError.Unauthorized, exception.error)
    }

    @Test
    fun `server error on channels maps to channels failure`() = runTest {
        val transport = RecordingTransport(getResponse = HttpResponse(code = 500, body = ""))
        val api = HttpMobileSdkApi(configuration, staticSession(), transport)
        val session = api.createSession()

        val exception = runCatching { api.getChannels(session) }.exceptionOrNull() as SimPayApiException

        assertEquals(MobileCheckoutError.ChannelsFailed, exception.error)
        assertEquals(500, exception.httpCode)
    }

    @Test
    fun `session comes from the provider and never from an api key`() = runTest {
        val transport = RecordingTransport()
        val api = HttpMobileSdkApi(configuration, staticSession("mobsdk_from_backend"), transport)

        val session = api.createSession()

        // The SDK must not call the session endpoint itself.
        assertNull(transport.lastPostUrl)
        assertEquals("mobsdk_from_backend", session.token)
        assertTrue(session.authorizationHeaderValue.startsWith("Bearer mobsdk_"))
    }
}



