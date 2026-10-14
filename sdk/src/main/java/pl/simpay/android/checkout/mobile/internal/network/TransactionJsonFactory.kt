package pl.simpay.android.checkout.mobile.internal.network

import org.json.JSONArray
import org.json.JSONObject
import pl.simpay.android.checkout.mobile.BlikCode
import pl.simpay.android.checkout.mobile.CreateTransactionRequest

/**
 * Builds JSON payloads for the transaction endpoints.
 *
 * Kept separate from the public models so the JSON shape can change without touching
 * the SDK API surface.
 */
internal object TransactionJsonFactory {

    /** Body for POST /payment/{serviceId}/transactions. */
    fun createTransactionBody(request: CreateTransactionRequest): String {
        val json = JSONObject()

        // The contract requires a JSON number. BigDecimal keeps the decimal value exact
        // and JSONObject serialises it without binary floating point conversion.
        json.put("amount", request.amount)
        json.put("currency", request.currency)

        request.description?.let { json.put("description", it) }
        request.control?.let { json.put("control", it) }
        request.directChannel?.let { json.put("directChannel", it) }

        request.customer?.let { customer ->
            // Direct channels require email, ip and countryCode together; the request
            // model guarantees they are present before we get here.
            json.put(
                "customer",
                JSONObject().apply {
                    put("email", customer.email)
                    put("ip", customer.ip)
                    customer.name?.let { put("name", it) }
                    customer.countryCode?.let { put("countryCode", it) }
                }
            )
        }

        request.antifraud?.let { antifraud ->
            // Field name is lowercase "useragent", as required by the API.
            json.put("antifraud", JSONObject().apply { put("useragent", antifraud.userAgent) })
        }

        request.returns?.let { returns ->
            json.put(
                "returns",
                JSONObject().apply {
                    put("success", returns.success)
                    put("failure", returns.failure)
                }
            )
        }

        return json.toString()
    }

    /**
     * Body for POST /payment/{serviceId}/blik/level0/{transactionId}.
     *
     * The key casing is significant: the API rejects a lowercase "t6".
     */
    fun blikLevel0Body(code: BlikCode): String =
        JSONObject()
            .put("ticket", JSONObject().put("T6", code.value))
            .toString()

    /** Reads field level validation messages, used for diagnostics without secrets. */
    fun validationSummary(rawBody: String): String? {
        val errors = runCatching { JSONObject(rawBody).optJSONObject("errors") }.getOrNull()
            ?: return runCatching { JSONObject(rawBody).optString("message") }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }

        val fields = errors.keys().asSequence().toList()
        if (fields.isEmpty()) {
            return null
        }
        return fields.joinToString(", ") { field ->
            val messages = errors.opt(field)
            val first = when (messages) {
                is JSONArray -> messages.optString(0)
                else -> messages?.toString().orEmpty()
            }
            "$field: $first"
        }
    }
}
