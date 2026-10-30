"""Exercise the production bootstrap with tiny model bundles and a fake downloader."""
import hashlib
import io
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor


REQUIRED = (
    "local-model-artifact.json", "djl/vqvae/model.pt", "djl/vqvae/metadata.json",
    "djl/text/model.pt", "djl/text/metadata.json", "djl/emotional/model.pt",
    "djl/emotional/metadata.json", "thymos-6d/model.pt", "thymos-6d/tokenizer.json",
    "thymos-6d/metadata.json",
)
ENTRYPOINT = Path(__file__).resolve().parents[1] / "model-entrypoint.sh"


class ModelBootstrapTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="openeden-model-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.bash = shutil.which("bash")
        if os.name == "nt":
            self.bash = str(Path(os.environ["ProgramFiles"]) / "Git/bin/bash.exe")
        if not self.bash or not Path(self.bash).exists():
            self.skipTest("Bash is required")
        self.manifest = self.root / "manifest"
        self.manifest.mkdir()
        self.cache = self.root / "cache"
        self.bin = self.root / "bin"
        self.bin.mkdir()
        downloader = self.bin / "curl"
        downloader.write_text(
            '#!/usr/bin/env bash\nset -eu\n'
            'printf "download\\n" >> "$TEST_DOWNLOAD_LOG"\n'
            'while [[ $# -gt 0 ]]; do\n'
            '  if [[ $1 == --output ]]; then target=$2; shift; fi\n'
            '  shift\n'
            'done\n'
            'cp "$TEST_ARCHIVE" "$target"\n', encoding="utf-8")
        downloader.chmod(0o755)
        # Git Bash lacks flock; production/CI on Linux use the actual utility.
        if os.name == "nt":
            (self.bin / "flock").write_text("#!/bin/bash\nexit 0\n", encoding="utf-8")
        self.env = dict(os.environ)
        for key in tuple(self.env):
            if key.startswith("OPENEDEN_"):
                del self.env[key]
        self.env.update({
            "OPENEDEN_MODEL_MANIFEST_DIR": self.shell_path(self.manifest),
            "OPENEDEN_MODEL_CACHE_DIR": self.shell_path(self.cache),
            "TEST_DOWNLOAD_LOG": self.shell_path(self.root / "downloads"),
            "TEST_ARCHIVE": self.shell_path(self.root / "bundle.tar.gz"),
            "TEST_STARTED": self.shell_path(self.root / "started"),
        })
        self.create_bundle()

    def shell_path(self, path):
        if os.name != "nt":
            return str(path)
        return subprocess.check_output(
            [self.bash, "-c", 'cygpath -u "$1"', "_", str(path)], text=True).strip()

    def create_bundle(self, content=b"fixture model", missing=None):
        archive = self.root / "bundle.tar.gz"
        with tarfile.open(archive, "w:gz") as tar:
            for name in REQUIRED:
                if name == missing:
                    continue
                info = tarfile.TarInfo(name)
                info.size = len(content)
                tar.addfile(info, io.BytesIO(content))
        self.digest = hashlib.sha256(archive.read_bytes()).hexdigest()
        (self.manifest / "sha256").write_text(self.digest + "\n")
        (self.manifest / "url").write_text("https://example.invalid/models.tar.gz\n")

    def run_bootstrap(self):
        command = (
            'test -s "$OPENEDEN_LOCAL_MODEL_ARTIFACT" && '
            'test -s "$OPENEDEN_DJL_VQVAE_MODEL_PATH/model.pt" && '
            'test -s "$OPENEDEN_DJL_TEXT_MODEL_PATH/model.pt" && '
            'test -s "$OPENEDEN_DJL_EMOTIONAL_MODEL_PATH/model.pt" && '
            'test -s "$OPENEDEN_DJL_AFFECT_MODEL_PATH/tokenizer.json" && '
            'printf started > "$TEST_STARTED"'
        )
        wrapper = 'export PATH="$1:$PATH"; shift; exec bash "$@"'
        return subprocess.run(
            [self.bash, "-c", wrapper, "_", self.shell_path(self.bin),
             self.shell_path(ENTRYPOINT), "bash", "-c", command],
            env=self.env, capture_output=True, text=True, timeout=20)

    def test_first_start_downloads_and_restart_uses_cache_offline(self):
        result = self.run_bootstrap()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue((self.root / "started").exists())
        (self.root / "bundle.tar.gz").unlink()
        result = self.run_bootstrap()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["download"], (self.root / "downloads").read_text().splitlines())

    def test_checksum_failure_never_starts_server_and_can_retry(self):
        archive = self.root / "bundle.tar.gz"
        original = archive.read_bytes()
        archive.write_bytes(b"corrupt download")
        result = self.run_bootstrap()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("checksum mismatch", result.stderr)
        self.assertFalse((self.root / "started").exists())
        archive.write_bytes(original)
        self.assertEqual(0, self.run_bootstrap().returncode)

    def test_incomplete_bundle_never_starts_server(self):
        self.create_bundle(missing="thymos-6d/tokenizer.json")
        result = self.run_bootstrap()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("missing thymos-6d/tokenizer.json", result.stderr)
        self.assertFalse((self.root / "started").exists())

    def test_model_change_downloads_new_version_and_preserves_old_cache(self):
        self.assertEqual(0, self.run_bootstrap().returncode)
        old_digest = self.digest
        self.create_bundle(content=b"updated fixture")
        result = self.run_bootstrap()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue((self.cache / "bundles" / old_digest / ".complete").exists())
        self.assertTrue((self.cache / "bundles" / self.digest / ".complete").exists())
        self.assertEqual(2, len((self.root / "downloads").read_text().splitlines()))

    @unittest.skipIf(os.name == "nt", "Git Bash lacks flock; Linux CI tests the real lock")
    def test_concurrent_starts_download_only_once(self):
        with ThreadPoolExecutor(max_workers=2) as executor:
            results = list(executor.map(lambda _: self.run_bootstrap(), range(2)))
        for result in results:
            self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["download"], (self.root / "downloads").read_text().splitlines())

    def test_packaging_is_stable_and_excludes_training_files(self):
        source = self.root / "data/models"
        for name in REQUIRED:
            path = source / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"model content")
        (source / "thymos-6d/private-checkpoint.pt").write_bytes(b"not a runtime model")
        script = ENTRYPOINT.with_name("package-models.sh")
        def package(tag):
            result = subprocess.run(
                [self.bash, self.shell_path(script), "example/project", tag],
                cwd=self.root, capture_output=True, text=True, timeout=20)
            self.assertEqual(0, result.returncode, result.stderr)
            return (self.root / "build/model-manifest/sha256").read_text().strip()
        original = package("v1")
        for name in REQUIRED:
            os.utime(source / name, (10000, 10000))
        self.assertEqual(original, package("v2"))
        archive = self.root / f"release-assets/openeden-models-{original}.tar.gz"
        with tarfile.open(archive) as tar:
            self.assertEqual(set(REQUIRED), set(tar.getnames()))
        self.assertIn("/v2/", (self.root / "build/model-manifest/url").read_text())


if __name__ == "__main__":
    unittest.main()
