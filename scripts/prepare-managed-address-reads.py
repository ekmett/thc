#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Exact native/Core proofs for managed Word32/Word/Int32/Int address reads."""
import ast
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'build/managed-address-reads'
ENTRIES = {'word32Read': ('readWord32OffAddr#', 4, False),
           'wordRead': ('readWordOffAddr#', 8, False),
           'int32Read': ('readInt32OffAddr#', 4, True),
           'intRead': ('readIntOffAddr#', 8, True)}
SEEDS = [-(1 << 63), -4294967296, -2147483649, -2147483648, -1, 0, 1, 127,
         128, 255, 256, 2147483647, 2147483648, 4294967295, (1 << 63) - 1]


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def cases():
    for name, (_, width, _) in ENTRIES.items():
        for raw in SEEDS:
            for base in range(0, 33, 8):
                for start in range(0, 33 - width, width):
                    yield name, raw, base, (start - base) // width


def expected(name, raw, base, offset):
    _, width, signed = ENTRIES[name]
    mask = (1 << 64) - 1
    words = [raw & mask, (raw ^ 0x0123456789abcdef) & mask,
             (raw ^ mask) & mask, (raw ^ 0xaaaaaaaaaaaaaaaa) & mask]
    data = bytearray(b''.join(w.to_bytes(8, sys.byteorder) for w in words))
    start = base + offset * width
    before = int.from_bytes(data[start:start + width], sys.byteorder, signed=signed)
    data[start] = (raw + 173) & 255
    after = int.from_bytes(data[start:start + width], sys.byteorder, signed=signed)
    return ((3 * before + 5 * after + (1 << 63)) & mask) - (1 << 63)


def nodes(value):
    if isinstance(value, list):
        yield value
        for item in value:
            yield from nodes(item)
    elif isinstance(value, dict):
        for item in value.values():
            yield from nodes(item)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    manifest = OUT / 'manifest.json'
    if manifest.exists():
        old = OUT / 'previous-manifests'
        old.mkdir(exist_ok=True)
        digest = hashlib.sha256(manifest.read_bytes()).hexdigest()
        manifest.rename(old / (digest + '.json'))
    commands = []

    def run(args, env=None, **kwargs):
        args = list(map(str, args))
        commands.append(dict(argv=args, environment=env or {}))
        return subprocess.run(args, cwd=ROOT, env=dict(os.environ, **(env or {})), check=True, **kwargs)

    ghc = os.environ.get('GHC', 'ghc')
    require(run([ghc, '--numeric-version'], text=True, capture_output=True).stdout.strip() == '9.14.1', 'Requires GHC 9.14.1')
    info = run([ghc, '--info'], text=True, capture_output=True).stdout
    require(dict(ast.literal_eval(info))['target word size in bits'] == '64', 'Requires 64-bit words')
    domain = list(cases())
    requests = ''.join('\t'.join(map(str, row)) + '\n' for row in domain)
    model = ''.join('\t'.join(map(str, (*row, expected(*row)))) + '\n' for row in domain)
    (OUT / 'requests.tsv').write_text(requests)
    source = ROOT / 'compiler/test-fixtures/ManagedAddressReadAudit.hs'
    driver = ROOT / 'compiler/test-fixtures/ManagedAddressReadNative.hs'
    native = OUT / 'native'
    native.mkdir(exist_ok=True)
    binary = native / 'managed-address-read-oracle'
    run([ghc, '--make', '-O2', '-j2', '-fforce-recomp', '-dcore-lint', '-dstg-lint',
         '-icompiler/test-fixtures', '-odir', native, '-hidir', native, driver, '-o', binary])
    result = run([binary], input=requests, text=True, capture_output=True, timeout=60).stdout
    require(result == model, 'GHC output differs from independent byte model')
    (OUT / 'oracle.tsv').write_text(result)
    spec = importlib.util.spec_from_file_location('address_read_audit', ROOT / 'scripts/audit-core.py')
    audit = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(audit)
    caps = json.loads((ROOT / 'scripts/core-capabilities.json').read_text())
    stages, negative_reports, signatures = {}, {}, {}
    artifacts = [binary, OUT / 'oracle.tsv', OUT / 'requests.tsv']
    for stage in ('pre', 'post'):
        core = OUT / (stage + '-core')
        run(['compiler/export.sh', *(['-fplugin-opt=THC.Plugin:post-tidy'] if stage == 'post' else []),
             *['-fplugin-opt=THC.Plugin:closure=' + name for name in ENTRIES], source],
            env=dict(THC_CORE_OUT=str(core), THC_GHC_OUT=str(OUT / (stage + '-ghc'))))
        paths = sorted(core.glob('*.json'))
        modules = [(str(p.relative_to(ROOT)), json.loads(p.read_text())) for p in paths]
        stages[stage] = [name for name, _ in modules]
        artifacts += paths
        fixture = next(module for _, module in modules if module.get('module') == 'ManagedAddressReadAudit')
        require(fixture['boundary'] == ('optimized-Core-before-Tidy' if stage == 'pre' else 'optimized-Core-after-Tidy-before-CorePrep'), 'Wrong Core stage')
        for name, (primitive, _, _) in ENTRIES.items():
            report = audit.Audit(modules, caps).run([name])
            path = OUT / (stage + '-' + name + '.audit.json')
            path.write_text(json.dumps(report, indent=2) + '\n')
            artifacts.append(path)
            require(report['accepted'], (stage, name, report['issues'], report['missingGlobals']))
            require(primitive in {p['name'] for p in report['primitives']}, (name, 'read optimized away'))
            reachable = {b['id'] for b in report['reachableBindings']}

            def calls(ms):
                return [n for _, m in ms for b in m.get('bindings', []) if b['id'] in reachable
                        for n in nodes(b['expr']) if n and n[0] == 'app' and n[1][:2] == ['prim', primitive]]

            call = calls(modules)[0]
            signatures[stage + '/' + primitive] = dict(arguments=[a[-1]['rep'] for a in call[2]], result=call[-1]['rep'])
            for label in ('address', 'offset', 'state', 'payload', 'state-result', 'aggregate'):
                changed = copy.deepcopy(modules)
                altered = calls(changed)[0]
                wrong = dict(kind='long', primReps=['WordRep'], evaluated=True)
                if label in ('address', 'offset', 'state'):
                    altered[2][('address', 'offset', 'state').index(label)][-1]['rep'] = wrong
                elif label == 'payload':
                    wrong['primReps'] = ['Word8Rep']
                    altered[-1]['rep']['components'][1] = wrong
                    altered[-1]['rep']['primReps'] = ['Word8Rep']
                elif label == 'state-result':
                    altered[-1]['rep']['components'][0] = wrong
                    altered[-1]['rep']['primReps'].insert(0, 'WordRep')
                else:
                    altered[-1]['rep'] = wrong
                rejected = audit.Audit(changed, caps).run([name])
                require(not rejected['accepted'], (stage, name, label, 'forged proof accepted'))
                negative_reports[stage + '/' + name + '/' + label] = rejected['issues']
    negative = OUT / 'negative-proofs.json'
    negative.write_text(json.dumps(negative_reports, indent=2) + '\n')
    artifacts.append(negative)
    sources = [source, driver, Path(__file__), ROOT / 'scripts/core-capabilities.json',
               ROOT / 'scripts/audit-core.py', *sorted((ROOT / 'scripts').glob('core_*.py')),
               *sorted((ROOT / 'compiler/THC').glob('*.hs'))]
    hashes = lambda ps: {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(set(ps))}
    manifest.write_text(json.dumps(dict(schema=1, ghc='9.14.1', wordBits=64, nativeByteOrder=sys.byteorder,
        entries=list(ENTRIES), nativeRows=len(domain), strictAccepted=True, stages=stages,
        signatures=signatures, negativeProofs=len(negative_reports), commands=commands, ghcInfo=info,
        inputHashes=hashes(sources), artifactHashes=hashes(artifacts)), indent=2) + '\n')
    print(f'Managed address reads: {len(domain)} native/model rows, 8 strict audits, {len(negative_reports)} rejected proof mutations')


if __name__ == '__main__':
    main()
