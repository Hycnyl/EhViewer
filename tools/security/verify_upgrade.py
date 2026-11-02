#!/usr/bin/env python3
"""Destructive signature migration checks, restricted to a disposable emulator.

Verify a real old Default APK and a legacy-signed Marshmallow fixture upgrade to
the new release while retaining UID/data, then reject higher-version old-key APKs.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import time
import urllib.request

OLD_DEFAULT_URL = "https://github.com/Hycnyl/EhViewer/releases/download/v1.17.8/EhViewer-1.17.8-universal.apk"
OLD_DEFAULT_SHA256 = "c2274c75d10427cabc114ce79136fabea5aaac07298c9335bda3ae6dacd00744"


def command(arguments, success=True, timeout=180):
    result = subprocess.run(arguments, capture_output=True, text=True, timeout=timeout)
    text = result.stdout + result.stderr
    if success and result.returncode:
        raise RuntimeError(f"{Path(arguments[0]).name} failed: {text[-2000:]}")
    return result.returncode, text.strip()


def universal(report):
    data = json.loads(report.read_text())
    return Path(next(a["path"] for a in data["artifacts"] if not a["filters"]))


def verify(adb, package, old_apk, new_apk, forbidden_apk):
    shell = lambda text: command([adb, "shell", text])[1]
    command([adb, "uninstall", package], success=False)
    command([adb, "install", "--no-streaming", str(old_apk)])
    before = shell("dumpsys package " + package)
    uid = re.search(r"\buserId=(\d+)", before).group(1)
    previous = int(re.search(r"\bversionCode=(\d+)", before).group(1))
    marker = "sec01-" + secrets.token_hex(16)
    path = "/data/user/0/" + package + "/files/sec01-signing-marker"
    shell(f"mkdir -p /data/user/0/{package}/files; echo {marker} > {path}; chown {uid}:{uid} /data/user/0/{package}/files {path}; restorecon -R /data/user/0/{package}/files")
    command([adb, "install", "-r", "--no-streaming", str(new_apk)])
    after = shell("dumpsys package " + package)
    version = int(re.search(r"\bversionCode=(\d+)", after).group(1))
    if re.search(r"\buserId=(\d+)", after).group(1) != uid or shell("cat " + path) != marker:
        raise RuntimeError(package + ": rotation did not retain UID and application data")
    if version <= previous:
        raise RuntimeError(package + ": expected a higher version for the release upgrade test")
    exit_code, rejection = command([adb, "install", "-r", "--no-streaming", str(forbidden_apk)], success=False)
    if exit_code == 0 or "INSTALL_FAILED_UPDATE_INCOMPATIBLE" not in rejection:
        raise RuntimeError(package + ": higher-version compromised-key APK was not rejected for its signature: " + rejection)
    current = shell("dumpsys package " + package)
    if int(re.search(r"\bversionCode=(\d+)", current).group(1)) != version or shell("cat " + path) != marker:
        raise RuntimeError(package + ": rejected update changed the installed application")
    shell("rm " + path)
    activity = shell("cmd package resolve-activity --brief " + package).splitlines()[-1]
    if "/" not in activity:
        raise RuntimeError(package + ": no launcher activity")
    launch = shell("am start -W -n " + activity)
    if "Error:" in launch:
        raise RuntimeError(package + ": launch failed")
    time.sleep(2)
    if not shell("pidof " + package):
        raise RuntimeError(package + ": application did not remain running")
    shell("am force-stop " + package)
    return {"package": package, "oldVersionCode": previous, "newVersionCode": version, "uidRetained": True, "dataRetained": True, "higherVersionLegacySignerRejected": True, "launchSucceeded": True, "rejection": rejection}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--default-report", type=Path)
    parser.add_argument("--marshmallow-report", type=Path)
    parser.add_argument("--new-default", type=Path)
    parser.add_argument("--old-default", type=Path)
    parser.add_argument("--probes", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    adb = str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb")
    serial = command([adb, "get-serialno"])[1]
    if not serial.startswith("emulator-"):
        raise RuntimeError("Refusing to uninstall applications on a physical device")
    sdk = command([adb, "shell", "getprop ro.build.version.sdk"])[1]
    if sdk != "33":
        raise RuntimeError("Run the minimum supported OS test on API 33")
    command([adb, "root"])
    command([adb, "wait-for-device"])
    if command([adb, "shell", "id", "-u"])[1] != "0":
        raise RuntimeError("The disposable emulator must permit adb root")
    old = args.old_default or args.probes / "old-default-1.17.8.apk"
    if not old.exists():
        with urllib.request.urlopen(OLD_DEFAULT_URL, timeout=180) as response:
            old.write_bytes(response.read())
    with old.open("rb") as source:
        if hashlib.file_digest(source, "sha256").hexdigest() != OLD_DEFAULT_SHA256:
            raise RuntimeError("Old Default APK checksum mismatch")
    current = args.new_default or universal(args.default_report)
    reports = [verify(adb, "moe.hycnyl.ehviewer", old, current, args.probes / "default-higher.apk")]
    if args.marshmallow_report:
        reports.append(verify(adb, "moe.hycnyl.ehviewer.m", args.probes / "marshmallow-baseline.apk", universal(args.marshmallow_report), args.probes / "marshmallow-higher.apk"))
    result = {"api": int(sdk), "serial": serial, "deviceType": "disposable emulator", "marshmallowBaselineIsTestFixture": True, "checks": reports}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, ValueError, KeyError, subprocess.TimeoutExpired) as error:
        print(f"SEC-01: {error}", file=sys.stderr)
        sys.exit(1)
