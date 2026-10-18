#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
"""Fetch the pinned, hash-checked research inputs without altering existing repos."""
import concurrent.futures
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import urllib.request


def main():
    args = argparse.ArgumentParser()
    mode = args.add_mutually_exclusive_group()
    mode.add_argument('--hotspot-patch-base', action='store_true',
                      help='Fetch the original OpenJDK archive for HotSpot patch export only')
    mode.add_argument('--graal', action='store_true', help='Fetch LabsJDK, Graal and mx for the GraalVM build')
    options = args.parse_args()
    root = Path(__file__).resolve().parents[1]
    pins = json.loads((root / "config/source-pins.json").read_text())
    upstream = root / "upstream"
    upstream.mkdir(exist_ok=True)
    if options.hotspot_patch_base or options.graal:
        label = 'labsjdk25' if options.graal else 'hotspot-patch-base'
        archive = upstream / (label + '.tar.gz')
        pin = pins[label]
        if not archive.exists():
            temporary = archive.with_suffix('.part')
            urllib.request.urlretrieve(f"https://github.com/{pin['repo']}/archive/{pin['commit']}.tar.gz", temporary)
            if hashlib.sha256(temporary.read_bytes()).hexdigest() != pin['archive_sha256']:
                raise SystemExit(f'{label} source archive hash mismatch.')
            temporary.replace(archive)
        if hashlib.sha256(archive.read_bytes()).hexdigest() != pin['archive_sha256']:
            raise SystemExit('JDK source archive hash mismatch.')

    if options.graal:
        for label, directory in [('graal', 'graal25'), ('mx', 'mx-graal25')]:
            destination = upstream / directory
            pin = pins[label]
            if not destination.exists():
                subprocess.run(['git', 'init', '--quiet', str(destination)], check=True)
                subprocess.run(['git', '-C', str(destination), 'remote', 'add', 'origin',
                                f"https://github.com/{pin['repo']}.git"], check=True)
                subprocess.run(['git', '-C', str(destination), 'fetch', '--depth=1', 'origin', pin['commit']], check=True)
                subprocess.run(['git', '-C', str(destination), 'checkout', '--quiet', '--detach', pin['commit']], check=True)
            revision = subprocess.check_output(['git', '-C', str(destination), 'rev-parse', 'HEAD'], text=True).strip()
            if revision != pin['commit']:
                raise SystemExit(f'Preserving changed upstream/{directory}; expected {pin["commit"]}.')
            if label == 'mx' and subprocess.check_output(
                    ['git', '-C', str(destination), 'status', '--porcelain']):
                raise SystemExit('Preserving changed mx checkout; it must match the clean pin.')
            print(f'Verified {label} revision {revision}; existing working files preserved.')
        return

    def fetch(job):
        label, pin, path = job
        dest = upstream / "sources" / label / pin["commit"] / path
        url = f"https://raw.githubusercontent.com/{pin['repo']}/{pin['commit']}/{path}"
        if dest.exists():
            data = dest.read_bytes()
        else:
            with urllib.request.urlopen(url, timeout=30) as response:
                data = response.read()
        if hashlib.sha256(data).hexdigest() != pin["sha256"][path]:
            raise RuntimeError(f"Source hash mismatch: {label}/{path}")
        if not dest.exists():
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(data)
        return f"verified {label}/{path}"

    jobs = [(label, pin, path) for label, pin in pins.items() for path in pin.get("files", [])]
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        for result in pool.map(fetch, jobs):
            print(result)
    print(f"Verified {len(jobs)} source files.")


if __name__ == "__main__":
    main()
