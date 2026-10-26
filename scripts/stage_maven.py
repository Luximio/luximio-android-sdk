#!/usr/bin/env python3
"""Validate and stage the standalone Android SDK; no network or credentials.

Only the reviewed development coordinate is supported. The private review ZIP
is checked before staging, then excluded. A staged inventory is rechecked
against this checkout's source and legal files before any publishing step.
"""
import argparse
import hashlib
from io import BytesIO
import json
from pathlib import Path
import shutil
import sys
import tempfile
import xml.etree.ElementTree as ET
from zipfile import BadZipFile, ZipFile

SDK_ROOT = Path(__file__).resolve().parents[1]
MAX_FILE_SIZE = 8 * 1024 * 1024
PREFIX = "app/luximio/luximio-links-android"
ALGORITHMS = ("md5", "sha1", "sha256", "sha512")
CONTENT_TYPES = {".aar": "application/java-archive", ".pom": "application/xml",
                 ".module": "application/json", "-sources.jar": "application/java-archive"}
CACHE_CONTROL = "public, max-age=31536000, immutable"

VERSION = "0.1.0-dev.1"
NAME = f"luximio-links-android-{VERSION}"
COORDINATE = f"app.luximio:luximio-links-android:{VERSION}"
ANDROID = "{http://schemas.android.com/apk/res/android}"
POM = "{http://maven.apache.org/POM/4.0.0}"
APACHE_LICENSE_SHA256 = "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30"
LICENSE_METADATA = {
    "name": "Apache License, Version 2.0",
    "url": "https://www.apache.org/licenses/LICENSE-2.0.txt",
    "distribution": "repo",
}
SCM_METADATA = {
    "url": "https://github.com/Luximio/luximio-android-sdk",
    "connection": "scm:git:https://github.com/Luximio/luximio-android-sdk.git",
}
NOTICE_PATH = "META-INF/app.luximio/luximio-links-android"
API = {
    ("org.jetbrains.kotlinx", "kotlinx-coroutines-core"): "1.11.0",
    ("org.jetbrains.kotlinx", "kotlinx-serialization-json"): "1.11.0",
    ("org.jetbrains.kotlin", "kotlin-stdlib"): "2.3.21",
}
RUNTIME = {
    ("com.android.installreferrer", "installreferrer"): "2.2",
    ("com.google.android.play", "integrity"): "1.6.0",
    ("org.jetbrains.kotlinx", "kotlinx-coroutines-android"): "1.11.0",
    ("org.jetbrains.kotlinx", "kotlinx-coroutines-play-services"): "1.11.0",
    ("com.squareup.okhttp3", "okhttp"): "5.5.1",
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def files(archive):
    entries = archive.namelist()
    require(len(entries) <= 4096 and sum(i.file_size for i in archive.infolist()) <= 32 * 1024 * 1024,
            "Archive is oversized")
    require(not any((i.external_attr >> 16) & 0o170000 == 0o120000 for i in archive.infolist()),
            "Archive symlink is not allowed")
    require(len(entries) == len(set(entries)), "Duplicate ZIP entries")
    require(all(not n.startswith("/") and ".." not in Path(n).parts and "\\" not in n for n in entries),
            "Unexpected ZIP path")
    return {n for n in entries if not n.endswith("/")}


def verify_artifacts(repository, sdk_root, review=False):
    sdk_root = checked_path(sdk_root)
    notices = {"LICENSE": sdk_root / "LICENSE", "NOTICE": sdk_root / "NOTICE"}
    require(all(path.is_file() and not path.is_symlink() for path in notices.values()),
            "Missing or linked SDK legal file")
    require(hashlib.sha256(notices["LICENSE"].read_bytes()).hexdigest() == APACHE_LICENSE_SHA256,
            "SDK LICENSE differs from the approved Apache License 2.0 text")
    require(notices["NOTICE"].read_text() ==
            "Luximio Links Android SDK\nCopyright 2026 Dot Square\n\n"
            "This NOTICE applies to the Luximio Links Android SDK.\n",
            "SDK NOTICE differs from the approved SDK attribution")
    archive_notices = {f"{NOTICE_PATH}/{name}": path for name, path in notices.items()}
    directory = repository / f"app/luximio/luximio-links-android/{VERSION}"
    suffixes = (".aar", ".pom", ".module", "-sources.jar") + (("-review-docs.zip",) if review else ())
    artifacts = {suffix: directory / f"{NAME}{suffix}" for suffix in suffixes}
    allowed = {p.name for p in artifacts.values()}
    allowed |= {f"{n}.{h}" for n in tuple(allowed) for h in ("md5", "sha1", "sha256", "sha512")}
    require(directory.is_dir(), "Local candidate directory is missing")
    require({p.name for p in directory.iterdir()} == allowed, "Unexpected or missing publication files")
    require(all(p.is_file() and not p.is_symlink() and p.stat().st_size <= MAX_FILE_SIZE
                for p in directory.iterdir()), "Linked, non-file or oversized publication file")
    hashes = {}
    for suffix, path in artifacts.items():
        data = path.read_bytes()
        hashes[path.name] = hashlib.sha256(data).hexdigest()
        for algorithm in ("md5", "sha1", "sha256", "sha512"):
            expected = hashlib.new(algorithm, data).hexdigest()
            require(Path(f"{path}.{algorithm}").read_text().strip() == expected,
                    f"Publication checksum mismatch: {suffix}/{algorithm}")

    pom = ET.parse(artifacts[".pom"]).getroot()
    for key, value in {"groupId": "app.luximio", "artifactId": "luximio-links-android",
                       "version": VERSION, "packaging": "aar"}.items():
        require(pom.findtext(POM + key) == value, f"Wrong POM {key}")
    require(pom.find(POM + "repositories") is None, "POM embeds repositories")
    licenses = pom.find(POM + "licenses")
    require(licenses is not None and len(licenses) == 1 and licenses[0].tag == POM + "license",
            "POM must declare exactly the approved Apache License 2.0 license")
    license_fields = list(licenses[0])
    require(len(license_fields) == len(LICENSE_METADATA) and
            {field.tag.removeprefix(POM): field.text for field in license_fields} == LICENSE_METADATA,
            "POM license differs from approved Apache License 2.0 metadata")
    scm = pom.find(POM + "scm")
    require(scm is not None and len(scm) == len(SCM_METADATA) and
            {field.tag.removeprefix(POM): field.text for field in scm} == SCM_METADATA,
            "POM SCM differs from the selected SDK repository")
    dependencies = pom.findall(f"{POM}dependencies/{POM}dependency")
    actual = {(d.findtext(POM + "groupId"), d.findtext(POM + "artifactId")):
              (d.findtext(POM + "version"), d.findtext(POM + "scope")) for d in dependencies}
    expected = {k: (v, "compile") for k, v in API.items()} | {k: (v, "runtime") for k, v in RUNTIME.items()}
    require(len(dependencies) == len(actual) and actual == expected, "POM dependency/scope mismatch")

    metadata = json.loads(artifacts[".module"].read_text())
    component = metadata["component"]
    require((component["group"], component["module"], component["version"]) ==
            ("app.luximio", "luximio-links-android", VERSION), "Wrong module coordinate")
    variants = metadata["variants"]
    require(len(variants) == 3, "Unexpected publication variants")
    for usage, deps in (("java-api", API), ("java-runtime", API | RUNTIME)):
        matches = [v for v in variants if v["attributes"].get("org.gradle.usage") == usage
                   and v["attributes"].get("org.gradle.category") == "library"]
        require(len(matches) == 1, f"Missing or ambiguous {usage} variant")
        variant = matches[0]
        entries = variant["dependencies"]
        actual = {(d["group"], d["module"]): d["version"]["requires"] for d in entries}
        require(len(entries) == len(actual) and actual == deps, f"Module {usage} dependency mismatch")
        require([f["name"] for f in variant["files"]] == [artifacts[".aar"].name], "Wrong library artifact")
    for variant in variants:
        for entry in variant.get("files", []):
            require(entry["url"] == entry["name"] and entry["name"] in hashes,
                    "Unexpected module artifact URL")
            path = directory / entry["name"]
            require(entry["sha256"] == hashes[path.name] and entry["size"] == path.stat().st_size,
                    "Module artifact hash/size mismatch")
    source_variants = [v for v in variants if v["attributes"].get("org.gradle.docstype") == "sources"]
    require(len(source_variants) == 1 and [f["name"] for f in source_variants[0]["files"]] ==
            [artifacts["-sources.jar"].name], "Sources variant is missing or points to the wrong artifact")

    with ZipFile(artifacts[".aar"]) as aar:
        require(files(aar) == {"R.txt", "AndroidManifest.xml", "classes.jar",
                              "META-INF/com/android/build/gradle/aar-metadata.properties"},
                "Unexpected AAR contents (resources, assets or native libraries)")
        manifest = ET.fromstring(aar.read("AndroidManifest.xml"))
        require(manifest.attrib == {"package": "app.luximio.links"}, "Wrong SDK manifest package")
        require(len(manifest) == 1 and manifest[0].tag == "uses-sdk" and
                manifest[0].attrib == {ANDROID + "minSdkVersion": "26"},
                "SDK adds unexpected permissions, components or SDK requirements")
        properties = dict(line.split("=", 1) for line in
                          aar.read("META-INF/com/android/build/gradle/aar-metadata.properties").decode().splitlines()
                          if "=" in line and not line.startswith("#"))
        require(properties.get("minCompileSdk") == "37", "Unexpected minimum compile SDK")
        with ZipFile(BytesIO(aar.read("classes.jar"))) as classes:
            members = files(classes)
            allowed_metadata = {"META-INF/MANIFEST.MF", "META-INF/luximio-links-android_release.kotlin_module",
                                "META-INF/links_release.kotlin_module"} | set(archive_notices)
            require(all(n in allowed_metadata or (n.startswith("app/luximio/links/") and n.endswith(".class"))
                        for n in members), "Classes outside the SDK namespace")
            require(not any("Fixture" in n or n.endswith("Test.class") for n in members),
                    "Test/fixture classes in AAR")
            require("app/luximio/links/CustomerPlaySession.class" in members, "Customer adapter is missing")
            require(any("$$serializer.class" in n for n in members), "Generated serializers are missing")
            require(set(archive_notices) <= members, "AAR SDK license/notice is missing")
            for name, path in archive_notices.items():
                require(classes.read(name) == path.read_bytes(), "AAR SDK license/notice is stale")

    source_root = checked_path(sdk_root / "src/main/kotlin")
    require(source_root.is_dir() and not any(p.is_symlink() for p in source_root.rglob("*")),
            "Linked or missing main source tree")
    source_files = {str(p.relative_to(source_root)): p for p in source_root.rglob("*.kt")}
    with ZipFile(artifacts["-sources.jar"]) as sources:
        require(files(sources) - {"META-INF/MANIFEST.MF"} == set(source_files) | set(archive_notices),
                "Sources JAR contains missing, extra or non-main sources")
        for name, path in source_files.items():
            require(sources.read(name) == path.read_bytes(), "Sources JAR is stale")
        for name, path in archive_notices.items():
            require(sources.read(name) == path.read_bytes(), "Sources JAR SDK license/notice is stale")

    if review:
        documents = {"README.md": sdk_root / "README.md", "PACKAGING_NOTICE.md": sdk_root / "PACKAGING_NOTICE.md"} | notices
        docs = checked_path(sdk_root / "docs")
        names = {path.name for path in docs.glob("*.md")}
        require({"integration.md", "privacy.md", "dependencies.md"} <= names <=
                {"integration.md", "privacy.md", "dependencies.md", "publishing.md"},
                "Review-document source inventory changed")
        documents |= {f"docs/{name}": docs / name for name in names}
        with ZipFile(artifacts["-review-docs.zip"]) as archive:
            require(files(archive) == set(documents), "Unexpected review-document contents")
            for name, path in documents.items():
                require(not path.is_symlink() and archive.read(name) == path.read_bytes(),
                        "Review-document archive is stale")
    return {"coordinate": COORDINATE, "result": "passed", "minSdk": 26, "minCompileSdk": 37,
            "sourceFiles": len(source_files), "artifactSha256": hashes,
            "license": "Apache-2.0", "sourceRepository": SCM_METADATA["url"],
            "scope": "Local metadata/content verification; no runtime, license or publication approval"}



def checked_path(path):
    path = Path(path).absolute()
    require(not any(p.is_symlink() for p in (*path.parents, path)), "Symlinked path is not allowed")
    return path.resolve()


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "Duplicate JSON field")
        result[key] = value
    return result


def publication_paths():
    artifacts = {f"{PREFIX}/{VERSION}/{NAME}{suffix}": kind for suffix, kind in CONTENT_TYPES.items()}
    return artifacts | {f"{path}.{algorithm}": "text/plain; charset=utf-8"
                        for path in artifacts for algorithm in ALGORITHMS}


def manifest_for(repository):
    result = []
    for relative, content_type in sorted(publication_paths().items()):
        data = (repository / relative).read_bytes()
        result.append({"path": relative, "size": len(data), "sha256": hashlib.sha256(data).hexdigest(),
                       "contentType": content_type, "cacheControl": CACHE_CONTROL})
    return {"schemaVersion": 1, "coordinate": COORDINATE, "version": VERSION, "files": result}


def verify_stage(stage, sdk_root=SDK_ROOT):
    stage = checked_path(stage)
    require(stage.is_dir(), "Stage is missing")
    expected = {"manifest.json"} | {"repository/" + path for path in publication_paths()}
    directories = {str(parent) for name in expected for parent in Path(name).parents if str(parent) != "."}
    actual = set()
    for path in stage.rglob("*"):
        require(not path.is_symlink(), "Symlink in stage")
        relative = path.relative_to(stage).as_posix()
        if path.is_dir():
            require(relative in directories, "Unexpected staged directory")
        else:
            require(path.is_file() and path.stat().st_size <= MAX_FILE_SIZE, "Invalid staged file")
            actual.add(relative)
    require(actual == expected, "Unexpected or missing staged files")
    manifest = json.loads((stage / "manifest.json").read_text(), object_pairs_hook=unique_object)
    require(isinstance(manifest, dict) and type(manifest.get("schemaVersion")) is int, "Invalid manifest schema")
    require(manifest == manifest_for(stage / "repository"), "Stage manifest integrity mismatch")
    verify_artifacts(stage / "repository", sdk_root)
    return manifest


def prepare(source, output, sdk_root=SDK_ROOT):
    source, output = checked_path(source), checked_path(output)
    require(source.is_dir(), "Candidate repository is missing")
    require(not output.exists(), "Stage output exists; use a fresh directory")
    require(not output.is_relative_to(source) and not source.is_relative_to(output), "Source and stage overlap")
    directory = checked_path(source / PREFIX / VERSION)
    require(directory.is_dir(), "Candidate version is missing")
    require(all(path.is_file() and not path.is_symlink() for path in directory.iterdir()),
            "Linked or non-file candidate entry")
    with tempfile.TemporaryDirectory(prefix="luximio-stage-check-") as temporary:
        snapshot = Path(temporary)
        target = snapshot / PREFIX / VERSION
        target.mkdir(parents=True)
        for path in directory.iterdir():
            require(path.stat().st_size <= MAX_FILE_SIZE, "Candidate file is oversized")
            (target / path.name).write_bytes(path.read_bytes())
        verify_artifacts(snapshot, sdk_root, review=True)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.mkdir(mode=0o700)
        try:
            for relative in publication_paths():
                path = output / "repository" / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes((snapshot / relative).read_bytes())
            manifest = manifest_for(output / "repository")
            (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
            verify_stage(output, sdk_root)
        except Exception:
            shutil.rmtree(output)
            raise
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("prepare", "verify"))
    parser.add_argument("--source", type=Path, default=SDK_ROOT / "build/local-maven")
    parser.add_argument("--output", type=Path, default=SDK_ROOT / "build/r2-stage")
    parser.add_argument("--stage", type=Path, default=SDK_ROOT / "build/r2-stage")
    parser.add_argument("--sdk-root", type=Path, default=SDK_ROOT)
    args = parser.parse_args()
    try:
        manifest = prepare(args.source, args.output, args.sdk_root) if args.action == "prepare" else verify_stage(args.stage, args.sdk_root)
        print(json.dumps({"result": "passed", "coordinate": manifest["coordinate"], "files": len(manifest["files"]),
                          "scope": "Offline staging validation; no upload"}, indent=2))
    except (ValueError, OSError, KeyError, TypeError, BadZipFile, ET.ParseError) as error:
        print(f"Android Maven staging failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
