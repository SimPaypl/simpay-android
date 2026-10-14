package pl.simpay.android.checkout.mobile.internal.network

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import pl.simpay.android.checkout.mobile.ChannelAmountRange
import pl.simpay.android.checkout.mobile.CreatedTransaction
import pl.simpay.android.checkout.mobile.MobileCheckoutError
import pl.simpay.android.checkout.mobile.PaymentChannel
import pl.simpay.android.checkout.mobile.SimPayApiException
import pl.simpay.android.checkout.mobile.TransactionId
import pl.simpay.android.checkout.mobile.TransactionSnapshot
import pl.simpay.android.checkout.mobile.TransactionStatus

/**
 * Maps raw JSON payloads of the mobile SDK API into SDK models.
 *
 * Only fields confirmed by the contract are read. Unknown fields are ignored so that
 * backend additions do not break the flow. The session payload is not parsed here: the
 * session is created by the merchant backend and handed to the SDK ready to use.
 */
internal object MobileSdkResponseParser {

    fun parseChannels(rawBody: String): List<PaymentChannel> {
        val root = parseObject(rawBody, MobileCheckoutError.ChannelsFailed)
        requireSuccessFlag(root, MobileCheckoutError.ChannelsFailed)

        val array = root.optJSONArray("data")
            ?: throw SimPayApiException(
                error = MobileCheckoutError.InvalidResponse,
                message = "Channels response is missing data list"
            )

        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let(::parseChannel)
        }
    }

    /** Parses the response of POST /payment/{serviceId}/transactions. */
    fun parseCreatedTransaction(rawBody: String): CreatedTransaction {
        val data = requireSuccessObject(rawBody, MobileCheckoutError.TransactionFailed)

        val transactionId = TransactionId.parseOrNull(data.optString("transactionId"))
            ?: throw SimPayApiException(
                error = MobileCheckoutError.InvalidResponse,
                message = "Transaction response is missing a valid transactionId"
            )

        val redirectUrl = data.optString("redirectUrl").orNullIfBlank()
            ?: throw SimPayApiException(
                error = MobileCheckoutError.InvalidResponse,
                message = "Transaction response is missing redirectUrl"
            )

        return CreatedTransaction(transactionId = transactionId, redirectUrl = redirectUrl)
    }

    /**
     * Parses the transaction status response.
     *
     * The raw status value is always preserved, and an unrecognised value never maps to
     * a successful outcome.
     */
    fun parseTransactionStatus(rawBody: String, fallbackId: TransactionId): TransactionSnapshot {
        val root = parseObject(rawBody, MobileCheckoutError.TransactionFailed)
        requireSuccessFlag(root, MobileCheckoutError.TransactionFailed)

        val data = root.optJSONObject("data")
            ?: throw SimPayApiException(
                error = MobileCheckoutError.InvalidResponse,
                message = "Status response is missing data object"
            )

        val raw = sequenceOf("status", "transactionStatus", "transaction_status")
            .mapNotNull { key -> data.optString(key).orNullIfBlank() }
            .firstOrNull()
            ?: throw SimPayApiException(
                error = MobileCheckoutError.InvalidResponse,
                message = "Status response is missing status"
            )

        val id = TransactionId.parseOrNull(data.optString("transactionId")) ?: fallbackId

        return TransactionSnapshot(
            transactionId = id,
            status = TransactionStatus.fromRaw(raw)
        )
    }

    private fun parseChannel(json: JSONObject): PaymentChannel? {
        val id = readRawScalar(json, "id").orNullIfBlank() ?: return null
        val name = json.optString("name").orNullIfBlank() ?: id

        return PaymentChannel(
            id = id,
            name = name,
            type = json.optString("type").orNullIfBlank(),
            img = json.optString("img").orNullIfBlank(),
            currencies = parseCurrencies(json.optJSONArray("currencies")),
            amount = parseAmount(json.optJSONObject("amount"))
        )
    }

    private fun parseCurrencies(array: JSONArray?): List<String> {
        if (array == null) {
            return emptyList()
        }
        return (0 until array.length())
            .mapNotNull { index -> array.optString(index).orNullIfBlank() }
    }

    private fun parseAmount(json: JSONObject?): ChannelAmountRange {
        if (json == null) {
            return ChannelAmountRange(min = null, max = null)
        }
        return ChannelAmountRange(
            min = readRawScalar(json, "min").orNullIfBlank(),
            max = readRawScalar(json, "max").orNullIfBlank()
        )
    }

    /** Reads a scalar as text so monetary values never pass through Float/Double. */
    private fun readRawScalar(json: JSONObject, key: String): String? {
        if (!json.has(key) || json.isNull(key)) {
            return null
        }
        return when (val value = json.opt(key)) {
            is String -> value
            null -> null
            else -> value.toString()
        }
    }

    private fun requireSuccessObject(
        rawBody: String,
        failureError: MobileCheckoutError
    ): JSONObject {
        val root = parseObject(rawBody, failureError)
        requireSuccessFlag(root, failureError)

        return root.optJSONObject("data")
            ?: throw SimPayApiException(
                error = MobileCheckoutError.InvalidResponse,
                message = "Response is missing data object"
            )
    }

    private fun requireSuccessFlag(root: JSONObject, failureError: MobileCheckoutError) {
        if (!root.optBoolean("success", false)) {
            throw SimPayApiException(
                error = failureError,
                message = "API reported an unsuccessful response"
            )
        }
    }

    private fun parseObject(rawBody: String, failureError: MobileCheckoutError): JSONObject {
        return try {
            JSONObject(rawBody)
        } catch (exception: JSONException) {
            throw SimPayApiException(
                error = MobileCheckoutError.InvalidResponse,
                message = "Malformed ${failureError.name} response",
                cause = exception
            )
        }
    }

    private fun String?.orNullIfBlank(): String? =
        this?.takeIf { it.isNotBlank() && it != "null" }
}

