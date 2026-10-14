# Adding the payment sheet to another app

How to run this SDK inside a different Android application, either to evaluate it or to
integrate it for real.

For the behaviour of the SDK itself — result mapping, direct channel fields, BLIK rules
and the rules that protect money — see [README.md](README.md).

---

## 1. Prerequisite that no code can replace

The service must be prepared in the **SimPay merchant panel**:

1. **Mobile SDK support enabled.** Without it the session endpoint issues no token and
   the sheet fails before loading a single channel.
2. **The deep link scheme registered**, character for character identical to the one in
   your `AndroidManifest.xml` and in `TransactionReturns`.

A mismatch in point 2 has no compile error and no runtime error: the payment succeeds,
the payer simply never comes back to the app.

---

## 2. Wiring the dependency

The SDK needs `compileSdk 34` and `minSdk 21` — both the lowest its dependencies permit,
so it constrains your project as little as possible (see the badges in
[README.md](README.md)). Do **not** apply `org.jetbrains.kotlin.android`: AGP 9 already
brings Kotlin, and applying both fails with *"extension with name 'kotlin' already
registered"*.

### Option A - composite build (best while testing)

Changes in the SDK are picked up on the next build, with no publishing step.

`settings.gradle.kts` of the other app:

```kotlin
includeBuild("../SimPayAndroidSDK") {
    dependencySubstitution {
        substitute(module("pl.simpay:checkout-android"))
            .using(project(":sdk"))
    }
}
```

`build.gradle.kts` of the app module - note there is no version, the substitution
supplies it:

```kotlin
dependencies {
    implementation("pl.simpay:checkout-android")
}
```

### Option B - local Maven (closest to real consumption)

In this project:

```zsh
./gradlew :sdk:publishToMavenLocal
```

In the other app, `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenLocal()   // must come first
        google()
        mavenCentral()
    }
}
```

`build.gradle.kts`:

```kotlin
dependencies {
    implementation("pl.simpay:checkout-android:1.0.0")
}
```

Republish after every SDK change, otherwise the app keeps the previous artifact.

### What arrives with it

`androidx.fragment:fragment-ktx` reaches your compile classpath, because
`present(activity: FragmentActivity)` exposes that type. AppCompat, Browser (Custom
Tabs), Lifecycle, Material and coroutines arrive at runtime.

---

## 3. The activity has to be a `FragmentActivity`

The sheet keeps its state in a `ViewModel` and the transaction id in saved state, so it
survives a rotation and a process death. `ComponentActivity` alone is not enough;
`AppCompatActivity` already qualifies.

```kotlin
class CheckoutActivity : FragmentActivity() { /* ... */ }
```

---

## 4. Supplying the session

The session endpoint needs the **merchant API key**, which must never ship inside an
app: anyone can read it out of the APK and use it to create transactions and order
refunds. The SDK therefore provides no implementation that takes an API key - your
backend exchanges the key for a short lived token (~15 minutes).

```kotlin
class BackendSessionProvider(
    private val client: MyBackendClient
) : MobileSessionProvider {

    override suspend fun provideSession(): MobileSdkSession {
        // The backend calls POST /payment/{serviceId}/mobile-sdk/session with the API key
        // and returns the token plus the payer IP it observed on that request.
        val response = client.createPaymentSession()

        return MobileSdkSession(
            token = response.token,          // full value, including the "mobsdk_" prefix
            expiresAt = response.expiresAt,
            payerIp = response.callerIp      // required by direct channels such as BLIK
        )
    }
}
```

`payerIp` must be the **public** address seen by the backend. A device cannot determine
its own public IP, and a local interface address is rejected by the API.

### Trying it without a backend

Two options, both debug-only:

- Copy `app/src/debug/java/pl/simpay/android/demo/debug/DebugApiKeySessionProvider.kt`
  from this repository into your own `src/debug` source set. It lives there on purpose,
  so it cannot reach a release build.
- Or skip the network entirely with `SimPayMobileCheckout.withApi(configuration, fake)`,
  passing your own `SimPayMobileApi`. See `FakeMobileSdkApi` in the demo.

A successful fake run proves the UI works, nothing more.

---

## 5. Presenting the sheet

```kotlin
private val checkout = SimPayMobileCheckout(
    configuration = SimPayMobileConfiguration(
        serviceId = "a1b2c3d4",              // from the merchant panel
        merchantDisplayName = "My Shop"      // shown on the sheet
    ),
    sessionProvider = BackendSessionProvider(client)
)

private fun pay() {
    val order = TransactionOrder(
        amount = BigDecimal("49.99"),        // at least 1
        currency = "PLN",
        description = "Wireless headphones", // max 64 characters
        control = "order-2451",              // your order id, max 255
        customerEmail = "buyer@example.com", // required by direct channels
        customerCountryCode = "PL",          // derived from the currency when omitted
        returns = TransactionReturns(
            success = "myapp://payment-return?result=success",
            failure = "myapp://payment-return?result=failure"
        )
    )

    checkout.present(this, order) { result ->
        when (result) {
            is CheckoutResult.Succeeded -> showThankYou()
            is CheckoutResult.Pending   -> showPendingNotice()
            is CheckoutResult.Failed    -> showRetry()
            CheckoutResult.Cancelled    -> Unit
        }
    }
}
```

> **No value above is proof of payment.** The amount is whatever the app claims, and the
> API does not bind it to the session token. Release goods only after your backend has
> verified the SimPay webhook - status, amount, currency, `control`, service id - against
> its own order record.

---

## 6. Coming back from the browser

Redirect channels open a Custom Tab. Without the activity below the payer stays in the
browser after paying.

`AndroidManifest.xml`:

```xml
<activity
    android:name=".PaymentReturnActivity"
    android:exported="true"
    android:theme="@android:style/Theme.Translucent.NoTitleBar">
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <!-- Must match TransactionReturns and the merchant panel exactly. -->
        <data android:scheme="myapp" android:host="payment-return" />
    </intent-filter>
</activity>
```

```kotlin
class PaymentReturnActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Bring the checkout activity back; the sheet re-checks the status itself.
        startActivity(
            Intent(this, CheckoutActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }
}
```

The checkout activity needs `android:launchMode="singleTop"`. Returning proves nothing
by itself - the SDK always re-reads the transaction status.

---

## 7. Checklist

| # | Step | How you know it worked |
|---|---|---|
| 1 | Mobile SDK enabled in the panel | session endpoint returns a token, not HTTP 500 |
| 2 | Deep link scheme in all three places | the browser hands control back to the app |
| 3 | Dependency resolves | `SimPayMobileCheckout` imports |
| 4 | Activity is a `FragmentActivity` | `present(this, ...)` compiles |
| 5 | Session provider returns a token | the sheet lists payment channels |
| 6 | `payerIp` present | BLIK does not fail with `MissingPayerIp` |
| 7 | Backend verifies the webhook | only then are goods released |

---

## 8. When something fails

The sheet shows the payer a localised message, while the technical reason travels in
`CheckoutResult.Failed.error` and `.message`. Log those two in debug builds; without
them every configuration mistake looks the same as a declined payment.

To tell a wrong service id from a service that simply has no mobile SDK enabled, call
the endpoints directly - no token needed, the status code alone is the answer:

```zsh
curl -s -o /dev/null -w '%{http_code}\n' -X POST \
    https://api.simpay.pl/payment/<service-id>/transactions \
    -H 'Content-Type: application/json' -d '{}'
```

`ROUTE_NOT_FOUND` or `404` means the service id is wrong. A `401`, `403` or `422` means
the id is fine and only the authentication is missing. A `500` on
`/mobile-sdk/session`, while the same key works on `/channels`, points at the service
configuration on the SimPay side rather than at anything in your app.

| Symptom | Cause |
|---|---|
| sheet fails immediately, `SessionFailed` | mobile SDK not enabled for the service, or the API key does not match the `serviceId` |
| `MissingPayerIp` | the session provider returned no `payerIp` and the order carries no `payerIpOverride` |
| `MissingCountryCode` | currency maps to no single country; pass `customerCountryCode` |
| payer stays in the browser | the deep link scheme differs between the panel, the manifest and `TransactionReturns` |
| `RateLimited` | the session endpoint allows roughly 5 requests per minute |
| build fails on AAR metadata | the app compiles against an SDK older than 34 |
| *"extension with name 'kotlin' already registered"* | `org.jetbrains.kotlin.android` applied next to AGP 9; remove it |



