#!/usr/bin/env python3
"""Extract and verify the bundled arm64 runtime from a published APK."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from pathlib import PurePosixPath
import shutil
import tempfile
import zipfile


class ReuseError(Exception):
    pass


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def read_entry(archive: zipfile.ZipFile, name: str) -> bytes:
    try:
        info = archive.getinfo(name)
    except KeyError as exc:
        raise ReuseError(f"APK is missing {name}") from exc
    if info.is_dir():
        raise ReuseError(f"APK entry is a directory: {name}")
    if info.external_attr >> 28 == 0xA:
        raise ReuseError(f"APK runtime entry is a symbolic link: {name}")
    return archive.read(name)


def verify_release_sum(apk: Path, sums: Path) -> None:
    digest = hashlib.sha256()
    with apk.open("rb") as source:
        for chunk in iter(lambda: source.read(1 << 20), b""):
            digest.update(chunk)
    apk_hash = digest.hexdigest()
    matches = []
    for line in sums.read_text(encoding="utf-8").splitlines():
        fields = line.split(maxsplit=1)
        if len(fields) == 2 and fields[1].lstrip(" *") == apk.name:
            matches.append(fields[0].lower())
    if len(matches) != 1 or matches[0] != apk_hash:
        raise ReuseError("APK does not match exactly one entry in the published SHA256SUMS")


def extract(apk_path: Path, sums: Path, out: Path, abi: str = "arm64-v8a") -> None:
    if not apk_path.is_file() or not sums.is_file():
        raise ReuseError("published APK or SHA256SUMS is missing")
    verify_release_sum(apk_path, sums)

    with zipfile.ZipFile(apk_path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ReuseError("APK contains duplicate ZIP entries")
        manifest_name = "assets/runtime/manifest.json"
        manifest = json.loads(read_entry(archive, manifest_name))
        if manifest.get("schema") != 1:
            raise ReuseError(f"unsupported runtime manifest schema: {manifest.get('schema')!r}")
        if manifest.get("abi") != abi:
            raise ReuseError(f"runtime manifest ABI {manifest.get('abi')!r} != {abi!r}")
        rootfs = manifest.get("rootfs")
        if rootfs != f"rootfs-{abi}.tar.xz":
            raise ReuseError(f"unexpected runtime rootfs name: {rootfs!r}")
        if not isinstance(manifest.get("runtimeVersion"), str) or not manifest["runtimeVersion"]:
            raise ReuseError("runtime manifest has no version")
        rootfs_name = f"assets/runtime/{rootfs}"
        rootfs_data = read_entry(archive, rootfs_name)
        if manifest.get("rootfsBytes") != len(rootfs_data):
            raise ReuseError("runtime rootfs byte count does not match manifest")
        if manifest.get("rootfsSha256") != sha256(rootfs_data):
            raise ReuseError("runtime rootfs SHA-256 does not match manifest")

        jni = manifest.get("jniLibs")
        if not isinstance(jni, dict) or not jni:
            raise ReuseError("runtime manifest has no native library inventory")
        required = {"libandroid-shmem.so", "libproot-loader.so", "libproot.so", "libtalloc.so"}
        if not required.issubset(jni):
            raise ReuseError(f"runtime native library inventory is incomplete: {sorted(required - set(jni))}")
        extracted = {
            manifest_name: json.dumps(manifest, ensure_ascii=False, indent=2).encode() + b"\n",
            rootfs_name: rootfs_data,
        }
        for library, details in jni.items():
            if not isinstance(library, str) or not library.startswith("lib") or not library.endswith(".so") or "/" in library:
                raise ReuseError(f"invalid native library name: {library!r}")
            if not isinstance(details, dict):
                raise ReuseError(f"invalid native library record: {library}")
            entry = f"lib/{abi}/{library}"
            data = read_entry(archive, entry)
            if details.get("bytes") != len(data) or details.get("sha256") != sha256(data):
                raise ReuseError(f"native library does not match manifest: {library}")
            extracted[entry] = data

        for name in names:
            if name.startswith("assets/runtime/licenses/") and not name.endswith("/"):
                relative = PurePosixPath(name.removeprefix("assets/runtime/"))
                if relative.is_absolute() or ".." in relative.parts:
                    raise ReuseError(f"unsafe runtime license path: {name}")
                extracted[name] = read_entry(archive, name)

    out.parent.mkdir(parents=True, exist_ok=True)
    out.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="runtime-reuse-", dir=out.parent) as temp:
        staged = Path(temp) / abi
        assets = staged / "assets" / "runtime"
        jni_dir = staged / "jniLibs" / abi
        assets.mkdir(parents=True)
        jni_dir.mkdir(parents=True)
        for name, data in extracted.items():
            if name.startswith("assets/runtime/"):
                target = assets / name.removeprefix("assets/runtime/")
            else:
                target = jni_dir / name.removeprefix(f"lib/{abi}/")
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)

        index = {"schema": 1, "abis": {}}
        for other_abi in ("x86_64", abi):
            other_manifest = out / other_abi / "assets" / "runtime" / "manifest.json"
            if other_abi == abi:
                other = manifest
            elif other_manifest.is_file():
                other = json.loads(other_manifest.read_text(encoding="utf-8"))
            else:
                continue
            entry = {key: other.get(key) for key in (
                "runtimeVersion", "rootfs", "rootfsSha256", "rootfsBytes", "uncompressedBytes",
                "entries", "dshVersion", "nodeVersion", "pnpmVersion", "ubuntuVersion", "prootVersion",
            )}
            entry["jniLibs"] = {
                name: {"sha256": details["sha256"], "bytes": details["bytes"]}
                for name, details in other["jniLibs"].items()
            }
            index["abis"][other_abi] = entry
        (Path(temp) / "manifest.json").write_text(json.dumps(index, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        if (out / abi).exists():
            shutil.rmtree(out / abi)
        shutil.move(str(staged), str(out / abi))
        shutil.move(str(Path(temp) / "manifest.json"), str(out / "manifest.json"))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--sums", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--abi", default="arm64-v8a")
    args = parser.parse_args()
    try:
        extract(args.apk, args.sums, args.out, args.abi)
    except (OSError, ValueError, zipfile.BadZipFile, ReuseError) as exc:
        raise SystemExit(f"runtime reuse failed: {exc}") from exc


if __name__ == "__main__":
    main()
