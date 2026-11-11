#!/usr/bin/env python3
"""Build handoff and two-step install verification for the API 28 signing bridge."""

import argparse
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import tempfile
import urllib.request
import zipfile

from sign_release import run, sign_artifacts, validate_lineage

ROOT = Path(__file__).resolve().parents[2]
VERSION = "1.17.10"
CODE = 180085
PACKAGE = "moe.hycnyl.ehviewer"
ABIS = {"universal", "arm64-v8a", "armeabi-v7a", "x86_64", "x86"}
APKS = {f"EhViewer-{VERSION}-{abi}.apk" for abi in ABIS}
FILES = APKS | {"SHA256SUMS.txt", "bridge-release.json"}
OLD_URL = "https://github.com/Hycnyl/EhViewer/releases/download/v1.17.8/EhViewer-1.17.8-universal.apk"
OLD_SHA = "c2274c75d10427cabc114ce79136fabea5aaac07298c9335bda3ae6dacd00744"
REVIVED_URL = "https://github.com/Hycnyl/EhViewer-Revived/releases/download/v1.17.17/EhViewer-1.17.17-universal.apk"
REVIVED_SHA = "5d6f89b1bafe57a77699c8c8f2a4c00b086b3449f5892267d911bbbd62424afe"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def write_json(path, data):
    path.write_text(json.dumps(data, indent=2) + "\n")


def tools():
    return Path(os.environ["ANDROID_HOME"]) / "build-tools/36.0.0"


def package():
    candidates = []
    for path in (ROOT / "app/build/outputs/apk").rglob("output-metadata.json"):
        metadata = json.loads(path.read_text())
        if metadata["variantName"] == "defaultUnsignedRelease":
            candidates.append((path, metadata))
    if len(candidates) != 1 or len(candidates[0][1]["elements"]) != 5:
        raise RuntimeError("Expected five Default unsigned APKs")
    path, metadata = candidates[0]
    output = ROOT / "bridge-handoff"
    output.mkdir()
    records = []
    with zipfile.ZipFile(output / "unsigned-apks.zip", "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("defaultUnsignedRelease/output-metadata.json", path.read_bytes())
        for item in metadata["elements"]:
            apk = path.parent / item["outputFile"]
            badging = run([str(tools() / "aapt"), "dump", "badging", str(apk)])
            if "sdkVersion:'28'" not in badging or "application-debuggable" in badging or item["versionCode"] != CODE or item["versionName"] != VERSION:
                raise RuntimeError("Unexpected bridge APK configuration")
            if subprocess.run([str(tools() / "apksigner"), "verify", str(apk)], capture_output=True).returncode == 0:
                raise RuntimeError("Unsigned handoff unexpectedly signed")
            data = apk.read_bytes()
            name = "defaultUnsignedRelease/" + apk.name
            archive.writestr(name, data)
            records.append({"path": name, "sha256": sha(data)})
        archive.writestr("unsigned-build.json", json.dumps({"sourceCommit": os.environ["GITHUB_SHA"], "buildRunId": int(os.environ["GITHUB_RUN_ID"]), "files": records}))
    digest = sha((output / "unsigned-apks.zip").read_bytes())
    (output / "SHA256SUMS.txt").write_text(digest + "  unsigned-apks.zip\n")
    print("Unsigned bridge SHA-256: " + digest)


def sign(args):
    data = args.archive.read_bytes()
    if sha(data) != args.sha256:
        raise RuntimeError("Unsigned handoff checksum mismatch")
    credentials = json.loads((args.vault / "credentials.json").read_text())
    os.environ.update({"RELEASE_KEYSTORE_FILE": str(args.vault / "release.p12"), "RELEASE_SIGNING_LINEAGE_FILE": str(args.vault / "release.lineage"), "RELEASE_STORE_PASSWORD": credentials["storePassword"], "RELEASE_KEY_PASSWORD": credentials["keyPassword"], "RELEASE_KEY_ALIAS": credentials["keyAlias"]})
    args.output.mkdir(parents=True, exist_ok=False)
    with tempfile.TemporaryDirectory(prefix="bridge-unsigned-") as temporary:
        source = Path(temporary)
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            for item in archive.infolist():
                target = (source / item.filename).resolve()
                if not target.is_relative_to(source) or item.is_dir():
                    raise RuntimeError("Invalid unsigned archive path")
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(archive.read(item))
        manifest = json.loads((source / "unsigned-build.json").read_text())
        if manifest["sourceCommit"] != args.source_commit:
            raise RuntimeError("Unsigned source mismatch")
        for item in manifest["files"]:
            if sha((source / item["path"]).read_bytes()) != item["sha256"]:
                raise RuntimeError("Unsigned APK checksum mismatch")
        report_file = args.output / "signing-report.json"
        sign_artifacts(source / "defaultUnsignedRelease", args.output / "signed", args.sdk_dir, ROOT / "app/signing/release.json", report_file)
    delivery = args.output / "delivery"
    delivery.mkdir()
    records = []
    for item in json.loads(report_file.read_text())["artifacts"]:
        abi = "universal" if not item["filters"] else item["filters"][0]["value"]
        name = f"EhViewer-{VERSION}-{abi}.apk"
        shutil.copyfile(item["path"], delivery / name)
        records.append({"file": name, "sha256": item["sha256"]})
    if {r["file"] for r in records} != APKS:
        raise RuntimeError("Incomplete signed bridge output")
    policy = json.loads((ROOT / "app/signing/release.json").read_text())
    write_json(delivery / "bridge-release.json", {"sourceCommit": args.source_commit, "buildRunId": manifest["buildRunId"], "versionName": VERSION, "versionCode": CODE, "minimumSdk": 28, "certificateSha256": policy["certificateSha256"], "artifacts": records})
    (delivery / "SHA256SUMS.txt").write_text("".join(sorted(f"{r['sha256']}  {r['file']}\n" for r in records)))
    bundle = args.output / "signed-bridge.zip"
    with zipfile.ZipFile(bundle, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(delivery.iterdir()):
            archive.write(path, path.name)
    parts = []
    with bundle.open("rb") as source:
        while chunk := source.read(524288):
            parts.append({"sha": hashlib.sha1(b"blob " + str(len(chunk)).encode() + b"\0" + chunk).hexdigest(), "size": len(chunk)})
    request = {"sourceCommit": args.source_commit, "buildRunId": manifest["buildRunId"], "bundleSha256": sha(bundle.read_bytes()), "parts": parts}
    write_json(args.output / "transfer-plan.json", {"archive": str(bundle), "request": request})
    print("Signed bridge and transfer plan: " + str(args.output))


def receive():
    request = json.loads((ROOT / ".github/bridge-candidate.json").read_text())
    source = request["sourceCommit"]
    if not re.fullmatch(r"[0-9a-f]{40}", source):
        raise RuntimeError("Invalid source identity")
    run(["git", "merge-base", "--is-ancestor", source, "HEAD"])
    if set(run(["git", "diff", "--name-only", source, "HEAD"]).splitlines()) - {".github/bridge-candidate.json"}:
        raise RuntimeError("Bridge source changed after build")
    if not 1 <= len(request["parts"]) <= 150:
        raise RuntimeError("Invalid transfer size")
    data = bytearray()
    for part in request["parts"]:
        if not re.fullmatch(r"[0-9a-f]{40}", part["sha"]) or not 0 < part["size"] <= 524288:
            raise RuntimeError("Invalid signed part")
        obj = json.loads(run(["gh", "api", f"repos/{os.environ['GITHUB_REPOSITORY']}/git/blobs/{part['sha']}"]))
        chunk = base64.b64decode(obj["content"])
        if len(chunk) != part["size"] or hashlib.sha1(b"blob " + str(len(chunk)).encode() + b"\0" + chunk).hexdigest() != part["sha"]:
            raise RuntimeError("Signed part checksum mismatch")
        data.extend(chunk)
    if sha(data) != request["bundleSha256"]:
        raise RuntimeError("Signed bundle checksum mismatch")
    directory = ROOT / "bridge-delivery"
    directory.mkdir()
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        if set(archive.namelist()) != FILES:
            raise RuntimeError("Unexpected signed handoff files")
        for name in FILES:
            (directory / name).write_bytes(archive.read(name))
    info = json.loads((directory / "bridge-release.json").read_text())
    policy = json.loads((ROOT / "app/signing/release.json").read_text())
    if (info["sourceCommit"], info["buildRunId"], info["versionCode"], info["minimumSdk"], info["certificateSha256"]) != (source, request["buildRunId"], CODE, 28, policy["certificateSha256"]):
        raise RuntimeError("Signed identity mismatch")
    records = {r["file"]: r["sha256"] for r in info["artifacts"]}
    if set(records) != APKS or len(info["artifacts"]) != 5:
        raise RuntimeError("Unexpected signed APK set")
    for name, expected in records.items():
        apk = directory / name
        if sha(apk.read_bytes()) != expected:
            raise RuntimeError("Signed APK checksum mismatch")
        text = run([str(tools() / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)])
        if f"Signer #1 certificate SHA-256 digest: {policy['certificateSha256']}" not in text or "Verified using v3 scheme (APK Signature Scheme v3): true" not in text:
            raise RuntimeError("Bridge signer mismatch")
        validate_lineage(tools() / "apksigner", apk, policy["legacyCertificateSha256"], policy["certificateSha256"])
        text = run([str(tools() / "aapt"), "dump", "badging", str(apk)])
        if any(value not in text for value in (f"name='{PACKAGE}'", f"versionCode='{CODE}'", f"versionName='{VERSION}'", "sdkVersion:'28'")) or "application-debuggable" in text:
            raise RuntimeError("Bridge package mismatch")
    expected_sums = "".join(sorted(f"{digest}  {name}\n" for name, digest in records.items()))
    if (directory / "SHA256SUMS.txt").read_text() != expected_sums:
        raise RuntimeError("Bridge checksum list mismatch")
    print("Five API 28 bridge APKs and signing lineages verified")


def migration():
    adb = str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb")
    shell = lambda command: run([adb, "shell", command]).strip()
    if not run([adb, "get-serialno"]).strip().startswith("emulator-") or shell("getprop ro.build.version.sdk") != "28":
        raise RuntimeError("Migration check requires the disposable minimum-OS API 28 emulator")
    run([adb, "root"])
    run([adb, "wait-for-device"])
    if shell("id -u") != "0":
        raise RuntimeError("Migration emulator must permit adb root")
    temporary = ROOT / "bridge-test-inputs"
    temporary.mkdir()
    for name, url, expected in (("old.apk", OLD_URL, OLD_SHA), ("revived.apk", REVIVED_URL, REVIVED_SHA)):
        with urllib.request.urlopen(url, timeout=180) as response:
            data = response.read()
        if sha(data) != expected:
            raise RuntimeError("Migration input checksum mismatch")
        (temporary / name).write_bytes(data)
    subprocess.run([adb, "uninstall", PACKAGE], capture_output=True)
    run([adb, "install", "--no-streaming", str(temporary / "old.apk")])
    before = shell("dumpsys package " + PACKAGE)
    uid = re.search(r"\buserId=(\d+)", before).group(1)
    marker = secrets.token_hex(24)
    path = f"/data/user/0/{PACKAGE}/files/signature-bridge-marker"
    shell(f"mkdir -p /data/user/0/{PACKAGE}/files; echo {marker} > {path}; chown {uid}:{uid} /data/user/0/{PACKAGE}/files {path}; restorecon -R /data/user/0/{PACKAGE}/files")
    steps = []
    for label, apk, code in (("bridge", ROOT / f"bridge-delivery/EhViewer-{VERSION}-universal.apk", CODE), ("revived", temporary / "revived.apk", 180092)):
        run([adb, "install", "-r", "--no-streaming", str(apk)])
        details = shell("dumpsys package " + PACKAGE)
        if re.search(r"\buserId=(\d+)", details).group(1) != uid or shell("cat " + path) != marker or int(re.search(r"\bversionCode=(\d+)", details).group(1)) != code:
            raise RuntimeError(label + " update did not preserve UID/data/version")
        steps.append({"step": label, "versionCode": code, "uidRetained": True, "dataRetained": True})
    result = {"api": 28, "fromVersion": "1.17.8", "bridgeVersion": VERSION, "toProject": "Hycnyl/EhViewer-Revived", "toVersion": "1.17.17", "steps": steps}
    write_json(ROOT / "bridge-delivery/bridge-migration.json", result)
    print(json.dumps(result))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("package", "receive", "migration"):
        commands.add_parser(name)
    signing = commands.add_parser("sign")
    for name in ("archive", "vault", "sdk-dir", "output"):
        signing.add_argument("--" + name, required=True, type=Path)
    for name in ("sha256", "source-commit"):
        signing.add_argument("--" + name, required=True)
    args = parser.parse_args()
    if args.command == "sign":
        sign(args)
    else:
        {"package": package, "receive": receive, "migration": migration}[args.command]()


if __name__ == "__main__":
    try:
        main()
    finally:
        for key in ("RELEASE_STORE_PASSWORD", "RELEASE_KEY_PASSWORD"):
            os.environ.pop(key, None)
