"""Create no-code, legacy-signed installation test inputs. Never release these as apps."""

import io
import zipfile

from sign_release import run


def make_probes(tools, android_jar, legacy_store, legacy_alias, directory):
    """These no-code APKs are test inputs, never release assets."""
    result = io.BytesIO()
    with zipfile.ZipFile(result, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, package, version, minimum in (
            ("default-higher.apk", "moe.hycnyl.ehviewer", 2147483646, 33),
            ("marshmallow-higher.apk", "moe.hycnyl.ehviewer.m", 2147483646, 33),
            ("marshmallow-baseline.apk", "moe.hycnyl.ehviewer.m", 180083, 23),
        ):
            manifest = directory / "AndroidManifest.xml"
            manifest.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}" android:versionCode="{version}" android:versionName="SEC-01 test only">
<uses-sdk android:minSdkVersion="{minimum}" android:targetSdkVersion="36"/>
<application android:hasCode="false" android:label="SEC-01 signature test"/>
</manifest>''')
            unsigned = directory / (name + ".unsigned")
            signed = directory / name
            run([str(tools / "aapt2"), "link", "-I", str(android_jar), "--manifest", str(manifest), "-o", str(unsigned)])
            run([
                str(tools / "apksigner"), "sign", "--ks", str(legacy_store),
                "--ks-key-alias", legacy_alias, "--ks-pass", "env:SEC01_LEGACY_STORE_PASSWORD",
                "--key-pass", "env:SEC01_LEGACY_KEY_PASSWORD", "--v4-signing-enabled", "false",
                "--out", str(signed), str(unsigned),
            ])
            archive.write(signed, name)
    return result.getvalue()
