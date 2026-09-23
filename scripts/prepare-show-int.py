#!/usr/bin/env python3
"""Fresh public Show Int oracle and full, pinned original Show source export."""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
from show_int_model import ENTRIES, inputs, requests, verify

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'build/show-int'
WORKER = "ghc-internal:GHC.Internal.Show.$fShowCallStack_itos'"
STAGES = {'pre': 'optimized-Core-before-Tidy', 'post': 'optimized-Core-after-Tidy-before-CorePrep'}
FIXTURES = [ROOT / 'compiler/test-fixtures' / name for name in ('ShowIntAudit.hs', 'ShowIntAuditNative.hs')]

def check(condition, message):
    if not condition:
        raise AssertionError(message)
def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()
def record(path):
    return dict(path=str(path.relative_to(ROOT)), sha256=digest(path))
def audit_inputs():
    return [ROOT/'scripts/audit-core.py', ROOT/'scripts/core-capabilities.json', *sorted((ROOT/'scripts').glob('core_*.py')),
            ROOT/'src/main/resources/thc/scalar-primop-signatures.json', ROOT/'scripts/generate-scalar-signatures.py']
def auditor():
    spec = importlib.util.spec_from_file_location('audit_core', ROOT/'scripts/audit-core.py')
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module, json.loads((ROOT/'scripts/core-capabilities.json').read_text())
def inventory():
    audit, caps = auditor()
    source_path = OUT/'boot/core/GHC.Internal.Show.json'
    source = json.loads(source_path.read_text())
    check(source['ghc'] == '9.14.1' and source['boundary'] == STAGES['post'], 'Show must be genuine post-Tidy GHC9.14.1 source')
    source_ids = {b['id'] for b in source['bindings']}
    worker = [b for b in source['bindings'] if b['id'] == WORKER]
    check(len(worker) == 1 and worker[0]['arity'] == 2 and worker[0]['expr'][0] == 'lam', 'Missing exact installed Show digit worker')
    check(source.get('sourceCore') and source.get('sourceSpans'), 'Complete original source evidence is required')
    stages = {}; coverage = {}
    for stage, boundary in STAGES.items():
        public_path = OUT/f'{stage}-core/ShowIntAudit.json'
        closure_path = OUT/f'{stage}-core/THC.InterfaceClosure.json'
        public = json.loads(public_path.read_text()); closure = json.loads(closure_path.read_text())
        check(public['ghc'] == '9.14.1' and public['boundary'] == boundary, f'{stage}: wrong public export boundary')
        interface_ids = {b['id'] for b in closure['bindings']}
        check(interface_ids and interface_ids <= source_ids, 'Whole interface closure must be supplied by the complete original Show module')
        check(all(b.get('origin') in ('interface-core-unfolding', 'interface-dfun-unfolding') for b in closure['bindings']), 'Expected genuine installed interface definitions')
        original = audit.Audit([(str(public_path), public), (str(closure_path), closure)], caps).run(list(ENTRIES))
        check(not original['accepted'] and not original['issues'] and [m['id'] for m in original['missingGlobals']] == [WORKER],
              f'{stage}: public-only frontier changed: {original["summary"]}')
        (OUT/f'{stage}-missing-worker.audit.json').write_text(json.dumps(original, indent=2)+'\n')
        paths = [public_path, source_path]  # Whole source module replaces its whole redundant interface file.
        stages[stage] = [str(p.relative_to(ROOT)) for p in paths]
        reports = {}
        for entry in ENTRIES:
            report = audit.Audit([(str(public_path), public), (str(source_path), source)], caps).run([entry])
            check(report['accepted'], f'{stage}/{entry}: strict Show source closure rejected: {report["summary"]}')
            check(WORKER in {b['id'] for b in report['reachableBindings']}, f'{entry}: actual digit worker disappeared')
            (OUT/f'{stage}-{entry}.audit.json').write_text(json.dumps(report, indent=2)+'\n')
            reports[entry] = dict(reachable=len(report['reachableBindings']), missing=0, issues=0)
        coverage[stage] = dict(replacedWholeInterfaceBindingIds=sorted(interface_ids), entries=reports)
    return stages, coverage

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check-only', action='store_true')
    args = parser.parse_args()
    manifest_path = OUT/'manifest.json'
    if not args.check_only:
        ghc = os.environ.get('GHC', 'ghc'); ghc_pkg = os.environ.get('GHC_PKG', 'ghc-pkg')
        check(subprocess.check_output([ghc, '--numeric-version'], text=True).strip() == '9.14.1', 'Requires pinned GHC9.14.1')
        (OUT/'native').mkdir(parents=True, exist_ok=True)
        commands = []
        def run(argv, extra_env=None):
            commands.append(dict(argv=argv, environment=extra_env or {}))
            subprocess.run(argv, cwd=ROOT, env=dict(os.environ, **(extra_env or {})), check=True)
        run(['compiler/build.sh'])
        run([sys.executable, 'compiler/export-boot.py', '--frontier', 'show', '--build-dir', str(OUT/'boot')])
        for stage in STAGES:
            run(['compiler/export.sh', *(['-fplugin-opt=Thc.Plugin:post-tidy'] if stage == 'post' else []),
                 *['-fplugin-opt=Thc.Plugin:closure='+name for name in ENTRIES], str(FIXTURES[0])],
                dict(THC_CORE_OUT=str(OUT/f'{stage}-core'), THC_GHC_OUT=str(OUT/f'{stage}-ghc'), THC_SOURCE_NOTES='true'))
        stages, coverage = inventory()
        run([ghc, '--make', '-O2', '-fforce-recomp', '-dcore-lint', '-dstg-lint', '-icompiler/test-fixtures',
             '-odir', str(OUT/'native'), '-hidir', str(OUT/'native'), '-o', str(OUT/'native/show-int-oracle'), str(FIXTURES[1])])
        text = ''.join(f'{name}\t{x}\t{i}\n' for name,x,i in requests())
        (OUT/'requests.tsv').write_text(text)
        commands.append(dict(argv=[str(OUT/'native/show-int-oracle')], stdin=str(OUT/'requests.tsv'), stdout=str(OUT/'oracle.tsv')))
        result = subprocess.run([str(OUT/'native/show-int-oracle')], input=text, text=True, capture_output=True, check=True)
        (OUT/'oracle.tsv').write_text(result.stdout)
        rows = verify(result.stdout)
        sources = [*FIXTURES, Path(__file__).resolve(), ROOT/'scripts/show_int_model.py', ROOT/'compiler/export-boot.py',
                   ROOT/'compiler/build.sh', ROOT/'compiler/export.sh', ROOT/'compiler/toolchain.sh',
                   *sorted((ROOT/'compiler/Thc').glob('*.hs')), *audit_inputs(),
                   ROOT/'vendor/ghc-9.14.1/GHC/Internal/Show.hs', ROOT/'vendor/ghc-9.14.1/LICENSE']
        artifacts = [OUT/'requests.tsv', OUT/'oracle.tsv', OUT/'boot/boot-provenance.json', *sorted(OUT.glob('*.audit.json'))]
        artifacts += [p for folder in ('pre-core', 'post-core', 'boot/core', 'native') for p in sorted((OUT/folder).rglob('*')) if p.is_file()]
        installed = Path(subprocess.check_output([ghc_pkg, 'field', 'ghc-internal', 'import-dirs', '--simple-output'], text=True).strip())/'GHC/Internal/Show.dyn_hi'
        manifest_path.write_text(json.dumps(dict(schema=1, recordedAtUtc=datetime.now(timezone.utc).isoformat(), commands=commands,
            ghcInfo=subprocess.check_output([ghc, '--info'], text=True), installedShowInterface=dict(path=str(installed), sha256=digest(installed)),
            wordBits=64, entries=list(ENTRIES), inputs=inputs(), nativeRows=len(rows), stages=stages, coverage=coverage,
            sources=[record(p) for p in sources], artifacts=[record(p) for p in artifacts],
            claim='Complete pinned original Show source; public native results and independent decimal/character model. Runtime compiled gates are separate.'), indent=2)+'\n')
    manifest = json.loads(manifest_path.read_text())
    check({str(p.relative_to(ROOT)) for p in audit_inputs()} <= {r['path'] for r in manifest['sources']}, 'Missing auditor source fingerprint')
    for item in manifest['sources'] + manifest['artifacts']:
        check(record(ROOT/item['path']) == item, 'Stale Show fixture: '+item['path'])
    rows = verify((OUT/'oracle.tsv').read_text())
    stages, coverage = inventory()
    check(stages == manifest['stages'] and coverage == manifest['coverage'] and len(rows) == manifest['nativeRows'], 'Stale Show inventory')
    print(f'Show Int: {len(rows)} native/model rows, {len(inputs())} inputs, exact digit-by-digit observations, 4 strict audits and 2 missing-source controls')

if __name__ == '__main__':
    main()
