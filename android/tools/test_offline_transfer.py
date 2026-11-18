import io
import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch

import offline_transfer as transfer


class OfflineTransferTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.directory = Path(self.tmp.name)
        self.path = self.directory / "video2-offline.tar"

    @staticmethod
    def member(archive, name, body=b"", mtime=1_700_000_000):
        entry = tarfile.TarInfo(name)
        entry.mtime = mtime
        entry.size = len(body)
        archive.addfile(entry, io.BytesIO(body))

    def test_transfer_keeps_weights_jobs_and_exact_video_time_and_never_overwrites(self):
        video = "files/Videos/مثال filmed.mp4"
        model = "whisper-medium-fixture.bin"
        mos = "no_backup/offline-models/" + model
        mtime = 1_700_000_000_265
        with tarfile.open(self.path, "w:") as archive:
            self.member(archive, mos, b"verified fixture")
            self.member(archive, video, b"video")
            self.member(archive, "files/library.json", b"{}")
            self.member(archive, "files/Translations/jobs.json", b"[]")
            self.member(archive, "files/Translations/test/offline-source.json", json.dumps({
                "path": f"/data/user/0/{transfer.PACKAGE}/{video}", "size": 5, "mtime": mtime,
            }).encode())
        with patch.object(transfer, "WEIGHTS", {model: (16, transfer.digest(io.BytesIO(b"verified fixture")))}), \
                patch.object(transfer, "phone", return_value=type("Response", (), {
                    "stdout": "2023-11-15 01:13:20.265524811 +0300\n",
                })()):
            with tarfile.open(self.path, "r:") as archive:
                entries = transfer.checked_entries(archive)
                self.assertEqual([model], transfer.installed_weights(archive, entries))
                self.assertEqual({video: mtime}, transfer.capture_resume_times(archive, entries))
            record = {**transfer.size_and_hash(self.path), "format": 1, "package": transfer.PACKAGE,
                      "models": [model], "media_mtime_ms": {video: mtime}}
            transfer.sidecar(self.path).write_text(json.dumps(record), encoding="utf-8")
            self.assertEqual(record, transfer.verify(self.path))
        destination = self.directory / "debug-app"
        destination.mkdir()
        (destination / "files/Videos").mkdir(parents=True)  # empty after first launch is okay
        script = transfer.restore_script({video: mtime})
        with self.path.open("rb") as stream:
            subprocess.run(["sh", "-c", script], stdin=stream, cwd=destination, check=True)
        self.assertEqual(b"video", (destination / video).read_bytes())
        self.assertEqual(mtime, (destination / video).stat().st_mtime_ns // 1_000_000)
        self.assertEqual(b"verified fixture", (destination / mos).read_bytes())
        stopped = subprocess.run(["sh", "-c", script], input=b"", cwd=destination, capture_output=True)
        self.assertNotEqual(0, stopped.returncode)
        self.assertIn(b"Refusing to overwrite", stopped.stderr)
        self.assertEqual(b"verified fixture", (destination / mos).read_bytes())

    def test_rejects_unrelated_directories_path_traversal_links_and_truncated_tar(self):
        for name in ("files/Videos/../../shared_prefs/private.xml", "shared_prefs/config.xml", "/files/Videos/a.mp4"):
            with self.subTest(name=name):
                with tarfile.open(self.path, "w:") as archive:
                    self.member(archive, name, b"data")
                with tarfile.open(self.path, "r:") as archive, self.assertRaises(ValueError):
                    transfer.checked_entries(archive)
        with tarfile.open(self.path, "w:") as archive:
            link = tarfile.TarInfo("files/Videos/a.mp4")
            link.type = tarfile.SYMTYPE
            link.linkname = "../../shared_prefs/private.xml"
            archive.addfile(link)
        with tarfile.open(self.path, "r:") as archive, self.assertRaises(ValueError):
            transfer.checked_entries(archive)
        with tarfile.open(self.path, "w:") as archive:
            self.member(archive, "files/Videos/good.mp4", b"file contents")
        os.truncate(self.path, 1024 + 512)
        with tarfile.open(self.path, "r:") as archive, self.assertRaises(ValueError):
            transfer.checked_entries(archive)

    def test_invalid_seal_leaves_backup_unmodified(self):
        with tarfile.open(self.path, "w:") as archive:
            self.member(archive, "files/library.json", b"{}")
        transfer.sidecar(self.path).write_text(json.dumps({"format": 1, "package": transfer.PACKAGE,
            "size": self.path.stat().st_size, "sha256": "not-the-real-digest"}), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "checksum failed"):
            transfer.verify(self.path)
        self.assertTrue(self.path.is_file())


if __name__ == "__main__":
    unittest.main()
