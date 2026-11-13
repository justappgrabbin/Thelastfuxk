#!/usr/bin/env python3
"""Validate and reassemble The Last Fuxk's four ZIP uploads without deleting source."""
import argparse
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import tempfile
import zipfile

PATTERN = re.compile(r"The-Last-Fuck-Missing-10MB-Part-([1-4])-of-4\.zip$")
MARKERS = {"assets", "native", "res", "src", "tests"}
TOP_FILES = {
    "AndroidManifest.xml", "build.sh", "README.md", "YORK-Termux.sh",
    "BUILDER-STATUS.md", "WORKBENCH-STATUS.md", "REPAIR-VERIFICATION.txt",
    "import-lastfuck-to-github.sh",
}
REQUIRED = [
    "AndroidManifest.xml", "build.sh", "tests/run.sh",
    "assets/alpine-coder.tar.gz", "native/arm64-v8a/libllama_server.so",
    "src/app/lastfuck/AgentService.java", "assets/computer.html",
]
MAX_FILE_BYTES = 256 * 1024 * 1024
MAX_TOTAL_BYTES = 1024 * 1024 * 1024

def safe_name(info):
    name = info.filename
    if not name or name.startswith("/") or "\\" in name:
        raise ValueError(f"Unsafe ZIP path: {name!r}")
    path = PurePosixPath(name)
    if any(part in ("..", ".git", ".github") for part in path.parts):
        raise ValueError(f"Unsafe ZIP path: {name!r}")
    if stat.S_ISLNK(info.external_attr >> 16):
        raise ValueError(f"ZIP symlink rejected: {name!r}")
    if info.file_size > MAX_FILE_BYTES:
        raise ValueError(f"Archive file exceeds 256 MiB: {name!r}")
    return path

def normalize(path, root):
    parts = path.parts
    if root and parts[:len(root)] == root:
        rel = parts[len(root):]
    else:
        idx = next((i for i, name in enumerate(parts)
                    if name in MARKERS or (name in TOP_FILES and i == len(parts)-1)), None)
        if idx is not None:
            rel = parts[idx:]
        elif len(parts) == 1:
            rel = parts
        elif parts[0].lower().startswith("the-last-fuck"):
            rel = parts[1:]
        else:
            raise ValueError(f"Cannot determine source-relative path for {path}")
    if not rel or rel[0] in {".git", ".github"}:
        raise ValueError(f"Unsafe or empty normalized path: {path}")
    return Path(*rel)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    found = {}
    for p in args.input.glob("The-Last-Fuck-Missing-10MB-Part-*-of-4.zip"):
        m = PATTERN.fullmatch(p.name)
        if m:
            found[int(m.group(1))] = p
    if set(found) != {1, 2, 3, 4}:
        raise SystemExit(f"Need all 4 ZIP parts (1,2,3,4); found: {sorted(found)}")
    parts = [found[n] for n in range(1, 5)]
    print("Source parts:", *[f"{p.name} ({p.stat().st_size:,} bytes)" for p in parts], sep="\n  ")

    with tempfile.TemporaryDirectory() as scratch:
        if all(zipfile.is_zipfile(p) for p in parts):
            archives = parts
            print("Detected four independent ZIP archives")
        else:
            combined = Path(scratch) / "combined.zip"
            with combined.open("wb") as dst:
                for part in parts:
                    with part.open("rb") as src:
                        shutil.copyfileobj(src, dst)
            if not zipfile.is_zipfile(combined):
                raise SystemExit("Parts aren't independent ZIPs and their concatenation isn't a valid ZIP; check original split format")
            archives = [combined]
            print("Detected byte-split ZIP; concatenated in numerical order")

        infos = []
        manifest_roots = set()
        total_size = 0
        for archive in archives:
            with zipfile.ZipFile(archive) as z:
                bad = z.testzip()
                if bad:
                    raise SystemExit(f"Corrupt ZIP entry in {archive.name}: {bad}")
                for info in z.infolist():
                    name = safe_name(info)
                    if info.is_dir():
                        continue
                    total_size += info.file_size
                    if total_size > MAX_TOTAL_BYTES:
                        raise SystemExit("Archive unpacked size exceeds 1 GiB; inspect before importing")
                    infos.append((archive, info, name))
                    if name.name == "AndroidManifest.xml":
                        manifest_roots.add(name.parts[:-1])
        if len(manifest_roots) > 1:
            raise SystemExit(f"Ambiguous multiple Android project roots: {manifest_roots}")
        root = next(iter(manifest_roots), ())
        print("Project prefix:", "/".join(root) or "(archive root)")
        output = args.output
        output.mkdir(parents=True, exist_ok=True)
        seen = {}
        for archive, info, path in infos:
            rel = normalize(path, root)
            if rel.is_absolute() or ".." in rel.parts:
                raise SystemExit(f"Unsafe normalized target: {rel}")
            target = output / rel
            with zipfile.ZipFile(archive) as z, z.open(info) as src:
                data = src.read()
            if rel in seen:
                if seen[rel] != data:
                    raise SystemExit(f"Conflicting ZIP parts both contain {rel}; refusing to overwrite")
                continue
            seen[rel] = data
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        missing = [name for name in REQUIRED if not (output / name).is_file() or (output / name).stat().st_size == 0]
        if missing:
            print("Archive inventory:", len(seen), "files")
            print("Runtime and asset candidates:")
            candidates = [name for name in sorted(seen, key=str)
                          if "alpine" in str(name).lower() or "rootfs" in str(name).lower()
                          or str(name).startswith("assets/") or str(name).startswith("native/")]
            for name in candidates[:120]:
                print("  ", str(name), f"({len(seen[name]):,} bytes)")
            raise SystemExit(f"Import incomplete; missing required components: {', '.join(missing)}")
        print(f"Validated {len(seen)} files ({total_size:,} uncompressed bytes). Ready to import.")

if __name__ == "__main__":
    main()
