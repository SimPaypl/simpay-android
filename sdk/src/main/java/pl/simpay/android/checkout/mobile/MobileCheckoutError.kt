package pl.simpay.android.checkout.mobile

/** Errors of the mobile checkout. */
enum class MobileCheckoutError {
    NoInternet,
    Timeout,
    SessionFailed,
    ChannelsFailed,
    Unauthorized,
    InvalidResponse,
    Internal,

    /** Transaction creation failed or was rejected by the API. */
    TransactionFailed,

    /** The API rejected the request payload. */
    ValidationFailed,

    /** Transaction is unknown to the API. */
    TransactionNotFound,

    /** BLIK code was rejected or is no longer usable. */
    BlikRejected,

    /** Too many requests; the caller must wait before retrying. */
    RateLimited,

    /**
     * A direct channel requires `customer.ip`, but the session provider did not supply
     * the payer IP. The SDK refuses to invent one.
     */
    MissingPayerIp,

    /**
     * A direct channel requires `customer.countryCode` matching the currency, but none
     * was supplied and the currency maps to no single country.
     */
    MissingCountryCode,

    /** No browser able to handle the redirect was found. */
    BrowserUnavailable,

    /**
     * The outcome of a request could not be determined, for example a timeout after the
     * request was already sent. The SDK never silently repeats such a request.
     */
    Indeterminate
}

/** Exception carrying a mapped API failure. Never contains token material. */
class SimPayApiException(
    val error: MobileCheckoutError,
    val httpCode: Int? = null,
    message: String? = null,
    cause: Throwable? = null
) : Exception(message, cause)
