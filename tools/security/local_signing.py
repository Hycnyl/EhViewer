#!/usr/bin/env python3
"""Create a protected local signing vault, or sign an unsigned CI handoff.

This tool never uploads keys, passwords or vault contents to GitHub.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import tempfile
import zipfile

from legacy_probes import make_probes
from sign_release import run, validate_lineage

ROOT = Path(__file__).resolve().parents[2]


def private_json(path, data):
    with path.open("x") as output:
        os.chmod(path, 0o600)
        json.dump(data, output, indent=2)
        output.write("\n")


def create(vault, sdk, legacy_store, legacy_config):
    os.umask(0o077)
    policy_file = ROOT / "app/signing/release.json"
    policy = json.loads(policy_file.read_text())
    if policy.get("certificateSha256") is not None:
        raise RuntimeError("A release identity is already pinned; restore its vault instead of generating a replacement")
    if vault.is_relative_to(ROOT) or vault.exists():
        raise RuntimeError("Use a new private directory outside the Git checkout; existing vaults are never overwritten")
    legacy = json.loads(legacy_config.read_text())
    legacy = legacy.get("legacy", legacy)
    tools = sdk / "build-tools/36.0.0"
    os.environ["SEC01_LEGACY_STORE_PASSWORD"] = legacy["storePassword"]
    os.environ["SEC01_LEGACY_KEY_PASSWORD"] = legacy["keyPassword"]
    old_cert = run(["keytool", "-exportcert", "-keystore", str(legacy_store), "-alias", legacy["keyAlias"], "-storepass:env", "SEC01_LEGACY_STORE_PASSWORD"], binary=True)
    if hashlib.sha256(old_cert).hexdigest() != policy["legacyCertificateSha256"]:
        raise RuntimeError("Unexpected legacy signing identity")
    vault.mkdir(mode=0o700, parents=True)
    password = secrets.token_urlsafe(48)
    credentials = {"storePassword": password, "keyPassword": password, "keyAlias": "hycnyl-ehviewer-release-2026"}
    private_json(vault / "credentials.json", credentials)
    os.environ["RELEASE_STORE_PASSWORD"] = password
    os.environ["RELEASE_KEY_PASSWORD"] = password
    store = vault / "release.p12"
    run([
        "keytool", "-J-Dkeystore.pkcs12.keyPbeIterationCount=600000",
        "-J-Dkeystore.pkcs12.certPbeIterationCount=600000", "-J-Dkeystore.pkcs12.macIterationCount=600000",
        "-genkeypair", "-noprompt", "-storetype", "PKCS12", "-keystore", str(store),
        "-storepass:env", "RELEASE_STORE_PASSWORD", "-keypass:env", "RELEASE_KEY_PASSWORD",
        "-alias", credentials["keyAlias"], "-keyalg", "RSA", "-keysize", "4096", "-sigalg", "SHA256withRSA",
        "-validity", "36500", "-dname", "CN=Hycnyl EhViewer Release,O=Hycnyl",
    ])
    store.chmod(0o600)
    certificate = run(["keytool", "-exportcert", "-keystore", str(store), "-alias", credentials["keyAlias"], "-storepass:env", "RELEASE_STORE_PASSWORD"], binary=True)
    fingerprint = hashlib.sha256(certificate).hexdigest()
    if fingerprint == policy["legacyCertificateSha256"]:
        raise RuntimeError("New signing identity must differ from the compromised identity")
    lineage = vault / "release.lineage"
    run([
        str(tools / "apksigner"), "rotate", "--out", str(lineage), "--old-signer",
        "--ks", str(legacy_store), "--ks-key-alias", legacy["keyAlias"],
        "--ks-pass", "env:SEC01_LEGACY_STORE_PASSWORD", "--key-pass", "env:SEC01_LEGACY_KEY_PASSWORD",
        "--set-installed-data", "true", "--set-shared-uid", "false", "--set-permission", "false",
        "--set-rollback", "false", "--set-auth", "false", "--new-signer", "--ks", str(store),
        "--ks-key-alias", credentials["keyAlias"], "--ks-pass", "env:RELEASE_STORE_PASSWORD", "--key-pass", "env:RELEASE_KEY_PASSWORD",
    ])
    validate_lineage(tools / "apksigner", lineage, policy["legacyCertificateSha256"], fingerprint)
    with tempfile.TemporaryDirectory(prefix="sec01-probes-") as temporary:
        (vault / "upgrade-probes.zip").write_bytes(make_probes(tools, sdk / "platforms/android-36/android.jar", legacy_store, legacy["keyAlias"], Path(temporary)))
    identity = {"certificateSha256": fingerprint, "legacyCertificateSha256": policy["legacyCertificateSha256"], "algorithm": "RSA-4096", "minimumSdk": 33}
    private_json(vault / "identity.json", identity)
    backup = vault / "backup"
    backup.mkdir(mode=0o700)
    shutil.copyfile(store, backup / "release.p12")
    shutil.copyfile(lineage, backup / "release.lineage")
    (backup / "release.p12").chmod(0o600)
    (backup / "release.lineage").chmod(0o600)
    policy["certificateSha256"] = fingerprint
    policy_file.write_text(json.dumps(policy, indent=2) + "\n")
    print("New release certificate SHA-256: " + fingerprint)
    print("Protected local vault: " + str(vault))
    print("The encrypted backup remains on this host; an independent offline copy must be stored separately.")


def sign(vault, sdk, archive, expected_commit, output):
    credentials = json.loads((vault / "credentials.json").read_text())
    env = os.environ.copy()
    env.update({"RELEASE_KEYSTORE_FILE": str(vault / "release.p12"), "RELEASE_SIGNING_LINEAGE_FILE": str(vault / "release.lineage"), "RELEASE_STORE_PASSWORD": credentials["storePassword"], "RELEASE_KEY_PASSWORD": credentials["keyPassword"], "RELEASE_KEY_ALIAS": credentials["keyAlias"]})
    if output.exists():
        raise RuntimeError("Choose a new output directory; signed candidates are never overwritten")
    output.mkdir(parents=True)
    with tempfile.TemporaryDirectory(prefix="sec01-unsigned-") as temporary:
        source = Path(temporary)
        with zipfile.ZipFile(archive) as package:
            if sum(i.file_size for i in package.infolist()) > 1000000000:
                raise RuntimeError("Unexpected unsigned archive size")
            for info in package.infolist():
                path = (source / info.filename).resolve()
                if not path.is_relative_to(source) or info.is_dir():
                    raise RuntimeError("Invalid unsigned archive path")
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(package.read(info))
        manifest = json.loads((source / "unsigned-build.json").read_text())
        if manifest["sourceCommit"] != expected_commit:
            raise RuntimeError("Unsigned build is not from the approved source commit")
        for item in manifest["files"]:
            path = source / item["path"]
            with path.open("rb") as data:
                if hashlib.file_digest(data, "sha256").hexdigest() != item["sha256"]:
                    raise RuntimeError("Unsigned build checksum mismatch")
        for variant in ("defaultUnsignedRelease", "marshmallowUnsignedRelease"):
            subprocess.run([sys.executable, str(ROOT / "tools/security/sign_release.py"), "--input-dir", str(source / variant), "--output-dir", str(output / variant), "--sdk-dir", str(sdk), "--policy", str(ROOT / "app/signing/release.json"), "--report", str(output / (variant + ".json"))], env=env, check=True)
        delivery = output / "delivery"
        subprocess.run([sys.executable, str(ROOT / "tools/security/collect_release.py"), "--report", str(output / "defaultUnsignedRelease.json"), "--version", manifest["versionName"], "--output", str(delivery)], check=True)
        m_report = json.loads((output / "marshmallowUnsignedRelease.json").read_text())
        m_apk = Path(next(a["path"] for a in m_report["artifacts"] if not a["filters"]))
        shutil.copyfile(m_apk, delivery / "SEC01-Marshmallow-universal.apk")
        shutil.copyfile(vault / "upgrade-probes.zip", delivery / "SEC01-upgrade-probes.zip")
        files = []
        for path in sorted(delivery.glob("*.apk")):
            with path.open("rb") as data:
                files.append({"file": path.name, "sha256": hashlib.file_digest(data, "sha256").hexdigest()})
        candidate = {"sourceCommit": expected_commit, "buildRunId": manifest["buildRunId"], "versionName": manifest["versionName"], "versionCode": manifest["versionCode"], "certificateSha256": json.loads((vault / "identity.json").read_text())["certificateSha256"], "artifacts": files}
        (delivery / "candidate.json").write_text(json.dumps(candidate, indent=2) + "\n")
    print("Signed APKs and public verification inputs prepared: " + str(delivery))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--vault", required=True, type=Path)
    parser.add_argument("--sdk-dir", required=True, type=Path)
    commands = parser.add_subparsers(dest="command", required=True)
    initialize = commands.add_parser("create")
    initialize.add_argument("--legacy-keystore", required=True, type=Path)
    initialize.add_argument("--legacy-config", required=True, type=Path)
    candidate = commands.add_parser("sign")
    candidate.add_argument("--archive", required=True, type=Path)
    candidate.add_argument("--expected-commit", required=True)
    candidate.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if args.command == "create":
        create(args.vault.resolve(), args.sdk_dir.resolve(), args.legacy_keystore.resolve(), args.legacy_config.resolve())
    else:
        sign(args.vault.resolve(), args.sdk_dir.resolve(), args.archive.resolve(), args.expected_commit, args.output.resolve())


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        print(f"SEC-01: {error}", file=sys.stderr)
        sys.exit(1)
    finally:
        for name in ("RELEASE_STORE_PASSWORD", "RELEASE_KEY_PASSWORD", "SEC01_LEGACY_STORE_PASSWORD", "SEC01_LEGACY_KEY_PASSWORD"):
            os.environ.pop(name, None)
