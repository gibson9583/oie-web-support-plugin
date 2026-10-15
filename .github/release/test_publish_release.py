"""Offline publication contracts; no GitHub writes or paid signatures."""

import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import publish_release as publisher


class RemoteRelease:
    """Model gh's asset operations; an upload never overwrites an existing name."""

    def __init__(self):
        self.exists = False
        self.draft = True
        self.assets = {}
        self.calls = []
        self.fail_upload = None
        self.fail_finalize = False
        self.lookup_error = None
        self.asset_states = {}

    def gh(self, *args):
        self.calls.append(args)
        if args[0] == "api":
            assert args[1:3] == ("--paginate", "--slurp")
            assert args[3].endswith("/releases?per_page=100")
            status = self.lookup_error
            if status:
                raise subprocess.CalledProcessError(1, ["gh", *args], stderr=f"gh: failed (HTTP {status})")
            return json.dumps([[{"tag_name": "v1.1.0", "draft": self.draft,
                                 "assets": [{"name": name, "state": self.asset_states.get(name, "uploaded"),
                                             "size": len(data)} for name, data in self.assets.items()]}]
                               if self.exists else []])
        action = args[1]
        if action == "create":
            assert "--draft" in args and "--verify-tag" in args
            self.exists = True
        elif action == "download":
            name = args[args.index("--pattern") + 1]
            (Path(args[args.index("--dir") + 1]) / name).write_bytes(self.assets[name])
        elif action == "upload":
            assert "--clobber" not in args
            path = Path(args[3])
            if path.name == self.fail_upload:
                raise subprocess.CalledProcessError(1, ["gh", *args], stderr="upload interrupted")
            if path.name in self.assets:
                raise subprocess.CalledProcessError(1, ["gh", *args], stderr="asset already exists")
            self.assets[path.name] = path.read_bytes()
        elif action == "edit":
            if self.fail_finalize:
                raise subprocess.CalledProcessError(1, ["gh", *args], stderr="finalization interrupted")
            assert "--draft=false" in args
            self.draft = False
        else:
            raise AssertionError(args)
        return ""


class PublicationTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="websupport-publication-test-")
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.tag = "v1.1.0"
        self.source = "a" * 40
        self.package = "websupport-1.1.0.zip"
        self.war = b"verified upstream WAR"
        with zipfile.ZipFile(self.directory / self.package, "w") as archive:
            archive.writestr("websupport/oie-webadmin.war", self.war)
            archive.writestr("websupport/websupport-shared.jar", b"signed and timestamped JAR")
        publisher.stage(self.directory, self.tag, self.source, self.tag, hashlib.sha256(self.war).hexdigest())
        self.remote = RemoteRelease()
        self.addCleanup(patch.stopall)
        patch.object(publisher, "gh", self.remote.gh).start()

    def publish(self, **kwargs):
        publisher.publish(self.directory, kwargs.get("tag", self.tag), kwargs.get("source", self.source), "owner/repo")

    def test_new_release_uploads_all_assets_before_publishing_and_retry_is_noop(self):
        self.publish()
        self.assertFalse(self.remote.draft)
        self.assertEqual(set(self.remote.assets), {self.package, "SHA256SUMS", "release-receipt.json"})
        self.assertEqual(self.remote.calls[-1][1], "edit")
        self.remote.calls.clear()
        self.publish()
        self.assertFalse(any(call[1] in ("upload", "edit", "create") for call in self.remote.calls))

    def test_partial_upload_retains_draft_and_retry_uses_identical_signed_bytes(self):
        self.remote.fail_upload = "SHA256SUMS"
        with self.assertRaises(subprocess.CalledProcessError):
            self.publish()
        self.assertTrue(self.remote.draft)
        self.assertEqual(list(self.remote.assets), [self.package])
        original = self.remote.assets[self.package]
        self.remote.fail_upload = None
        self.publish()
        self.assertFalse(self.remote.draft)
        self.assertEqual(self.remote.assets[self.package], original)
        uploads = [call for call in self.remote.calls if call[1] == "upload" and Path(call[3]).name == self.package]
        self.assertEqual(len(uploads), 1)

    def test_finalization_failure_retries_without_reupload(self):
        self.remote.fail_finalize = True
        with self.assertRaises(subprocess.CalledProcessError):
            self.publish()
        self.assertTrue(self.remote.draft)
        self.remote.fail_finalize = False
        self.remote.calls.clear()
        self.publish()
        self.assertFalse(self.remote.draft)
        self.assertFalse(any(call[1] == "upload" for call in self.remote.calls))

    def test_rebuilt_or_resigned_package_is_rejected_without_changing_release(self):
        self.publish()
        existing = dict(self.remote.assets)
        with zipfile.ZipFile(self.directory / self.package, "a") as archive:
            archive.writestr("signature-timestamp", b"a later signing timestamp")
        publisher.stage(self.directory, self.tag, self.source, self.tag, hashlib.sha256(self.war).hexdigest())
        self.remote.calls.clear()
        with self.assertRaisesRegex(ValueError, "Existing release asset differs"):
            self.publish()
        self.assertEqual(self.remote.assets, existing)
        self.assertFalse(any(call[1] in ("upload", "edit", "create") for call in self.remote.calls))

    def test_mismatch_in_later_asset_prevents_any_partial_upload(self):
        self.remote.exists = True
        self.remote.assets["release-receipt.json"] = b"different source receipt"
        with self.assertRaisesRegex(ValueError, "Existing release asset differs"):
            self.publish()
        self.assertEqual(list(self.remote.assets), ["release-receipt.json"])

    def test_any_lookup_failure_never_creates_a_release(self):
        for status in [401, 403, 404, 429, 500]:
            with self.subTest(status=status):
                self.remote.lookup_error = status
                with self.assertRaises(subprocess.CalledProcessError):
                    self.publish()
                self.assertFalse(self.remote.exists)

    def test_wrong_tag_source_or_tampered_assets_fail_before_network(self):
        for kwargs in [{"tag": "v1.0.5"}, {"source": "b" * 40}]:
            with self.assertRaises(ValueError):
                self.publish(**kwargs)
        with (self.directory / self.package).open("ab") as stream:
            stream.write(b"modified after signing")
        with self.assertRaisesRegex(ValueError, "Checksum mismatch"):
            self.publish()
        self.assertEqual(self.remote.calls, [])

    def test_stage_rejects_a_different_embedded_war(self):
        with self.assertRaisesRegex(ValueError, "Bundled WAR differs"):
            publisher.stage(self.directory, self.tag, self.source, self.tag, "b" * 64)

    def test_draft_empty_starter_requires_explicit_cleanup_then_retry_succeeds(self):
        self.remote.exists = True
        self.remote.assets[self.package] = b""
        self.remote.asset_states[self.package] = "starter"
        with self.assertRaisesRegex(ValueError, "Remove only this empty starter asset from the draft"):
            self.publish()
        self.assertEqual(self.remote.assets[self.package], b"")
        self.assertTrue(self.remote.draft)
        del self.remote.assets[self.package]
        del self.remote.asset_states[self.package]
        self.publish()
        self.assertFalse(self.remote.draft)

    def test_published_or_nonempty_starter_is_not_suggested_for_automatic_cleanup(self):
        self.remote.exists = True
        self.remote.asset_states[self.package] = "starter"
        for draft, content in [(False, b""), (True, b"incomplete bytes")]:
            with self.subTest(draft=draft):
                self.remote.draft = draft
                self.remote.assets[self.package] = content
                with self.assertRaisesRegex(ValueError, "Inspect this release before retrying"):
                    self.publish()
                self.assertEqual(self.remote.assets[self.package], content)

    def test_prerelease_stays_marked_prerelease(self):
        self.tag = "v1.1.0-rc.1"
        (self.directory / self.package).rename(self.directory / "websupport-1.1.0-rc.1.zip")
        publisher.stage(self.directory, self.tag, self.source, self.tag, hashlib.sha256(self.war).hexdigest())
        self.publish()
        create = next(call for call in self.remote.calls if call[1] == "create")
        self.assertIn("--prerelease", create)


if __name__ == "__main__":
    unittest.main()
