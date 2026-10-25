# Dependencies and notices

The authoritative version pins are in `build.gradle.kts`:

| Dependency | Version |
| --- | --- |
| Play Install Referrer | 2.2 |
| Play Integrity | 1.6.0 |
| Kotlin coroutines core, Android and Play Services bridge | 1.11.0 |
| Kotlin serialization JSON | 1.11.0 |
| OkHttp | 5.5.1 |
| Kotlin compiler and serialization plugin | 2.3.21 |
| JUnit, for tests only | 4.13.2 |

Gradle resolves these components and their transitive dependencies; their
source and runtime binaries are not vendored into this repository. They retain
their own terms. The SDK's Apache-2.0 license does not relicense Google, Kotlin,
OkHttp or other third-party components. Reconcile resolved release dependencies
and required notices before public distribution or distributing a consuming app.

The checked-in Gradle wrapper is upstream Gradle bootstrap code. Its script
headers and the wrapper JAR's `META-INF/LICENSE` are retained. The wrapper selects
Gradle 9.5.0; Android Gradle Plugin remains 9.3.1. Build tooling licenses are
separate from the SDK code license.

The SDK LICENSE/NOTICE are copied to a namespaced `META-INF` directory inside
the AAR and sources JAR, and to the local review-document archive. A consuming
app must preserve the required notices for every component it redistributes;
this private development import is not a whole-app notice audit.
