#!/usr/bin/env python3
"""Fresh native values and complete original-source closure for public byte slices."""
import argparse
import ast
import hashlib
import importlib.util
import json
import os
import re
from pathlib import Path
import subprocess
import sys
from short_bytes_slice_model import ENTRIES, seeds, requests, verify

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT/'build/short-bytes-slices'
STAGES = {'pre': 'optimized-Core-before-Tidy', 'post': 'optimized-Core-after-Tidy-before-CorePrep'}
FRONTIERS = ('appendFrontier', 'concatFrontier')
OVERFLOW_WORKER = ':Data.ByteString.Internal.Type.overflowError'
LENGTH = 'ghc-internal:GHC.Internal.List.$wlenAcc'
FIXTURES = [ROOT/'compiler/test-fixtures'/name for name in ('ShortByteStringSliceAudit.hs', 'ShortByteStringSliceAuditNative.hs')]

def check(ok, message):
    if not ok: raise AssertionError(message)

def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def record(path): return dict(path=str(path.relative_to(ROOT)), sha256=digest(path))
def audit_inputs():
    return [ROOT/'scripts/audit-core.py', ROOT/'scripts/core-capabilities.json', *sorted((ROOT/'scripts').glob('core_*.py')),
            ROOT/'src/main/resources/thc/scalar-primop-signatures.json']

def overflow_id(unit):
    check(isinstance(unit, str) and re.fullmatch(r'bytestring-0\.12\.2\.0(?:-[A-Za-z0-9]+)?', unit),
          'Requires one exact installed bytestring0.12.2.0 unit id')
    return unit+OVERFLOW_WORKER

def check_frontier(report, unit, label):
    expected = overflow_id(unit)
    check(not report['accepted'] and not report['issues'] and
          [m['id'] for m in report['missingGlobals']] == [expected],
          label+': exact unimplemented overflow frontier changed')

def inventory(unit=None):
    if unit is None:
        unit = json.loads((OUT/'manifest.json').read_text())['installedBytestringUnitId']
    overflow_id(unit)
    spec = importlib.util.spec_from_file_location('slice_audit', ROOT/'scripts/audit-core.py')
    audit = importlib.util.module_from_spec(spec); spec.loader.exec_module(audit)
    caps = json.loads((ROOT/'scripts/core-capabilities.json').read_text())
    originals = [OUT/'list/core/GHC.Internal.List.json', OUT/'cstring/core/GHC.Internal.CString.json']
    original_modules = [(str(p), json.loads(p.read_text())) for p in originals]
    for path, module in original_modules:
        check(module['ghc'] == '9.14.1' and module['boundary'] == STAGES['post'] and module.get('sourceCore') and module.get('sourceSpans'),
              'Complete original post-Tidy source evidence required: '+path)
    stages, coverage = {}, {}
    for stage, boundary in STAGES.items():
        paths = sorted((OUT/f'{stage}-core').glob('*.json'))
        modules = [(str(p), json.loads(p.read_text())) for p in paths]
        fixture = next(m for _,m in modules if m['module'] == 'ShortByteStringSliceAudit')
        check(fixture['boundary'] == boundary, 'Wrong public Core boundary')
        closure = next(m for _,m in modules if m['module'] == 'THC.InterfaceClosure')
        check(all(b.get('origin') in ('interface-core-unfolding', 'interface-dfun-unfolding') for b in closure['bindings']), 'Interface bodies must be genuine unfoldings')
        ids = [b['id'] for _,m in [*modules, *original_modules] for b in m['bindings']]
        check(len(ids) == len(set(ids)), 'Original whole-module composition must not duplicate or replace bindings')
        reports = {}
        for name in (*ENTRIES, *FRONTIERS):
            report = audit.Audit([*modules, *original_modules], caps).run([name])
            if name in ENTRIES:
                check(report['accepted'] and not report['issues'] and not report['missingGlobals'], stage+'/'+name+': strict slice closure rejected')
                check(LENGTH in {b['id'] for b in report['reachableBindings']}, 'Actual installed pack/List length dependency disappeared')
                check('copyByteArray#' in {p['name'] for p in report['primitives']}, 'Actual slice copy path disappeared')
                public = next(b for b in fixture['bindings'] if b['name'] == name)
                check(public['arity'] == 4 and public['expr'][0] == 'lam' and len(public['expr'][1]) == 4, 'Changed scalar host ABI')
                check(all(p['rep']['kind'] == 'long' and p['rep']['primReps'] == ['IntRep'] for p in public['expr'][1]), 'Inexact scalar input proof')
                missing_list = audit.Audit([*modules, original_modules[1]], caps).run([name])
                check(not missing_list['accepted'] and not missing_list['issues'] and
                      [m['id'] for m in missing_list['missingGlobals']] == [LENGTH], 'Missing-source control changed')
                (OUT/f'{stage}-{name}-missing-list.audit.json').write_text(json.dumps(missing_list, indent=2)+'\n')
            else:
                check_frontier(report, unit, stage+'/'+name)
            (OUT/f'{stage}-{name}.audit.json').write_text(json.dumps(report, indent=2)+'\n')
            reports[name] = dict(accepted=report['accepted'], reachable=report['reachableBindings'],
                                 primitives=[p['name'] for p in report['primitives']], missing=report['missingGlobals'], issues=report['issues'])
        stages[stage] = [str(p.relative_to(ROOT)) for p in [*paths, *originals]]
        coverage[stage] = reports
    return stages, coverage

def main():
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('--check-only', action='store_true'); args = parser.parse_args()
    manifest_path = OUT/'manifest.json'
    if not args.check_only:
        ghc = os.environ.get('GHC', 'ghc'); pkg = os.environ.get('GHC_PKG', 'ghc-pkg')
        check(subprocess.check_output([ghc,'--numeric-version'],text=True).strip() == '9.14.1', 'Requires GHC9.14.1')
        ghc_info = subprocess.check_output([ghc,'--info'],text=True)
        check(dict(ast.literal_eval(ghc_info))['target word size in bits'] == '64', 'Requires 64-bit machine Int')
        check(subprocess.check_output([pkg,'field','bytestring','version','--simple-output'],text=True).strip() == '0.12.2.0', 'Requires bytestring0.12.2.0')
        unit = subprocess.check_output([pkg,'field','bytestring','id','--simple-output'],text=True).strip()
        overflow_id(unit)
        (OUT/'native').mkdir(parents=True, exist_ok=True); manifest_path.unlink(missing_ok=True); commands=[]
        def run(argv, env=None):
            argv=list(map(str,argv)); commands.append(dict(argv=argv, environment=env or {}))
            subprocess.run(argv, cwd=ROOT, env=dict(os.environ, **(env or {})), check=True)
        run(['compiler/build.sh'])
        for frontier, directory in [('lists','list'),('exceptions','cstring')]:
            run([sys.executable,'compiler/export-boot.py','--frontier',frontier,'--build-dir',OUT/directory])
        for stage in STAGES:
            run(['compiler/export.sh',*(['-fplugin-opt=Thc.Plugin:post-tidy'] if stage=='post' else []),
                 *['-fplugin-opt=Thc.Plugin:closure='+n for n in (*ENTRIES,*FRONTIERS)],FIXTURES[0]],
                dict(THC_CORE_OUT=str(OUT/f'{stage}-core'),THC_GHC_OUT=str(OUT/f'{stage}-ghc'),THC_SOURCE_NOTES='true'))
        stages, coverage = inventory(unit)
        binary=OUT/'native/short-bytes-slices-oracle'
        run([ghc,'--make','-O2','-fforce-recomp','-dcore-lint','-dstg-lint','-icompiler/test-fixtures',
             '-odir',OUT/'native','-hidir',OUT/'native',FIXTURES[1],'-o',binary])
        text=''.join('\t'.join(map(str,key))+'\n' for key in requests());(OUT/'requests.tsv').write_text(text)
        commands.append(dict(argv=[str(binary)],stdin=str(OUT/'requests.tsv'),stdout=str(OUT/'oracle.tsv')))
        native=subprocess.run([str(binary)],input=text,text=True,capture_output=True,check=True);(OUT/'oracle.tsv').write_text(native.stdout)
        count=verify(native.stdout)
        sources=[*FIXTURES,Path(__file__).resolve(),ROOT/'scripts/short_bytes_slice_model.py',ROOT/'compiler/export-boot.py',
                 *[ROOT/'compiler'/n for n in ('build.sh','export.sh','toolchain.sh')],*sorted((ROOT/'compiler/Thc').glob('*.hs')),*audit_inputs()]
        for d in ('list','cstring'):
            sources += [ROOT/r['path'] for r in json.loads((OUT/d/'boot-provenance.json').read_text())['sources']]
        artifacts=[OUT/'requests.tsv',OUT/'oracle.tsv',*sorted(OUT.glob('*.audit.json')),
                   *[OUT/d/'boot-provenance.json' for d in ('list','cstring')]]
        artifacts += [p for d in ('pre-core','post-core','list/core','cstring/core','native') for p in sorted((OUT/d).rglob('*')) if p.is_file()]
        installed=Path(subprocess.check_output([pkg,'field','bytestring','import-dirs','--simple-output'],text=True).strip())/'Data/ByteString/Short/Internal.dyn_hi'
        manifest_path.write_text(json.dumps(dict(schema=1,entries=ENTRIES,seeds=seeds(),nativeRows=count,wordBits=64,
            stages=stages,coverage=coverage,commands=commands,ghcInfo=ghc_info,
            installedBytestringUnitId=unit,
            installedShortInterface=dict(path=str(installed),sha256=digest(installed)),
            sources=[record(p) for p in dict.fromkeys(sources)],artifacts=[record(p) for p in dict.fromkeys(artifacts)],
            claim='Public native slices and independent byte/list model with complete original List/CString composition; compiled guest execution is a separate gate.'),indent=2)+'\n')
    manifest=json.loads(manifest_path.read_text())
    check(set(map(str,ENTRIES)) == set(manifest['entries']), 'Entry set changed')
    for r in [*manifest['sources'],*manifest['artifacts']]: check(record(ROOT/r['path']) == r, 'Stale slice input: '+r['path'])
    installed_unit = subprocess.check_output([os.environ.get('GHC_PKG', 'ghc-pkg'),'field','bytestring','id','--simple-output'],text=True).strip()
    check(installed_unit == manifest['installedBytestringUnitId'], 'Installed bytestring unit changed')
    installed=manifest['installedShortInterface'];check(digest(Path(installed['path'])) == installed['sha256'], 'Installed bytestring interface changed')
    count=verify((OUT/'oracle.tsv').read_text());stages,coverage=inventory()
    check(stages == manifest['stages'] and coverage == manifest['coverage'] and count == manifest['nativeRows'], 'Slice evidence changed')
    print(f'ShortByteString slices: {count} native/model rows; 6 accepted audits, 4 exact overflow frontiers, 6 missing-List controls')
if __name__ == '__main__': main()
