#!/usr/bin/env python3
"""Local, explicitly requested transfer of Video2 debug data via Android platform-tools.

No private signing keys, provider settings, browser data, or network transfer.
"""

import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tarfile
from tempfile import NamedTemporaryFile

PACKAGE = "com.ahcrazy.video2"
# Last entry is an index: publish it only after its files have been restored.
ROOTS = ("no_backup/offline-models", "files/Videos", "files/Thumbs", "files/Subtitles",
         "files/Dubbing", "files/Translations", "files/library.json")
WEIGHTS = {
    "whisper-medium-6c14d5adee5f.bin": (1_533_763_059, "6c14d5adee5f86394037b4e4e8b59f1673b6cee10e3cf0b11bbdbee79c156208"),
    "whisper-medium-q5-19fea4b380c3.bin": (539_212_467, "19fea4b380c3a618ec4723c3eef2eb785ffba0d0538cf43f8f235e7b3b34220f"),
    "hymt2-1p8b-q4-dc5f44fcf1fa.gguf": (1_133_080_448, "dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699"),
    "whisper-large-v3-64d182b440b9.bin": (3_095_033_483, "64d182b440b98d5203c4f9bd541544d84c605196c4f7b845dfa11fb23594d1e2"),
    "hymt2-7b-q4-9f96256500f3.gguf": (4_624_648_896, "9f96256500f3fc1ab4d64336b58f52a949a95ad7516b0c229476eef782f9f77b"),
}
EPOCH = datetime(1970, 1, 1, tzinfo=timezone.utc)


def phone(script, **kwargs):
    # -T uses the ADB shell protocol without a TTY, preserving binary data and remote exit status.
    return subprocess.run(["adb", "shell", "-T", f"run-as {PACKAGE} sh -c {shlex.quote(script)}"],
                          check=True, **kwargs)


def stop_debug_app():
    phone("true", stdin=subprocess.DEVNULL)  # refuses non-debug apps or an unauthorized connection
    subprocess.run(["adb", "shell", "-T", "am", "force-stop", "--user", "0", PACKAGE], check=True)


def digest(stream):
    sha = hashlib.sha256()
    while data := stream.read(4 * 1024 * 1024):
        sha.update(data)
    return sha.hexdigest()


def size_and_hash(path):
    with path.open("rb") as stream:
        return {"size": path.stat().st_size, "sha256": digest(stream)}


def checked_entries(archive):
    entries = {}
    for entry in archive:
        name = entry.name
        label = name.split('/')
        if (any(part in ("", ".", "..") for part in label) or "\\" in name or
                any(ord(c) < 32 for c in name) or name in entries or
                not (entry.isdir() or entry.type in (tarfile.REGTYPE, tarfile.AREGTYPE))):
            raise ValueError(f"Unsafe archive member: {name!r}")
        # The library index is a file, not a directory or a prefix.
        if name == ROOTS[-1]:
            allowed = entry.isfile()
        else:
            allowed = any((entry.isdir() and name == root) or name.startswith(root + '/') for root in ROOTS[:-1])
        if not allowed:
            raise ValueError(f"Archive member outside Video2 media/model directories: {name!r}")
        entries[name] = entry
    if not entries:
        raise ValueError("Snapshot is empty")
    for name in entries:
        parent = name.rpartition('/')[0]
        while parent:
            if parent in entries and not entries[parent].isdir():
                raise ValueError(f"Archive member has a non-directory parent: {name!r}")
            parent = parent.rpartition('/')[0]
    # tarfile can stop after one zero block. Require both blocks of a completed tar stream.
    end = max(entry.offset_data + (entry.size + 511) // 512 * 512 for entry in entries.values())
    archive.fileobj.seek(end)
    if archive.fileobj.read(1024) != bytes(1024):
        raise ValueError("Snapshot stream ended incompletely")
    return entries


def installed_weights(archive, entries):
    verified = []
    for name, (size, sha) in WEIGHTS.items():
        entry = entries.get(f"no_backup/offline-models/{name}")
        if entry is None:
            continue
        if not entry.isfile() or entry.size != size:
            raise ValueError(f"Installed weight file has an unexpected size: {name}")
        with archive.extractfile(entry) as stream:
            if digest(stream) != sha:
                raise ValueError(f"Installed weight file failed its publisher SHA-256: {name}")
        verified.append(name)
    return verified


def parse_stat_time(text):
    # Android toybox stat %y emits e.g. 2026-11-18 16:20:55.265792082 +0300.
    match = re.fullmatch(r"(\d{4}-\d\d-\d\d) (\d\d:\d\d:\d\d)(?:\.(\d{1,9}))? ([+-]\d{4})", text.strip())
    if not match:
        raise ValueError(f"Could not read the video's original modification time: {text.strip()!r}")
    day, clock, fraction, zone = match.groups()
    parsed = datetime.fromisoformat(f"{day}T{clock}{zone}").astimezone(timezone.utc)
    return (parsed - EPOCH) // timedelta(milliseconds=1) + int((fraction or "").ljust(3, '0')[:3])


def media_path(fingerprint, entries):
    for prefix in (f"/data/user/0/{PACKAGE}/", f"/data/data/{PACKAGE}/"):
        full_path = fingerprint["path"]
        if full_path.startswith(prefix):
            name = full_path[len(prefix):]
            entry = entries.get(name)
            if name.startswith("files/Videos/") and entry and entry.isfile() and entry.size == fingerprint["size"]:
                return name
    return None


def capture_resume_times(archive, entries):
    result = {}
    for name, entry in entries.items():
        if (name.startswith("files/Translations/") and name.endswith("/offline-source.json") and
                entry.isfile() and entry.size <= 16_384):
            with archive.extractfile(entry) as stream:
                fingerprint = json.load(stream)
            video = media_path(fingerprint, entries)
            if video and video not in result:
                stat = phone(f"stat -c %y {shlex.quote(video)}", capture_output=True, text=True).stdout
                millis = parse_stat_time(stat)
                if millis // 1000 != int(entries[video].mtime):
                    raise ValueError("Video changed during backup; try again with Video2 closed")
                result[video] = millis
            if video and result[video] != fingerprint["mtime"]:
                print(f"Note: {name} was already pointing at a changed input video.", file=sys.stderr)
    return result


def sidecar(path):
    return path.with_name(path.name + ".json")


def backup(path):
    meta = sidecar(path)
    if path.exists() or meta.exists():
        raise ValueError("Choose a new snapshot file; nothing will be overwritten")
    stop_debug_app()
    paths = ' '.join(shlex.quote(root) for root in ROOTS)
    # Explicit directory list: do not transfer passwords, keys, cookies, WebView state, or downloads in flight.
    script = f'''set -eu
set --
for path in {paths}; do
    if [ -e "$path" ]; then set -- "$@" "$path"; fi
done
[ "$#" -gt 0 ]
tar -cf - "$@"
'''
    with NamedTemporaryFile(dir=path.parent, prefix=".video2-snapshot-", delete=False) as stream:
        temporary = Path(stream.name)
    try:
        with temporary.open("wb") as stream:
            phone(script, stdin=subprocess.DEVNULL, stdout=stream)
        with tarfile.open(temporary, "r:") as archive:
            entries = checked_entries(archive)
            models = installed_weights(archive, entries)
            times = capture_resume_times(archive, entries)
        record = {**size_and_hash(temporary), "format": 1, "package": PACKAGE,
                  "models": models, "media_mtime_ms": times}
        # Keep the standard tar and the small manifest together. No deletion on the phone.
        temporary.rename(path)
        with meta.open("x", encoding="utf-8") as output:
            json.dump(record, output, ensure_ascii=False, indent=2)
    finally:
        temporary.unlink(missing_ok=True)
    show_record(path, record)


def verify(path):
    record = json.loads(sidecar(path).read_text(encoding="utf-8"))
    if record.get("format") != 1 or record.get("package") != PACKAGE:
        raise ValueError("This is not a Video2 offline snapshot")
    if size_and_hash(path) != {key: record[key] for key in ("size", "sha256")}:
        raise ValueError("Snapshot checksum failed; do NOT remove the original app")
    with tarfile.open(path, "r:") as archive:
        entries = checked_entries(archive)
        if installed_weights(archive, entries) != record["models"]:
            raise ValueError("Model list does not match the snapshot")
        for name, millis in record["media_mtime_ms"].items():
            entry = entries.get(name)
            if (not name.startswith("files/Videos/") or not entry or not entry.isfile() or
                    type(millis) is not int or millis // 1000 != int(entry.mtime)):
                raise ValueError("Invalid video time in the snapshot manifest")
    return record


def show_record(path, record):
    models = record["models"]
    print(f"Verified snapshot: {path} ({record['size']:,} bytes); keep {sidecar(path).name} with it.")
    print("Verified model files: " + (", ".join(models) if models else "none"))
    if not any(n.startswith("whisper-") for n in models) or not any(n.startswith(("hymt2-7b-q4-", "hymt2-1p8b-q4-")) for n in models):
        print("This is not a complete offline model pack. Do not uninstall expecting every model to be restored.", file=sys.stderr)


def restore_script(times):
    paths = ' '.join(shlex.quote(root) for root in ROOTS)
    # USTAR doesn't carry millisecond mtime. Restore exact verified times for files with saved jobs.
    touches = '\n'.join(
        f'touch -m -d {shlex.quote((EPOCH + timedelta(milliseconds=ms)).isoformat(timespec="milliseconds").replace("+00:00", "Z"))}'
        f' "$stage"/{shlex.quote(name)}' for name, ms in times.items())
    return f'''set -eu
for path in {paths}; do
    if [ -e "$path" ] || [ -L "$path" ]; then
        if [ -L "$path" ] || ! rmdir "$path" 2>/dev/null; then
            echo "Refusing to overwrite existing Video2 data: $path" >&2
            exit 1
        fi
    fi
done
stage=$(mktemp -d .video2-restore.XXXXXX)
trap 'rm -rf "$stage"' EXIT
# Stage the stream. A truncated transfer must never become visible in Video2.
tar -xf - -C "$stage"
{touches}
mkdir -p no_backup files
for path in {paths}; do
    if [ -e "$stage/$path" ]; then mv "$stage/$path" "$path"; fi
done
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=["backup", "verify", "restore"])
    parser.add_argument("snapshot", type=Path, help="Private, local video2-offline.tar (not a cloud folder)")
    args = parser.parse_args()
    try:
        if args.operation == "backup":
            backup(args.snapshot)
        else:
            record = verify(args.snapshot)
            show_record(args.snapshot, record)
            if args.operation == "restore":
                stop_debug_app()
                with args.snapshot.open("rb") as stream:
                    phone(restore_script(record["media_mtime_ms"]), stdin=stream)
                print("Restore complete. Open Video2 and resume the existing job.")
    except (OSError, ValueError, KeyError, TypeError, tarfile.TarError, subprocess.CalledProcessError) as error:
        print(f"Transfer stopped: {error}. Keep the existing app or original verified backup.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
