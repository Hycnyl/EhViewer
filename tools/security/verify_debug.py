#!/usr/bin/env python3
"""Check that ordinary Debug APKs retain their IDs, SDK floors and debug signer."""

import json
import os
from pathlib import Path
import re
import subprocess


def main():
    tools = Path(os.environ["ANDROID_HOME"]) / "build-tools/36.0.0"
    policy = json.loads(Path("app/signing/release.json").read_text())
    for flavor, minimum, package in (("default", 26, "moe.hycnyl.ehviewer.debug"), ("marshmallow", 23, "moe.hycnyl.ehviewer.m.debug")):
        apks = list(Path(f"app/build/outputs/apk/{flavor}/debug").glob("*.apk"))
        if not apks:
            raise RuntimeError("Missing Debug APKs for " + flavor)
        for apk in apks:
            data = subprocess.check_output([str(tools / "aapt"), "dump", "badging", str(apk)], text=True)
            if f"name='{package}'" not in data or f"sdkVersion:'{minimum}'" not in data or "application-debuggable" not in data:
                raise RuntimeError("Debug configuration changed for " + flavor)
            cert = subprocess.check_output([str(tools / "apksigner"), "verify", "--print-certs", str(apk)], text=True)
            fingerprint = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})", cert).group(1)
            if fingerprint in (policy["legacyCertificateSha256"], policy["certificateSha256"]):
                raise RuntimeError("Debug must use its independent debug signing identity")
    print("Default and Marshmallow Debug IDs, SDK floors and signing verified")


if __name__ == "__main__":
    main()
