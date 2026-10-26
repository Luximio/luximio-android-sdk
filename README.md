# Luximio Links for Android

**Release candidate `0.1.0-rc.1` — not published.** This repository contains
standalone Android source for deferred recovery through Google Play Install
Referrer and Standard Play Integrity. APIs and broader runtime support remain
provisional; source access does not enable the Luximio recovery service.

The source repository is
[`Luximio/luximio-android-sdk`](https://github.com/Luximio/luximio-android-sdk).
It is private and requires GitHub read access, including on CI. Keep repository
credentials in Git/CI authentication, never app configuration.

## Build and consume locally

The SDK is not publicly published. With JDK 25 and Android SDK 37
configured, run from this repository:

```sh
./gradlew testDebugUnitTest assembleRelease lintRelease publishReleasePublicationToLocalCandidateRepository
```

The only configured Gradle publication destination is the local `build/local-maven` directory.
It contains the AAR, dependency POM, Gradle metadata, sources and review-docs ZIP
for `app.luximio:luximio-links-android:0.1.0-rc.1`. This candidate coordinate
does not identify a public version tag or release. The Build SDK workflow is
started manually from GitHub Actions, uses read-only repository permissions and
has no release or deployment steps. Importing a workflow does not start a
hosted run.

Add that generated directory as a Maven repository in the consuming app's
`settings.gradle.kts`. Here `luximioSdkRepository` is an app-supplied Gradle
property containing the local repository directory:

```kotlin
val luximioSdkRepository: String by settings
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri(luximioSdkRepository) }
    }
}
```

Then add the dependency to the app module:

```kotlin
implementation("app.luximio:luximio-links-android:0.1.0-rc.1")
```

The library compiles for API 26+ with compile SDK 37 and Java 17 bytecode.
Compilation does not establish runtime coverage across that range.

## Planned public installation

After this exact version has been approved and published, its repository URL
will be `https://maven.luximio.app`. It requires no download credentials. Until
then, use the local build above; these instructions do not indicate that a
public artifact already exists.

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        exclusiveContent {
            forRepository {
                maven { url = uri("https://maven.luximio.app") }
            }
            filter { includeGroup("app.luximio") }
        }
    }
}
```

Add the same exact dependency coordinate shown above. This keeps Luximio's
namespace tied to its own repository without replacing Google/Maven Central
for ordinary dependencies. Repository access grants no backend entitlement.

## Candidate scope

- Customer integration starts with `CustomerPlaySession`; it composes real
  Play referrer acquisition, request-bound Standard Play Integrity, HTTPS
  recovery and an app-private no-backup journal.
- The app controls onboarding/account readiness and resource authorization.
  The coordinator supports held recovery, explicit network retry, terminal
  completion and cancellation before newer manual navigation.
- Native proof and original campaign context do not establish user identity,
  private-resource permission or reward/conversion eligibility.
- Earlier prototype adapters remain in the source but are unsupported customer
  profiles. Customer verification failures do not fall back to them.
- Deferred recovery, customer provisioning and setup documentation are not yet
  enabled on the public service. A public SDK download alone will not enable
  those features. Follow the [integration](docs/integration.md),
  [privacy](docs/privacy.md) and [dependency notice](docs/dependencies.md) guides.

## Publishing preparation

The separate **Publish Android Maven candidate** workflow prepares distribution
through the Maven repository on Cloudflare R2. Its default manual plan builds
and validates the candidate, transfers only 20 allowlisted Maven artifact and
checksum files as a private workflow artifact, then revalidates them in a fresh
job. The internal review-docs ZIP is excluded, and this default plan receives
no R2 secrets and uploads nothing to R2.

The independent Python publisher leaves Gradle's local destination unchanged.
Actual uploads require an explicit manual apply action and a reviewed source
commit/manifest approval; the checked-in approval record is disabled. See the
[publishing guide](docs/publishing.md) for the credential, retry and release
boundaries. This preparation does not approve or create a public SDK release.

## Integration and release scope

Use the [customer integration guide](docs/integration.md) to construct one
`CustomerPlaySession` with fixed project, origin, package and public Cloud
project number. Real recovery requires app-specific Play Install Referrer,
Standard Play Integrity and server authorization setup. The full app owns
current-account authorization and navigation. Native proof is not user login,
resource permission or trustworthy conversion attribution.

Review [privacy and retention](docs/privacy.md) against the complete consuming
app. Earlier prototype adapters in the source are separate profiles, not a
fallback when customer verification fails. Customer setup screens, authenticated
guides and production recovery remain disabled. The resolved dependency review
is recorded in the dependency guide; whole-app notices, public release approval
and broader device validation remain release responsibilities.

## License

Dot Square-authored SDK code is licensed under the [Apache License 2.0](LICENSE).
Retain [NOTICE](NOTICE); third-party dependencies retain their own terms. This
license covers the SDK, not the Luximio backend, dashboard or service. It grants
no service access or paid-plan entitlement and changes no Starter or future Pro
feature gates.
