#!/usr/bin/env python3
"""Reduce checked graph runs to reviewable evidence; keep BGV/CFG outside git."""
import collections,hashlib,json,os,pathlib,subprocess,sys
base,runtime,destination=map(pathlib.Path,sys.argv[1:4]); destination.mkdir(parents=True,exist_ok=True)
runtime_revision=sys.argv[4] if len(sys.argv)>4 else 'HEAD'
runtime_commit=subprocess.check_output(['git','-C',str(runtime),'rev-parse',runtime_revision],text=True).strip()
here=pathlib.Path(__file__).resolve().parent
entries=('pairCase','forwardedCase','outstandingCase','mixedCase','lazyMixedCase')
records=[]; excerpts=[]
for mode,prefix in (('inline','runtime'),('residual','residual')):
    for backend in ('ast','bytecode'):
        for entry in entries:
            out=base/f'{prefix}-{backend}-{entry}'
            g=json.loads((out/'graph-evidence.json').read_text())
            phases={}
            for name,phase in g['phases'].items():
                phases[name]={'nodes':phase['nodes'], 'invokes':phase['invokes'],
                    'allocationRelatedNodes':phase['allocationRelatedNodes'],
                    'memoryLocations':dict(sorted(collections.Counter(str(m['location']) for m in phase['memoryOperations']).items()))}
            records.append({'backend':backend,'entry':entry,'mode':mode,'checks':g['checks'],
                'bgvSha256':g['bgvSha256'],'cfgSha256':g['cfgSha256'],'phases':phases})
            if mode=='inline' and entry in ('pairCase','mixedCase'):
                excerpts.append(f'=== {backend} / {entry}: actual After FinalCodeAnalysisStage ===')
                # Full LIR remains in each run output. Retain the arithmetic,
                # final ABI return and any residual call, without presenting
                # this excerpt as a substitute for the audited phase graphs.
                excerpts.extend(line for line in (out/'final-lir.txt').read_text().splitlines()
                    if any(token in line for token in (' = ADD ',' = SUB ',' = MUL ',' = MADD ',' = LSL ','RETURN','CALL_DIRECT')))
files=[p for p in sorted(here.iterdir()) if p.is_file() and p.suffix in ('.java','.hs','.sh','.py')]
runtime_files=[runtime/'src/main/kotlin/thc/runtime'/name for name in
               ('TupleResults.kt','Program.kt','BytecodeProgram.kt','Handoff.kt','GuestRoot.kt','CoreRepresentations.kt')]
runtime_files.extend([runtime/'src/main/java/thc/runtime/BytecodeRoot.java',runtime/'src/main/kotlin/thc/Language.kt'])
java_home=pathlib.Path(os.environ['JAVA_HOME'])
evidence={'javaVersion':subprocess.check_output([str(java_home/'bin/java'),'-version'],stderr=subprocess.STDOUT,text=True).splitlines(),
          'jdkReleaseSha256':hashlib.sha256((java_home/'release').read_bytes()).hexdigest(),
          'scope':'Production THC AST/bytecode, genuine opaque GHC Core, fixed native inputs; no timing',
          'runtimeSourceCommit':runtime_commit,
          'runtimeSourceSha256':{str(p.relative_to(runtime)):hashlib.sha256(subprocess.check_output(['git','-C',str(runtime),'show',runtime_commit+':'+str(p.relative_to(runtime))])).hexdigest() for p in runtime_files},
          'runtimeJarSha256':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((runtime/'build/install/thc/lib').glob('thc-*.jar'))},
          'probeSourceSha256':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in files},
          'fixture':json.loads((base/'runtime-fixture/fixture-evidence.json').read_text()),
          'results':records,
          'limitations':['Final scalar Long host-result box remains', 'No multiregister machine-call ABI',
                         'No inference about application-wide inlining frequency',
                         'Protocol ownership stress tests are separate']}
(destination/'evidence.json').write_text(json.dumps(evidence,indent=2)+'\n')
(destination/'lir-excerpts.txt').write_text('\n'.join(excerpts)+'\n')
(destination/'native-oracle.tsv').write_bytes((base/'runtime-fixture/oracle.tsv').read_bytes())
print('Collected',len(records),'compiled runtime graph checks')
