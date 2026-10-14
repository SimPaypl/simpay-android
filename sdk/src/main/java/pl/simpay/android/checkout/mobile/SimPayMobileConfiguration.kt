package pl.simpay.android.checkout.mobile

/**
 * Configuration of the mobile checkout.
 *
 * The API address is fixed: the SDK always talks to the SimPay production host, so an
 * integrator only supplies the service identifier and the name shown on the sheet.
 * The access token comes from a [MobileSessionProvider].
 *
 * Prerequisite that cannot be satisfied from the app: the service must have **mobile
 * SDK support enabled in the SimPay merchant panel** ("Mobilne SDK" tab). Without it the
 * session endpoint refuses to issue a token and the sheet fails before loading any
 * channel. The same tab registers the deep link scheme used by [TransactionReturns].
 *
 * @param serviceId short service identifier used by the API as `:uuid_short`. It is the
 * first segment of the service UUID (8 characters), not the full UUID. The same value
 * must be used for every mobile SDK endpoint, because the issued token is bound to that
 * service through its `sub` claim.
 */
class SimPayMobileConfiguration internal constructor(
    val serviceId: String,
    val merchantDisplayName: String,
    /**
     * Not part of the public configuration. Production for every integrator; only SDK
     * tests and the debug-only variant of the library may supply a different value.
     */
    internal val baseUrl: String
) {

    constructor(
        serviceId: String,
        merchantDisplayName: String
    ) : this(serviceId, merchantDisplayName, PRODUCTION_BASE_URL)

    init {
        require(serviceId.isNotBlank()) { "serviceId must not be blank" }
        require(baseUrl.isNotBlank()) { "baseUrl must not be blank" }
    }

    override fun toString(): String =
        "SimPayMobileConfiguration(serviceId=$serviceId, merchantDisplayName=$merchantDisplayName)"

    companion object {
        /** The only address the published SDK talks to. */
        const val PRODUCTION_BASE_URL: String = "https://api.simpay.pl"
    }
}
