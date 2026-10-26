#!/usr/bin/env python3
"""Plan reviewed Android Maven uploads; apply only an exact approved release.

The R2 key needs Object Read & Write on the one distribution bucket. That
provider permission includes operations this script never uses: this publisher
only conditionally creates objects and reads them back. It cannot make a
multi-object Maven release atomic, so POM/module discovery files upload last.
"""
import argparse
from datetime import datetime, timezone
import hashlib
from http.client import HTTPException
import hmac
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import uuid
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import build_opener, HTTPRedirectHandler, Request

SDK_ROOT = Path(__file__).resolve().parents[1]
ACCOUNT = "70f040529ca96fb10776ff7f456f0810"
BUCKET = "luximio-sdk-maven"
STAGING_BUCKET = "luximio-sdk-maven-staging"
HOST = f"{ACCOUNT}.r2.cloudflarestorage.com"
GROUP = "app.luximio"
ARTIFACT = "luximio-links-android"
PREFIX = "app/luximio/luximio-links-android"
CACHE = "public, max-age=31536000, immutable"
ALGORITHMS = ("md5", "sha1", "sha256", "sha512")
TYPES = {".aar": "application/java-archive", "-sources.jar": "application/java-archive",
         ".pom": "application/xml", ".module": "application/json"}
VERSION = re.compile(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:-[A-Za-z0-9]+(?:[.-][A-Za-z0-9]+)*)?")
SHA = re.compile(r"[0-9a-f]{40}")
MAX_FILE_BYTES = 64 * 1024 * 1024
MAX_TOTAL_BYTES = 128 * 1024 * 1024


class PublishError(ValueError):
    """Safe, credential-free failure suitable for CI output."""


class AlreadyExists(Exception):
    pass


def require(condition, message):
    if not condition:
        raise PublishError(message)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "Duplicate JSON field")
        result[key] = value
    return result


def no_links(path):
    path = Path(path).absolute()
    require(not any(p.is_symlink() for p in (*path.parents, path)), "Symlinked path is not allowed")
    return path.resolve()


def validate_stage(directory):
    """Snapshot every allowlisted byte before credentials or HTTP are used."""
    directory = no_links(directory)
    require(directory.is_dir(), "Stage directory is missing")
    manifest_path = directory / "manifest.json"
    require(manifest_path.is_file() and not manifest_path.is_symlink() and manifest_path.stat().st_size <= 1024 * 1024,
            "Missing, linked or oversized manifest")
    manifest_bytes = manifest_path.read_bytes()
    manifest = json.loads(manifest_bytes, object_pairs_hook=unique_object)
    require(isinstance(manifest, dict) and set(manifest) ==
            {"schemaVersion", "coordinate", "version", "files"}, "Unexpected manifest fields")
    require(type(manifest["schemaVersion"]) is int and manifest["schemaVersion"] == 1,
            "Unsupported manifest schema")
    version = manifest["version"]
    require(isinstance(version, str) and VERSION.fullmatch(version) and "SNAPSHOT" not in version.upper(),
            "Use an immutable semantic release version")
    require(manifest["coordinate"] == f"{GROUP}:{ARTIFACT}:{version}", "Unexpected Maven coordinate")
    name = f"{PREFIX}/{version}/{ARTIFACT}-{version}"
    artifacts = {name + suffix: content_type for suffix, content_type in TYPES.items()}
    expected = artifacts | {path + "." + algorithm: "text/plain; charset=utf-8"
                            for path in artifacts for algorithm in ALGORITHMS}
    expected_files = {"manifest.json"} | {"repository/" + key for key in expected}
    directories = {str(parent) for name in expected_files for parent in Path(name).parents if str(parent) != "."}
    actual = set()
    for path in directory.rglob("*"):
        require(not path.is_symlink(), "Symlinked stage entry is not allowed")
        relative = path.relative_to(directory).as_posix()
        if path.is_dir():
            require(relative in directories, "Unexpected staged directory")
        else:
            require(path.is_file(), "Unexpected staged file type")
            actual.add(relative)
    require(actual == expected_files, "Unexpected or missing staged files")
    require(isinstance(manifest["files"], list) and len(manifest["files"]) == len(expected),
            "Unexpected manifest inventory")
    entries = {}
    for entry in manifest["files"]:
        require(isinstance(entry, dict) and set(entry) ==
                {"path", "size", "sha256", "contentType", "cacheControl"}, "Unexpected object metadata")
        key = entry["path"]
        require(isinstance(key, str) and key in expected and key not in entries, "Unexpected or duplicate object path")
        require(type(entry["size"]) is int and 0 < entry["size"] <= MAX_FILE_BYTES, "Invalid object size")
        require(entry["contentType"] == expected[key] and entry["cacheControl"] == CACHE,
                "Unexpected object HTTP metadata")
        entries[key] = entry
    require(sum(entry["size"] for entry in entries.values()) <= MAX_TOTAL_BYTES, "Stage is too large")
    objects = []
    data_by_key = {}
    for key, entry in sorted(entries.items()):
        path = directory / "repository" / key
        require(path.stat().st_size == entry["size"], "Staged size differs from manifest")
        data = path.read_bytes()
        require(len(data) == entry["size"] and sha256(data) == entry["sha256"], "Staged bytes differ from manifest")
        data_by_key[key] = data
        objects.append(entry | {"data": data})
    for key in artifacts:
        for algorithm in ALGORITHMS:
            require(data_by_key[key + "." + algorithm].decode("ascii").strip() ==
                    hashlib.new(algorithm, data_by_key[key]).hexdigest(), "Artifact checksum differs")
    return {"manifest": manifest, "manifestSha256": sha256(manifest_bytes), "objects": objects}


def plan(stage, source_sha):
    require(isinstance(source_sha, str) and SHA.fullmatch(source_sha), "Use the full source Git SHA")
    return {"schemaVersion": 1, "bucket": BUCKET, "coordinate": stage["manifest"]["coordinate"],
            "sourceSha": source_sha, "manifestSha256": stage["manifestSha256"],
            "objects": len(stage["objects"]), "bytes": sum(o["size"] for o in stage["objects"]),
            "mode": "plan", "networkRequests": 0}


def approve(release_plan, approval):
    require(isinstance(approval, dict) and set(approval) ==
            {"schemaVersion", "approved", "coordinate", "sourceSha", "manifestSha256"},
            "Unexpected approval record")
    require(type(approval["schemaVersion"]) is int and approval["schemaVersion"] == 1 and
            approval["approved"] is True, "Public release is not approved")
    require(all(approval[field] == release_plan[field]
                for field in ("coordinate", "sourceSha", "manifestSha256")),
            "Approval does not match the exact source and artifact manifest")


def publish(stage, source_sha, approval, store):
    """Never replace existing objects, including after partial/uncertain runs."""
    release_plan = plan(stage, source_sha)
    approve(release_plan, approval)
    # Byte files and their checksums first, then POM and finally Gradle metadata.
    # Exact-version clients can still observe partial publication; retry resumes
    # identical files and never deletes a partially uploaded release.
    def priority(obj):
        return (2 if obj["path"].endswith(".module") else 1 if obj["path"].endswith(".pom") else 0, obj["path"])
    created, existing = 0, 0
    for obj in sorted(stage["objects"], key=priority):
        try:
            store.put_if_absent(obj)
            created += 1
        except AlreadyExists:
            existing += 1
        stored, metadata = store.get(obj["path"], obj["size"])
        require(stored == obj["data"] and metadata ==
                {"contentType": obj["contentType"], "cacheControl": obj["cacheControl"], "sha256": obj["sha256"]},
                "Existing or uploaded object differs; release was not overwritten")
    return release_plan | {"mode": "applied", "created": created, "identicalExisting": existing,
                           "networkRequests": len(stage["objects"]) * 2}


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        return None


class R2Store:
    """Fixed-endpoint S3 SigV4 client; no listing, deletion or admin API."""
    def __init__(self, access_key, secret_key, opener=None, clock=None):
        require(re.fullmatch(r"[A-Za-z0-9]{16,128}", access_key or "") and
                re.fullmatch(r"[A-Za-z0-9/+_=.-]{32,256}", secret_key or ""), "R2 publishing credentials are missing or invalid")
        self.access_key, self.secret_key = access_key, secret_key
        self.opener = opener or build_opener(NoRedirect())
        self.clock = clock or (lambda: datetime.now(timezone.utc))

    def signed_request(self, method, key, data=b"", metadata=None, bucket=BUCKET):
        if method == "HEAD":
            require(bucket in (BUCKET, STAGING_BUCKET) and re.fullmatch(r"ci-access-check/nonexistent-[0-9a-f]{32}", key),
                    "Unexpected read-only access probe")
        else:
            require(bucket == BUCKET and key.startswith(PREFIX + "/") and
                    not any(part in (".", "..", "") for part in key.split("/")), "Unexpected R2 object key")
        timestamp = self.clock().strftime("%Y%m%dT%H%M%SZ")
        day = timestamp[:8]
        path = "/" + bucket + "/" + quote(key, safe="/-_.~")
        payload_hash = sha256(data)
        headers = {"host": HOST, "x-amz-content-sha256": payload_hash, "x-amz-date": timestamp}
        if method == "PUT":
            require(metadata is not None, "PUT metadata is required")
            headers |= {"if-none-match": "*", "content-type": metadata["contentType"],
                        "cache-control": metadata["cacheControl"], "x-amz-meta-sha256": metadata["sha256"]}
        require(method in ("PUT", "GET", "HEAD"), "Unsupported R2 operation")
        names = ";".join(sorted(headers))
        canonical_headers = "".join(name + ":" + " ".join(headers[name].split()) + "\n" for name in sorted(headers))
        canonical = "\n".join((method, path, "", canonical_headers, names, payload_hash))
        scope = day + "/auto/s3/aws4_request"
        to_sign = "AWS4-HMAC-SHA256\n" + timestamp + "\n" + scope + "\n" + sha256(canonical.encode())
        signing = ("AWS4" + self.secret_key).encode()
        for part in (day, "auto", "s3", "aws4_request"):
            signing = hmac.new(signing, part.encode(), hashlib.sha256).digest()
        signature = hmac.new(signing, to_sign.encode(), hashlib.sha256).hexdigest()
        headers["authorization"] = ("AWS4-HMAC-SHA256 Credential=" + self.access_key + "/" + scope +
                                    ", SignedHeaders=" + names + ", Signature=" + signature)
        headers["user-agent"] = "Luximio-Maven-Publisher/1"
        return Request("https://" + HOST + path, data=data if method == "PUT" else None,
                       method=method, headers=headers)

    def request(self, request, limit):
        try:
            with self.opener.open(request, timeout=60) as response:
                require(response.status == 200 and response.url == request.full_url, "Unexpected R2 response")
                body = response.read(limit + 1)
                require(len(body) <= limit, "R2 response exceeds expected size")
                return body, response.headers
        except HTTPError as error:
            code = error.code
            error.close()
            if request.method == "PUT" and code in (409, 412):
                raise AlreadyExists() from None
            raise PublishError("R2 request failed with HTTP " + str(code)) from None
        except (URLError, TimeoutError, OSError, HTTPException):
            raise PublishError("R2 network request failed; retry the same approved release") from None

    def put_if_absent(self, obj):
        self.request(self.signed_request("PUT", obj["path"], obj["data"], obj), 0)

    def get(self, key, expected_size):
        data, headers = self.request(self.signed_request("GET", key), expected_size)
        return data, {"contentType": headers.get("Content-Type"), "cacheControl": headers.get("Cache-Control"),
                      "sha256": headers.get("x-amz-meta-sha256")}

    def access_status(self, bucket, key):
        request = self.signed_request("HEAD", key, bucket=bucket)
        try:
            with self.opener.open(request, timeout=60) as response:
                return response.status
        except HTTPError as error:
            code = error.code
            error.close()
            return code  # HEAD probes never inspect response bodies.
        except (URLError, TimeoutError, OSError, HTTPException):
            raise PublishError("R2 access probe network request failed") from None


def check_access(store):
    key = "ci-access-check/nonexistent-" + uuid.uuid4().hex
    target = store.access_status(BUCKET, key)
    require(target == 404, "R2 distribution-bucket access check failed (expected HTTP 404)")
    other = store.access_status(STAGING_BUCKET, key)
    require(other == 403, "R2 private staging-bucket isolation check failed (expected HTTP 403)")
    return {"mode": "access-check", "bucket": BUCKET, "distributionStatus": target,
            "privateStagingStatus": other, "networkRequests": 2, "objectWrites": 0}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stage", type=Path, nargs="?")
    parser.add_argument("--source-sha")
    parser.add_argument("--sdk-root", type=Path, default=SDK_ROOT)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--check-approval", action="store_true", help="Check exact approval offline; do not read credentials")
    parser.add_argument("--check-access", action="store_true", help="HEAD-only credential scope check; no SDK upload")
    parser.add_argument("--approval", type=Path, default=SDK_ROOT / "scripts/release-approval.json")
    args = parser.parse_args()
    try:
        if args.check_access:
            require(args.stage is None and args.source_sha is None and not args.apply and not args.check_approval,
                    "Access check cannot be combined with release arguments")
            store = R2Store(os.environ.get("R2_ACCESS_KEY_ID"), os.environ.get("R2_SECRET_ACCESS_KEY"))
            print(json.dumps(check_access(store), indent=2))
            return 0
        require(not (args.apply and args.check_approval), "Use --apply or --check-approval, not both")
        require(args.stage is not None and args.source_sha is not None, "A stage and --source-sha are required")
        # The staging helper also verifies source identity, licenses and dependencies.
        from stage_maven import verify_stage
        verify_stage(args.stage, sdk_root=args.sdk_root)
        stage = validate_stage(args.stage)
        source = subprocess.check_output(["git", "-C", str(args.sdk_root), "rev-parse", "HEAD"], text=True).strip()
        require(source == args.source_sha, "Source checkout does not match the requested Git SHA")
        status = subprocess.check_output(["git", "-C", str(args.sdk_root), "status", "--porcelain", "--untracked-files=all"], text=True)
        require(not status.strip(), "Source checkout has modified or untracked files")
        release_plan = plan(stage, source)
        if args.apply or args.check_approval:
            approval = json.loads(no_links(args.approval).read_text(), object_pairs_hook=unique_object)
            approve(release_plan, approval)  # Credentials are not read before all gates pass.
            release_plan["approval"] = "matched"
        if args.apply:
            store = R2Store(os.environ.get("R2_ACCESS_KEY_ID"), os.environ.get("R2_SECRET_ACCESS_KEY"))
            release_plan = publish(stage, source, approval, store)
        print(json.dumps(release_plan, indent=2))
        return 0
    except (ValueError, OSError, UnicodeError, subprocess.SubprocessError) as error:
        print("Maven publication failed: " + str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
