# Android SDK publishing

The supported installation channel is a Maven repository on Cloudflare R2 at
`https://maven.luximio.app`. This repository is being prepared for release; the
SDK remains an unpublished release candidate. A successful build or publication
plan does not make a version available to customers.

The **Publish Android Maven candidate** workflow is manual. It has no push, tag, pull-request or
scheduled trigger, and defaults to a plan that needs no R2 credential. The
ordinary **Build SDK** workflow stays independent and receives no publisher
secret. Downloading an eventual public SDK will require no credentials and will
not grant access to the Luximio service.

## Prepare a version for review

1. Open Actions → Publish Android Maven candidate → Run workflow on `main`. Leave both `apply` and
   `check_access` false. Select the exact source commit with `source_sha`, or
   leave it blank to use the workflow's source revision.
2. The build job checks the source, runs SDK tests and release lint, and creates
   its local Maven candidate. Separate validation produces `manifest.json` and
   the allowlisted `repository/` tree. The artifact contains the AAR, POM,
   Gradle module metadata, sources JAR and checksum sidecars. It excludes the
   internal review-document ZIP and development files.
3. Review the exact source commit, notices, dependency metadata and staged
   artifact hashes. Save the manifest's SHA-256 and the full source commit SHA.
   A manifest is an integrity inventory, not a digital signature.

The current tooling accepts only the release-candidate coordinate
`app.luximio:luximio-links-android:0.1.0-rc.1`. A new release version requires
an explicit tooling and source review; changing a workflow input alone cannot
turn an arbitrary coordinate into an approved release.

## Approve and publish

The checked-in `scripts/release-approval.json` starts disabled:

```json
{
  "schemaVersion": 1,
  "approved": false,
  "coordinate": null,
  "sourceSha": null,
  "manifestSha256": null
}
```

After the owner approves the exact release, record its coordinate, full source
SHA and manifest SHA-256, and set `approved` to true in a separate reviewed
tooling commit. This distinction avoids a self-reference: source commit **A**
identifies the SDK being built, while later tooling/approval commit **B** records
approval of A. Run B's workflow on `main`, selecting A as `source_sha`; do not
replace the approved source SHA with B merely because B is now `main`.

Run the plan first and compare its manifest again. Then manually run the
workflow with `apply` true and `check_access` false. A fresh upload job
revalidates the stage and approval before the step that receives the R2 secrets.
It uses fixed account/bucket configuration and conditional S3 creation; existing
objects must have identical bytes and metadata. A conflicting object stops the
run instead of being overwritten. Retry an interrupted release with the same
reviewed input. Do not cancel a running upload or reuse its version for different
content. The workflow serializes publishing runs without cancelling an active
run.

The uploader does not delete objects, alter bucket settings, create DNS records
or publish discovery metadata. A successful upload still needs the release's
anonymous HTTPS/Gradle validation and release record. Public source visibility,
service activation and plan eligibility remain separate decisions.

## Publisher access

The dedicated R2 token must have **Object Read & Write** access only to the
distribution bucket `luximio-sdk-maven`. Never use an administrative token or
grant access to private staging/customer storage. Store the two values as
repository Actions secrets `R2_ACCESS_KEY_ID` and `R2_SECRET_ACCESS_KEY`; keep a
private backup and rotate/revoke them through the owner procedure. Do not paste
them into issues, workflow inputs, commands, commits or logs.

Use `check_access` true with `apply` false for a read-only credential check.
This mode is separate from publishing and must not upload SDK files. Its
successful/denied outcomes should be recorded by the operator before relying
on the credential's bucket isolation.

On a GitHub Free private organization repository, repository secrets are the
available approach: environments and required deployment reviewers are not an
enforced gate. The manual dispatch, main-only check and approval file are
workflow controls. Trusted repository writers can change workflows to request
repository secrets. The underlying R2 Object Read & Write token is bucket
scoped, not append-only; the uploader's refusal to overwrite or delete does not
remove the token's broader object privileges. Limit repository write access
accordingly.

See [R2 authentication](https://developers.cloudflare.com/r2/api/tokens/),
[GitHub environment availability](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments)
and [repository secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)
for provider requirements. This guide contains no publishing credential and
does not itself authorize a release.
