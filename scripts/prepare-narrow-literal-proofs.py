#!/usr/bin/env python3
"""Freeze genuine direct-write literals before testing metadata-only projections."""
import argparse
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'build/narrow-literal-proofs'
KINDS = {prefix.lower()+str(bits): prefix+str(bits) for bits in (8, 16, 32) for prefix in ('Int', 'Word')}
ENTRIES = ['write'+name+'Literal' for name in KINDS.values()]
INPUTS = [-(1 << 63), -1, 0, 1, 2, 3, 4, 7, (1 << 63)-1]
UNKNOWN = dict(kind='unknown', primReps=None, evaluated=False)


def values(kind):
    bits = int(''.join(c for c in kind if c.isdigit()))
    return [-(1 << (bits-1)), -1, 0, (1 << (bits-1))-1] if kind.startswith('int') else [0, (1 << (bits-1))-1, 1 << (bits-1), (1 << bits)-1]


def walk(value):
    yield value
    for child in value.values() if isinstance(value, dict) else value if isinstance(value, list) else []:
        yield from walk(child)


def write_literals(module):
    for node in walk(module):
        if isinstance(node, list) and len(node) >= 4 and node[0] == 'app' and node[1][:1] == ['prim']:
            name = node[1][1]
            if name in {'write'+v+'Array#' for v in KINDS.values()}:
                literal = node[2][2]
                assert literal[:1] == ['lit'] and literal[1] in KINDS, (name, literal)
                assert name == 'write'+KINDS[literal[1]]+'Array#'
                yield literal


def project(module, variant):
    result = copy.deepcopy(module)
    for literal in write_literals(result):
        if variant == 'absent':
            del literal[3:]
        elif variant != 'exact':
            literal[3]['rep'] = copy.deepcopy(UNKNOWN if variant == 'unknown' else variant)
    return result


def bad_proofs():
    return [dict(kind='long', primReps=[r], evaluated=True) for r in ('IntRep', 'WordRep', 'Int64Rep', 'Word64Rep')] + [
        {}, dict(kind='long', primReps=['Int8Rep']), dict(kind='unknown', primReps='Int8Rep', evaluated=True),
        dict(kind='object', primReps=['BoxedRep (Just Unlifted)'], evaluated=True),
        dict(kind='void', primReps=[], evaluated=True),
        dict(kind='unknown', primReps=[], evaluated=True, aggregate='unboxed-tuple', components=[])]


def audit(module):
    spec = importlib.util.spec_from_file_location('audit_core', ROOT/'scripts/audit-core.py')
    tool = importlib.util.module_from_spec(spec); spec.loader.exec_module(tool)
    return tool.Audit([('NarrowLiteralProofAudit.json', module)], json.loads((ROOT/'scripts/core-capabilities.json').read_text())).run(ENTRIES)


def record(path):
    return dict(path=str(path.relative_to(ROOT)), sha256=hashlib.sha256(path.read_bytes()).hexdigest())


def check_inputs():
    expected = [(entry, x, values(kind)[x & 3]) for kind, entry in zip(KINDS, ENTRIES) for x in INPUTS]
    actual = [(p[0], int(p[1]), int(p[2])) for line in (OUT/'oracle.tsv').read_text().splitlines() if (p := line.split('\t'))]
    assert actual == expected, 'Native observations disagree with independent index/sign model'
    inventories = {}
    for stage in ('pre', 'post'):
        path = OUT/f'{stage}-core/NarrowLiteralProofAudit.json'
        module = json.loads(path.read_text())
        assert module['ghc'] == '9.14.1'
        inventory = [(x[1], int(x[2])) for x in write_literals(module)]
        assert sorted(inventory) == sorted((kind, v) for kind in KINDS for v in values(kind)), inventory
        for literal in write_literals(module):
            assert literal[3]['rep'] == dict(kind='long', primReps=[KINDS[literal[1]]+'Rep'], evaluated=True)
        report = audit(module)
        assert report['accepted'], report['issues']
        (OUT/f'{stage}-audit.json').write_text(json.dumps(report, indent=2)+'\n')
        for variant in ('exact', 'absent', 'unknown'):
            report = audit(project(module, variant)); assert report['accepted'], report['issues']
        for proof in bad_proofs():
            assert not audit(project(module, proof))['accepted'], proof
        inventories[stage] = inventory
    return dict(nativeRows=len(actual), inputs=INPUTS, entries=ENTRIES, originalWriteLiterals=inventories)


def main():
    parser = argparse.ArgumentParser(); parser.add_argument('--check-only', action='store_true'); args = parser.parse_args()
    if args.check_only:
        manifest = json.loads((OUT/'manifest.json').read_text())
        for item in manifest['sources']+manifest['artifacts']:
            assert record(ROOT/item['path']) == item, item['path']
        # Rechecking must reproduce the exact retained audits.
        check_inputs(); print('Narrow literal proof inputs verified'); return
    OUT.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ); ghc = env.get('GHC', 'ghc')
    assert subprocess.check_output([ghc, '--numeric-version'], text=True).strip() == '9.14.1'
    commands = []
    def run(command, extra=None, output=None):
        commands.append(command)
        result = subprocess.run(command, cwd=ROOT, env=env | (extra or {}), text=True,
                                stdout=subprocess.PIPE if output else None, check=True)
        if output: output.write_text(result.stdout)
    run(['compiler/build.sh'])
    for stage in ('pre', 'post'):
        run(['compiler/export.sh', *(['-fplugin-opt=Thc.Plugin:post-tidy'] if stage == 'post' else []),
             'compiler/test-fixtures/NarrowLiteralProofAudit.hs'],
            dict(THC_CORE_OUT=str(OUT/f'{stage}-core'), THC_GHC_OUT=str(OUT/f'{stage}-ghc')))
    native = OUT/'native'; native.mkdir(exist_ok=True)
    run([ghc, '--make', '-O2', '-fforce-recomp', '-icompiler/test-fixtures',
         '-odir', str(native), '-hidir', str(native), 'compiler/test-fixtures/NarrowLiteralProofAuditNative.hs',
         '-o', str(native/'narrow-literal-oracle')])
    run([str(native/'narrow-literal-oracle')], output=OUT/'oracle.tsv')
    evidence = check_inputs()
    sources = [ROOT/p for p in ['scripts/prepare-narrow-literal-proofs.py', 'scripts/test-narrow-literal-proofs.py',
        'compiler/test-fixtures/NarrowLiteralProofAudit.hs', 'compiler/test-fixtures/NarrowLiteralProofAuditNative.hs',
        'compiler/build.sh', 'compiler/export.sh', 'compiler/toolchain.sh', 'scripts/audit-core.py',
        'scripts/core-capabilities.json', 'scripts/generate-scalar-signatures.py', 'src/main/resources/thc/scalar-primop-signatures.json']]
    sources += sorted((ROOT/'compiler/Thc').rglob('*.hs')) + sorted((ROOT/'scripts').glob('core_*.py'))
    artifacts = [OUT/'oracle.tsv', native/'narrow-literal-oracle'] + [OUT/f'{s}-core/NarrowLiteralProofAudit.json' for s in ('pre','post')] + [OUT/f'{s}-audit.json' for s in ('pre','post')]
    manifest = dict(schema=1, ghc='9.14.1', commands=commands, **evidence,
                    sources=[record(p) for p in sources], artifacts=[record(p) for p in artifacts])
    (OUT/'manifest.json').write_text(json.dumps(manifest, indent=2)+'\n')
    print(f"Narrow literal proofs prepared: {evidence['nativeRows']} native/model rows, 24 direct writes per stage")


if __name__ == '__main__': main()
