import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from reuse_runtime_from_apk import ReuseError, extract


class RuntimeReuseTest(unittest.TestCase):
    def make_apk(self, path: Path, *, corrupt_rootfs=False, abi="arm64-v8a"):
        rootfs = b"runtime-rootfs"
        native = b"proot-binary"
        manifest = {
            "schema": 1,
            "abi": abi,
            "runtimeVersion": "r15-test",
            "rootfs": f"rootfs-{abi}.tar.xz",
            "rootfsSha256": hashlib.sha256(rootfs).hexdigest(),
            "rootfsBytes": len(rootfs),
            "uncompressedBytes": len(rootfs),
            "entries": {"file": 1, "dir": 0, "symlink": 0},
            "dshVersion": "test",
            "nodeVersion": "test",
            "pnpmVersion": "test",
            "ubuntuVersion": "test",
            "prootVersion": "test",
            "jniLibs": {
                name: {"sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)}
                for name, data in {
                    "libandroid-shmem.so": b"shmem",
                    "libproot-loader.so": b"loader",
                    "libproot.so": native,
                    "libtalloc.so": b"talloc",
                }.items()
            },
        }
        if corrupt_rootfs:
            rootfs = b"x" * len(rootfs)
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("assets/runtime/manifest.json", json.dumps(manifest))
            archive.writestr(f"assets/runtime/rootfs-{abi}.tar.xz", rootfs)
            archive.writestr("assets/runtime/licenses/license.txt", "license")
            for name, data in {
                "libandroid-shmem.so": b"shmem",
                "libproot-loader.so": b"loader",
                "libproot.so": native,
                "libtalloc.so": b"talloc",
            }.items():
                archive.writestr(f"lib/{abi}/{name}", data)
        sums = path.with_name("SHA256SUMS")
        sums.write_text(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n")
        return sums

    def test_extracts_verified_runtime_and_rebuilds_index(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            apk, out = root / "old.apk", root / "out"
            sums = self.make_apk(apk)
            extract(apk, sums, out)
            self.assertEqual((out / "arm64-v8a/assets/runtime/rootfs-arm64-v8a.tar.xz").read_bytes(), b"runtime-rootfs")
            self.assertEqual((out / "arm64-v8a/jniLibs/arm64-v8a/libproot.so").read_bytes(), b"proot-binary")
            index = json.loads((out / "manifest.json").read_text())
            self.assertEqual(index["abis"]["arm64-v8a"]["runtimeVersion"], "r15-test")

    def test_rejects_wrong_published_checksum(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            apk = root / "old.apk"
            sums = self.make_apk(apk)
            sums.write_text(f"{'0' * 64}  {apk.name}\n")
            with self.assertRaisesRegex(ReuseError, "SHA256SUMS"):
                extract(apk, sums, root / "out")

    def test_rejects_wrong_abi(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            apk = root / "old.apk"
            sums = self.make_apk(apk, abi="x86_64")
            with self.assertRaisesRegex(ReuseError, "manifest ABI"):
                extract(apk, sums, root / "out")

    def test_rejects_rootfs_hash_mismatch(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            apk = root / "old.apk"
            sums = self.make_apk(apk, corrupt_rootfs=True)
            with self.assertRaisesRegex(ReuseError, "rootfs SHA-256"):
                extract(apk, sums, root / "out")


if __name__ == "__main__":
    unittest.main()
