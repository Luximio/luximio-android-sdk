# Release candidate 0.1.0-rc.1

This public prerelease contains the standalone Luximio Android SDK, its
synthetic tests and manual build/publishing workflows. The `0.1.0-rc.1` tag
preserves the reviewed source commit; its Maven artifacts are served from
`https://maven.luximio.app`. Gradle itself still publishes only to a local build
directory; the separate restricted uploader handles approved R2 publication.

The default publishing plan transfers and revalidates 20 allowlisted artifact
and checksum files as a private workflow artifact. It excludes the internal
review-docs ZIP, receives no R2 secrets and uploads nothing to R2. The independent
Python uploader requires explicit manual apply and exact reviewed approval;
the current approval pins only the reviewed release. See [publishing](docs/publishing.md).
Neither workflow changes SDK runtime behavior or activates recovery service.

Coordinate: `app.luximio:luximio-links-android:0.1.0-rc.1`.

Use `CustomerPlaySession` for customer integration. Earlier prototype adapters
remain in the library and are not a fallback when native verification fails.

Apache-2.0 covers Dot Square's original SDK code. See LICENSE, NOTICE and
[dependency notes](docs/dependencies.md). Repository access does not provide
service entitlement, Google authorization or a verified customer installation.
