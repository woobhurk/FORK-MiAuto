#!/usr/bin/env python3
"""Build app/libs/icar-sdk.dex from the Xiaoba CarLink APK already in apks/.

The reference APK mixes the ICCOA SDK with the demo shell. Gradle cannot consume a
dex dependency, and the demo package name is hard-wired into a few SDK helpers, so
this script:

1. disassembles every classes*.dex in the APK,
2. keeps the SDK packages plus the libraries they call (protobuf, Guava, Gson, Tink)
   and the demo classes those helpers name,
3. rewrites the hardcoded head-unit name to MiAutoCar,
4. assembles one dex.

The result is gitignored (*.dex). assemble* runs this when the file is missing.
"""

import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APK_DIR = ROOT / "apks"
OUT_DEX = ROOT / "app" / "libs" / "icar-sdk.dex"
OUT_DEPS = ROOT / "app" / "libs" / "icar-sdk-deps.dex"
BAKSMALI = ROOT / "tools" / "baksmali.jar"
SMALI = ROOT / "tools" / "smali.jar"

# One dex cannot hold the SDK and Guava/Tink/protobuf together (method ids overflow 16 bits).
CORE = (
    "com/ucar",
    "com/ucarhu",
    "com/share",
    "com/ucarsink",
    "com/easy",
)
DEPS = (
    "com/google/protobuf",
    "com/google/common",
    "com/google/gson",
    "com/google/crypto",
    "com/googlecode",
    "org/apache",
)

DISPLAY_NAME_OLD = 'const-string v0, "XB\\u8f66\\u8054"'
DISPLAY_NAME_NEW = 'const-string v0, "MiAutoCar"'


def reference_apk() -> Path:
    matches = sorted(APK_DIR.glob("ICCOA Carlink_*.apk"))
    if not matches:
        sys.exit(f"no Xiaoba CarLink APK under {APK_DIR}")
    return matches[-1]


def run(cmd):
    subprocess.check_call(cmd)


def merge_tree(src: Path, dst: Path):
    for path in src.rglob("*"):
        rel = path.relative_to(src)
        target = dst / rel
        if path.is_dir():
            target.mkdir(parents=True, exist_ok=True)
            continue
        if target.exists():
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, target)


def main():
    if not BAKSMALI.is_file() or not SMALI.is_file():
        sys.exit(f"missing {BAKSMALI.name} / {SMALI.name} under tools/")
    apk = reference_apk()
    work = ROOT / "build" / "icar-sdk"
    if work.exists():
        shutil.rmtree(work)
    dex_dir = work / "dex"
    smali_root = work / "smali"
    kept = work / "kept"
    deps = work / "deps"
    dex_dir.mkdir(parents=True)
    kept.mkdir(parents=True)
    deps.mkdir(parents=True)

    with zipfile.ZipFile(apk) as z:
        dexes = [n for n in z.namelist() if n.startswith("classes") and n.endswith(".dex")]
        if not dexes:
            sys.exit(f"{apk.name} has no classes*.dex")
        for name in dexes:
            dest = dex_dir / Path(name).name
            dest.write_bytes(z.read(name))
            out = smali_root / dest.stem
            run(["java", "-jar", str(BAKSMALI), "d", str(dest), "-o", str(out), "-j", "4"])
            for prefix in CORE:
                src = out / prefix
                if src.is_dir():
                    merge_tree(src, kept / prefix)
            for prefix in DEPS:
                src = out / prefix
                if src.is_dir():
                    merge_tree(src, deps / prefix)

    device = kept / "com/share/connect/Device.smali"
    text = device.read_text(encoding="utf-8")
    found = text.count(DISPLAY_NAME_OLD)
    if found != 2:
        sys.exit(f"expected 2 head-unit name strings in Device.smali, found {found}")
    device.write_text(text.replace(DISPLAY_NAME_OLD, DISPLAY_NAME_NEW), encoding="utf-8")

    OUT_DEX.parent.mkdir(parents=True, exist_ok=True)
    run(["java", "-jar", str(SMALI), "a", str(kept), "-o", str(OUT_DEX), "--api", "30"])
    run(["java", "-jar", str(SMALI), "a", str(deps), "-o", str(OUT_DEPS), "--api", "30"])
    data = OUT_DEX.read_bytes()
    if b"MiAutoCar" not in data or "XB车联".encode() in data:
        sys.exit("display-name patch did not land in the dex")
    print(f"wrote {OUT_DEX} ({len(data)} bytes) and {OUT_DEPS} ({OUT_DEPS.stat().st_size} bytes) from {apk.name}")


if __name__ == "__main__":
    main()
