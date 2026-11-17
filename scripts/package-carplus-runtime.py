"""Add a supplied ICCOA DEX to the base APK and sign it with the existing debug key."""

import argparse
import hashlib
import os
import re
import struct
import subprocess
from pathlib import Path
from tempfile import TemporaryDirectory
from zipfile import ZIP_DEFLATED, ZipFile


APK_DEX = re.compile(r"classes(\d*)\.dex")
SINK_LIBRARY = "lib/arm64-v8a/libovmsink.so"
SINK_SHA256 = "298b9bff630751fbb6a934e2e6f549bcea83d619ed6d6b2eadd63ba68b231f34"


def patch_wfd_profile_control(library: bytes) -> bytes:
    """Allow the app to advertise Constrained Baseline Level 4.0 for 1412×654."""
    if hashlib.sha256(library).hexdigest() != SINK_SHA256:
        raise ValueError("The Audi WFD experiment requires libovmsink.so from MiAuto 0.1.1")

    profile_load = 0xA2E20
    dpi_load = 0x9D4C0
    code_start = 0x16004C

    def branch(source: int, target: int) -> int:
        return 0x14000000 | (((target - source) // 4) & 0x03FFFFFF)

    # M3's profile formatter receives the VideoFormats member at sink + 0x60.
    # x8 points to that member and x10 to its capability row. w7 is scratch.
    # Level 4.0 covers the advertised canvas at 60 fps, unlike the stock level 3.1.
    profile_check = (
        0x3940E542,  # ldrb w2, [x10, #0x39]  (original WFD profile mask)
        0x3940E943,  # ldrb w3, [x10, #0x3a]  (original WFD level mask)
        0xB94CED07,  # ldr  w7, [x8, #0xcec]  (sink DPI with the app's marker)
        0x53107CE7,  # lsr  w7, w7, #16
        0x714010FF,  # cmp  w7, #0x4000
        0x54000121,  # b.ne resume profile formatting
        0xB94CF107,  # ldr  w7, [x8, #0xcf0]  (sink width)
        0x711610FF,  # cmp  w7, #1412
        0x540000C1,  # b.ne resume profile formatting
        0xB94CF507,  # ldr  w7, [x8, #0xcf4]  (sink height)
        0x710A38FF,  # cmp  w7, #654
        0x54000061,  # b.ne resume profile formatting
        0x52800022,  # mov  w2, #1             (WFD Constrained Baseline)
        0x52800083,  # mov  w3, #4             (WFD Level 4.0)
    )
    profile_resume = code_start + 4 * len(profile_check)
    dpi_start = profile_resume + 4

    # M3's display-mode formatter is the only wire use of the stored DPI.
    # Strip the reserved high word before sending it. w0 is overwritten next.
    dpi_clean = (
        0xB94D4D03,  # ldr  w3, [x8, #0xd4c]
        0x53107C60,  # lsr  w0, w3, #16
        0x7140101F,  # cmp  w0, #0x4000
        0x54000041,  # b.ne resume display-mode formatting
        0x53003C63,  # uxth w3, w3
    )
    instructions = (
        *profile_check, branch(profile_resume, profile_load + 8),
        *dpi_clean, branch(dpi_start + 4 * len(dpi_clean), dpi_load + 4),
    )
    code = struct.pack(f"<{len(instructions)}I", *instructions)
    code_end = code_start + len(code)

    patched = bytearray(library)
    # The first PT_LOAD starts at offset and virtual address zero. Extend its
    # file and memory sizes into padding within the same executable page.
    struct.pack_into("<QQ", patched, 0x40 + 32, code_end, code_end)
    struct.pack_into("<I", patched, profile_load, branch(profile_load, code_start))
    struct.pack_into("<I", patched, dpi_load, branch(dpi_load, dpi_start))
    patched[code_start:code_end] = code
    return bytes(patched)


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
            audio_java: Path | None = None, video_java: Path | None = None,
            wfd_profile_control: bool = False) -> None:
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
        patched_sink = None
        with ZipFile(base_apk) as source, ZipFile(unsigned, "w") as destination:
            if "assets/ccd.data" not in source.namelist():
                raise ValueError("The Android build did not package ccd.data")
            if audio_java is not None and b"CarPlusAudioTap" in source.read("assets/iccoa-relay/relay.js"):
                raise ValueError("Use the standard ICCOA relay assets. The SDK DEX already handles audio forwarding.")
            if wfd_profile_control:
                if SINK_LIBRARY not in source.namelist():
                    raise ValueError("The Audi WFD experiment requires an arm64-v8a ICCOA sink library")
                patched_sink = patch_wfd_profile_control(source.read(SINK_LIBRARY))
            classes = [APK_DEX.fullmatch(name) for name in source.namelist()]
            last_dex = max(int(match[1] or "1") for match in classes if match)
            for entry in source.infolist():
                if re.fullmatch(r"META-INF/(MANIFEST\.MF|[^/]+\.(SF|RSA|DSA|EC))", entry.filename):
                    continue
                # Compress the top-level DEX entries without changing resource or native library packaging.
                compression = ZIP_DEFLATED if APK_DEX.fullmatch(entry.filename) else entry.compress_type
                if entry.filename == SINK_LIBRARY and patched_sink is not None:
                    content = patched_sink
                else:
                    content = source.read(entry)
                destination.writestr(entry, content, compress_type=compression)
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
            if patched_sink is not None and archive.read(SINK_LIBRARY) != patched_sink:
                raise ValueError("The ICCOA sink library changed during packaging")
        signed.replace(output)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for argument in ("base_apk", "sdk_dex", "tools", "keystore", "output"):
        parser.add_argument(argument, type=Path)
    parser.add_argument("--audio-java", type=Path)
    parser.add_argument("--video-java", type=Path)
    parser.add_argument("--wfd-profile-control", action="store_true")
    package(**vars(parser.parse_args()))
