package pl.simpay.android.checkout.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.simpay.android.checkout.mobile.internal.network.MobileSdkResponseParser

class MobileSdkResponseParserTest {


    @Test
    fun `parses channels example payload`() {
        val body = """
            {
              "success": true,
              "data": [
                {
                  "id": "blik",
                  "name": "BLIK",
                  "type": "blik",
                  "img": "https://example-host/img/blik.png",
                  "currencies": ["PLN"],
                  "amount": { "min": "1.00", "max": "5000.00" }
                },
                {
                  "id": 12,
                  "name": "Bank transfer",
                  "type": "pbl",
                  "img": null,
                  "currencies": ["PLN", "EUR"],
                  "amount": { "min": 2, "max": 100 }
                }
              ]
            }
        """.trimIndent()

        val channels = MobileSdkResponseParser.parseChannels(body)

        assertEquals(2, channels.size)

        val blik = channels[0]
        assertEquals("blik", blik.id)
        assertEquals("BLIK", blik.name)
        assertEquals("blik", blik.type)
        assertEquals("https://example-host/img/blik.png", blik.img)
        assertEquals(listOf("PLN"), blik.currencies)
        assertEquals("1.00", blik.amount.min)
        assertEquals("5000.00", blik.amount.max)

        val transfer = channels[1]
        assertEquals("12", transfer.id)
        assertNull(transfer.img)
        assertEquals(listOf("PLN", "EUR"), transfer.currencies)
        assertEquals("2", transfer.amount.min)
        assertEquals("100", transfer.amount.max)
    }

    @Test
    fun `parses empty channel list`() {
        val channels = MobileSdkResponseParser.parseChannels("""{"success":true,"data":[]}""")

        assertTrue(channels.isEmpty())
    }

    @Test
    fun `channel without amount falls back to null range`() {
        val body = """{"success":true,"data":[{"id":"card","name":"Card","currencies":["PLN"]}]}"""

        val channel = MobileSdkResponseParser.parseChannels(body).single()

        assertNull(channel.amount.min)
        assertNull(channel.amount.max)
        assertNull(channel.type)
    }

    @Test
    fun `unsuccessful channels response maps to channels failure`() {
        val exception = runCatching {
            MobileSdkResponseParser.parseChannels("""{"success":false}""")
        }.exceptionOrNull() as SimPayApiException

        assertEquals(MobileCheckoutError.ChannelsFailed, exception.error)
    }

    @Test
    fun `a transaction response without a redirect url maps to invalid response`() {
        val exception = runCatching {
            MobileSdkResponseParser.parseCreatedTransaction(
                """{"success":true,"data":{"transactionId":"11111111-2222-3333-4444-555555555555"}}"""
            )
        }.exceptionOrNull() as SimPayApiException

        assertEquals(MobileCheckoutError.InvalidResponse, exception.error)
    }

    @Test
    fun `malformed body maps to invalid response`() {
        val exception = runCatching {
            MobileSdkResponseParser.parseChannels("not-json")
        }.exceptionOrNull() as SimPayApiException

        assertEquals(MobileCheckoutError.InvalidResponse, exception.error)
    }
}

