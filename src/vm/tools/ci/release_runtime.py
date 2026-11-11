#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
"""Prepare release assets from a successful Managed runtimes run whose source is merged into main.

Requires gh authentication with Actions read access. Never rebuilds or executes
artifact code. Publication is separate, so all assets can be reviewed first.
"""

import argparse
import base64
import hashlib
import json
from pathlib import Path, PurePosixPath
import posixpath
import re
import shutil
import struct
import subprocess
import tarfile
import zipfile

REPO = 'ekmett/thc'
FLAVORS = ('release', 'fastdebug')
PLATFORMS = {'linux': ('Linux', 'x86_64', 'Ubuntu 24.04'),
             'macos': ('Darwin', 'arm64', 'macOS 26'),
             'windows': ('Windows', 'x86_64', 'Windows Server 2022')}


def api(path):
    return json.loads(subprocess.check_output(['gh', 'api', f'repos/{REPO}/{path}']))


def sha256(path):
    with path.open('rb') as stream:
        digest = hashlib.sha256()
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
        return digest.hexdigest()


def require_run(run, jobs, comparison):
    if (run['status'] != 'completed' or run['conclusion'] != 'success'
            or run['event'] not in ('push', 'workflow_dispatch', 'pull_request', 'schedule')
            or run['head_repository']['full_name'] != REPO
            or comparison['status'] not in ('ahead', 'identical')
            or run['path'] != '.github/workflows/vm.yml'):
        raise ValueError('require a completed successful Managed runtimes run whose source is merged into main')
    passed = {job['name'] for job in jobs if job['conclusion'] == 'success'}
    expected = {f'{suite} ({platform})' for platform in PLATFORMS for suite in
                ('Build GraalVM', 'Native and guest bridge',
                 'GraalVM runtime', 'SubstrateVM')}
    if not expected <= passed:
        raise ValueError(f'missing qualification: {sorted(expected - passed)}')


def qualified_artifacts(artifacts, flavor):
    selected = [a for a in artifacts if re.fullmatch(r'thc-graal-(release|fastdebug)-(linux|macos|windows)', a['name'])]
    expected_artifacts = {f'thc-graal-{flavor}-{p}' for p in PLATFORMS}
    if {a['name'] for a in selected if not a['expired']} != expected_artifacts:
        raise ValueError('require all three qualified Graal artifacts of the requested build flavor')
    if len({a['name'] for a in selected}) != len(selected):
        raise ValueError('ambiguous duplicate artifact names')
    return selected


def inventory(path, root):
    """Hash the installation exactly as THC's sha256-path-manifest-v1 verifier."""
    records, jars, release = [], [], {}
    glibc, macos = set(), set()
    with tarfile.open(path, 'r:gz') as archive:
        seen = set()
        for item in archive:
            name = item.name
            if (name in seen or name.startswith('/') or '\\' in name
                    or '..' in PurePosixPath(name).parts):
                raise ValueError(f'unsafe or duplicate archive path: {name}')
            seen.add(name)
            if item.isdir():
                continue
            if not item.isfile() and not item.issym():
                raise ValueError(f'unsupported archive entry: {name}')
            if item.issym():
                target = posixpath.normpath(posixpath.join(posixpath.dirname(name), item.linkname))
                if item.linkname.startswith('/') or '\\' in item.linkname or not target.startswith(root + '/'):
                    raise ValueError(f'external archive symlink: {name}')
                if not name.startswith(root + '/'):
                    raise ValueError(f'symlink outside installation: {name}')
                records.append({'path': name[len(root) + 1:], 'link': item.linkname})
                continue
            data = archive.extractfile(item).read()
            digest = hashlib.sha256(data).hexdigest()
            if not name.startswith(root + '/'):
                if not name.startswith('upstream/graal25/') or not name.endswith('.jar'):
                    raise ValueError(f'unexpected file outside installation: {name}')
                jars.append({'path': name, 'sha256': digest, 'bytes': item.size})
                continue
            relative = name[len(root) + 1:]
            records.append({'path': relative, 'sha256': digest, 'bytes': item.size})
            if relative == 'release':
                release = dict(re.findall(r'^([A-Z_]+)="(.*)"$', data.decode().replace('\r\n', '\n'), re.M))
            if data.startswith(b'\x7fELF'):
                glibc.update(tuple(map(int, v.split(b'.'))) for v in re.findall(rb'GLIBC_(\d+\.\d+(?:\.\d+)?)\x00', data))
            if data.startswith(b'\xcf\xfa\xed\xfe'):
                offset = 32
                for _ in range(struct.unpack_from('<I', data, 16)[0]):
                    command, size = struct.unpack_from('<II', data, offset)
                    if size < 8 or offset + size > len(data):
                        raise ValueError(f'invalid Mach-O load command: {name}')
                    if command in (0x24, 0x32):
                        version = struct.unpack_from('<I', data, offset + (8 if command == 0x24 else 12))[0]
                        macos.add((version >> 16, (version >> 8) & 255, version & 255))
                    offset += size
    names = {r['path'] for r in records}
    if not {'release', 'lib/thc/thc-vm.jar', 'lib/thc/runtime-libraries.txt', 'legal/thc-vm/NOTICE.md'} <= names:
        raise ValueError('incomplete packaged installation')
    tree = hashlib.sha256()
    for record in sorted(records, key=lambda r: r['path']):
        prefix = 'link  ' + record['link'] if 'link' in record else record['sha256']
        tree.update(f"{prefix}  {record['path']}\n".encode())
    return {'algorithm': 'sha256-path-manifest-v1', 'sha256': tree.hexdigest(),
            'files': sum('sha256' in r for r in records), 'symlinks': sum('link' in r for r in records)}, {
            'files': sorted(records, key=lambda r: r['path']), 'sdk_jars': sorted(jars, key=lambda r: r['path'])}, release, {
            'glibc': '.'.join(map(str, max(glibc))) if glibc else None,
            'macos': '.'.join(map(str, max(macos))) if macos else None}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', required=True, type=int)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--build-flavor', choices=FLAVORS, required=True)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--cache', required=True, type=Path)
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]*', args.tag):
        parser.error('tag must contain only letters, digits, dots, underscores and hyphens')
    run = api(f'actions/runs/{args.run}')
    jobs = api(f'actions/runs/{args.run}/jobs?per_page=100')['jobs']
    require_run(run, jobs, api(f"compare/{run['head_sha']}...main"))
    artifacts = api(f'actions/runs/{args.run}/artifacts?per_page=100')['artifacts']
    source = run['head_sha']
    pins = json.loads(base64.b64decode(api(f'contents/src/vm/config/source-pins.json?ref={source}')['content']))
    selected = qualified_artifacts(artifacts, args.build_flavor)
    args.output.mkdir(parents=True, exist_ok=False)
    args.cache.mkdir(parents=True, exist_ok=True)
    manifest = {'schema': 1, 'tag': args.tag, 'repository': REPO, 'source_commit': source,
                'source_pins': pins, 'qualification': {'run_id': args.run, 'url': run['html_url'],
                'scope': 'THC VM CI runtime regressions; not general Haskell weak semantics or arbitrary application deployment'},
                'packages': []}
    base = f'https://github.com/{REPO}/releases/download/{args.tag}/'
    for artifact in sorted(selected, key=lambda a: a['name']):
        if artifact['expired']:
            raise ValueError(f"expired artifact: {artifact['name']}")
        _, _, flavor, platform = artifact['name'].split('-')
        system, arch, tested = PLATFORMS[platform]
        product = root = 'graalvm'
        transport = args.cache / f"{artifact['id']}.zip"
        expected = artifact['digest'].removeprefix('sha256:')
        if not transport.exists():
            temporary = transport.with_suffix('.part')
            with temporary.open('wb') as output:
                subprocess.run(['gh', 'api', f"repos/{REPO}/actions/artifacts/{artifact['id']}/zip"], stdout=output, check=True)
            temporary.rename(transport)
        if sha256(transport) != expected:
            raise ValueError(f'artifact checksum mismatch: {transport}')
        filename = f'thc-{product}-{args.tag}-{flavor}-{platform}-{arch}.tar.gz'
        target = args.output / filename
        with zipfile.ZipFile(transport) as archive:
            inner = 'thc-graal-ci.tar.gz'
            if archive.namelist() != [inner]:
                raise ValueError(f'unexpected artifact members: {archive.namelist()}')
            with archive.open(inner) as src, target.open('wb') as dst:
                shutil.copyfileobj(src, dst)
        installation, contents, release, requirements = inventory(target, root)
        if release.get('JAM_BUILD_FLAVOR') != flavor:
            raise ValueError(f'packaged VM flavor disagrees with artifact identity: {artifact["name"]}')
        static_manifest = 'lib/thc/native-image-libraries.txt'
        static_linkage = any(entry['path'] == static_manifest for entry in contents['files'])
        inventory_name = filename.removesuffix('.tar.gz') + '.files.json'
        (args.output / inventory_name).write_text(json.dumps(contents, indent=2) + '\n')
        manifest['packages'].append({'product': product, 'os': system, 'arch': arch,
            'tested_on': tested, 'requirements': requirements, 'build_flavor': flavor,
            'native_image_linkage': 'static' if static_linkage else 'shared',
            'java_version': release.get('JAVA_VERSION'), 'graal_version': release.get('GRAALVM_VERSION'),
            'archive': {'url': base + filename, 'type': 'tar.gz', 'sha256': sha256(target), 'bytes': target.stat().st_size},
            'installation': {**installation, 'root': root, 'inventory_url': base + inventory_name},
            'paths': {'java': 'bin/java.exe' if platform == 'windows' else 'bin/java',
                'api_jar': 'lib/thc/thc-vm.jar', 'native_directory': 'bin' if platform == 'windows' else 'lib/thc',
                'runtime_manifest': 'lib/thc/runtime-libraries.txt', 'legal': 'legal',
                'native_image_manifest': static_manifest if static_linkage else None,
                'sdk_jars': contents['sdk_jars']},
            'ci_artifact': {'id': artifact['id'], 'sha256': expected}})
        print(f'{platform} {product}: {target.stat().st_size} bytes, installation {installation["sha256"]}', flush=True)
    (args.output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    (args.output / 'SHA256SUMS').write_text(''.join(f'{sha256(p)}  {p.name}\n' for p in sorted(args.output.iterdir())))
    notes = f'''Prebuilt THC VM runtimes from [{source[:12]}](https://github.com/{REPO}/commit/{source}).

These are **{args.build_flavor} preview builds**, promoted byte-for-byte from the [successful runtime CI run]({run['html_url']}). Download the archive for your platform, verify SHA256SUMS, extract it, and set JAVA_HOME to its graalvm/ directory. See manifest.json for exact source pins, platform requirements, matching SDK JARs and full installation identities. Windows includes tar; use tar -xf to preserve the published layout.

GraalVM includes the matching LabsJDK, patched Graal compiler and Native Image/SubstrateVM. Use -XX:+UnlockExperimentalVMOptions -XX:+UseJamGC for Java and --gc=jam for Native Image. Native Image still needs the platform C/C++ toolchain. Its outputs may require companion THC VM libraries; see the [deployment documentation](https://github.com/{REPO}/blob/{source}/docs/vm/native-image.md).

Qualification covers the producer's runtime regressions. General Haskell System.Mem.Weak integration and arbitrary application Native Image deployment are not claimed. No source runtime rebuild was performed for publication.
'''
    (args.output / 'release-notes.md').write_text(notes)


if __name__ == '__main__':
    main()
