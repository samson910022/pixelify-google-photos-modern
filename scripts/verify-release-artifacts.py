#!/usr/bin/env python3
"""Verify signed Pixelify Photos release artifacts without reading signing secrets."""

from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import ssl
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
EXPECTED_CERT_SHA256 = "37186E5C2694E553E5FAB1F7787C04DBCD4384AB84963E60BE9C3CCB6BA907B1"


def find_tool(name: str, candidates: list[Path]) -> Path:
    on_path = shutil.which(name)
    if on_path:
        return Path(on_path)
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    raise SystemExit(f"required tool not found: {name}")


def android_tool(name: str) -> Path:
    candidates: list[Path] = []
    for env_name in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        value = os.environ.get(env_name)
        if not value:
            continue
        build_tools = Path(value) / "build-tools"
        if build_tools.is_dir():
            candidates.extend(sorted(build_tools.glob(f"*/{name}"), reverse=True))
    return find_tool(name, candidates)


def java_tool(name: str) -> Path:
    candidates: list[Path] = []
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidates.append(Path(java_home) / "bin" / name)
    return find_tool(name, candidates)


def run(command: list[str]) -> str:
    try:
        return subprocess.check_output(command, text=True, stderr=subprocess.STDOUT)
    except subprocess.CalledProcessError as exc:
        if exc.output:
            print(exc.output, file=sys.stderr)
        raise SystemExit(f"command failed ({exc.returncode}): {command[0]}") from exc


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def apk_fingerprint(apksigner: Path, apk: Path) -> str:
    output = run([str(apksigner), "verify", "--verbose", "--print-certs", str(apk)])
    match = re.search(r"^Signer #1 certificate SHA-256 digest: ([0-9a-fA-F:]+)$", output, re.MULTILINE)
    if not match:
        raise SystemExit("APK signer SHA-256 fingerprint was not reported")
    return match.group(1).replace(":", "").upper()


def aab_fingerprint(keytool: Path, jarsigner: Path, aab: Path) -> str:
    verification = run([str(jarsigner), "-verify", str(aab)])
    if "jar verified." not in verification:
        raise SystemExit("AAB JAR signature did not report successful verification")
    certificate_output = run([str(keytool), "-printcert", "-jarfile", str(aab), "-rfc"])
    match = re.search(
        r"-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----",
        certificate_output,
        re.DOTALL,
    )
    if not match:
        raise SystemExit("AAB signer certificate was not reported")
    der = ssl.PEM_cert_to_DER_cert(match.group(0))
    return hashlib.sha256(der).hexdigest().upper()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--apk",
        type=Path,
        default=ROOT / "app/build/outputs/apk/release/app-release.apk",
    )
    parser.add_argument(
        "--aab",
        type=Path,
        default=ROOT / "app/build/outputs/bundle/release/app-release.aab",
    )
    args = parser.parse_args()
    apk = args.apk.resolve()
    aab = args.aab.resolve()
    if not apk.is_file() or not aab.is_file():
        raise SystemExit("signed release APK and AAB are both required")

    apksigner = android_tool("apksigner")
    keytool = java_tool("keytool")
    jarsigner = java_tool("jarsigner")

    apk_cert = apk_fingerprint(apksigner, apk)
    aab_cert = aab_fingerprint(keytool, jarsigner, aab)
    if apk_cert != EXPECTED_CERT_SHA256:
        raise SystemExit("APK signer does not match the approved release certificate")
    if aab_cert != EXPECTED_CERT_SHA256:
        raise SystemExit("AAB signer does not match the approved release certificate")
    if apk_cert != aab_cert:
        raise SystemExit("APK and AAB use different signing certificates")

    print("Release artifact signatures PASS.")
    print(f"APK SHA-256: {sha256(apk)}")
    print(f"AAB SHA-256: {sha256(aab)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
