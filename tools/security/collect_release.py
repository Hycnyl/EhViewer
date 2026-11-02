#!/usr/bin/env python3
"""Collect only the signed APK artifact transform outputs, never intermediates."""

import argparse
import hashlib
import json
from pathlib import Path
import shutil


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--output", type=Path, default=Path("delivery"))
    args = parser.parse_args()
    report = json.loads(args.report.read_text())
    if report["applicationId"] != "moe.hycnyl.ehviewer":
        raise RuntimeError("Unexpected application ID")
    files = {}
    for artifact in report["artifacts"]:
        filters = artifact["filters"]
        abi = "universal" if not filters else next(f["value"] for f in filters if f["filterType"] == "ABI")
        if abi in files or artifact["versionName"] != args.version or artifact["minimumSdk"] != 33:
            raise RuntimeError("Inconsistent signed APK metadata")
        source = Path(artifact["path"])
        with source.open("rb") as data:
            if hashlib.file_digest(data, "sha256").hexdigest() != artifact["sha256"]:
                raise RuntimeError("APK changed after signing verification")
        files[abi] = (source, artifact["sha256"])
    if set(files) != {"universal", "arm64-v8a", "armeabi-v7a", "x86_64", "x86"}:
        raise RuntimeError("Missing or unexpected release ABI")
    args.output.mkdir(parents=True, exist_ok=True)
    checksums = []
    for abi, (source, digest) in files.items():
        name = f"EhViewer-{args.version}-{abi}.apk"
        shutil.copyfile(source, args.output / name)
        checksums.append(f"{digest}  {name}\n")
    (args.output / "SHA256SUMS.txt").write_text("".join(sorted(checksums)))


if __name__ == "__main__":
    main()
