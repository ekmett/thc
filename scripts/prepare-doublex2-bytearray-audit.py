#!/usr/bin/env python3
"""Fresh native/model/Core evidence for six bounded DoubleX2 ByteArray intrinsics."""
import argparse
from collections import Counter
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import tempfile
from core_vector_memory import read_case
from doublex2_bytearray_model import BYTE_ORDER, HELPERS, check, entries, graph_entries, model_rows, parse_rows, diagnostic_rows

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT/'build/simd-doublex2-bytearray'
FIXTURE = ROOT/'compiler/test-fixtures/SimdDoubleX2ByteArray.hs'
NATIVE = ROOT/'compiler/test-fixtures/SimdDoubleX2ByteArrayNative.hs'
STAGES = {'pre': 'optimized-Core-before-Tidy', 'post': 'optimized-Core-after-Tidy-before-CorePrep'}
VECTOR = {'lanes': 2, 'element': 'DoubleElemRep'}
OPERATIONS = {prefix+suffix for prefix in ('index', 'read', 'write')
              for suffix in ('DoubleX2Array#', 'DoubleArrayAsDoubleX2#')}
EXPECTED_CALLS = {entry['name']: 4 if entry['name'].endswith('IndexCase') and 'Graph' not in entry['name']
                  else 3 if entry['name'] in HELPERS else 2 for entry in entries()}
HOST_TUPLES = [family+kind+'Worker' for family in ('vector', 'scalar') for kind in ('Read', 'Write')]
FRONTIERS = {
    'vectorArgument': {('vector-boundary', 'vector formal argument'): 1},
    'readVectorEscape': {('vector-boundary', 'vector function result'): 1},
    'readTupleEscape': {('aggregate-boundary', 'unboxed-tuple host result'): 1,
                        ('aggregate-representation', 'unboxed-tuple: unsupported component'): 2,
                        ('vector-representation', 'Vector representation lacks exact vector metadata'): 2,
                        ('malformed-expression', 'Invalid local vector memory intrinsic: read requires an immediate exact case'): 1},
    **{name: {('aggregate-boundary', 'unboxed-tuple host result'): 1} for name in HOST_TUPLES}}


def walk(value):
    yield value
    for child in value.values() if isinstance(value, dict) else value if isinstance(value, list) else []:
        yield from walk(child)


def expressions(value, tag):
    return [node for node in walk(value) if isinstance(node, list) and node[:1] == [tag]]


def representation(expr):
    return expr[-1].get('rep') if isinstance(expr[-1], dict) else None


def exact(proof, kind, reps):
    return (isinstance(proof, dict) and proof.get('kind') == kind and proof.get('primReps') == reps
            and 'aggregate' not in proof and type(proof.get('evaluated')) is bool)


def scalar(proof):
    return exact(proof, 'long', ['IntRep'])


def state(proof):
    return exact(proof, 'void', [])


def array(proof):
    return exact(proof, 'object', ['BoxedRep (Just Unlifted)'])


def scalar_state_tuple(proof):
    fields = proof.get('components', []) if isinstance(proof, dict) else []
    return (isinstance(proof, dict) and proof.get('aggregate') == 'unboxed-tuple' and proof.get('kind') == 'unknown'
            and proof.get('primReps') == ['IntRep'] and len(fields) == 2
            and state(fields[0]) and scalar(fields[1]) and 'vector' not in proof)


def guest_structure(entry, report, module):
    name = entry['name']
    bindings = {b['id']: b for b in module['bindings']}
    root = bindings[report['roots'][0]]
    helper = HELPERS.get(name)
    reachable = [bindings[b['id']] for b in report['reachableBindings']]
    check({b['name'] for b in reachable} == {name, *([helper] if helper else [])}, name+': changed global closure')
    total = 0
    roots = []
    for binding in reachable:
        expr = binding['expr']
        lambdas = expressions(expr, 'lam')
        references = [node[1] for node in expressions(expr, 'var') if node[1] in bindings]
        calls = [node for node in expressions(expr, 'app') if node[1][:1] == ['var'] and node[1][1] in bindings]
        is_wrapper = binding is root
        check(expr[0] == 'lam', name+': non-lambda root')
        if is_wrapper:
            check(binding['arity'] == entry['arity'] and len(expr[1]) == entry['arity']
                  and all(scalar(p.get('rep')) for p in expr[1]), name+': changed scalar wrapper ABI')
            check(scalar(expr[-1].get('resultRep')) and len(lambdas) == 2, name+': expected scalar wrapper and runRW lambda')
            local = lambdas[1]
            check(len(local[1]) == 1 and state(local[1][0].get('rep')) and scalar(local[-1].get('resultRep')),
                  name+': runRW lambda ABI changed')
            local_apps = [node for node in expressions(expr, 'app') if node[1][:1] == ['lam']]
            check(len(local_apps) == 1 and local_apps[0][1] is local and len(local_apps[0][2]) == 1,
                  name+': state lambda is not called exactly once')
        else:
            validate_worker(binding)
        check(len(references) == len(calls) == (1 if is_wrapper and helper else 0), name+': missing/duplicate residual helper')
        if calls:
            call = calls[0]
            callee = bindings[call[1][1]]
            check(callee['name'] == helper and len(call[2]) == callee['arity']
                  and call[3:6] == [[False]*callee['arity'], False, False], name+': helper call is not saturated/unlifted')
        check(all(len(case[3]) == 1 for case in expressions(expr, 'case')), name+': conditional guest call path')
        total += len(lambdas)
        roots.append(dict(id=binding['id'], name=binding['name'], lambdaCount=len(lambdas)))
    check(total == EXPECTED_CALLS[name], name+': actual guest root count changed')
    return dict(guestCalls=total, roots=roots)


def validate_worker(binding):
    name, expr = binding['name'], binding['expr']
    formals = expr[1]
    lambdas = expressions(expr, 'lam')
    check(len(lambdas) == (2 if name.endswith('IndexWorker') else 1) and array(formals[0]['rep']),
          name+': worker array/lambda boundary changed')
    if name.endswith('IndexWorker'):
        local = lambdas[1]
        calls = [x for x in expressions(expr, 'app') if x[1][:1] == ['lam']]
        check(len(local[1]) == 1 and state(local[1][0]['rep']) and scalar(local[-1].get('resultRep'))
              and len(calls) == 1 and calls[0][1] is local and len(calls[0][2]) == 1,
              name+': raw index scratch runRW is not exactly one state-to-scalar call')
    result = expr[-1].get('resultRep')
    if name.endswith(('IndexWorker', 'IndexGraph')):
        arity = 3 if name.endswith('IndexWorker') else 2
        check(len(formals) == arity and all(scalar(x['rep']) for x in formals[1:]) and scalar(result), name+': index ABI changed')
    elif name.endswith('ReadWorker'):
        check(len(formals) == 4 and all(scalar(x['rep']) for x in formals[1:3]) and state(formals[3]['rep'])
              and scalar_state_tuple(result), name+': read helper must return only state/scalar')
    else:
        check(len(formals) == 5 and all(scalar(x['rep']) for x in formals[1:4]) and state(formals[4]['rep']),
              name+': store/write scalar arguments changed')
        check(array(result) if name.endswith('StoreGraph') else scalar_state_tuple(result), name+': write result ABI changed')
    memory = [x for x in expressions(expr, 'app') if x[1][:1] == ['prim'] and x[1][1] in OPERATIONS]
    check(len(memory) == 1, name+': worker must perform exactly one vector memory operation')
    if name.endswith('Graph'):
        primitives = Counter(x[1] for x in expressions(expr, 'prim'))
        check(not any(x in primitives for x in ('newByteArray#', 'readDoubleArray#', 'writeDoubleArray#', 'readIntArray#', 'writeIntArray#')),
              name+': graph root gained scratch alias storage')
        if name.endswith('IndexGraph'):
            check(primitives['*##'] == 2 and primitives['+##'] == 1 and primitives['double2Int#'] == 1,
                  name+': graph checksum must observe both lanes')
        else:
            check(primitives['int2Double#'] == 2 and primitives['packDoubleX2#'] == 1 and primitives['unsafeFreezeByteArray#'] == 1,
                  name+': graph store must pack two converted seeds and freeze once')
    if name.endswith('StoreGraph'):
        outer = expr[2]
        check(outer[0] == 'case' and outer[1] is memory[0], name+': write must be the first case')
        after_write = outer[3][0][3]
        check(after_write[0] == 'case' and after_write[1][0] == 'app'
              and after_write[1][1][:2] == ['prim', 'unsafeFreezeByteArray#'], name+': missing final freeze')
        freeze = after_write[1]
        check([arg[:2] for arg in freeze[2]] == [['var', formals[0]['id']], ['var', outer[2]]], name+': write state not consumed by freeze')
        alt = after_write[3][0]
        check(alt[3][:2] == ['var', alt[2][1]], name+': final frozen array is not returned directly')


def inventory(module, stage):
    check(module['boundary'] == STAGES[stage], 'Wrong Core stage')
    primitives = Counter(node[1] for node in expressions(module['bindings'], 'prim'))
    check(OPERATIONS <= primitives.keys() and 'setByteArray#' not in primitives, 'Missing six memory ops or unrelated setByteArray#')
    check(not any('cast' in name.lower() for name in primitives), 'Scalar bitcasts are outside this slice')
    vector_proofs = [p for p in walk(module['bindings']) if isinstance(p, dict) and p.get('kind') == 'vector']
    check(vector_proofs and all(p.get('primReps') == ['VecRep 2 DoubleElemRep'] and p.get('vector') == VECTOR
                              and 'aggregate' not in p for p in vector_proofs), 'Inexact vector leaf proof')
    constructors = {c['id']: c for c in module['constructors']}
    read_count = 0
    for binding in module['bindings']:
        if binding['name'] not in {*EXPECTED_CALLS, *HELPERS.values()}:
            continue
        if 'Graph' not in binding['name']:
            primitive_names = {x[1] for x in expressions(binding['expr'], 'prim')}
            check(not primitive_names.intersection({'int2Double#', 'double2Int#', '+##', '*##'}),
                  binding['name']+': raw-bit observer acquired numerical floating conversion/arithmetic')
        for case in expressions(binding['expr'], 'case'):
            read = read_case(case, constructors)
            if read is not None:
                read_count += 1
        if binding['name'] in HELPERS.values():
            validate_worker(binding)
    check(read_count == 6, 'Expected four alias and two worker immediate reads')
    literals = [node for node in expressions(module['bindings'], 'lit') if node[1] == 'word32']
    check(literals and all(0 <= int(node[2]) < 1 << 32 for node in literals)
          and any(int(node[2]) >= 1 << 31 for node in literals), 'Missing/noncanonical genuine Word32 literals')
    doubles = [node for node in expressions(module['bindings'], 'lit') if node[1] == 'double']
    check(Counter(node[2] for node in doubles) == {'3.0': 3, '5.0': 3},
          'Genuine finite checksum literal sites changed')
    return dict(localReadSites=read_count, word32LiteralSites=len(literals), doubleLiteralSites=len(doubles), vectorProofs=len(vector_proofs),
                memoryPrimitiveCounts={name: primitives[name] for name in sorted(OPERATIONS)})


def family_controls(module, path, auditor, capabilities, element):
    result = {}
    for family in ('vector', 'scalar'):
        for operation in ('Index', 'Read', 'Write'):
            changed = copy.deepcopy(module)
            worker = next(b for b in changed['bindings'] if b['name'] == family+operation+'Worker')
            call = next(x for x in expressions(worker['expr'], 'app') if x[1][:1] == ['prim'] and x[1][1] in OPERATIONS)
            proof = representation(call) if operation == 'Index' else representation(call)['components'][1] if operation == 'Read' else representation(call[2][2])
            lanes = 2 if element in ('DoubleElemRep', 'Int64ElemRep') else 4
            proof['primReps'] = [f'VecRep {lanes} {element}']; proof['vector'] = dict(lanes=lanes, element=element)
            detail = {'Index': 'result representation', 'Read': 'read result components', 'Write': 'argument representation'}[operation]
            expected = {('malformed-expression', 'Invalid local vector memory intrinsic: '+detail): 1}
            if operation == 'Index':
                expected.update({('vector-shape', 'Exact vector primitive argument representation required'): 1,
                                 ('aggregate-shape', 'Conflicting or missing logical aggregate representation proofs'): 1})
            report = auditor.Audit([(str(path)+' [MUTATED '+element+' '+family+operation+']', changed)], capabilities).run([family+operation+'Case'])
            negative(report, expected, family+operation)
            result[family+operation] = dict(origin='Mutated proof metadata only; never native input', report=report)
    return result


def negative(report, expected, label):
    check(not report['accepted'] and not report['missingGlobals']
          and Counter((i['code'], i['detail']) for i in report['issues']) == expected,
          label+': wrong exact negative issue multiset')


def record(path):
    return dict(path=str(path.relative_to(ROOT)), sha256=hashlib.sha256(path.read_bytes()).hexdigest())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--export-only', action='store_true', help='Pre-Tidy Core/model only; no native or post-Tidy claim')
    args = parser.parse_args()
    ghc = os.environ.get('GHC', 'ghc')
    check(subprocess.check_output([ghc, '--numeric-version'], text=True).strip() == '9.14.1', 'Requires GHC9.14.1')
    launcher = Path(shutil.which(ghc) or ghc).resolve()
    libdir = Path(subprocess.check_output([ghc, '--print-libdir'], text=True).strip())
    executable = libdir.parent/'bin/ghc-9.14.1'
    check(executable.is_file(), 'Missing actual pinned GHC executable behind launcher')
    check(sys.byteorder == BYTE_ORDER, 'This bounded corpus requires little-endian native order')
    OUT.mkdir(parents=True, exist_ok=True)
    for name in ('provenance.json', 'oracle.tsv', 'snan-expected.tsv', 'snan-oracle.tsv'):
        (OUT/name).unlink(missing_ok=True)
    run_dir = Path(tempfile.mkdtemp(prefix='prepare-run-', dir=OUT))
    commands = []
    def run(argv, env=None, input_text=None):
        argv = [str(x) for x in argv]
        prefix = run_dir/str(len(commands)+1)
        command = dict(argv=argv, environment=env or {}, log=str(prefix)+'.log')
        commands.append(command)
        prefix.with_suffix('.command.json').write_text(json.dumps(command, indent=2)+'\n')
        proc = subprocess.run(argv, cwd=ROOT, env=dict(os.environ, **(env or {})), input=input_text,
                              text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        prefix.with_suffix('.log').write_text(proc.stdout)
        prefix.with_suffix('.stderr.log').write_text(proc.stderr)
        prefix.with_suffix('.exit-status.txt').write_text(str(proc.returncode)+'\n')
        check(proc.returncode == 0, f'Command failed; preserved logs: {prefix}')
        return proc.stdout
    wanted = model_rows()
    expected_text = ''.join('\t'.join(map(str, (*key, answer)))+'\n' for key, answer in wanted.items())
    (OUT/'expected.tsv').write_text(expected_text)
    stages = ['pre'] if args.export_only else ['pre', 'post']
    spec = importlib.util.spec_from_file_location('doublex2_bytearray_auditor', ROOT/'scripts/audit-core.py')
    auditor = importlib.util.module_from_spec(spec); spec.loader.exec_module(auditor)
    capabilities = json.loads((ROOT/'scripts/core-capabilities.json').read_text())
    run(['compiler/build.sh'])
    audits, structure, calls, controls, graph_calls = {}, {}, {}, {}, {}
    artifacts = [OUT/'expected.tsv']
    graph_names = [e['name'] for e in graph_entries()]
    positives = [e['name'] for e in entries()]+graph_names
    for stage in stages:
        path = OUT/f'{stage}-core/SimdDoubleX2ByteArray.json'
        path.unlink(missing_ok=True)
        options = ['-fno-code', '-fwrite-if-simplified-core'] if args.export_only else []
        if stage == 'post': options += ['-fplugin-opt=Thc.Plugin:post-tidy']
        run(['compiler/export.sh', *options, FIXTURE],
            dict(THC_CORE_OUT=str(path.parent), THC_GHC_OUT=str(OUT/f'{stage}-ghc'), THC_SOURCE_NOTES='true'))
        module = json.loads(path.read_text())
        structure[stage] = inventory(module, stage)
        audits[stage] = {name: auditor.Audit([(str(path), module)], capabilities).run([name]) for name in [*positives, *FRONTIERS]}
        for name in positives:
            report = audits[stage][name]
            check(report['accepted'] and not report['issues'] and not report['missingGlobals'], stage+'/'+name+': positive audit rejected')
        for name, expected in FRONTIERS.items(): negative(audits[stage][name], expected, stage+'/'+name)
        for graph_entry in graph_entries():
            name = graph_entry['name']
            report = audits[stage][name]
            binding = next(b for b in module['bindings'] if b['name'] == name)
            check(binding['arity'] == graph_entry['arity'] and len(report['reachableBindings']) == 1,
                  name+': graph root arity/global closure changed')
            validate_worker(binding)
            graph_calls[stage+'/'+name] = len(expressions(binding['expr'], 'lam'))
            check(graph_calls[stage+'/'+name] == 1, name+': graph root must have exactly one actual lambda')
        structure[stage]['entries'] = {}
        for entry in entries():
            facts = guest_structure(entry, audits[stage][entry['name']], module)
            structure[stage]['entries'][entry['name']] = facts
            calls[stage+'/'+entry['name']] = facts['guestCalls']
        controls[stage] = {element: family_controls(module, path, auditor, capabilities, element)
                           for element in ('Int64ElemRep', 'Int32ElemRep', 'Word32ElemRep', 'FloatElemRep')}
        audit_path = OUT/f'{stage}-audit.json'
        audit_path.write_text(json.dumps(audits[stage], indent=2)+'\n')
        artifacts += [path, audit_path]
    native_rows = None
    native_diagnostics = None
    if not args.export_only:
        native = OUT/'native'; native.mkdir(exist_ok=True)
        binary = native/'doublex2-bytearray-oracle'
        run([ghc, '--make', '-O2', '-fforce-recomp', '-dcore-lint', '-icompiler/test-fixtures',
             '-odir', native, '-hidir', native, NATIVE, '-o', binary])
        native_text = run([binary], input_text=''.join('\t'.join(map(str, key))+'\n' for key in wanted))
        (OUT/'oracle.tsv').write_text(native_text)
        actual = parse_rows(native_text)
        check(actual == wanted and native_text == expected_text, 'Native/model corpus mismatch')
        native_rows = len(actual)
        artifacts += [OUT/'oracle.tsv', binary]
        diagnostics = diagnostic_rows()
        diagnostic_expected = ''.join('\t'.join(map(str, (*key, answer)))+'\n' for key, answer in diagnostics.items())
        diagnostic_actual = run([binary], input_text=''.join('\t'.join(map(str, key))+'\n' for key in diagnostics))
        (OUT/'snan-expected.tsv').write_text(diagnostic_expected)
        (OUT/'snan-oracle.tsv').write_text(diagnostic_actual)
        observed = parse_rows(diagnostic_actual)
        check(observed.keys() == diagnostics.keys(), 'Native signaling-NaN diagnostic key set changed')
        differences = [dict(key=key, expected=value, actual=observed[key])
                       for key, value in diagnostics.items() if value != observed[key]]
        native_diagnostics = dict(kind='selected-signaling-NaN/native-only', rows=len(diagnostics),
                                  matches=not differences, differences=differences,
                                  claim='Pinned native observations only; no portable scalar copying/boxing or arithmetic NaN promise')
        artifacts += [OUT/'snan-expected.tsv', OUT/'snan-oracle.tsv']
    sources = [FIXTURE, NATIVE, Path(__file__).resolve(), ROOT/'scripts/doublex2_bytearray_model.py',
               ROOT/'scripts/test-doublex2-bytearray-model.py', ROOT/'scripts/audit-core.py',
               ROOT/'scripts/core-capabilities.json', ROOT/'scripts/core_vectors.py', ROOT/'scripts/core_vector_memory.py',
               ROOT/'src/main/kotlin/thc/runtime/CoreVectorMemory.kt', ROOT/'src/main/java/thc/runtime/DoubleX2.java',
               ROOT/'src/main/resources/thc/scalar-primop-signatures.json',
               *[ROOT/'compiler/Thc'/name for name in ('Cbv.hs', 'Demands.hs', 'Plugin.hs', 'Sources.hs', 'Wired.hs')],
               *[ROOT/'compiler'/name for name in ('build.sh', 'export.sh', 'toolchain.sh')]]
    provenance = dict(schema=1, vector='doublex2-bytearray', stages=stages, modelByteOrder=BYTE_ORDER,
                      nativeByteOrder=None if args.export_only else BYTE_ORDER,
                      nativeRows=native_rows, modelRows=len(wanted), modelMatched=True if native_rows is not None else None,
                      entries=entries(), graphEntries=graph_entries(), positiveAuditsAccepted=True,
                      audits=audits, structure=structure, frontiers=list(FRONTIERS),
                      expectedGuestCallsByEntry=EXPECTED_CALLS, checkedGuestCallsByStage=calls,
                      expectedGraphGuestCallsByEntry={name: 1 for name in graph_names},
                      checkedGraphGuestCallsByStage=graph_calls,
                      guestCountPolicy='Actual retained lambdas: alias2, raw index4 including its scratch runRW, other wrappers3, graph roots1; no settling calls',
                      familyNegativeControls=controls, nativeDiagnostics=native_diagnostics, commands=commands,
                      sources=[record(path) for path in sources], artifacts=[record(path) for path in artifacts],
                      toolchain=dict(ghc=ghc, ghcVersion='9.14.1', architecture=platform.machine(), byteOrder=sys.byteorder,
                                     host=platform.node(), machine=platform.machine(), system=platform.platform(),
                                     ghcInfo=subprocess.check_output([ghc, '--info'], text=True),
                                     ghcBinaryPath=str(executable), ghcBinarySha256=hashlib.sha256(executable.read_bytes()).hexdigest(),
                                     ghcLauncherPath=str(launcher), ghcLauncherSha256=hashlib.sha256(launcher.read_bytes()).hexdigest()),
                      claim=('Six local DoubleX2 ByteArray primitives; native/model, exact Core metadata and strict audits; no JVM graph claim'
                             if native_rows is not None else 'Pre-Tidy Core and byte model only; NO native/post-Tidy validation'),
                      limitations=['Little-endian 64-bit native corpus only', 'No Addr or general vector/aggregate ABI; source hashes are selected inputs, not a complete transitive runtime inventory',
                                   'No native pointer-identity claim; JVM must check actual returned array identity',
                                   '16 rotating raw patterns and 16 finite graph patterns are bounded, not exhaustive encodings',
                                   'Portable raw movement excludes signaling NaNs; arithmetic NaN payload preservation is not claimed',
                                   'Native signaling-NaN diagnostics are not portable JVM corpus rows'])
    encoded = json.dumps(provenance, indent=2)+'\n'
    (OUT/'provenance.json').write_text(encoded)
    (run_dir/'provenance.json').write_text(encoded)  # Retain each full/export-only mode snapshot.
    copies = []
    for item in artifacts:
        target = run_dir/'artifacts'/item.relative_to(OUT)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(item, target)
        copies.append(dict(original=str(item.relative_to(ROOT)), **record(target)))
    (run_dir/'artifact-copies.json').write_text(json.dumps(copies, indent=2)+'\n')
    print(f'PASS doublex2-bytearray: model={len(wanted)}, native={native_rows}, stages={stages}, sourceHashes={len(sources)}, artifactHashes={len(artifacts)}')


if __name__ == '__main__':
    main()
