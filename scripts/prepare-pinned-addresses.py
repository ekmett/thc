#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Genuine pinned-address Core/native fixtures; public Storable stays a frontier.

--native-only builds no exporter. --allow-unsupported retains honest pre-runtime
diagnostics; it never emits strictAccepted=true. Default preparation fails closed.
"""
import argparse
from collections import Counter
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

from pinned_address_model import ENTRIES, PUBLIC_FRONTIERS, cases, expected, parse_rows, check

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT/'build/pinned-addresses'
SOURCE = 'compiler/test-fixtures/PinnedAddressAudit.hs'
DRIVER = 'compiler/test-fixtures/PinnedAddressAuditNative.hs'
ADDITIONS = {'newPinnedByteArray#', 'newAlignedPinnedByteArray#', 'byteArrayContents#',
             'readWord8OffAddr#', 'writeWord8OffAddr#', 'keepAlive#'}
EXPECTED_GUEST_CALLS = {'pinnedBytes': 4, 'alignedBytes': 4, 'keepAliveWord8': 3,
                      'keepAliveLazy': 3, 'fingerprintByte': 3}

def nodes(value):
    if isinstance(value, list):
        yield value
        for child in value:
            yield from nodes(child)
    elif isinstance(value, dict):
        for child in value.values():
            yield from nodes(child)

def applications(modules, primitive, reachable=None):
    for _, module in modules:
        for binding in module.get('bindings', []):
            if reachable is None or binding['id'] in reachable:
                for node in nodes(binding['expr']):
                    if node and node[0] == 'app' and node[1][:2] == ['prim', primitive]:
                        yield node

def raw_proof(expression):
    check(isinstance(expression[-1], dict) and isinstance(expression[-1].get('rep'), dict),
          'Missing actual expression representation')
    return expression[-1]['rep']

def guest_structure(entry, report, modules):
    """Count only concrete unconditional call sites in this bounded fixture.

    Entry + immediate runRW State lambda + keepAlive continuation, with the
    opaque addressBytes helper for the two allocation roots. Complete join
    prefixes are local jumps. The sole bottom global is retained but not called.
    """
    bindings = {b['id']: b for _, module in modules for b in module['bindings']}
    reachable = [bindings[b['id']] for b in report['reachableBindings']]
    wanted = {entry} | ({'addressBytes'} if entry in ('pinnedBytes', 'alignedBytes') else set())
    if entry == 'keepAliveLazy': wanted.add('keptBottom')
    check({b['name'] for b in reachable} == wanted, entry+': global closure changed')
    root = bindings[report['roots'][0]]['expr']
    check(root[0] == 'lam' and len(root[1]) == ENTRIES[entry], entry+': host arity changed')
    for formal in root[1]:
        check(formal['rep']['kind'] == 'long' and formal['rep']['primReps'] == ['IntRep']
              and formal['lifted'] is False and formal['coercion'] is False, entry+': wrong host formal')
    call = root[2]
    check(call[0] == 'app' and call[1][0] == 'lam' and len(call[1][1]) == 1,
          entry+': missing immediate State lambda')
    state = call[1][1][0]
    void = dict(primReps=[], kind='void', evaluated=True)
    check(state['rep'] == void and state['type'] == 'State# RealWorld' and state['lifted'] is False
          and state['coercion'] is False and len(call[2]) == 1 and call[2][0][0] == 'void'
          and raw_proof(call[2][0]) == void and call[3] == [False], entry+': State call shape changed')
    roots, joined = [], 0
    for binding in reachable:
        expression = binding['expr']
        if binding['name'] == 'keptBottom':
            check(expression[:2] == ['var', binding['id']], 'Bottom no longer a retained self-reference')
            continue
        join_lambdas = set()
        for node in nodes(expression):
            if node and node[0] == 'let':
                for local in node[2]:
                    arity, rhs = local.get('joinValueArity'), local['expr']
                    if type(arity) is int and arity > 0:
                        check(rhs[0] == 'lam' and len(rhs[1]) == arity and
                              local['joinResultRep'] == rhs[-1]['resultRep'] and
                              local['joinResultRep']['kind'] == 'long' and
                              local['joinResultRep']['primReps'] == ['IntRep'], entry+': incomplete join prefix')
                        join_lambdas.add(id(rhs))
        joined += len(join_lambdas)
        roots += [node for node in nodes(expression) if node and node[0] == 'lam' and id(node) not in join_lambdas]
    keep = list(applications(modules, 'keepAlive#', {b['id'] for b in reachable}))
    check(len(keep) == 1, entry+': expected one keepAlive call')
    continuation = keep[0][2][2]
    check(continuation[0] == 'lam' and len(continuation[1]) == 1 and
          continuation[1][0]['rep'] == void and continuation[1][0]['lifted'] is False and
          raw_proof(keep[0]) == continuation[-1]['resultRep'], entry+': continuation/result proof changed')
    check(len(roots) == EXPECTED_GUEST_CALLS[entry], entry+': guest lambda count changed')
    allowed = {id(root), id(call[1]), id(continuation)}
    if entry in ('pinnedBytes','alignedBytes'):
        allowed.add(id(next(b['expr'] for b in reachable if b['name'] == 'addressBytes')))
    check({id(node) for node in roots} == allowed, entry+': hidden guest lambda')
    return dict(guestCalls=len(roots), localJoinPrefixes=joined,
                lambdaFormals=[[formal['name'] for formal in node[1]] for node in roots],
                lazyUncalledGlobal='keptBottom' if entry == 'keepAliveLazy' else None)

def proof_negatives(modules, capabilities, audit):
    # All mutations start from a strictly accepted genuine export. Changing only
    # a serialized type certificate cannot establish a passing negative by an
    # unrelated pre-existing unsupported primitive.
    specifications = [
        ('read-word-not-word8', 'keepAliveWord8', 'readWord8OffAddr#', 'result', None, 'long', ['WordRep']),
        ('write-word-not-word8', 'keepAliveWord8', 'writeWord8OffAddr#', 'argument', 2, 'long', ['WordRep']),
        ('read-address-is-word', 'keepAliveWord8', 'readWord8OffAddr#', 'argument', 0, 'long', ['WordRep']),
        ('read-state-is-int', 'keepAliveWord8', 'readWord8OffAddr#', 'argument', 2, 'long', ['IntRep']),
        ('read-offset-is-word', 'keepAliveWord8', 'readWord8OffAddr#', 'argument', 1, 'long', ['WordRep']),
        ('contents-lifted-array', 'keepAliveWord8', 'byteArrayContents#', 'argument', 0, 'object', ['BoxedRep (Just Lifted)']),
        ('contents-result-is-word', 'keepAliveWord8', 'byteArrayContents#', 'result', None, 'long', ['WordRep']),
        ('allocation-size-is-word', 'pinnedBytes', 'newPinnedByteArray#', 'argument', 0, 'long', ['WordRep']),
        ('allocation-state-is-int', 'pinnedBytes', 'newPinnedByteArray#', 'argument', 1, 'long', ['IntRep']),
        ('aligned-alignment-is-word', 'alignedBytes', 'newAlignedPinnedByteArray#', 'argument', 1, 'long', ['WordRep']),
        ('keepalive-state-is-int', 'keepAliveWord8', 'keepAlive#', 'argument', 1, 'long', ['IntRep']),
        ('keepalive-result-word-not-word8', 'keepAliveWord8', 'keepAlive#', 'result', None, 'long', ['WordRep']),
    ]
    results = {}
    for label, entry, primitive, place, index, kind, reps in specifications:
        baseline = audit.Audit(modules, capabilities).run([entry])
        check(baseline['accepted'], 'Negative baseline rejected: '+entry)
        modified = copy.deepcopy(modules)
        reachable = {b['id'] for b in baseline['reachableBindings']}
        candidates = list(applications(modified, primitive, reachable))
        check(candidates, 'Missing mutation site '+label)
        app = candidates[0]
        proof = raw_proof(app[2][index]) if place == 'argument' else raw_proof(app)
        if place == 'result' and proof.get('aggregate') == 'unboxed-tuple':
            check(len(proof['components']) == 2, 'Unexpected result shape '+label)
            proof['components'][1]['kind'] = kind
            proof['components'][1]['primReps'] = reps
            proof['primReps'] = reps
        else:
            proof['kind'], proof['primReps'] = kind, reps
        report = audit.Audit(modified, capabilities).run([entry])
        check(not report['accepted'] and report['issues'], 'Forged proof accepted: '+label)
        results[label] = dict(entry=entry, primitive=primitive, summary=report['summary'], issues=report['issues'])
    return results

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--native-only', action='store_true')
    parser.add_argument('--export-only', action='store_true')
    parser.add_argument('--allow-unsupported', action='store_true')
    args = parser.parse_args()
    check(not (args.native_only and args.export_only), 'Conflicting modes')
    BUILD.mkdir(parents=True, exist_ok=True)
    manifest = BUILD/'manifest.json'
    if manifest.exists():
        previous = BUILD/'previous-manifests'
        previous.mkdir(exist_ok=True)
        digest = hashlib.sha256(manifest.read_bytes()).hexdigest()
        target = previous/(digest+'.json')
        if not target.exists(): target.write_bytes(manifest.read_bytes())
        manifest.unlink()
    ghc = os.environ.get('GHC', 'ghc')
    commands = []
    def run(command, env=None, **kwargs):
        command = list(map(str, command))
        commands.append(dict(argv=command, environment=env or {}))
        return subprocess.run(command, cwd=ROOT, env=dict(os.environ, **(env or {})), check=True, **kwargs)
    version = run([ghc, '--numeric-version'], text=True, capture_output=True).stdout.strip()
    check(version == '9.14.1', 'Requires pinned GHC9.14.1')
    compiler_info = run([ghc, '--info'], text=True, capture_output=True).stdout
    domain = list(cases())
    requests = ''.join(name+'\t'+'\t'.join(map(str, arguments))+'\n' for name, arguments in domain)
    oracle = ''.join(name+'\t'+'\t'.join(map(str, (*arguments, expected(name, arguments))))+'\n'
                     for name, arguments in domain)
    (BUILD/'requests.tsv').write_text(requests)
    (BUILD/'expected.tsv').write_text(oracle)
    artifacts = [BUILD/'requests.tsv', BUILD/'expected.tsv']
    if not args.export_only:
        native = BUILD/'native'; native.mkdir(exist_ok=True)
        binary = native/'pinned-address-oracle'
        run([ghc, '--make', '-O2', '-fforce-recomp', '-dcore-lint', '-dstg-lint',
             '-i'+str(ROOT/'compiler/test-fixtures'), '-odir', native, '-hidir', native,
             DRIVER, '-o', binary])
        output = run([binary], input=requests, text=True, capture_output=True, timeout=60).stdout
        parse_rows(output, domain)
        check(output == oracle, 'Native TSV differs from independent model')
        (BUILD/'oracle.tsv').write_text(output)
        artifacts += [binary, BUILD/'oracle.tsv']
    stages, audits, keep_alive_sites, negative_reports, structures = {}, {}, {}, {}, {}
    strict = False
    if not args.native_only:
        spec = importlib.util.spec_from_file_location('pinned_auditor', ROOT/'scripts/audit-core.py')
        audit = importlib.util.module_from_spec(spec); spec.loader.exec_module(audit)
        capabilities = json.loads((ROOT/'scripts/core-capabilities.json').read_text())
        strict = True
        for stage, boundary in [('pre', 'optimized-Core-before-Tidy'), ('post', 'optimized-Core-after-Tidy-before-CorePrep')]:
            directory = BUILD/stage; core = directory/'core'
            options = ['-fplugin-opt=THC.Plugin:post-tidy'] if stage == 'post' else []
            run([ROOT/'compiler/export.sh', *options,
                 *['-fplugin-opt=THC.Plugin:closure='+entry for entry in ENTRIES | PUBLIC_FRONTIERS], SOURCE],
                env=dict(THC_CORE_OUT=str(core), THC_GHC_OUT=str(directory/'ghc'), THC_SOURCE_NOTES='true'))
            paths = [core/'PinnedAddressAudit.json', core/'THC.InterfaceClosure.json']
            modules = [(str(p.relative_to(ROOT)), json.loads(p.read_text())) for p in paths]
            check(modules[0][1]['boundary'] == boundary, 'Wrong actual Core stage')
            stages[stage] = [name for name, _ in modules]; artifacts += paths
            audits[stage] = {}
            seen = set()
            for entry in ENTRIES | PUBLIC_FRONTIERS:
                report = audit.Audit(modules, capabilities).run([entry])
                path = directory/(entry+'.audit.json'); path.write_text(json.dumps(report, indent=2)+'\n'); artifacts.append(path)
                audits[stage][entry] = dict(summary=report['summary'], accepted=report['accepted'],
                    reachableBindings=report['reachableBindings'], primitives=report['primitives'],
                    missingGlobals=report['missingGlobals'])
                if entry in ENTRIES:
                    strict &= report['accepted']
                    if not args.allow_unsupported:
                        check(report['accepted'], stage+'/'+entry+': unsupported genuine Core; inspect '+str(path))
                    seen |= {p['name'] for p in report['primitives']}
                    structures[stage+'/'+entry] = guest_structure(entry, report, modules)
                else:
                    missing = {x['id'].split(':', 1)[-1] for x in report['missingGlobals']}
                    required = {'GHC.Internal.Foreign.Storable.$fStorableFingerprint_$s$wpokeW64'}
                    if entry == 'publicFingerprintRoundtrip':
                        required.add('GHC.Internal.Foreign.Storable.$fStorableFingerprint_$s$wpeekW64')
                    check(not report['accepted'] and required <= missing,
                          entry+': public Storable frontier changed; review original source closure')
            check(ADDITIONS <= seen, 'Required primitives disappeared: '+str(ADDITIONS-seen))
            keep_alive_sites[stage] = [dict(argumentProofs=[raw_proof(x) for x in app[2]],
                flags=app[3], result=raw_proof(app)) for app in applications(modules, 'keepAlive#')]
            if all(audits[stage][entry]['accepted'] for entry in ENTRIES):
                negative_reports[stage] = proof_negatives(modules, capabilities, audit)
                path = directory/'negative-proofs.json'; path.write_text(json.dumps(negative_reports[stage],indent=2)+'\n'); artifacts.append(path)
    sources = [ROOT/SOURCE, ROOT/DRIVER, Path(__file__), ROOT/'scripts/pinned_address_model.py',
        ROOT/'scripts/test-pinned-addresses.py', ROOT/'scripts/audit-core.py', ROOT/'scripts/core-capabilities.json',
        ROOT/'src/main/resources/thc/scalar-primop-signatures.json']
    sources += list((ROOT/'compiler/THC').glob('*.hs')) + list((ROOT/'scripts').glob('core_*.py'))
    sources += [ROOT/('compiler/'+name) for name in ('build.sh','export.sh','toolchain.sh')]
    hashes = lambda paths: {str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(set(paths))}
    counts = Counter(name for name, _ in domain)
    manifest.write_text(json.dumps(dict(schema=1, ghc=version, entries=ENTRIES, publicFrontiers=PUBLIC_FRONTIERS,
        ghcInfo=compiler_info, nativeByteOrder=sys.byteorder, fingerprintByteOrder='big',
        strictAccepted=strict, mode='native-only' if args.native_only else 'export-only' if args.export_only else 'full',
        stages=stages, audits=audits, keepAliveSites=keep_alive_sites, negativeProofs=negative_reports,
        expectedGuestCallsByEntry=EXPECTED_GUEST_CALLS, checkedGuestStructureByStage=structures,
        nativeRows=0 if args.export_only else len(domain), modelRows=len(domain), rowCounts=dict(counts),
        rows=[dict(entry=n, arguments=list(a), expected=expected(n,a)) for n,a in domain], commands=commands,
        inputHashes=hashes(sources), artifactHashes=hashes(artifacts),
        limits=['Public Storable roots are native evidence / explicit exported frontiers, not claimed THC support.',
                'Primitive fingerprintByte is a labeled byte-layout conformance control, not a replacement Storable implementation.',
                'Defined native domains only; malformed/bounds failures belong to non-native runtime tests.',
                'Managed pinning is not physical JVM pinning; only the closed GHC MD5 FFI uses Sulong, with no general foreign calls or raw process pointers.']), indent=2)+'\n')
    print(f'Pinned address preparation: mode={args} nativeRows={0 if args.export_only else len(domain)} modelRows={len(domain)} strictAccepted={strict} counts={dict(counts)}')

if __name__ == '__main__':
    main()
