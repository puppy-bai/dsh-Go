#!/usr/bin/env python3
"""Exercise the real runtime download functions with curl replaced; no network/build."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest


def find_bash():
    if os.name == 'nt':
        # Prefer Git Bash to the Windows bash.exe shim that starts WSL.
        git = shutil.which('git')
        if git:
            candidate = Path(git).parent.parent / 'bin/bash.exe'
            if candidate.is_file():
                return str(candidate)
    return shutil.which('bash')


BASH = find_bash()
RECIPE = Path(__file__).resolve().parents[1] / 'build-runtime.sh'
PAYLOAD = b'locked package fixture'
SHA = hashlib.sha256(PAYLOAD).hexdigest()
PACKAGE = 'pool/main/f/fixture/fixture_1_arm64.deb'
PRIMARY = 'https://primary.invalid/ubuntu-ports/' + PACKAGE
SNAPSHOT = 'https://snapshot.ubuntu.com/ubuntu/20261101T000000Z/' + PACKAGE

SETUP = r'''
if command -v cygpath >/dev/null; then TEST_ROOT="$(cygpath -u "$TEST_ROOT")"; fi
# Native Windows Python emits CRLF; shell read expects the Linux lock-reader output.
python3() { "$TEST_PYTHON" "$@" | sed 's/\r$//'; }
source "$TEST_ROOT/functions.sh"
CACHE="$TEST_ROOT/cache"
LOCKS="$TEST_ROOT/locks"
UBUNTU_ARCHIVE_ARM64=https://primary.invalid/ubuntu-ports
UBUNTU_EXTRA=fixture
UPDATE_DEB_LOCK=0
curl() {
  local dest='' url="${@: -1}"
  while (( $# )); do
    case "$1" in -o) dest="$2"; shift 2;; *) shift;; esac
  done
  printf '%s\n' "$url" >> "$TEST_ROOT/requests"
  case "$TEST_MODE:$url" in
    http:*) return 22;;
    fallback*:https://primary.invalid/*) printf 'partial' > "$dest"; return 22;;
    corrupt:*|fallback-corrupt:*) printf 'wrong package' > "$dest";;
    *) printf 'locked package fixture' > "$dest";;
  esac
}
'''


@unittest.skipUnless(BASH, 'bash is required to test the runtime recipe')
class RuntimeFetch(unittest.TestCase):
    def run_recipe(self, mode, command, cached=False):
        source = RECIPE.read_text(encoding='utf-8')
        source, count = re.subn(r'(?m)^main "\$@"\s*\Z', '', source)
        self.assertEqual(count, 1, 'Only strip the final build entrypoint')
        with tempfile.TemporaryDirectory(prefix='dsh-runtime-fetch-') as temporary:
            root = Path(temporary)
            (root / 'functions.sh').write_text(source, encoding='utf-8', newline='\n')
            locks = root / 'locks'
            locks.mkdir()
            (locks / 'ubuntu-noble-arm64.json').write_text(json.dumps({
                'want': ['fixture'], 'packages': [{'filename': PACKAGE, 'sha256': SHA}],
            }), encoding='utf-8')
            destination = root / 'cache/ubuntu/debs/fixture_1_arm64.deb'
            if cached:
                destination.parent.mkdir(parents=True)
                destination.write_bytes(PAYLOAD)
            env = dict(os.environ, TEST_ROOT=str(root), TEST_PYTHON=sys.executable,
                       TEST_MODE=mode, TEST_SHA=SHA)
            result = subprocess.run([BASH, '--noprofile', '--norc', '-s'],
                                    input=SETUP + '\n' + command, text=True,
                                    capture_output=True, env=env, timeout=30)
            requests = (root / 'requests').read_text().splitlines() if (root / 'requests').exists() else []
            contents = destination.read_bytes() if destination.exists() else None
            output_exists = (root / 'cache/output').exists()
            return result, requests, contents, output_exists

    def test_http_failure_propagates_even_in_an_or_context(self):
        result, requests, _, output_exists = self.run_recipe(
            'http', 'fetch https://primary.invalid/missing "$CACHE/output" || exit $?\n')
        self.assertEqual(result.returncode, 22, result.stderr)
        self.assertEqual(requests, ['https://primary.invalid/missing'])
        self.assertFalse(output_exists)

    def test_snapshot_replaces_partial_download_and_keeps_the_locked_checksum(self):
        result, requests, contents, _ = self.run_recipe('fallback', 'ubuntu_extra_debs arm64 unused\n')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(requests, [PRIMARY, SNAPSHOT])
        self.assertEqual(contents, PAYLOAD)
        self.assertEqual(len(result.stdout.splitlines()), 1)
        self.assertTrue(result.stdout.strip().endswith('/ubuntu/debs/fixture_1_arm64.deb'))

    def test_hash_mismatch_fails_without_trying_another_source(self):
        result, requests, contents, _ = self.run_recipe('corrupt', 'ubuntu_extra_debs arm64 unused\n')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('sha256 mismatch', result.stderr)
        self.assertEqual(requests, [PRIMARY])
        self.assertIsNone(contents)

    def test_snapshot_hash_mismatch_also_fails_and_removes_the_download(self):
        result, requests, contents, _ = self.run_recipe('fallback-corrupt', 'ubuntu_extra_debs arm64 unused\n')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('sha256 mismatch', result.stderr)
        self.assertEqual(requests, [PRIMARY, SNAPSHOT])
        self.assertIsNone(contents)

    def test_valid_locked_cache_needs_no_download(self):
        result, requests, contents, _ = self.run_recipe('http', 'ubuntu_extra_debs arm64 unused\n', cached=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(requests, [])
        self.assertEqual(contents, PAYLOAD)


if __name__ == '__main__':
    unittest.main(verbosity=2)
