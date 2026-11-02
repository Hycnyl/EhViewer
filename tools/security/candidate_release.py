#!/usr/bin/env python3
"""Receive signed APKs through GitHub blob objects and verify a release candidate.

Only signed public APKs and public verification data cross this boundary. The
private signing vault is never a transfer input or a GitHub Actions dependency.
"""

import argparse
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

from sign_release import run, validate_lineage

ABIS = {"universal", "arm64-v8a", "armeabi-v7a", "x86_64", "x86"}
EXTRA = {"candidate.json", "SHA256SUMS.txt", "SEC01-Marshmallow-universal.apk", "SEC01-upgrade-probes.zip"}
PROBES = {"default-higher.apk", "marshmallow-higher.apk", "marshmallow-baseline.apk"}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def expected_names(version):
    return {f"EhViewer-{version}-{abi}.apk" for abi in ABIS} | EXTRA


def source_version():
    text = Path("app/build.gradle.kts").read_text()
    return re.search(r'versionName = "([0-9.]+)"', text).group(1), int(re.search(r"versionCode = (\d+)", text).group(1))


def transfer_plan(directory, output):
    candidate = json.loads((directory / "candidate.json").read_text())
    version = candidate["versionName"]
    names = {p.name for p in directory.iterdir() if p.is_file()}
    if names != expected_names(version):
        raise RuntimeError("Only the exact signed APK and public verification file set may be transferred")
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as package:
        for name in sorted(names):
            package.write(directory / name, name)
    parts = []
    with output.open("rb") as source:
        while chunk := source.read(524288):
            parts.append({"offset": len(parts) * 524288, "size": len(chunk), "sha": hashlib.sha1(b"blob " + str(len(chunk)).encode() + b"\0" + chunk).hexdigest()})
    request = {"sourceCommit": candidate["sourceCommit"], "buildRunId": candidate["buildRunId"], "versionName": version, "bundleSha256": digest(output.read_bytes()), "candidateSha256": digest((directory / "candidate.json").read_bytes()), "parts": [{"sha": p["sha"], "size": p["size"]} for p in parts]}
    plan = {"archive": str(output.resolve()), "request": request, "parts": parts}
    output.with_suffix(".plan.json").write_text(json.dumps(plan, indent=2) + "\n")
    print(json.dumps({"parts": len(parts), "bundleSha256": request["bundleSha256"], "privateSigningMaterialIncluded": False}))


def receive(request_file, directory):
    request = json.loads(request_file.read_text())
    source = request["sourceCommit"]
    if not re.fullmatch(r"[0-9a-f]{40}", source):
        raise RuntimeError("Invalid source commit")
    run(["git", "merge-base", "--is-ancestor", source, "HEAD"])
    changes = set(run(["git", "diff", "--name-only", source, "HEAD"]).splitlines())
    if changes - {".github/release-candidate.json"}:
        raise RuntimeError("Application source changed after the unsigned build")
    version, code = source_version()
    if request["versionName"] != version:
        raise RuntimeError("Candidate version does not match source")
    repository = os.environ["GITHUB_REPOSITORY"]
    build = json.loads(run(["gh", "api", f"repos/{repository}/actions/runs/{int(request['buildRunId'])}"]))
    if build["head_sha"] != source or build["conclusion"] != "success" or build["path"] != ".github/workflows/cache-rescue.yml":
        raise RuntimeError("The approved unsigned CI build did not succeed for this source")
    if not 1 <= len(request["parts"]) <= 200:
        raise RuntimeError("Invalid signed handoff part count")
    data = bytearray()
    for part in request["parts"]:
        sha = part["sha"]
        if not re.fullmatch(r"[0-9a-f]{40}", sha) or not 0 < part["size"] <= 524288:
            raise RuntimeError("Invalid signed handoff part")
        blob = json.loads(run(["gh", "api", f"repos/{repository}/git/blobs/{sha}"]))
        chunk = base64.b64decode(blob["content"])
        if len(chunk) != part["size"] or hashlib.sha1(b"blob " + str(len(chunk)).encode() + b"\0" + chunk).hexdigest() != sha:
            raise RuntimeError("Signed handoff part checksum mismatch")
        data.extend(chunk)
    if digest(data) != request["bundleSha256"]:
        raise RuntimeError("Signed handoff checksum mismatch")
    directory.mkdir(parents=True, exist_ok=False)
    with zipfile.ZipFile(io.BytesIO(data)) as package:
        if set(package.namelist()) != expected_names(version) or sum(i.file_size for i in package.infolist()) > 1000000000:
            raise RuntimeError("Unexpected signed candidate contents")
        for name in package.namelist():
            (directory / name).write_bytes(package.read(name))
    raw = (directory / "candidate.json").read_bytes()
    candidate = json.loads(raw)
    policy = json.loads(Path("app/signing/release.json").read_text())
    if policy.get("historySanitized") is not True or run(["git", "rev-list", "--objects", "--all", "--", "app/keystore/androidkey.jks"]):
        raise RuntimeError("Legacy signing key history has not been sanitized")
    if digest(raw) != request["candidateSha256"] or candidate["sourceCommit"] != source or candidate["versionCode"] != code or candidate["versionName"] != version or candidate["buildRunId"] != request["buildRunId"] or candidate["certificateSha256"] != policy["certificateSha256"]:
        raise RuntimeError("Candidate identity does not match the approved source")
    files = {a["file"]: a["sha256"] for a in candidate["artifacts"]}
    if len(candidate["artifacts"]) != 6 or set(files) != expected_names(version) - {"candidate.json", "SHA256SUMS.txt", "SEC01-upgrade-probes.zip"}:
        raise RuntimeError("Unexpected signed APK set")
    tools = Path(os.environ["ANDROID_HOME"]) / "build-tools/36.0.0"
    apksigner = tools / "apksigner"
    for name, checksum in files.items():
        apk = directory / name
        if digest(apk.read_bytes()) != checksum:
            raise RuntimeError("Signed APK checksum mismatch")
        text = run([str(apksigner), "verify", "--verbose", "--print-certs", str(apk)])
        if f"Signer #1 certificate SHA-256 digest: {policy['certificateSha256']}" not in text or "Verified using v3 scheme (APK Signature Scheme v3): true" not in text or "Verified using v2 scheme (APK Signature Scheme v2): false" not in text:
            raise RuntimeError("Unexpected release signature")
        validate_lineage(apksigner, apk, policy["legacyCertificateSha256"], policy["certificateSha256"])
        badging = run([str(tools / "aapt"), "dump", "badging", str(apk)])
        application_id = "moe.hycnyl.ehviewer.m" if name == "SEC01-Marshmallow-universal.apk" else "moe.hycnyl.ehviewer"
        version_name = version + "-M" if name == "SEC01-Marshmallow-universal.apk" else version
        if f"name='{application_id}'" not in badging or f"versionCode='{code}'" not in badging or f"versionName='{version_name}'" not in badging or "sdkVersion:'33'" not in badging or "application-debuggable" in badging:
            raise RuntimeError("Unexpected release package configuration")
        with zipfile.ZipFile(apk) as package:
            if package.testzip() is not None or not any(source[:7].encode() in package.read(n) for n in package.namelist() if re.fullmatch(r"classes\d*\.dex", n)):
                raise RuntimeError("APK integrity or source commit marker mismatch")
        run([str(tools / "zipalign"), "-c", "-P", "16", "4", str(apk)])
    sums = dict((name.strip().lstrip("*"), checksum) for checksum, name in (line.split(maxsplit=1) for line in (directory / "SHA256SUMS.txt").read_text().splitlines()))
    if sums != {name: checksum for name, checksum in files.items() if name.startswith("EhViewer-")}:
        raise RuntimeError("Published APK checksum file mismatch")
    probes = directory / "probes"
    probes.mkdir()
    with zipfile.ZipFile(directory / "SEC01-upgrade-probes.zip") as package:
        if set(package.namelist()) != PROBES or sum(i.file_size for i in package.infolist()) > 256000:
            raise RuntimeError("Invalid legacy-signature test inputs")
        for name in PROBES:
            path = probes / name
            path.write_bytes(package.read(name))
            text = run([str(apksigner), "verify", "--print-certs", str(path)])
            if f"Signer #1 certificate SHA-256 digest: {policy['legacyCertificateSha256']}" not in text:
                raise RuntimeError("Legacy rejection probe has the wrong signer")
    (directory / "default-report.json").write_text(json.dumps({"artifacts": [{"path": str((directory / f'EhViewer-{version}-universal.apk').resolve()), "filters": []}]}))
    (directory / "marshmallow-report.json").write_text(json.dumps({"artifacts": [{"path": str((directory / 'SEC01-Marshmallow-universal.apk').resolve()), "filters": []}]}))
    print("All signed APKs, rotation proofs, source markers and checksums verified")


def finalize(directory, output):
    result = json.loads((directory / "upgrade-result.json").read_text())
    if result["api"] != 33 or len(result["checks"]) != 2:
        raise RuntimeError("Both variants must pass Android 13 upgrade verification")
    for check in result["checks"]:
        if not all(check.get(key) is True for key in ("uidRetained", "dataRetained", "higherVersionLegacySignerRejected", "launchSucceeded")):
            raise RuntimeError("Signing migration did not satisfy all checks")
    output.mkdir(parents=True, exist_ok=False)
    candidate = json.loads((directory / "candidate.json").read_text())
    for name in expected_names(candidate["versionName"]):
        if name.startswith("EhViewer-") or name == "SHA256SUMS.txt":
            shutil.copyfile(directory / name, output / name)
    result.update({k: candidate[k] for k in ("sourceCommit", "buildRunId", "versionName", "versionCode", "certificateSha256")})
    (output / "SEC01-verification.json").write_text(json.dumps(result, indent=2) + "\n")
    with Path(os.environ["GITHUB_OUTPUT"]).open("a") as values:
        for name in ("sourceCommit", "buildRunId", "versionName"):
            print(f"{name}={candidate[name]}", file=values)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    operations = parser.add_subparsers(dest="command", required=True)
    plan = operations.add_parser("plan")
    plan.add_argument("--directory", type=Path, required=True)
    plan.add_argument("--output", type=Path, required=True)
    load = operations.add_parser("receive")
    load.add_argument("--request", type=Path, default=Path(".github/release-candidate.json"))
    load.add_argument("--directory", type=Path, default=Path("candidate"))
    final = operations.add_parser("finalize")
    final.add_argument("--directory", type=Path, default=Path("candidate"))
    final.add_argument("--output", type=Path, default=Path("verified-delivery"))
    args = parser.parse_args()
    if args.command == "plan":
        transfer_plan(args.directory.resolve(), args.output.resolve())
    elif args.command == "receive":
        receive(args.request, args.directory)
    else:
        finalize(args.directory, args.output)


if __name__ == "__main__":
    main()
