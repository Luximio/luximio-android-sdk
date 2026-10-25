# Android customer SDK privacy inventory

This inventory covers the private-preview `CustomerPlaySession` adapter. It is
an input to the consuming app's privacy/Data Safety review, not a submitted
declaration or a claim that production recovery is enabled. Earlier prototype
adapters have separate endpoints and retention behavior.

## Data flow

| Stage | Data and recipient | Implemented boundary |
| --- | --- | --- |
| Browser preparation | Original URL/query, scope, operation IDs and times go to the recovery server | Original context stays out of the opaque Play referrer |
| Play input | Referrer capability and positive click/install timestamps are read | Timestamps are transient; this adapter neither journals nor sends them |
| Local installation | A random UUID and acquisition marker are stored | UUID stays local; it is not an analytics ID or customer request field |
| Local recovery | Capability, deadline, phase, receipt, original URL/destination and pending completion | Atomic app-private files under `noBackupFilesDir`; no SDK-level encryption |
| Customer requests | Project/channel, hashes, receipt/delivery pins, completion outcome, capability and proof go to the fixed origin | HTTPS, header-only bearer, bounded JSON, no redirects or automatic replay |
| Google verification | Public Cloud project number and request hash go to the native provider; the server sends the integrity token to Google for decode | Server credentials stay server-side; provider retention is separate |

Native requests contain no separate customer name, email/current-account ID,
local installation UUID or referrer timestamps. Native resolve does not resend
the original URL. The default transport has no logging interceptor. Luximio's
adapter emits no separate app-open/click analytics or client crash/performance
upload, accesses no clipboard and performs no fingerprint matching.

The complete app and transitive libraries determine merged permissions and
Google's processing. The SDK does not establish the absence of device data
merely by omitting an advertising-ID or location permission. Review resolved
dependencies and the final merged manifest for each release.

## Retention and deletion

**Device:** applying/discarding a delivery does not immediately erase its local
capability or context. A successful acknowledgement clears pending completion
only. Successful activation, readiness and retry operations check expiry and
purge expired context; the deadline is the earlier of receipt expiry and
retrieval plus seven days. Inspect/read, standalone completion flush and close
do not perform that purge. There is no scheduled local deletion, so inactive
apps may retain expired bytes. Installation/acquisition markers survive context
erasure until app data is removed, preventing referrer reacquisition. Backup
exclusion is not encryption and does not cover custom exports or log copies.

**Implemented service:** pending context has at most seven-day validity.
Observed terminal completion/rejection or expiry cleanup scrubs readable
context. Minimal replay markers remain until their fixed deadline; the bounded
capture-clock tolerance can make deletion eligibility seven days plus five
minutes after admission. Retries do not renew deadlines. Challenges are
five-minute, one-use records; expired rows need later cleanup/admission to be
removed. This Android adapter creates no App Attest key record. Raw integrity
tokens/verdicts are not persisted by that backend; its OAuth access token is
cached in memory until provider expiry. Verify actual deployed cleanup, logs,
access, backups and exports separately; validity is not proof of deletion.

**Google and infrastructure:** Google's integrity/referrer processing and
retention follow its provider arrangements, not Luximio's expiry periods. Native
proof cancellation does not stop an already-issued Google Task. Receiving
infrastructure handles IP/connection metadata; a missing receipt-table IP field
does not establish the absence of logs or rate-limit state. Uninstalling cannot
erase server/provider records.

## App responsibilities

Onboarding/account holds prevent navigation, not collection. Activation can read
Play input, fetch context and request proof before the route callback holds.
Gate SDK work separately if your app requires a disclosure/choice first.

Original URL/query and destination content are app-defined. Keep credentials
and sensitive personal data out of links; disclose any specific account,
location, purchase or other categories your schema introduces. Added analytics
or attribution use needs its own assessment. Native verification is not user
authentication or proof of conversion/reward eligibility.

Do not log capabilities, proof, original URLs, destinations or whole delivery
objects: some public data classes can include context in `toString`. Ordinary
direct resolution is a separate URL-bearing request; provider invocation logs
may retain its query. Verify effective logging and recipient roles on the actual
deployment. The SDK exposes no user-facing erasure UI or public erase endpoint.
Whole-app disclosure/deletion procedures and third-party notice/provider review
remain release responsibilities.
