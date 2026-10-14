package pl.simpay.android.checkout.mobile

import java.math.BigDecimal

/** Identifier of a SimPay transaction (UUID, 36 characters). */
@JvmInline
value class TransactionId(val value: String) {
    init {
        require(UUID_PATTERN.matches(value)) { "transactionId must be a UUID" }
    }

    override fun toString(): String = value

    companion object {
        private val UUID_PATTERN =
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        fun parseOrNull(value: String?): TransactionId? =
            value?.takeIf { UUID_PATTERN.matches(it) }?.let(::TransactionId)
    }
}

/**
 * Six digit BLIK code.
 *
 * The value is never logged and is not included in [toString].
 */
@JvmInline
value class BlikCode private constructor(val value: String) {

    override fun toString(): String = "BlikCode(******)"

    companion object {
        private val SIX_DIGITS = Regex("^\\d{6}$")

        fun isValid(raw: String): Boolean = SIX_DIGITS.matches(raw)

        /** Returns null instead of throwing so UI can validate while typing. */
        fun parseOrNull(raw: String): BlikCode? =
            raw.takeIf(::isValid)?.let(::BlikCode)
    }
}

/**
 * Payer information required by the API when a direct channel is used.
 *
 * For `directChannel` transactions the API requires `email`, `ip` and `countryCode`
 * together; payment providers reject transactions built from incomplete or obviously
 * wrong payer data, for example a server IP instead of the payer one.
 */
data class TransactionCustomer(
    val email: String,
    /** Public address of the payer, as observed by the merchant backend. */
    val ip: String,
    val name: String? = null,
    /** ISO 3166-1 alpha-2, uppercase. Must match the transaction currency. */
    val countryCode: String? = null
) {
    init {
        require(email.isNotBlank()) { "customer.email must not be blank" }
        require(ip.isNotBlank()) { "customer.ip must not be blank" }
        require(countryCode == null || COUNTRY_CODE_PATTERN.matches(countryCode)) {
            "customer.countryCode must be two uppercase letters (ISO 3166-1 alpha-2)"
        }
    }

    companion object {
        private val COUNTRY_CODE_PATTERN = Regex("^[A-Z]{2}$")
    }
}

/**
 * Antifraud information sent as `antifraud.useragent`.
 *
 * The API expects the User-Agent of the client that continues the payment. A missing or
 * placeholder value can get the transaction rejected by the provider, so the SDK reads
 * the real device agent instead of hardcoding one. Contains no personal data.
 */
data class TransactionAntifraud(
    val userAgent: String
) {
    init {
        require(userAgent.isNotBlank()) { "antifraud.useragent must not be blank" }
    }
}

/**
 * Return URLs used by the payment gateway.
 *
 * Their scheme has to be registered in **three** places with the same value:
 * the "Mobilne SDK" tab of the SimPay merchant panel, the `<data android:scheme>` of the
 * intent filter in `AndroidManifest.xml`, and here. The gateway redirects only to a
 * scheme it knows, so a mismatch leaves the payer in the browser - without any error,
 * because the payment itself still goes through.
 */
data class TransactionReturns(
    val success: String,
    val failure: String
)

/**
 * Request for POST /payment/{serviceId}/transactions.
 *
 * The amount is a [BigDecimal] so no binary floating point rounding can occur in the
 * public API. It is serialised as a JSON number, as required by the contract.
 *
 * A `directChannel` transaction skips the SimPay channel picker, so the API demands the
 * payer data the gateway would otherwise collect: `customer.email`, `customer.ip`,
 * `customer.countryCode` and `antifraud.useragent`. The check below fails fast instead
 * of letting the API reject the call after money-related state was already created.
 */
data class CreateTransactionRequest(
    val amount: BigDecimal,
    val currency: String = "PLN",
    val description: String? = null,
    val control: String? = null,
    val directChannel: String? = null,
    val customer: TransactionCustomer? = null,
    val antifraud: TransactionAntifraud? = null,
    val returns: TransactionReturns? = null
) {
    init {
        require(amount >= BigDecimal.ONE) { "amount must be at least 1" }
        require(currency.isNotBlank()) { "currency must not be blank" }
        require(description == null || description.length <= 64) { "description max length is 64" }
        require(control == null || control.length <= 255) { "control max length is 255" }

        if (directChannel != null) {
            require(directChannel.isNotBlank()) { "directChannel must not be blank" }
            val payer = requireNotNull(customer) {
                "directChannel requires customer data (email, ip, countryCode)"
            }
            requireNotNull(payer.countryCode) {
                "directChannel requires customer.countryCode"
            }
            requireNotNull(antifraud) { "directChannel requires antifraud.useragent" }
        }
    }
}

/** Result of creating a transaction. */
data class CreatedTransaction(
    val transactionId: TransactionId,
    val redirectUrl: String
)

/**
 * Transaction status.
 *
 * The raw API value is always preserved so an unrecognised status can be diagnosed and
 * is never silently treated as success.
 */
sealed class TransactionStatus(val rawStatus: String) {

    class Processing(raw: String) : TransactionStatus(raw)
    class Verification(raw: String) : TransactionStatus(raw)
    class Paid(raw: String) : TransactionStatus(raw)
    class Failed(raw: String) : TransactionStatus(raw)
    class Expired(raw: String) : TransactionStatus(raw)
    class Refunded(raw: String) : TransactionStatus(raw)
    class Fraud(raw: String) : TransactionStatus(raw)
    class Unknown(raw: String) : TransactionStatus(raw)


    override fun toString(): String = "${this::class.simpleName}($rawStatus)"

    companion object {
        fun fromRaw(raw: String): TransactionStatus = when (raw) {
            "transaction_new",
            "transaction_generated",
            "transaction_confirmed" -> Processing(raw)

            "transaction_fraud_possibility" -> Verification(raw)
            "transaction_paid" -> Paid(raw)
            "transaction_failure" -> Failed(raw)
            "transaction_expired" -> Expired(raw)
            "transaction_refunded" -> Refunded(raw)
            "transaction_fraud" -> Fraud(raw)
            else -> Unknown(raw)
        }
    }
}

/** Status snapshot returned by the mobile SDK status endpoint. */
data class TransactionSnapshot(
    val transactionId: TransactionId,
    val status: TransactionStatus
)

