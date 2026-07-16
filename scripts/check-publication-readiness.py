#!/usr/bin/env python3
"""Fail-closed checks for Pixelify Infinity source and release artifacts."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import ssl
import subprocess
import sys
import urllib.parse
import zipfile

ROOT = Path(__file__).resolve().parents[1]
APP_ID = "io.github.samson910022.pixelifyphotos"
ENTRY_POINT = f"{APP_ID}.PixelifyModule"
SCOPE = "com.google.android.apps.photos"
VERSION_CODE = 3
VERSION_NAME = "1.0.2"
CERT_SHA256 = "37186E5C2694E553E5FAB1F7787C04DBCD4384AB84963E60BE9C3CCB6BA907B1"
PUBLIC_CERT = Path("certificates/pixelifyphotos-release-cert.pem")

errors: list[str] = []
checks = 0


def fail(message: str) -> None:
    errors.append(message)


def check(condition: bool, message: str) -> None:
    global checks
    checks += 1
    if not condition:
        fail(message)


def text(relative: str | Path) -> str:
    path = ROOT / relative
    try:
        return path.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as exc:
        fail(f"cannot read {relative}: {exc}")
        return ""


def exact_match(pattern: str, content: str, description: str) -> str:
    matches = re.findall(pattern, content, flags=re.MULTILINE)
    check(len(matches) == 1, f"expected exactly one {description}, found {len(matches)}")
    return matches[0] if len(matches) == 1 else ""


def check_identity_and_versions() -> None:
    gradle = text("app/build.gradle.kts")
    namespace = exact_match(r'^\s*namespace\s*=\s*"([^"]+)"', gradle, "namespace")
    application_id = exact_match(r'^\s*applicationId\s*=\s*"([^"]+)"', gradle, "applicationId")
    version_code = exact_match(r"^\s*versionCode\s*=\s*(\d+)", gradle, "versionCode")
    version_name = exact_match(r'^\s*versionName\s*=\s*"([^"]+)"', gradle, "versionName")
    expected_fingerprint = exact_match(
        r'val expectedReleaseCertificateSha256\s*=\s*\n?\s*"([0-9A-Fa-f:]+)"',
        gradle,
        "expected release certificate fingerprint",
    ).replace(":", "").upper()

    check(namespace == APP_ID, f"namespace must be {APP_ID}")
    check(application_id == APP_ID, f"applicationId must be {APP_ID}")
    check(version_code == str(VERSION_CODE), f"versionCode must be {VERSION_CODE}")
    check(version_name == VERSION_NAME, f"versionName must be {VERSION_NAME}")
    check(expected_fingerprint == CERT_SHA256, "Gradle approved release fingerprint changed")

    source_roots = [ROOT / "app/src/main/java", ROOT / "app/src/test/java"]
    kotlin_files = [path for base in source_roots for path in base.rglob("*.kt")]
    check(bool(kotlin_files), "no Kotlin source files found")
    expected_path = Path(*APP_ID.split("."))
    for path in kotlin_files:
        relative_to_java = path.relative_to(next(base for base in source_roots if path.is_relative_to(base)))
        check(relative_to_java.parent == expected_path, f"wrong Kotlin package path: {path.relative_to(ROOT)}")
        declaration = exact_match(r"^package\s+([A-Za-z0-9_.]+)\s*$", text(path.relative_to(ROOT)), f"package declaration in {path.relative_to(ROOT)}")
        check(declaration == APP_ID, f"wrong package declaration in {path.relative_to(ROOT)}")

    check(text("app/src/main/resources/META-INF/xposed/java_init.list").strip() == ENTRY_POINT, "wrong Xposed entry point")
    check(text("app/src/main/resources/META-INF/xposed/scope.list").strip() == SCOPE, "wrong Xposed scope")
    module_properties = {
        key: value
        for key, value in (
            line.split("=", 1)
            for line in text("app/src/main/resources/META-INF/xposed/module.prop").splitlines()
            if line and not line.startswith("#") and "=" in line
        )
    }
    check(module_properties == {"minApiVersion": "101", "targetApiVersion": "101", "staticScope": "true"}, "unexpected Xposed module.prop")

    for feed in (Path("update_info.json"), Path("distribution/xposed-repository/update_info.json")):
        try:
            value = json.loads(text(feed))
        except json.JSONDecodeError as exc:
            fail(f"invalid JSON in {feed}: {exc}")
            continue
        check(value == {"latest_version_code": VERSION_CODE}, f"{feed} is not synchronized with versionCode")

    try:
        mirror_scope = json.loads(text("distribution/xposed-repository/SCOPE"))
    except json.JSONDecodeError as exc:
        fail(f"invalid Xposed repository SCOPE: {exc}")
    else:
        check(mirror_scope == [SCOPE], "Xposed repository SCOPE must contain only Google Photos")

    changelog = text("CHANGELOG.md")
    check(f"## [{VERSION_NAME}] - " in changelog, "CHANGELOG current version heading is missing")
    check(f"versionCode {VERSION_CODE}" in changelog, "CHANGELOG versionCode is missing")
    for strings_file in ("app/src/main/res/values/strings.xml", "app/src/main/res/values-zh-rTW/strings.xml"):
        check(VERSION_NAME in text(strings_file), f"{strings_file} does not mention versionName")


def check_manifest_and_sources() -> None:
    manifest = text("app/src/main/AndroidManifest.xml")
    check('android:authorities="${applicationId}.fileprovider"' in manifest, "FileProvider authority is not applicationId-derived")
    check('android:allowBackup="false"' in manifest, "Android backup must remain disabled")
    check('android:dataExtractionRules="@xml/data_extraction_rules"' in manifest, "data extraction rules are not configured")
    check(f'<package android:name="{SCOPE}"' in manifest, "Google Photos package query is missing")
    activity_main = text(f"app/src/main/java/{APP_ID.replace('.', '/')}/ActivityMain.kt")
    check('"${BuildConfig.APPLICATION_ID}.fileprovider"' in activity_main, "FileProvider use does not match BuildConfig.APPLICATION_ID")


def check_certificate() -> None:
    certificate_pem = text(PUBLIC_CERT)
    check("BEGIN CERTIFICATE" in certificate_pem and "PRIVATE KEY" not in certificate_pem, "public certificate file is invalid or contains private material")
    try:
        der = ssl.PEM_cert_to_DER_cert(certificate_pem)
        actual = hashlib.sha256(der).hexdigest().upper()
    except (ValueError, ssl.SSLError) as exc:
        fail(f"cannot parse public release certificate: {exc}")
        return
    check(actual == CERT_SHA256, f"public release certificate fingerprint mismatch: {actual}")
    signing_doc = text("docs/RELEASE_SIGNING.md")
    documented_hex = "".join(re.findall(r"[0-9A-Fa-f]", signing_doc))
    check(CERT_SHA256 in documented_hex.upper(), "release signing documentation does not contain the approved fingerprint")
    check(f"`{APP_ID}`" in signing_doc, "release signing documentation has the wrong application ID")


def markdown_files() -> list[Path]:
    result: list[Path] = []
    for path in ROOT.rglob("*.md"):
        if any(part in {".git", "build", ".gradle"} for part in path.parts):
            continue
        result.append(path)
    return sorted(result)


def check_markdown_links() -> None:
    link_pattern = re.compile(r"(?<!!)\[[^\]]+\]\(([^)]+)\)")
    for path in markdown_files():
        content = text(path.relative_to(ROOT))
        for raw_target in link_pattern.findall(content):
            target = raw_target.strip().split(maxsplit=1)[0].strip("<>")
            parsed = urllib.parse.urlparse(target)
            if parsed.scheme or target.startswith(("#", "mailto:")):
                continue
            local_path = urllib.parse.unquote(target.split("#", 1)[0])
            if not local_path:
                continue
            resolved = (path.parent / local_path).resolve()
            try:
                resolved.relative_to(ROOT.resolve())
            except ValueError:
                fail(f"Markdown link escapes repository: {path.relative_to(ROOT)} -> {target}")
                continue
            check(resolved.exists(), f"broken Markdown link: {path.relative_to(ROOT)} -> {target}")

    canonical_nav = [
        "[English](README.md)",
        "[繁體中文](docs/README.zh-TW.md)",
        "[简体中文](docs/README.zh-CN.md)",
        "[日本語](docs/README.ja.md)",
    ]
    readme = text("README.md")
    for item in canonical_nav:
        check(item in readme, f"README language navigation is missing {item}")
    translated_nav = [
        "[English](../README.md)",
        "[繁體中文](README.zh-TW.md)",
        "[简体中文](README.zh-CN.md)",
        "[日本語](README.ja.md)",
    ]
    for translated in ("docs/README.zh-TW.md", "docs/README.zh-CN.md", "docs/README.ja.md"):
        content = text(translated)
        for item in translated_nav:
            check(item in content, f"{translated} language navigation is missing {item}")
        check("../README.md" in content, f"{translated} does not link to canonical English README")


def repository_files() -> list[Path]:
    try:
        output = subprocess.check_output(
            ["git", "ls-files", "-co", "--exclude-standard", "-z"],
            cwd=ROOT,
        )
    except (OSError, subprocess.CalledProcessError) as exc:
        fail(f"cannot enumerate repository files: {exc}")
        return []
    return [Path(name.decode("utf-8")) for name in output.split(b"\0") if name]


def check_sensitive_content() -> None:
    allowed_sensitive_extension = {PUBLIC_CERT}
    forbidden_suffixes = {".jks", ".keystore", ".p12", ".pfx", ".pk8", ".pkcs8", ".key"}
    forbidden_names = {
        "key.properties", "keystore.properties", "signing.properties", "release.properties",
        "publication.properties", ".env", "google-services.json",
    }
    # Build markers from fragments so secret scanners do not flag the scanner itself.
    private_key_markers = tuple(
        b"-----BEGIN " + label + b"-----"
        for label in (
            b"PRIVATE KEY",
            b"ENCRYPTED PRIVATE KEY",
            b"RSA PRIVATE KEY",
            b"EC PRIVATE KEY",
            b"OPENSSH PRIVATE KEY",
        )
    )
    local_path_pattern = re.compile(rb"(?:/home/|/Users/|/mnt/|/run/media/)[A-Za-z0-9._~/-]+")

    for relative in repository_files():
        lower_name = relative.name.lower()
        suffix = relative.suffix.lower()
        if relative not in allowed_sensitive_extension:
            check(suffix not in forbidden_suffixes, f"forbidden private-key/keystore file: {relative}")
            check(lower_name not in forbidden_names, f"forbidden signing/credential file: {relative}")
            check(suffix not in {".pem", ".der", ".cer", ".crt"}, f"unapproved certificate/key file: {relative}")
        path = ROOT / relative
        try:
            if not path.is_file() or path.stat().st_size > 5_000_000:
                continue
            data = path.read_bytes()
        except OSError as exc:
            fail(f"cannot inspect {relative}: {exc}")
            continue
        check(not any(marker in data for marker in private_key_markers), f"private-key material detected in {relative}")
        if relative != Path("scripts/check-publication-readiness.py"):
            check(local_path_pattern.search(data) is None, f"local absolute path detected in {relative}")


def find_aapt2() -> Path | None:
    explicit = os.environ.get("AAPT2")
    if explicit and Path(explicit).is_file():
        return Path(explicit)
    for env_name in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        sdk = os.environ.get(env_name)
        if not sdk:
            continue
        build_tools = Path(sdk) / "build-tools"
        if build_tools.is_dir():
            candidates = sorted(build_tools.glob("*/aapt2"), reverse=True)
            if candidates:
                return candidates[0]
    return None


def verify_xposed_zip(path: Path, prefix: str) -> None:
    expected = {
        f"{prefix}META-INF/xposed/java_init.list": ENTRY_POINT,
        f"{prefix}META-INF/xposed/scope.list": SCOPE,
        f"{prefix}META-INF/xposed/module.prop": "minApiVersion=101\ntargetApiVersion=101\nstaticScope=true",
    }
    try:
        with zipfile.ZipFile(path) as archive:
            for member, expected_content in expected.items():
                check(member in archive.namelist(), f"{path.relative_to(ROOT)} is missing {member}")
                if member in archive.namelist():
                    actual = archive.read(member).decode("utf-8").strip()
                    check(actual == expected_content, f"{path.relative_to(ROOT)} has incorrect {member}")
    except (OSError, zipfile.BadZipFile, UnicodeError) as exc:
        fail(f"cannot inspect {path.relative_to(ROOT)}: {exc}")


def check_artifacts(required: bool) -> None:
    apk_dir = ROOT / "app/build/outputs/apk/release"
    bundle_dir = ROOT / "app/build/outputs/bundle/release"
    apks = sorted(apk_dir.glob("*.apk")) if apk_dir.is_dir() else []
    aabs = sorted(bundle_dir.glob("*.aab")) if bundle_dir.is_dir() else []
    if not required:
        return
    check(len(apks) == 1, f"expected exactly one release APK, found {len(apks)}")
    check(len(aabs) == 1, f"expected exactly one release AAB, found {len(aabs)}")
    if len(apks) == 1:
        verify_xposed_zip(apks[0], "")
        aapt2 = find_aapt2()
        check(aapt2 is not None, "aapt2 is required to verify release APK identity")
        if aapt2 is not None:
            try:
                badging = subprocess.check_output([str(aapt2), "dump", "badging", str(apks[0])], text=True, stderr=subprocess.STDOUT)
            except (OSError, subprocess.CalledProcessError) as exc:
                fail(f"aapt2 could not inspect release APK: {exc}")
            else:
                package_line = badging.splitlines()[0] if badging else ""
                check(f"name='{APP_ID}'" in package_line, "release APK applicationId mismatch")
                check(f"versionCode='{VERSION_CODE}'" in package_line, "release APK versionCode mismatch")
                check(f"versionName='{VERSION_NAME}'" in package_line, "release APK versionName mismatch")
                check("application-label:'Pixelify Infinity'" in badging, "release APK label mismatch")
    if len(aabs) == 1:
        verify_xposed_zip(aabs[0], "base/root/")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--require-artifacts", action="store_true", help="require and inspect one release APK and one release AAB")
    args = parser.parse_args()

    check_identity_and_versions()
    check_manifest_and_sources()
    check_certificate()
    check_markdown_links()
    check_sensitive_content()
    check_artifacts(args.require_artifacts)

    if errors:
        print(f"Publication readiness FAILED ({len(errors)} issue(s), {checks} checks):", file=sys.stderr)
        for issue in errors:
            print(f"  - {issue}", file=sys.stderr)
        return 1
    print(f"Publication readiness PASS ({checks} checks).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
