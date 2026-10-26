# Dependencies and notices

Reviewed for **0.1.0-rc.1** on **26 October 2026**. This is the SDK's resolved release-runtime inventory, not a whole-app license or privacy declaration. `build.gradle.kts` remains the version-pin source. A consuming app can select different versions through its own dependency graph.

## Distribution boundary

The Luximio AAR and sources JAR contain the SDK's own namespace/source files and namespaced SDK LICENSE/NOTICE. Runtime dependencies are resolved separately through Maven/Gradle metadata; their complete binaries and sources are not bundled into the Luximio AAR or this source repository. The SDK's Apache-2.0 license does not relicense Google or other components. Preserve each component's applicable terms and notices when distributing a consuming application.

## Declared runtime dependencies

| Declaration | Version | Resolved platform artifact |
| --- | --- | --- |
| `kotlinx-coroutines-core` API | 1.11.0 | `kotlinx-coroutines-core-jvm` |
| `kotlinx-serialization-json` API | 1.11.0 | `kotlinx-serialization-json-jvm`, with `core-jvm` |
| Kotlin standard library | 2.3.21 | `kotlin-stdlib` |
| Play Install Referrer | 2.2 | `installreferrer` AAR |
| Play Integrity | 1.6.0 | `integrity` AAR |
| Kotlin coroutines Android / Play Services | 1.11.0 | Same named JVM JARs |
| OkHttp | 5.5.1 | `okhttp-android` AAR |

The runtime graph selected **33 binary artifacts** below. Kotlin multiplatform aliases and BOM/constraint metadata are not additional binary artifacts. Conflicts resolved to the versions shown, including Kotlin 2.3.21, JetBrains annotations 23.0.0, AndroidX annotation 1.10.0 and Play Services Basement 18.9.0.

## Resolved runtime inventory

Each linked coordinate opens its publisher's exact-version POM; the license column records that POM's declaration. Those declarations do not replace the complete upstream terms or embedded third-party notices.

| Selected binary coordinate | Declared terms |
| --- | --- |
| [`androidx.activity:activity:1.0.0`](https://dl.google.com/dl/android/maven2/androidx/activity/activity/1.0.0/activity-1.0.0.pom) | Apache-2.0 |
| [`androidx.annotation:annotation-jvm:1.10.0`](https://dl.google.com/dl/android/maven2/androidx/annotation/annotation-jvm/1.10.0/annotation-jvm-1.10.0.pom) | Apache-2.0 |
| [`androidx.arch.core:core-common:2.1.0`](https://dl.google.com/dl/android/maven2/androidx/arch/core/core-common/2.1.0/core-common-2.1.0.pom) | Apache-2.0 |
| [`androidx.arch.core:core-runtime:2.0.0`](https://dl.google.com/dl/android/maven2/androidx/arch/core/core-runtime/2.0.0/core-runtime-2.0.0.pom) | Apache-2.0 |
| [`androidx.collection:collection:1.1.0`](https://dl.google.com/dl/android/maven2/androidx/collection/collection/1.1.0/collection-1.1.0.pom) | Apache-2.0 |
| [`androidx.core:core:1.2.0`](https://dl.google.com/dl/android/maven2/androidx/core/core/1.2.0/core-1.2.0.pom) | Apache-2.0 |
| [`androidx.customview:customview:1.0.0`](https://dl.google.com/dl/android/maven2/androidx/customview/customview/1.0.0/customview-1.0.0.pom) | Apache-2.0 |
| [`androidx.fragment:fragment:1.1.0`](https://dl.google.com/dl/android/maven2/androidx/fragment/fragment/1.1.0/fragment-1.1.0.pom) | Apache-2.0 |
| [`androidx.lifecycle:lifecycle-common:2.1.0`](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-common/2.1.0/lifecycle-common-2.1.0.pom) | Apache-2.0 |
| [`androidx.lifecycle:lifecycle-livedata:2.0.0`](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-livedata/2.0.0/lifecycle-livedata-2.0.0.pom) | Apache-2.0 |
| [`androidx.lifecycle:lifecycle-livedata-core:2.0.0`](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-livedata-core/2.0.0/lifecycle-livedata-core-2.0.0.pom) | Apache-2.0 |
| [`androidx.lifecycle:lifecycle-runtime:2.1.0`](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime/2.1.0/lifecycle-runtime-2.1.0.pom) | Apache-2.0 |
| [`androidx.lifecycle:lifecycle-viewmodel:2.1.0`](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-viewmodel/2.1.0/lifecycle-viewmodel-2.1.0.pom) | Apache-2.0 |
| [`androidx.loader:loader:1.0.0`](https://dl.google.com/dl/android/maven2/androidx/loader/loader/1.0.0/loader-1.0.0.pom) | Apache-2.0 |
| [`androidx.savedstate:savedstate:1.0.0`](https://dl.google.com/dl/android/maven2/androidx/savedstate/savedstate/1.0.0/savedstate-1.0.0.pom) | Apache-2.0 |
| [`androidx.startup:startup-runtime:1.2.0`](https://dl.google.com/dl/android/maven2/androidx/startup/startup-runtime/1.2.0/startup-runtime-1.2.0.pom) | Apache-2.0 |
| [`androidx.tracing:tracing:1.0.0`](https://dl.google.com/dl/android/maven2/androidx/tracing/tracing/1.0.0/tracing-1.0.0.pom) | Apache-2.0 |
| [`androidx.versionedparcelable:versionedparcelable:1.1.0`](https://dl.google.com/dl/android/maven2/androidx/versionedparcelable/versionedparcelable/1.1.0/versionedparcelable-1.1.0.pom) | Apache-2.0 |
| [`androidx.viewpager:viewpager:1.0.0`](https://dl.google.com/dl/android/maven2/androidx/viewpager/viewpager/1.0.0/viewpager-1.0.0.pom) | Apache-2.0 |
| [`com.android.installreferrer:installreferrer:2.2`](https://dl.google.com/dl/android/maven2/com/android/installreferrer/installreferrer/2.2/installreferrer-2.2.pom) | Android SDK License Agreement |
| [`com.google.android.gms:play-services-basement:18.9.0`](https://dl.google.com/dl/android/maven2/com/google/android/gms/play-services-basement/18.9.0/play-services-basement-18.9.0.pom) | Android SDK License Agreement |
| [`com.google.android.gms:play-services-tasks:18.2.0`](https://dl.google.com/dl/android/maven2/com/google/android/gms/play-services-tasks/18.2.0/play-services-tasks-18.2.0.pom) | Android SDK License Agreement |
| [`com.google.android.play:core-common:2.0.4`](https://dl.google.com/dl/android/maven2/com/google/android/play/core-common/2.0.4/core-common-2.0.4.pom) | Play Core Software Development Kit Terms of Service |
| [`com.google.android.play:integrity:1.6.0`](https://dl.google.com/dl/android/maven2/com/google/android/play/integrity/1.6.0/integrity-1.6.0.pom) | Play Integrity API Terms of Service |
| [`com.squareup.okhttp3:okhttp-android:5.5.1`](https://repo.maven.apache.org/maven2/com/squareup/okhttp3/okhttp-android/5.5.1/okhttp-android-5.5.1.pom) | Apache-2.0 |
| [`com.squareup.okio:okio-jvm:3.18.1`](https://repo.maven.apache.org/maven2/com/squareup/okio/okio-jvm/3.18.1/okio-jvm-3.18.1.pom) | Apache-2.0 |
| [`org.jetbrains.kotlin:kotlin-stdlib:2.3.21`](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.3.21/kotlin-stdlib-2.3.21.pom) | Apache-2.0 |
| [`org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0`](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-android/1.11.0/kotlinx-coroutines-android-1.11.0.pom) | Apache-2.0 |
| [`org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0`](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.11.0/kotlinx-coroutines-core-jvm-1.11.0.pom) | Apache-2.0 |
| [`org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0`](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-play-services/1.11.0/kotlinx-coroutines-play-services-1.11.0.pom) | Apache-2.0 |
| [`org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.11.0`](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-core-jvm/1.11.0/kotlinx-serialization-core-jvm-1.11.0.pom) | Apache-2.0 |
| [`org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.11.0`](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-json-jvm/1.11.0/kotlinx-serialization-json-jvm-1.11.0.pom) | Apache-2.0 |
| [`org.jetbrains:annotations:23.0.0`](https://repo.maven.apache.org/maven2/org/jetbrains/annotations/23.0.0/annotations-23.0.0.pom) | Apache-2.0 |

## Terms and retained notices

- The 28 non-Google binary POMs above declare Apache-2.0. When redistributing applicable code, retain its license, copyright/attribution and any required upstream NOTICE material; document modifications where applicable. [Apache License 2.0, section 4](https://www.apache.org/licenses/LICENSE-2.0).
- Install Referrer and the selected Play Services artifacts declare the [Android SDK License Agreement](https://developer.android.com/studio/terms). Play Core Common declares [Play Core SDK terms](https://developer.android.com/guide/playcore/license). Integrity declares [Play Integrity terms](https://developer.android.com/google/play/integrity/overview#tos). Their proprietary terms and API conditions remain separate from the SDK's license.
- The selected Google Integrity and Core Common AARs carry `LICENSE`, `third_party_licenses.json` and `third_party_licenses.txt`. Play Services Basement and Tasks carry the two `third_party_licenses` files. Preserve/reconcile those upstream notice collections for components your app redistributes; do not infer that Google's entire artifact is Apache-2.0 from its bundled open-source notices.
- This SDK does not copy those Google/AndroidX/Square/Kotlin runtime archives into its own AAR. The current package checks reject classes outside the SDK namespace and unexpected resources/assets/native libraries. A shaded/fat-AAR or vendored-source distribution would need a new notice review.

The checked-in Gradle 9.5.0 wrapper is upstream build bootstrap code. Its script copyright/license headers and JAR `META-INF/LICENSE` are retained unchanged. AGP 9.3.1, Kotlin compiler/serialization plugin 2.3.21 and JUnit 4.13.2 are build/test tooling rather than shipped SDK runtime artifacts; this inventory does not characterize every dependency of those tools.

## Check the consuming application

The reviewed clean RC consumer merged `android.permission.INTERNET` and `com.google.android.finsky.permission.BIND_GET_INSTALL_REFERRER_SERVICE`; an unexported AndroidX Startup provider with `okhttp3.internal.platform.PlatformInitializer`; an unexported `com.google.android.play.core.common.PlayCoreDialogWrapperActivity`; and `androidx.core.app.CoreComponentFactory`. These come from the dependency graph rather than extra declarations in the SDK's own AAR. Startup components can run before the app explicitly creates its recovery session. This manifest evidence does not itself establish network collection or its absence. Inspect your own complete merged app manifest and runtime graph, retain required notices in the distributed app, and complete its privacy/Data Safety review. See [the SDK privacy inventory](privacy.md).

The reviewed SDK LICENSE/NOTICE are included in the Luximio AAR, sources JAR and local review-document archive. Repository source access or a downloaded artifact grants no recovery-service entitlement. This inventory records reviewed package metadata and distribution boundaries; it is not a public release approval or a license determination for every possible consuming app.
