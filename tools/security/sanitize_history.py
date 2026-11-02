#!/usr/bin/env python3
"""SEC-01: rewrite a complete mirror, removing the leaked JKS and passwords.

Remote updates are opt-in and atomic, with a lease for every existing ref.
The original mirror should be backed up privately before running this tool.
"""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess


def git(*arguments):
    return subprocess.check_output(["git", *arguments], text=True).strip()


def clean(mirror, push, report):
    import git_filter_repo

    os.chdir(mirror)
    if git("rev-parse", "--is-bare-repository") != "true" or git("rev-parse", "--is-shallow-repository") != "false":
        raise RuntimeError("Use a complete bare mirror")
    origin = git("remote", "get-url", "origin")
    before = dict(line.split(maxsplit=1)[::-1] for line in git("show-ref").splitlines())
    old_key = [line.split(maxsplit=1)[0] for line in git("rev-list", "--objects", "--all", "--", "app/keystore/androidkey.jks").splitlines() if line.endswith(" app/keystore/androidkey.jks")]

    def remove_passwords(filename, mode, blob_id, value):
        if filename == b"app/build.gradle.kts":
            data = value.get_contents_by_identifier(blob_id)
            updated = re.sub(
                rb'(?m)^([ \t]*)(storePassword|keyPassword)[ \t]*=[ \t]*"[^"\r\n]*"[ \t]*$',
                lambda match: match[1] + match[2] + b" = null // SEC-01: exposed credential removed",
                data,
            )
            if updated != data:
                blob_id = value.insert_file_with_contents(updated)
        return filename, mode, blob_id

    arguments = git_filter_repo.FilteringOptions.parse_args([
        "--force", "--sensitive-data-removal", "--path", "app/keystore/androidkey.jks", "--invert-paths",
        "--prune-empty", "never", "--prune-degenerate", "never",
    ])
    git_filter_repo.RepoFilter(arguments, file_info_callback=remove_passwords).run()
    after = dict(line.split(maxsplit=1)[::-1] for line in git("show-ref").splitlines())
    if set(before) != set(after):
        raise RuntimeError("Unexpected ref removal during cleanup")
    if git("rev-list", "--objects", "--all", "--", "app/keystore/androidkey.jks"):
        raise RuntimeError("Legacy key remains reachable")
    subprocess.run(["git", "fsck", "--full"], check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    summary = {"legacyBlob": old_key, "refCount": len(after), "refs": [{"ref": ref, "before": before[ref], "after": after[ref]} for ref in sorted(after)], "legacyPathReachable": False, "pushed": False}
    if push:
        remotes = git("remote").splitlines()
        subprocess.run(["git", "remote", "set-url" if "origin" in remotes else "add", "origin", origin], check=True)
        commands = ["git", "push", "--atomic", "origin"]
        for ref in sorted(before):
            commands.append(f"--force-with-lease={ref}:{before[ref]}")
        commands.extend(f"{after[ref]}:{ref}" for ref in sorted(after))
        subprocess.run(commands, check=True)
        summary["pushed"] = True
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps(summary, indent=2) + "\n")
    print(f"SEC-01: sanitized {len(after)} refs; remote update={summary['pushed']}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mirror", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--push", action="store_true")
    args = parser.parse_args()
    clean(args.mirror.resolve(), args.push, args.report.resolve())


if __name__ == "__main__":
    main()
