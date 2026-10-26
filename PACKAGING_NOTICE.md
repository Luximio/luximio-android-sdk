# Release candidate 0.1.0-rc.1

This unpublished candidate contains the standalone Luximio Android SDK, its
synthetic tests, manual Build SDK workflow and separate publishing-preparation
workflow. There is no public SDK release or version tag yet. The only configured
Gradle publication destination is a local build directory.

The default publishing plan transfers and revalidates 20 allowlisted artifact
and checksum files as a private workflow artifact. It excludes the internal
review-docs ZIP, receives no R2 secrets and uploads nothing to R2. The independent
Python uploader requires explicit manual apply and exact reviewed approval;
the checked-in approval record is disabled. See [publishing](docs/publishing.md).
Neither workflow changes SDK runtime behavior or activates recovery service.

Coordinate: `app.luximio:luximio-links-android:0.1.0-rc.1`.

Use `CustomerPlaySession` for customer integration. Earlier prototype adapters
remain in the library and are not a fallback when native verification fails.

Apache-2.0 covers Dot Square's original SDK code. See LICENSE, NOTICE and
[dependency notes](docs/dependencies.md). Repository access does not provide
service entitlement, Google authorization or a verified customer installation.
