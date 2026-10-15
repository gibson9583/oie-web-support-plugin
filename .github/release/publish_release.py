#!/usr/bin/env python3
"""Retain signed release bytes and publish them without replacing existing assets."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def package_name(tag):
    if not re.fullmatch(r"v\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?", tag):
        raise ValueError("Expected a semantic-version release tag")
    return f"websupport-{tag[1:]}.zip"


def stage(directory, tag, source_sha, client_tag, war_sha):
    package = package_name(tag)
    package_name(client_tag)
    if not re.fullmatch(r"[0-9a-f]{40}", source_sha) or not re.fullmatch(r"[0-9a-f]{64}", war_sha):
        raise ValueError("Invalid source or WAR hash")
    with zipfile.ZipFile(directory / package) as archive:
        if hashlib.sha256(archive.read("websupport/oie-webadmin.war")).hexdigest() != war_sha:
            raise ValueError("Bundled WAR differs from the verified client release")
    (directory / "SHA256SUMS").write_text(f"{digest(directory / package)}  {package}\n")
    receipt = {"schema": 1, "tag": tag, "sourceSha": source_sha,
               "webClientTag": client_tag, "warSha256": war_sha,
               "artifacts": {name: digest(directory / name) for name in [package, "SHA256SUMS"]}}
    (directory / "release-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    (directory / "release-notes.md").write_text(
        f"`{package}` is the complete Web Support extension for OIE 4.6.0:\n\n"
        "- engine-side APIs for message trees, JavaScript validation, and plugin UIs\n"
        "- extension datatype vocabulary descriptions and custom response variables\n"
        "- the embedded OIE web administrator, served at `/oie-webadmin/`\n\n"
        f"Bundled web client: `gibson9583/oie-web-client@{client_tag}`\n\n"
        f"WAR SHA-256: `{war_sha}`\n\n"
        "Install the extension and restart OIE. The plugin JAR is signed and timestamped; "
        "the bundled WAR is byte-identical to the verified client release. "
        "`release-receipt.json` records the plugin source commit and package hashes.\n")
    return verify(directory, tag, source_sha)


def verify(directory, tag, source_sha):
    package = package_name(tag)
    receipt = json.loads((directory / "release-receipt.json").read_text())
    if (receipt.get("schema") != 1 or receipt.get("tag") != tag
            or not re.fullmatch(r"[0-9a-f]{40}", source_sha) or receipt.get("sourceSha") != source_sha):
        raise ValueError("Release identity does not match the tagged source")
    expected = [package, "SHA256SUMS"]
    if set(receipt.get("artifacts", {})) != set(expected):
        raise ValueError("Unexpected release artifact set")
    for name in expected:
        if digest(directory / name) != receipt["artifacts"][name]:
            raise ValueError(f"Checksum mismatch: {name}")
    if (directory / "SHA256SUMS").read_text() != f"{digest(directory / package)}  {package}\n":
        raise ValueError("Package checksum file does not match")
    return [*expected, "release-receipt.json"]


def gh(*args):
    return subprocess.run(["gh", *args], check=True, capture_output=True, text=True).stdout


def publish(directory, tag, source_sha, repository):
    names = verify(directory, tag, source_sha)
    # The tag REST endpoint can miss drafts (including pending tags). Listing
    # releases includes drafts with contents:write and keeps every API/auth/
    # network error fatal; creation requires a successful, complete lookup.
    pages = json.loads(gh("api", "--paginate", "--slurp", f"repos/{repository}/releases?per_page=100"))
    matches = [release for page in pages for release in page if release["tag_name"] == tag]
    if len(matches) > 1:
        raise ValueError("Multiple releases match the tag; resolve duplicates before publishing")
    release = matches[0] if matches else None
    if release is None:
        extra = ["--prerelease"] if "-" in tag else []
        gh("release", "create", tag, "--repo", repository, "--verify-tag", "--draft",
           "--title", tag, "--notes-file", str(directory / "release-notes.md"), *extra)
        release = {"draft": True, "assets": []}
    existing = {asset["name"]: asset for asset in release["assets"]}
    # Compare every existing asset before uploading anything. A rebuild/re-sign
    # cannot replace a published ZIP or invalidate a catalog's recorded digest.
    with tempfile.TemporaryDirectory(prefix="websupport-release-compare-") as temporary:
        for name in names:
            if name in existing:
                asset = existing[name]
                if asset.get("state") != "uploaded":
                    detail = f"Incomplete release asset {name}: state={asset.get('state')}, size={asset.get('size')}. "
                    if release["draft"] and asset.get("state") == "starter" and asset.get("size") == 0:
                        detail += "Remove only this empty starter asset from the draft, then rerun the failed publish job using retained signed assets."
                    else:
                        detail += "Inspect this release before retrying; no assets were changed."
                    raise ValueError(detail)
                gh("release", "download", tag, "--repo", repository,
                   "--pattern", name, "--dir", temporary)
                if (directory / name).read_bytes() != (Path(temporary) / name).read_bytes():
                    raise ValueError(f"Existing release asset differs: {name}; reuse the original signed-release-assets artifact")
        for name in names:
            if name not in existing:
                gh("release", "upload", tag, str(directory / name), "--repo", repository)
    if release["draft"]:
        gh("release", "edit", tag, "--repo", repository, "--draft=false")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["stage", "publish"])
    parser.add_argument("--directory", type=Path, default=Path("out"))
    parser.add_argument("--tag", required=True)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--client-tag")
    parser.add_argument("--war-sha256")
    parser.add_argument("--repository")
    args = parser.parse_args()
    if args.command == "stage":
        if not args.client_tag or not args.war_sha256:
            parser.error("stage requires --client-tag and --war-sha256")
        stage(args.directory, args.tag, args.source_sha, args.client_tag, args.war_sha256)
    else:
        if not args.repository:
            parser.error("publish requires --repository")
        publish(args.directory, args.tag, args.source_sha, args.repository)


if __name__ == "__main__":
    main()
