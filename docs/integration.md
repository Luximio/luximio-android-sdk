# Android customer recovery integration

Candidate `app.luximio:luximio-links-android:0.1.0-rc.1` is available as a
[local build](../README.md) while publication is pending. The planned artifact
host is Luximio's R2 Maven repository; the candidate is not available publicly
or on Maven Central. Customer provisioning and production recovery remain
disabled.

## Prepare the app and server

Register a fixed Luximio project, exact canonical HTTPS invocation origin and
actual Android package. Recovery uses Play Install Referrer and request-bound
Standard Play Integrity; it never falls back to a bearer-only profile.

Link the app's Google Cloud project, enable Play Integrity and obtain its public
numeric project number. Authorize the server to decode tokens for that project
and register the actual Google Play App Signing SHA-256 certificates. Upload or
other distribution certificates are not interchangeable. Keep service-account
credentials server-side; none belongs in the app or Gradle configuration.

Use a genuine Play installation from a prepared referrer-bearing listing URL on
an eligible physical device. A local/adb installation or mocked provider does
not verify store transport or native proof. Provider setup and verification are
app-specific; a successful build does not enable the service.

## Construct one session

Replace the public example identities with registered values. `context` is your
app's Android `Context`. The `route` callback below is app-owned and synchronous:

```kotlin
import app.luximio.links.CustomerPlayConfiguration
import app.luximio.links.CustomerPlaySession
import app.luximio.links.PlayDisposition
import app.luximio.links.PlayLinkDelivery

val configuration = CustomerPlayConfiguration(
    projectID = "customer-project",
    origin = "https://links.example.com",
    androidPackageName = "com.example.customer",
    actualPackageName = context.packageName,
    cloudProjectNumber = 123456789012L,
)
val route: (PlayLinkDelivery) -> PlayDisposition = { delivery ->
    // Replace with current-account authorization and synchronous navigation.
    PlayDisposition.HOLD
}
val session = CustomerPlaySession.create(context, configuration, route)
val coordinator = session.coordinator
```

Keep one session per installation/configuration for the owning app lifecycle.
The paired journal is isolated by protocol, origin, project and package. It does
not import prototype state. Changing only the Cloud project number does not
renew or discard saved recovery. Missing/corrupt durable state fails closed.

## Forward lifecycle and user choices

Call these suspending coordinator methods from the UI coroutine context:

- `activate()` on startup/foreground; an existing hold remains held.
- `readinessChanged()` after onboarding or current-account changes.
- `retry()` when the user explicitly retries a temporary failure.
- Await `cancelForManualNavigation()` before newer manual/direct-link navigation
  and handle failure before promising cancellation or applying the new choice.
- For an interrupted applying entry, use `confirmUncertain(deliveryID)` only
  after explicit user confirmation, or cancel it. Never auto-replay on restart.

The route callback returns `HOLD` while readiness/authorization is unresolved,
`DISCARDED` for denial/unsupported actions, and `APPLIED` only after installing
navigation state. Recheck the current account and resource permissions through
your own app/backend every time. Use `delivery.id` for idempotence. Treat its
destination and original URL/query as untrusted; proof and bearer possession do
not authorize private resources.

Keep `onStatus` and `onCompletionPending` displays separate; completion sync
must not reopen a screen or hide a routing result. `flushCompletion()` retries
completion only. Cancel the owner's coroutine jobs before `session.close()`.
Close releases owned proof resources and suppresses late callbacks, but does
not cancel durable recovery, erase data or stop Google's already-issued Tasks.

Holds gate navigation, not collection. Activation can retrieve context and
request proof before login. Gate SDK work separately where a disclosure/choice
must precede collection. Validate holds/restart, offline retry, permissions,
cancellation and replay in the customer's actual Play setup, and review
[privacy](privacy.md) before preparing whole-app declarations.
