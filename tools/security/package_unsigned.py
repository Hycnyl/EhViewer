#!/usr/bin/env python3
"""Create a checksummed unsigned build handoff containing APKs and metadata only."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build-dir", type=Path, default=Path("app/build"))
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--output", type=Path, default=Path("handoff"))
    args = parser.parse_args()
    tools = Path(os.environ["ANDROID_HOME"]) / "build-tools/36.0.0"
    variants = {"defaultUnsignedRelease", "marshmallowUnsignedRelease"}
    records, metadata, inputs = [], {}, {}
    for path in (args.build_dir / "outputs/apk").rglob("output-metadata.json"):
        data = json.loads(path.read_text())
        variant = data["variantName"]
        if variant not in variants:
            continue
        if variant in metadata or len(data["elements"]) != 5:
            raise RuntimeError("Unexpected unsigned variant outputs")
        metadata[variant] = data
        inputs[variant + "/output-metadata.json"] = path.read_bytes()
        for item in data["elements"]:
            apk = path.parent / item["outputFile"]
            badging = subprocess.check_output([str(tools / "aapt"), "dump", "badging", str(apk)], text=True)
            if "sdkVersion:'33'" not in badging or "application-debuggable" in badging:
                raise RuntimeError("Unsigned release must be non-debuggable and require API 33")
            signature = subprocess.run([str(tools / "apksigner"), "verify", str(apk)], capture_output=True)
            if signature.returncode == 0:
                raise RuntimeError("CI handoff must not contain an APK signed with another identity")
            payload = apk.read_bytes()
            name = variant + "/" + apk.name
            inputs[name] = payload
            records.append({"path": name, "sha256": hashlib.sha256(payload).hexdigest(), "size": len(payload)})
    if set(metadata) != variants:
        raise RuntimeError("Both unsigned variants must be built")
    first = metadata["defaultUnsignedRelease"]["elements"][0]
    manifest = {"sourceCommit": args.source_commit, "buildRunId": int(os.environ["GITHUB_RUN_ID"]), "versionName": first["versionName"], "versionCode": first["versionCode"], "files": records}
    args.output.mkdir(parents=True, exist_ok=True)
    archive = args.output / "unsigned-apks.zip"
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=1) as package:
        for name, payload in inputs.items():
            package.writestr(name, payload)
        package.writestr("unsigned-build.json", json.dumps(manifest, indent=2) + "\n")
    with archive.open("rb") as data:
        digest = hashlib.file_digest(data, "sha256").hexdigest()
    (args.output / "SHA256SUMS.txt").write_text(digest + "  unsigned-apks.zip\n")
    print("Unsigned CI handoff SHA-256: " + digest)


if __name__ == "__main__":
    main()
