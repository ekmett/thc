#!/usr/bin/env python3
"""Publish/verify selected compiler outputs. Publication is CPU work; schedule it explicitly."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import tarfile

BASE = Path(__file__).resolve().parent
ROOT = BASE.parents[3]
PLAN = BASE / 'selected-bgv-plan.json'
MANIFEST = BASE / 'selected-bgv-manifest.json'
ARCHIVE = BASE / 'selected-bgv.tar.xz'


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read_json(path):
    return json.loads(path.read_text())


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def topology(graph):
    # Node properties such as source stacks, timing and machine addresses are
    # intentionally excluded; all actual graph topology remains in this digest.
    canonical = {
        'name': graph['name'], 'group': graph['group'], 'graphType': graph['graphType'],
        'nodes': sorted([[n['id'], n['nodeClass'], n.get('block')] for n in graph['nodes']], key=lambda n: n[0]),
        'edges': sorted(graph['edges'], key=lambda e: json.dumps(e, sort_keys=True)),
        'blocks': sorted(graph['blocks'], key=lambda b: str(b['name'])),
    }
    encoded = json.dumps(canonical, sort_keys=True, separators=(',', ':')).encode()
    return {'sha256': hashlib.sha256(encoded).hexdigest(),
            'nodes': len(graph['nodes']), 'edges': len(graph['edges']), 'blocks': len(graph['blocks'])}


class HashingReader:
    def __init__(self, source):
        self.source = source
        self.hash = hashlib.sha256()
        self.bytes = 0

    def read(self, count=-1):
        data = self.source.read(count)
        self.hash.update(data)
        self.bytes += len(data)
        return data


def inventory():
    old = read_json(BASE / 'compact-manifest-original.json')
    write_json(BASE / 'manifest.json', {
        'recordedUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'scope': 'Original compact evidence plus six byte-identical selected BGVs and reproduction tools. No guest recapture.',
        'originalCompactManifest': {'path': 'compact-manifest-original.json',
                                    'sha256': digest(BASE / 'compact-manifest-original.json'),
                                    'recordedUtc': old.get('recordedUtc')},
        'files': [{'path': str(p.relative_to(BASE)), 'sha256': digest(p), 'bytes': p.stat().st_size}
                  for p in sorted(BASE.rglob('*')) if p.is_file() and p.name != 'manifest.json'
                  and '__pycache__' not in p.parts and not p.name.endswith('.partial')]
    })


def publish():
    plan = read_json(PLAN)
    require(not ARCHIVE.exists() and not MANIFEST.exists(), 'Publication already exists; verify it instead of overwriting.')
    original_manifest = BASE / 'manifest.json'
    require(digest(original_manifest) == plan['originalCompactManifestSha256'], 'Original compact manifest changed.')
    for f in read_json(original_manifest)['files']:
        path = BASE / f['path']
        require(path.stat().st_size == f['bytes'] and digest(path) == f['sha256'], 'Original compact evidence changed: ' + f['path'])
    selected = []
    for item in plan['selected']:
        entry = dict(item)
        phases = []
        for phase in item['phases']:
            source = ROOT / phase['originalPath']
            require(source.stat().st_size == phase['bytes'] and digest(source) == phase['sha256'],
                    'Original parsed graph changed: ' + str(source))
            graph = read_json(source)
            require(graph['name'] == phase['name'], 'Original phase identity changed.')
            actual = topology(graph)
            require(all(actual[k] == phase[k] for k in ('nodes', 'edges', 'blocks')), 'Original phase counts changed.')
            phases.append(dict(phase, topology=actual))
        entry['phases'] = phases
        selected.append(entry)
    partial = ARCHIVE.with_suffix(ARCHIVE.suffix + '.partial')
    require(not partial.exists(), 'Remove or inspect the previous partial archive first.')
    try:
        with tarfile.open(partial, 'w:xz', preset=3, format=tarfile.PAX_FORMAT) as archive:
            for item in selected:
                path = ROOT / item['originalPath']
                require(path.stat().st_size == item['bytes'], 'Raw BGV size changed: ' + str(path))
                member = tarfile.TarInfo(item['archiveMember'])
                member.size = item['bytes']; member.mode = 0o444
                member.mtime = 0; member.uid = 0; member.gid = 0; member.uname = ''; member.gname = ''
                with path.open('rb') as source:
                    reader = HashingReader(source)
                    archive.addfile(member, reader)
                    require(reader.bytes == item['bytes'] and reader.hash.hexdigest() == item['sha256'],
                            'Raw BGV hash changed: ' + str(path))
        partial.rename(ARCHIVE)
    except BaseException:
        partial.unlink(missing_ok=True)
        raise
    write_json(MANIFEST, {
        'status': 'Six original BGVs archived with validated original hashes; topology digests are from the original parsed captures.',
        'scope': plan['scope'], 'topologyScope': plan['topologyScope'], 'graalVersion': plan['graalVersion'],
        'planSha256': digest(PLAN), 'archive': {'path': ARCHIVE.name, 'sha256': digest(ARCHIVE), 'bytes': ARCHIVE.stat().st_size},
        'totalRawBytes': plan['totalRawBytes'], 'selected': selected,
        'reparseStatus': 'Not yet executed; reparse.sh writes a separate verification report.'
    })
    # Preserve the original inventory before updating the publication around it.
    (BASE / 'compact-manifest-original.json').write_bytes(original_manifest.read_bytes())
    raw = read_json(BASE / 'raw-artifacts.json')
    indexed = {i['originalPath']: i for i in selected}
    for f in raw['files']:
        if f['originalPath'] in indexed:
            f.update(archived=True, archive=ARCHIVE.name, archiveMember=indexed[f['originalPath']]['archiveMember'])
    raw['status'] = 'Six selected BGVs are published in selected-bgv.tar.xz; other listed BGVs/JFRs remain original work-path references.'
    write_json(BASE / 'raw-artifacts.json', raw)
    readme = BASE / 'README.md'
    text = readme.read_text()
    old = '- [Raw artifact references](raw-artifacts.json) name all **24 BGVs (622,104,094 bytes)** and both JFRs. They remain at the original `work/` paths. **Compressed BGV publication is pending**; these are provenance references, not downloadable archives. Complete parsed graphs, packet paths and sampled hot stacks also remain in the original captures.'
    new = '- [Selected raw BGV archive](selected-bgv.tar.xz) publishes baseline/candidate lookup, fold and worker compilations with original source positions. [Selection and hash manifest](selected-bgv-manifest.json) records all six original hashes and topology digests. [Reproduction instructions](selected-bgv-README.md) reparse them with the archived parser and verify the graph structure. [Raw artifact references](raw-artifacts.json) retain all 24 BGV and two JFR provenance entries; unselected raw artifacts remain at their recorded `work/` paths.'
    require(old in text, 'Publication README pending marker changed; update it explicitly before final inventory.')
    text = text.replace(old, new)
    text = text.replace('The copied sources are archival: no JVM, BGV reparse, compression or new benchmark was run while assembling this publication.',
                        'The copied sources are archival. Selected original BGVs were compressed without rerunning the guest; reparse verification is recorded separately from graph capture and benchmarking.')
    readme.write_text(text)
    inventory()
    print(json.dumps({'archive': str(ARCHIVE), 'sha256': digest(ARCHIVE), 'bytes': ARCHIVE.stat().st_size, 'rawBytes': plan['totalRawBytes']}))


def extract(out):
    manifest = read_json(MANIFEST)
    require(digest(ARCHIVE) == manifest['archive']['sha256'], 'Compressed archive hash mismatch.')
    require(not out.exists(), 'Choose a new extraction directory.')
    expected = {i['archiveMember']: i for i in manifest['selected']}
    out.mkdir(parents=True)
    seen = set()
    with tarfile.open(ARCHIVE, 'r:xz') as archive:
        for member in archive:
            require(member.name in expected and member.name not in seen and member.isfile(), 'Unexpected archive member.')
            item = expected[member.name]
            require(member.size == item['bytes'], 'Archive member size mismatch.')
            dest = out / member.name
            require(dest.resolve().is_relative_to(out.resolve()), 'Unsafe archive member path.')
            dest.parent.mkdir(parents=True, exist_ok=True)
            source = archive.extractfile(member)
            h = hashlib.sha256()
            with source, dest.open('wb') as output:
                for chunk in iter(lambda: source.read(1024 * 1024), b''):
                    h.update(chunk); output.write(chunk)
            require(h.hexdigest() == item['sha256'], 'Uncompressed BGV hash mismatch: ' + member.name)
            seen.add(member.name)
    require(seen == expected.keys(), 'Archive is incomplete.')
    print('Verified compressed archive and all six original BGV hashes.')


def verify_reparse(out):
    manifest = read_json(MANIFEST)
    results = []
    for item in manifest['selected']:
        parsed = out / item['capture'] / ('parsed-' + Path(item['archiveMember']).stem)
        index = read_json(parsed / 'index.json')
        for expected in item['phases']:
            matches = [g for g in index['graphs'] if g['graphType'] == 'StructuredGraph' and g['name'] == expected['name']]
            require(len(matches) == 1 and matches[0].get('file'), 'Missing/ambiguous reparsed phase: ' + expected['name'])
            actual = topology(read_json(parsed / matches[0]['file']))
            require(actual == expected['topology'], 'Graph topology differs: ' + item['archiveMember'] + ' ' + expected['phase'])
            results.append({'capture': item['capture'], 'role': item['role'], 'compilationId': item['compilationId'],
                            'phase': expected['phase'], 'topology': actual, 'matchesOriginal': True})
    report = {'scope': manifest['topologyScope'], 'archiveSha256': manifest['archive']['sha256'],
              'passed': True, 'phasesVerified': len(results), 'results': results}
    write_json(out / 'topology-verification.json', report)
    print('Verified exact original topology for', len(results), 'reparsed phases.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('publish')
    for name in ('extract', 'verify-reparse'):
        commands.add_parser(name).add_argument('directory', type=Path)
    args = parser.parse_args()
    if args.command == 'publish': publish()
    elif args.command == 'extract': extract(args.directory.resolve())
    else: verify_reparse(args.directory.resolve())
