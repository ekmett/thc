from pathlib import Path
import subprocess,json,os,datetime
root=Path.cwd();out=root/'work/defaults-validation';base=Path('/Users/ekmett/thc/work/boxed-value-cache-v7');java='/Users/ekmett/cadenza/.toolchains/graalvm-25.3.4.1+1.1/Contents/Home/bin/java'
expected=[line.split('\t')[1:] for line in (base/'map/oracle.tsv').read_text().splitlines() if line];assert len(expected)==18
records=[]
for backend in ['ast','bytecode']:
 argv=[java,'-XX:+UseCompactObjectHeaders','--enable-native-access=ALL-UNNAMED','-Xss2m','-Dpolyglot.compiler.InliningRecursionDepth=2','-Dpolyglot.compiler.InliningExpansionBudget=12000','-Dpolyglot.compiler.InliningInliningBudget=12000','-Dthc.backend='+backend,'-Dthc.diagnosticUnsupported=true','-Dthc.sourceNotesEnabled=true','-Dthc.traceCompilation=true','-cp',str(root/'build/install/thc/lib/*'),'thc.MapCheckKt',str(base/'map/modules.txt'),str(base/'map/oracle.tsv')]
 env=os.environ.copy();env.pop('JAVA_TOOL_OPTIONS',None);path=out/('map-default-'+backend+'.log');started=datetime.datetime.now(datetime.timezone.utc).isoformat()
 with path.open('w') as log:p=subprocess.run(argv,env=env,stdout=log,stderr=subprocess.STDOUT,timeout=180)
 lines=path.read_text().splitlines();rows={phase:[line.split('\t')[2:] for line in lines if line.startswith('VERIFIED_MAP\t'+phase+'\t')] for phase in ['before-requested-compilation','after-requested-compilation']}
 ds=[json.loads(line.removeprefix('MAP_DIAGNOSTICS ')) for line in lines if line.startswith('MAP_DIAGNOSTICS ')]
 passed=p.returncode==0 and all(v==expected for v in rows.values()) and len(ds)==1 and ds[0]['unsupportedTraps']==0
 record={'backend':backend,'argv':argv,'started':started,'returncode':p.returncode,'rows':{k:len(v) for k,v in rows.items()},'diagnostics':ds[0] if ds else None,'passed':passed};records.append(record)
 (out/'map-checks.json').write_text(json.dumps(records,indent=2)+'\n');print(backend,passed,flush=True);assert passed
