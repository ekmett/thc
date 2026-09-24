#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Native bytes/results plus exact original GHC write/errno call-copy evidence.

No replacement FFI declarations, installed-toolchain hashing, or JVM support
claim. --require-supported additionally gates the CURRENT strict Core auditor.
Run preparation under the repository's shared build-directory resource gate.
"""
import argparse
import ast
from collections import Counter
import ctypes
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys

from original_stdio_model import ENTRIES, PAYLOAD, cases, check, validate_rows

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / 'build/original-stdio'
SOURCE = 'compiler/test-fixtures/OriginalStdioAudit.hs'
DRIVER = 'compiler/test-fixtures/OriginalStdioAuditNative.hs'
UNSAFE_WRITE = 'ghczuwrapperZC21ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCwrite'
SAFE_WRITE = 'ghczuwrapperZC20ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCwrite'
ERRNO = '__hscore_get_errno'
SCALAR_KEYS = {'kind', 'primReps', 'evaluated'}


def scalar(primitive, evaluated=False):
    return dict(kind='void' if primitive is None else 'address' if primitive == 'AddrRep' else 'long',
                primReps=[] if primitive is None else [primitive], evaluated=evaluated)


def returned(primitive, evaluated=False):
    return dict(kind='unknown', primReps=[primitive], evaluated=evaluated, aggregate='unboxed-tuple',
                components=[scalar(None, True), scalar(primitive, True)])


def descriptor(symbol):
    check(symbol in (UNSAFE_WRITE, SAFE_WRITE, ERRNO), 'Unexpected original target')
    arguments = [None] if symbol == ERRNO else ['Int32Rep', 'AddrRep', 'Word64Rep', None]
    return dict(schema=1, target=dict(kind='static', symbol=symbol, unit='ghc-internal', isFunction=True),
                convention='ccall' if symbol == ERRNO else 'capi', safety='safe' if symbol == SAFE_WRITE else 'unsafe',
                arity=len(arguments), suppliedArity=len(arguments), argumentReps=[scalar(p) for p in arguments],
                resultRep=returned('Int32Rep' if symbol == ERRNO else 'Int64Rep'))


def nodes(value, path=''):
    if isinstance(value, list):
        yield path, value
        for i, child in enumerate(value):
            yield from nodes(child, path+'/'+str(i))
    elif isinstance(value, dict):
        for key, child in value.items():
            yield from nodes(child, path+'/'+key)


def representation(expression):
    check(isinstance(expression, list) and isinstance(expression[-1], dict), 'Missing expression metadata')
    proof = expression[-1].get('rep')
    check(isinstance(proof, dict), 'Missing raw expression representation')
    return proof


def exactly(actual, expected):
    # JSON equality must distinguish true/1, false/0, and integral/floating data.
    return json.dumps(actual, sort_keys=True) == json.dumps(expected, sort_keys=True)


def validate_application(app):
    check(len(app) == 7 and app[0] == 'app' and app[1][0] == 'var', 'Original FCall application shape')
    actual = app[-1]['foreignCall']
    symbol = actual.get('target', {}).get('symbol')
    expected = descriptor(symbol)
    # Equality alone treats bools as ints; require exact JSON types as well.
    check(type(actual.get('schema')) is int and type(actual.get('arity')) is int
          and type(actual.get('suppliedArity')) is int and exactly(actual, expected),
          'Original FCall descriptor differs from pinned LP64 target')
    check(len(app[2]) == expected['arity'] and len(app[3]) == expected['arity']
          and all(flag is False for flag in app[3]), 'Original FCall argument arity/flags')
    for argument, declared in zip(app[2], expected['argumentReps']):
        proof = representation(argument)
        check(proof.keys() == SCALAR_KEYS and type(proof.get('evaluated')) is bool
              and exactly(proof, dict(declared, evaluated=proof['evaluated'])), 'Original FCall actual argument proof')
    proof = representation(app)
    check(type(proof.get('evaluated')) is bool
          and exactly(proof, dict(expected['resultRep'], evaluated=proof['evaluated'])), 'Original FCall actual result proof')
    return dict(symbol=symbol, head=app[1][1], declaration=actual,
                actualArguments=[representation(x) for x in app[2]], flags=app[3], actualResult=proof)


def validate_module(module):
    bindings = {binding['name']: binding for binding in module['bindings'] if binding['name'] in ENTRIES}
    check(set(bindings) == set(ENTRIES), 'Missing original stdio consumers')
    records, structures = [], {}
    for entry in ENTRIES:
        binding = bindings[entry]
        root = binding['expr']
        check(root[0] == 'lam' and len(root[1]) == 4, entry+': host arity changed')
        for formal, primitive in zip(root[1], ('IntRep', 'AddrRep', 'IntRep', 'WordRep')):
            check(formal['rep'] == scalar(primitive, True) and formal['lifted'] is False
                  and formal['coercion'] is False, entry+': host argument proof changed')
        lambdas = [node for _, node in nodes(root) if node and node[0] == 'lam']
        call = root[2]
        check(len(lambdas) == 2 and call[0] == 'app' and call[1][0] == 'lam'
              and len(call[1][1]) == 1 and call[1][1][0]['rep'] == scalar(None, True)
              and call[1][1][0]['type'] == 'State# RealWorld'
              and call[1][1][0]['lifted'] is False and call[1][1][0]['coercion'] is False
              and call[3] == [False] and len(call[2]) == 1 and call[2][0][0] == 'void'
              and representation(call[2][0]) == scalar(None, True), entry+': runRW State boundary changed')
        expected = [SAFE_WRITE if 'Safe' in entry else UNSAFE_WRITE] + ([ERRNO] if entry.endswith('Errno') else [])
        found = []
        for path, node in nodes(root):
            if node and node[0] == 'app' and isinstance(node[-1], dict) and 'foreignCall' in node[-1]:
                record = validate_application(node)
                found.append(record['symbol'])
                records.append(dict(owner=binding['id'], entry=entry, path='/expr'+path, **record))
        check(Counter(found) == Counter(expected), entry+': original FCall copies changed')
        structures[entry] = dict(hostArity=4, guestCalls=2, resultPrimReps=['IntRep'], foreignCallCopies=len(found))
    all_calls = [node for _, node in nodes(module) if node and node[0] == 'app'
                 and isinstance(node[-1], dict) and 'foreignCall' in node[-1]]
    check(len(all_calls) == len(records) == 6, 'Extra or missing original FCall application copies')
    return records, structures


def validate_audit(entry, report, proofs):
    """Only the exact original FCalls may remain a pre-integration frontier."""
    owned = [proof for proof in proofs if proof['entry'] == entry]
    owner = owned[0]['owner']
    check(report['roots'] == [owner] and [row['id'] for row in report['reachableBindings']] == [owner],
          entry+': unexpected callable global closure')
    check(all(issue['code'] == 'foreign-call' and issue['owner'] == owner for issue in report['issues']),
          entry+': unexpected non-FFI strict-audit frontier')
    check({row['id'] for row in report['missingGlobals']} <= {row['head'] for row in owned},
          entry+': unexpected missing source global')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument('--native-only', action='store_true')
    modes.add_argument('--export-only', action='store_true')
    parser.add_argument('--require-supported', action='store_true')
    args = parser.parse_args()
    check(not (args.native_only and args.require_supported), 'Native-only cannot establish strict Core support')
    check(sys.platform in ('linux', 'darwin') and ctypes.sizeof(ctypes.c_void_p) == 8
          and ctypes.sizeof(ctypes.c_long) == 8 and ctypes.sizeof(ctypes.c_int) == 4,
          'Requires a native Linux/macOS LP64 host')
    BUILD.mkdir(parents=True, exist_ok=True)
    manifest = BUILD/'manifest.json'
    if manifest.exists():
        previous = BUILD/'previous-manifests'; previous.mkdir(exist_ok=True)
        previous.joinpath(hashlib.sha256(manifest.read_bytes()).hexdigest()+'.json').write_bytes(manifest.read_bytes())
        manifest.unlink()
    logs = BUILD/'logs'; logs.mkdir(exist_ok=True)
    commands, artifacts = [], []
    def run(label, command, environment=None, timeout=120):
        command = list(map(str, command))
        result = subprocess.run(command, cwd=ROOT, env=dict(os.environ, **(environment or {})),
                                capture_output=True, timeout=timeout)
        outputs = [logs/(label+'.stdout'), logs/(label+'.stderr'), logs/(label+'.command.json')]
        record = dict(argv=command, environment=environment or {}, exit=result.returncode)
        outputs[0].write_bytes(result.stdout); outputs[1].write_bytes(result.stderr)
        outputs[2].write_text(json.dumps(record, indent=2)+'\n')
        commands.append(record); artifacts.extend(outputs)
        check(result.returncode == 0, f'{label} failed; see {outputs[1]}')
        return result
    ghc = os.environ.get('GHC', 'ghc')
    version = run('ghc-version', [ghc, '--numeric-version']).stdout.decode().strip()
    check(version == '9.14.1', 'Requires pinned GHC 9.14.1')
    compiler_info = run('ghc-info', [ghc, '--info']).stdout.decode()
    info = dict(ast.literal_eval(compiler_info))
    check(info['Host platform'] == info['Target platform'] and info['target word size'] == '8',
          'Cross compilation/non-64-bit GHC unsupported')
    domain = list(cases())
    (BUILD/'expected.json').write_text(json.dumps(domain, indent=2)+'\n')
    artifacts.append(BUILD/'expected.json')
    native_rows = []
    if not args.export_only:
        native = BUILD/'native'; native.mkdir(exist_ok=True)
        binary = native/'original-stdio-oracle'
        run('native-build', [ghc, '--make', '-O2', '-fforce-recomp', '-dcore-lint', '-dstg-lint',
            '-package', 'ghc-internal', '-i'+str(ROOT/'compiler/test-fixtures'), '-odir', native,
            '-hidir', native, DRIVER, '-o', binary])
        artifacts.append(binary)
        results = BUILD/'results'; results.mkdir(exist_ok=True)
        for index, row in enumerate(domain):
            result_path = results/(str(index)+'.txt')
            # Failures must not inherit a stale result from an older invocation.
            if result_path.exists(): result_path.unlink()
            output = run(f'native-{index:03}', [binary, row['entry'], *row['arguments'], result_path], timeout=10)
            value = result_path.read_text()
            check(value == str(row['result'])+'\n', f'Native result differs: {row}')
            native_rows.append(dict(entry=row['entry'], arguments=row['arguments'], result=int(value),
                                    stdoutHex=output.stdout.hex(), stderrHex=output.stderr.hex()))
            artifacts.append(result_path)
        validate_rows(native_rows)
        (BUILD/'oracle.json').write_text(json.dumps(native_rows, indent=2)+'\n')
        artifacts.append(BUILD/'oracle.json')
    stages, audits = {}, {}
    if not args.native_only:
        spec = importlib.util.spec_from_file_location('original_stdio_audit', ROOT/'scripts/audit-core.py')
        audit = importlib.util.module_from_spec(spec); spec.loader.exec_module(audit)
        capabilities = json.loads((ROOT/'scripts/core-capabilities.json').read_text())
        for stage, boundary in (('pre', 'optimized-Core-before-Tidy'), ('post', 'optimized-Core-after-Tidy-before-CorePrep')):
            directory = BUILD/stage; core = directory/'core'
            options = ['-fplugin-opt=THC.Plugin:post-tidy'] if stage == 'post' else []
            run(stage+'-export', [ROOT/'compiler/export.sh', '-package', 'ghc-internal', *options,
                *['-fplugin-opt=THC.Plugin:closure='+entry for entry in ENTRIES], SOURCE],
                dict(THC_CORE_OUT=str(core), THC_GHC_OUT=str(directory/'ghc'), THC_SOURCE_NOTES='true'))
            paths = [core/'OriginalStdioAudit.json', core/'THC.InterfaceClosure.json']
            modules = [(str(path.relative_to(ROOT)), json.loads(path.read_text())) for path in paths]
            check(modules[0][1]['boundary'] == boundary, 'Wrong actual Core stage')
            proofs, structures = validate_module(modules[0][1])
            check(not modules[1][1]['bindings'], 'Unexpected imported global closure')
            proof_path = directory/'proofs.json'
            proof_path.write_text(json.dumps(dict(copies=proofs, structures=structures), indent=2)+'\n')
            artifacts.extend([*paths, proof_path])
            stages[stage] = dict(modules=[str(path.relative_to(ROOT)) for path in paths],
                                 proofCopies=proofs, checkedStructure=structures)
            audits[stage] = {}
            for entry in ENTRIES:
                report = audit.Audit(modules, capabilities).run([entry])
                path = directory/(entry+'.audit.json')
                path.write_text(json.dumps(report, indent=2)+'\n'); artifacts.append(path)
                validate_audit(entry, report, proofs)
                audits[stage][entry] = dict(accepted=report['accepted'], summary=report['summary'],
                    issues=report['issues'], missingGlobals=report['missingGlobals'])
                if args.require_supported:
                    check(report['accepted'], 'Current strict auditor rejected '+stage+'/'+entry+'; see '+str(path))
    source_paths = [ROOT/SOURCE, ROOT/DRIVER, Path(__file__), ROOT/'scripts/original_stdio_model.py',
                    ROOT/'scripts/test-original-stdio-fixtures.py', ROOT/'scripts/audit-core.py',
                    ROOT/'scripts/core-capabilities.json', ROOT/'src/main/resources/thc/scalar-primop-signatures.json']
    source_paths += list((ROOT/'compiler/THC').glob('*.hs')) + list((ROOT/'scripts').glob('core_*.py'))
    source_paths += [ROOT/('compiler/'+name) for name in ('build.sh', 'export.sh', 'toolchain.sh')]
    def hashes(paths):
        return {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in sorted(set(paths))}
    manifest.write_text(json.dumps(dict(schema=1, ghc=version, ghcInfo=compiler_info,
        mode='native-only' if args.native_only else 'export-only' if args.export_only else 'full',
        entries=list(ENTRIES), payloadHex=PAYLOAD.hex(), nativeRows=len(native_rows), modelRows=len(domain),
        stages=stages, audits=audits, installedArtifactsHashed=False,
        strictAccepted=bool(audits) and all(row['accepted'] for stage in audits.values() for row in stage.values()),
        runtimeVerified=False, commands=commands, inputHashes=hashes(source_paths), artifactHashes=hashes(artifacts),
        limits=['Exact pinned GHC9.14.1 LP64 original targets only, native Linux/macOS; no wildcard wrapper recognition.',
                'No malformed native pointers, negative counts, oversized writes, readiness, EINTR, or partial-write guarantee.',
                'No JVM execution or complete ordinary GHC Handle/putStrLn claim from fixture preparation.',
                'Scalar runRW fixture consumers are not a public pure IO API.']), indent=2)+'\n')
    print(f'Original stdio: nativeRows={len(native_rows)} stages={list(stages)} originalCopiesPerStage=6 runtimeVerified=false')


if __name__ == '__main__':
    main()
