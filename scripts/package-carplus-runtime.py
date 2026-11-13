"""Add a supplied ICCOA DEX to the base APK and sign it with the existing debug key."""

import argparse
import os
import re
import subprocess
from pathlib import Path
from tempfile import TemporaryDirectory
from zipfile import ZIP_DEFLATED, ZipFile


APK_DEX = re.compile(r"classes(\d*)\.dex")


def instrument_sdk(java: Path, sdk_dex: Path, output: Path, tools: Path, patch: str) -> None:
    libraries = tools.parent.parent / "cmdline-tools/latest/lib/external"
    jars = []
    for name in ("smali-dexlib2", "smali-util", "guava", "failureaccess"):
        matches = sorted(libraries.glob(f"**/{name}/*/{name}-*.jar"))
        if not matches:
            raise ValueError(f"Android command-line tools library missing: {name}")
        jars.append(matches[-1])
    subprocess.run(
        [java, "--class-path", os.pathsep.join(map(str, jars)),
         Path(__file__).with_name(patch), sdk_dex, output],
        check=True,
    )


def package(base_apk: Path, sdk_dex: Path, tools: Path, keystore: Path, output: Path,
            audio_java: Path | None = None, video_java: Path | None = None) -> None:
    dex = sdk_dex.read_bytes()
    if b"Lcom/ucar/vehiclesdk/UCarAdapter;" not in dex:
        raise ValueError("The supplied DEX does not contain the expected ICCOA adapter")
    subprocess.run([tools / "dexdump", "-c", sdk_dex], check=True, stdout=subprocess.DEVNULL)

    output.parent.mkdir(parents=True, exist_ok=True)
    with TemporaryDirectory(dir=output.parent) as temporary:
        temp_dir = Path(temporary)
        for java, patch in ((audio_java, "PatchCarPlusAudio.java"), (video_java, "PatchCarPlusVideo.java")):
            if java is None:
                continue
            patched_dex = temp_dir / (Path(patch).stem + ".dex")
            instrument_sdk(java, sdk_dex, patched_dex, tools, patch)
            subprocess.run([tools / "dexdump", "-c", patched_dex], check=True, stdout=subprocess.DEVNULL)
            dex = patched_dex.read_bytes()
            sdk_dex = patched_dex
        unsigned = temp_dir / "unsigned.apk"
        aligned = temp_dir / "aligned.apk"
        signed = temp_dir / "signed.apk"
        with ZipFile(base_apk) as source, ZipFile(unsigned, "w") as destination:
            if "assets/ccd.data" not in source.namelist():
                raise ValueError("The Android build did not package ccd.data")
            if audio_java is not None and b"CarPlusAudioTap" in source.read("assets/iccoa-relay/relay.js"):
                raise ValueError("Use the standard ICCOA relay assets. The SDK DEX already handles audio forwarding.")
            classes = [APK_DEX.fullmatch(name) for name in source.namelist()]
            last_dex = max(int(match[1] or "1") for match in classes if match)
            for entry in source.infolist():
                if re.fullmatch(r"META-INF/(MANIFEST\.MF|[^/]+\.(SF|RSA|DSA|EC))", entry.filename):
                    continue
                # Compress the top-level DEX entries without changing resource or native library packaging.
                compression = ZIP_DEFLATED if APK_DEX.fullmatch(entry.filename) else entry.compress_type
                destination.writestr(entry, source.read(entry), compress_type=compression)
            dex_name = f"classes{last_dex + 1}.dex"
            destination.writestr(dex_name, dex, compress_type=ZIP_DEFLATED)

        subprocess.run([tools / "zipalign", "-f", "-p", "4", unsigned, aligned], check=True)
        subprocess.run(
            [
                tools / "apksigner", "sign",
                "--ks", keystore, "--ks-key-alias", "androiddebugkey",
                "--ks-pass", "pass:android", "--key-pass", "pass:android",
                "--out", signed, aligned,
            ],
            check=True,
        )
        subprocess.run([tools / "apksigner", "verify", signed], check=True)
        with ZipFile(signed) as archive:
            if archive.read(dex_name) != dex:
                raise ValueError("The ICCOA DEX changed during packaging")
        signed.replace(output)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for argument in ("base_apk", "sdk_dex", "tools", "keystore", "output"):
        parser.add_argument(argument, type=Path)
    parser.add_argument("--audio-java", type=Path)
    parser.add_argument("--video-java", type=Path)
    package(**vars(parser.parse_args()))
