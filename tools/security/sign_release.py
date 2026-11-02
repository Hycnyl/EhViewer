#!/usr/bin/env python3
"""Sign AGP APK artifacts with a pinned, forward-only v3 lineage.

Credentials are read only at execution, never stored in Gradle caches or reports.
An absent secret or certificate pin is fatal; no other signing key is tried.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

REQUIRED_ENV = (
    "RELEASE_KEYSTORE_FILE", "RELEASE_STORE_PASSWORD", "RELEASE_KEY_ALIAS",
    "RELEASE_KEY_PASSWORD", "RELEASE_SIGNING_LINEAGE_FILE",
)


def run(arguments, binary=False):
    result = subprocess.run(arguments, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        raise RuntimeError(f"{Path(arguments[0]).name} failed (exit {result.returncode})")
    return result.stdout if binary else result.stdout.decode("utf-8", errors="replace")


def digest(path):
    with Path(path).open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def validate_lineage(apksigner, lineage, legacy, current):
    text = run([str(apksigner), "lineage", "--in", str(lineage), "--print-certs", "-v"])
    sections = re.split(r"Signer #\d+ in lineage certificate DN:", text)[1:]
    matches = [re.search(r"certificate SHA-256 digest: ([0-9a-f]{64})", s) for s in sections]
    if any(match is None for match in matches):
        raise RuntimeError("Invalid signing lineage certificate information")
    certs = [match.group(1) for match in matches]
    if certs != [legacy, current]:
        raise RuntimeError("Signing lineage does not match the pinned certificates")
    capabilities = {"installed data": "true", "shared UID": "false", "permission": "false", "rollback": "false", "auth": "false"}
    for capability, value in capabilities.items():
        if not re.search(r"Has " + capability + r" capability\s*:\s*" + value + r"\b", sections[0]):
            raise RuntimeError(f"Unsafe legacy signer capability: {capability}")
    return certs


def sign_artifacts(input_dir, output_dir, sdk_dir, policy_path, report_path):
    missing = [name for name in REQUIRED_ENV if not os.environ.get(name)]
    if missing:
        raise RuntimeError("Missing release signing configuration: " + ", ".join(missing))
    policy = json.loads(policy_path.read_text())
    expected = policy.get("certificateSha256")
    legacy = policy["legacyCertificateSha256"]
    if not isinstance(expected, str) or not re.fullmatch(r"[0-9a-f]{64}", expected) or expected == legacy:
        raise RuntimeError("SEC-01: initialize and pin the new release certificate first")
    if policy["minimumSdk"] != 33:
        raise RuntimeError("SEC-01 requires Android 13 or later")
    tools = sdk_dir / "build-tools" / "36.0.0"
    apksigner = tools / "apksigner"
    keystore = Path(os.environ["RELEASE_KEYSTORE_FILE"]).resolve()
    lineage = Path(os.environ["RELEASE_SIGNING_LINEAGE_FILE"]).resolve()
    checkout = Path(__file__).resolve().parents[2]
    for secret_file in (keystore, lineage):
        if not secret_file.is_file() or secret_file.is_relative_to(checkout):
            raise RuntimeError("Signing files must exist outside the Git checkout")
    certificate = run([
        "keytool", "-exportcert", "-keystore", str(keystore),
        "-alias", os.environ["RELEASE_KEY_ALIAS"], "-storepass:env", "RELEASE_STORE_PASSWORD",
    ], binary=True)
    if hashlib.sha256(certificate).hexdigest() != expected:
        raise RuntimeError("Release keystore does not match the pinned certificate")
    validate_lineage(apksigner, lineage, legacy, expected)
    metadata = json.loads((input_dir / "output-metadata.json").read_text())
    if not metadata.get("elements"):
        raise RuntimeError("No release APK artifacts were produced")
    if input_dir.resolve() == output_dir.resolve():
        raise RuntimeError("Signing must preserve unsigned inputs")
    if output_dir.exists():
        shutil.rmtree(output_dir)
    output_dir.mkdir(parents=True)
    reports = []
    for element in metadata["elements"]:
        source = (input_dir / element["outputFile"]).resolve()
        if not source.is_relative_to(input_dir.resolve()) or not source.is_file():
            raise RuntimeError("Invalid APK artifact path")
        target = output_dir / source.name.replace("-unsigned.apk", ".apk")
        badging = run([str(tools / "aapt"), "dump", "badging", str(source)])
        if "sdkVersion:'33'" not in badging or "application-debuggable" in badging:
            raise RuntimeError("Release APK must be non-debuggable and require Android 13")
        permissions = run([str(tools / "aapt"), "dump", "permissions", str(source)])
        if ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" in permissions:
            raise RuntimeError("Release must not redeclare the legacy AndroidX signature permission")
        run([
            str(apksigner), "sign", "--ks", str(keystore),
            "--ks-key-alias", os.environ["RELEASE_KEY_ALIAS"],
            "--ks-pass", "env:RELEASE_STORE_PASSWORD", "--key-pass", "env:RELEASE_KEY_PASSWORD",
            "--lineage", str(lineage), "--rotation-min-sdk-version", "28",
            "--v1-signing-enabled", "false", "--v2-signing-enabled", "false",
            "--v3-signing-enabled", "true", "--v4-signing-enabled", "false",
            "--debuggable-apk-permitted", "false", "--lib-page-alignment", "16384",
            "--out", str(target), str(source),
        ])
        verification = run([str(apksigner), "verify", "--verbose", "--print-certs", str(target)])
        if f"Signer #1 certificate SHA-256 digest: {expected}" not in verification:
            raise RuntimeError("Signed APK certificate mismatch")
        if "Verified using v3 scheme (APK Signature Scheme v3): true" not in verification:
            raise RuntimeError("Release APK lacks a verified v3 signature")
        validate_lineage(apksigner, target, legacy, expected)
        run([str(tools / "zipalign"), "-c", "-P", "16", "4", str(target)])
        element["outputFile"] = target.name
        reports.append({"path": str(target.resolve()), "file": target.name, "filters": element["filters"], "versionCode": element["versionCode"], "versionName": element["versionName"], "sha256": digest(target), "certificateSha256": expected, "minimumSdk": 33})
    (output_dir / "output-metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps({"applicationId": metadata["applicationId"], "variant": metadata["variantName"], "artifacts": reports}, indent=2) + "\n")
    print(f"SEC-01: signed and verified {len(reports)} APKs with certificate {expected}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("input-dir", "output-dir", "sdk-dir", "policy", "report"):
        parser.add_argument("--" + name, required=True, type=Path)
    args = parser.parse_args()
    sign_artifacts(args.input_dir, args.output_dir, args.sdk_dir, args.policy, args.report)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, ValueError, KeyError) as error:
        print(f"SEC-01: {error}", file=sys.stderr)
        sys.exit(1)
