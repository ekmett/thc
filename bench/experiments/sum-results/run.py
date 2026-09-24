#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Native-backed production sum graphs with fixed inputs and normal compiler limits."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT=Path(__file__).resolve().parents[3]
HERE=Path(__file__).resolve().parent
EXPORTS=['--add-modules','jdk.graal.compiler','--add-exports','jdk.graal.compiler/jdk.graal.compiler.graphio.parsing=ALL-UNNAMED',
         '--add-exports','jdk.graal.compiler/jdk.graal.compiler.graphio.parsing.model=ALL-UNNAMED']
CASES=[(backend,mode,entry) for backend in ('ast','bytecode') for mode in ('inline','residual') for entry in ('pairedInputs','lazyLeafCase')]
def digest(p): return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def require(c,m):
    if not c: raise ValueError(m)
def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('output',type=Path)
    parser.add_argument('--collect-only',action='store_true');parser.add_argument('--capture',type=Path);args=parser.parse_args()
    out=args.output.resolve();java=Path(os.environ['JAVA_HOME']);classes=out/'classes';cp=str(classes)+':'+str(ROOT/'build/install/thc/lib/*')
    module=ROOT/'build/sum-result/pre-core/SumResultAudit.json';manifest=ROOT/'build/sum-result/provenance.json'
    tools=[HERE/'run.py',HERE/'summarize.py',HERE/'SumGraphProbe.java',ROOT/'tools/GraphInspect.java',ROOT/'gradlew',ROOT/'Makefile']
    commands=[]
    def run(argv,log=None):
        argv=list(map(str,argv));commands.append(argv);(out/'commands.json').write_text(json.dumps(commands,indent=2)+'\n')
        if log:
            with log.open('w') as stream: subprocess.run(argv,cwd=ROOT,stdout=stream,stderr=subprocess.STDOUT,check=True)
        else: subprocess.run(argv,cwd=ROOT,check=True)
    if not args.collect_only:
        out.mkdir(parents=True,exist_ok=False);classes.mkdir()
        revision=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
        paths=subprocess.check_output(['git','ls-files','src/main','build.gradle.kts','settings.gradle.kts','gradle.properties','gradle','gradlew','gradlew.bat'],cwd=ROOT,text=True).splitlines()
        sources={p:digest(ROOT/p) for p in paths}
        for path,sha in sources.items():
            require(hashlib.sha256(subprocess.check_output(['git','show',revision+':'+path],cwd=ROOT)).hexdigest()==sha,'Commit source before graph capture: '+path)
        run(['python3',ROOT/'scripts/prepare-sum-result-audit.py','--check-existing'])
        run([ROOT/'gradlew','--offline','--no-daemon','installDist'],out/'installDist.log')
        require(sources=={p:digest(ROOT/p) for p in paths},'Runtime changed during build')
        inputs=tools+[module,manifest,ROOT/'build/sum-result/oracle.tsv',ROOT/'build/sum-result/oracle-pairs.tsv',java/'release',*sorted((ROOT/'build/install/thc/lib').glob('*.jar'))]
        launch=dict(runtimeCommit=revision,runtimeSourceSha256=sources,inputSha256={str(p.resolve()):digest(p) for p in inputs},
                    javaVersion=subprocess.check_output([java/'bin/java','-version'],stderr=subprocess.STDOUT,text=True).splitlines(),nativeProvenance=json.loads(manifest.read_text()))
        (out/'launch.json').write_text(json.dumps(launch,indent=2)+'\n')
        run([java/'bin/javac','-cp',cp,'-d',classes,HERE/'SumGraphProbe.java'])
        run([java/'bin/javac',*EXPORTS,'-d',classes,ROOT/'tools/GraphInspect.java'])
        for backend,mode,entry in CASES:
            case=out/'-'.join((backend,mode,entry));case.mkdir()
            oracle=ROOT/'build/sum-result'/('oracle-pairs.tsv' if entry=='pairedInputs' else 'oracle.tsv')
            run([java/'bin/java','--enable-native-access=ALL-UNNAMED','--add-modules=jdk.incubator.vector','-XX:+UseCompactObjectHeaders',
                 '-Djdk.graal.Dump=Truffle:2','-Djdk.graal.PrintGraph=File','-Djdk.graal.PrintGraphWithSchedule=true',
                 '-Djdk.graal.PrintBackendCFG=true','-Djdk.graal.DumpPath='+str(case/'graphs'),'-cp',cp,
                 'SumGraphProbe',module,oracle,entry,backend,mode],case/'run.log')
            for graph in (case/'graphs').glob('*.bgv'):
                run([java/'bin/java','-XX:-UseJVMCICompiler',*EXPORTS,'-cp',classes,'GraphInspect',graph,
                     case/('parsed-'+graph.stem),'(Before phase HighTierLowering|After low tier)'],case/('parse-'+graph.stem+'.log'))
            run(['python3',HERE/'summarize.py',case,mode])
    launch=json.loads((out/'launch.json').read_text())
    for path,sha in launch['inputSha256'].items():require(digest(path)==sha,'Capture input changed: '+path)
    records=[]
    for backend,mode,entry in CASES:
        case=out/'-'.join((backend,mode,entry));graph=json.loads((case/'graph-evidence.json').read_text())
        arity,rows=(2,7) if entry=='pairedInputs' else (1,10)
        expected=f'PASS entry={entry} backend={backend} mode={mode} nativeRows={rows} arity={arity} validAfterEveryRow=true'
        require(graph['mode']==mode and graph['checks']==[expected],'Incorrect native graph case: '+str(case))
        raw=case/'graphs'/graph['entryGraph'];cfg=raw.with_suffix('.cfg')
        require(digest(raw)==graph['bgvSha256'] and digest(cfg)==graph['cfgSha256'],'Raw graph hash changed: '+str(case))
        text=cfg.read_text();marker='  name "After FinalCodeAnalysisStage"';require(marker in text,'Missing physical LIR')
        block=text[text.index(marker):].split('\nbegin_cfg',1)[0]
        lir='\n'.join(line.strip() for line in block.splitlines() if 'instruction ' in line)+'\n'
        require((case/'final-lir.txt').read_text()==lir,'LIR differs from CFG')
        records.append(dict(backend=backend,mode=mode,entry=entry,graph=graph,finalLir= lir))
    if args.capture:
        args.capture.mkdir(parents=True,exist_ok=True)
        evidence=dict(scope='Actual pre-Tidy exported Core with fresh native inputs; no timing',launch=launch,results=records,
                      limitations=['Graph instrumentation disabled; exact active target validity is distinct from per-row compiled-entry counters',
                                   'Instrumented JVM tests separately enforce exact compiled-entry counts at both export stages',
                                   'Scalar Long Object host-result boxing remains', 'Residual calls use existing Object ABI; no additional return registers or no-spill guarantee'])
        (args.capture/'evidence.json').write_text(json.dumps(evidence,indent=2)+'\n')
        for name in ('oracle.tsv','oracle-pairs.tsv'):(args.capture/name).write_bytes((ROOT/'build/sum-result'/name).read_bytes())
    print('PASS',len(records),'native-backed production sum graph controls')
if __name__=='__main__':main()
