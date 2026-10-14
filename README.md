# SimPay Checkout Android SDK

[![Min Android SDK](https://img.shields.io/badge/Min_android_sdk-21-blue?logo=android)](https://shields.io/)
[![Compile SDK](https://img.shields.io/badge/Target/Compile%20sdk-34-informational.svg?logo=android)](https://shields.io/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10-informational.svg?logo=kotlin)](https://shields.io/)
[![Java](https://img.shields.io/badge/Java-11-informational.svg?logo=java)](https://shields.io/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/license/mit)

A drop-in bottom sheet that takes a payment through SimPay: lists the channels, creates
the transaction, handles BLIK Level 0 and redirect channels, and confirms the outcome
against the API before reporting anything.

| Module | What it is |
| --- | --- |
| `sdk` | the library — payment sheet and the `MobileSessionProvider` integration point |
| `app` | demo shop showing the full flow |

**Adding this to your own application?** See **[INTEGRATION.md](INTEGRATION.md)** for
the dependency wiring, the session provider, the deep link activity and a table of
failure symptoms. Everything below is the reference.

## Table of Contents

- [Before you start: the merchant panel](#1-before-you-start-the-merchant-panel)
- [Quick start](#2-quick-start)
- [How a payment runs](#3-how-a-payment-runs)
- [Reference](#4-reference)
- [Demo app](#5-demo-app)
- [Development](#6-development)
- [License](#7-license)

---

## 1. Before you start: the merchant panel

**Nothing works until the service is prepared in the SimPay merchant panel.** Open the
service settings and:

1. **Turn on mobile SDK support.** Without it the session endpoint issues no token and
   the sheet fails before loading a single channel. No code can work around this.
2. **Register the deep link scheme**, character for character identical to the manifest
   and to `TransactionReturns`.

```text
merchant panel          AndroidManifest.xml           TransactionReturns
--------------          -------------------           ------------------
scheme: myapp     ↔     android:scheme="myapp"   ↔     "myapp://payment-return?..."
        ^^^^^
        the same value in all three places
```

> A mismatch has **no compile error and no error message**. The payment succeeds and the
> payer simply never returns to the app.

The scheme is global to the device and owned by nobody, so pick something specific such
as reverse-DNS (`pl.example.shop`). For production prefer verified App Links (`https://`
with `assetlinks.json`) — the flow is identical, only the intent filter differs.

---

## 2. Quick start

```kotlin
val checkout = SimPayMobileCheckout(
    configuration = SimPayMobileConfiguration(
        serviceId = "a1b2c3d4",           
        merchantDisplayName = "Example Store"
    ),
    sessionProvider = object : MobileSessionProvider {
        override suspend fun provideSession(): MobileSdkSession =
            merchantBackend.createMobileSdkSession()   // never the API key here
    }
)

checkout.present(activity, order) { result ->
    when (result) {
        is CheckoutResult.Succeeded -> // transaction_paid — still confirm via webhook
        is CheckoutResult.Pending   -> // outcome open, never treat as paid
        is CheckoutResult.Failed    -> // show result.error
        CheckoutResult.Cancelled    -> // payer dismissed the sheet
    }
}
```

The API address is fixed to `https://api.simpay.pl` and the header is always
`Bearer <token>` with the full `mobsdk_` prefix. An integrator supplies only the service
id, the display name and a session provider.

---

## 3. How a payment runs

```text
merchant backend                     app + SDK
----------------                     ---------
POST /mobile-sdk/session
  Authorization: Bearer <API key>
        |
        v
  mobsdk_... token (~15 min)  ---->  MobileSessionProvider
                                          |
                                          v
                                     GET  /mobile-sdk/channels
                                     POST /transactions        (once per checkout)
                                          |
                            +-------------+-------------+
                            v                           v
                 POST /blik/level0/{id}          Custom Tab redirect
                            |                           |
                            +-------------+-------------+
                                          v
                               GET /mobile-sdk/transactions/{id}
                                    polled until terminal
```

**The merchant API key must never be embedded in a mobile application.** The session
endpoint needs it, so the backend exchanges it for a short lived token.

---

## 4. Reference

### Result mapping

| API status | Result |
| --- | --- |
| `transaction_paid` | `Succeeded` |
| `transaction_new` / `generated` / `confirmed` | keeps polling |
| `transaction_fraud_possibility` | `Pending` (verification required) |
| `transaction_failure` / `fraud` | `Failed`, not retryable |
| `transaction_expired` / `refunded` | `Failed` |
| unrecognised status | `Pending`, never success |
| polling budget elapsed | `Pending` |

### Direct channel fields

Every channel is created as a **direct channel**, so the SimPay picker is skipped and
the payer goes straight to the bank, wallet or BLIK confirmation. The API therefore
needs the full payer context:

| Field | Source |
| --- | --- |
| `directChannel` | id of the tapped channel (`blik`, `mbank`, …) |
| `customer.email` | `TransactionOrder.customerEmail` |
| `customer.ip` | `MobileSdkSession.payerIp`, fallback `payerIpOverride` |
| `customer.countryCode` | `TransactionOrder.customerCountryCode`, else derived from currency |
| `antifraud.useragent` | real device agent (`WebSettings.getDefaultUserAgent`) |

Validated locally, before any transaction exists: a missing payer IP fails with
`MissingPayerIp`; a currency shared by several countries (`EUR`) fails with
`MissingCountryCode` rather than guessing; the country code must be ISO 3166-1 alpha-2.

### Rejected BLIK code

A rejected ticket closes the transaction on the SimPay side, so the next code creates a
**new** one. Only an explicit rejection (HTTP 400/409/422) drops the transaction;
retries stop after `MAX_BLIK_REJECTIONS` (3). The abandoned id is remembered, so
dismissing the sheet reports `Pending`/`Failed` with that id rather than `Cancelled`.

### Session token

Valid for about 15 minutes. `SessionHolder` refreshes once on HTTP 401 and repeats the
call — but never for requests whose outcome is unknown. The token is a JWT after the
`mobsdk_` prefix; its payload carries `sub = payment:<serviceId>`, scopes and `exp`.

### Translations

Texts live in `values/strings.xml` (English) and `values-pl/strings.xml` (Polish);
Android picks by device locale. Adding a language means adding one file — no code
changes. All names carry the `simpay_` prefix, enforced by `resourcePrefix`, so the SDK
can never overwrite a host resource.

API messages are **not** shown to the payer — they are English and technical, and stay
in `CheckoutResult.Failed.message` for logging. If the host app limits packaged locales
(`androidResources.localeFilters`), it must include the languages it wants from the SDK.

---

## 5. Demo app

The whole integration is `DemoCheckout.present`.

It runs offline by default on `FakeMobileSdkApi`. To drive a debug build against the
real API, add to `local.properties` (git-ignored):

```properties
simpay.serviceId=...
simpay.apiKey=...
simpay.payerIp=...
```

All three are required; with any missing the demo stays offline. Release builds ignore
them entirely. Real-API runs also need the panel prepared — see
[section 1](#1-before-you-start-the-merchant-panel).

> The fake API is demo-only. The SDK never falls back to it, so a successful fake run
> does not prove a working integration.

---

## 6. Development

```zsh
./gradlew :app:installDebug          # run the demo
./gradlew :sdk:testDebugUnitTest     # unit tests
./gradlew :sdk:lintRelease           # lint
```

---

## 7. License

MIT — see [LICENSE](LICENSE)
