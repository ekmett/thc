from pathlib import Path
import datetime,json,os,subprocess,hashlib
r=Path('/home/ekmett/thc-benchmarks/2026-09-23-083914');tool=r/'diagnostics/caller-demand-tools';out=r/'diagnostics/caller-demand-default';out.mkdir(exist_ok=False)
def sha(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(1048576),b''):h.update(b)
 return h.hexdigest()
def now():return datetime.datetime.now(datetime.timezone.utc).isoformat()
assert json.loads((r/'results/caller-demand-default/validation.json').read_text())['passed']
assert json.loads((r/'provenance/caller-demand-preflight.json').read_text())['passed']
for f in json.loads((tool/'manifest.json').read_text())['files']:assert sha(tool/f['path'])==f['sha256']
proof=json.loads((r/'provenance/call-demand-structural-proof.json').read_text());assert proof['passed'];expected={x['file']:x for x in proof['modules']};aliases={}
for label,source,mode in [('control','call-demand-control-v1','off'),('enabled','call-demand-v1','on')]:
 src=r/'sources'/source;dest=out/('input-'+label);(dest/'lib').mkdir(parents=True);(dest/'map').mkdir()
 for p in (src/'lib').glob('*.jar'):os.link(p,dest/'lib'/p.name)
 for p in (src/'map').glob('*.json'):assert sha(p)==expected[p.name][mode];os.link(p,dest/'map'/p.name)
 (dest/'map/modules.txt').write_text('\n'.join(str(dest/'map'/Path(x).name) for x in (src/'map/modules-remote.txt').read_text().splitlines())+'\n')
 manifest={'schema':1,'scope':'Immutable diagnostic aliases of the exact completed benchmark runtime/Core; original frozen source manifest retained separately.','originalManifest':{'path':str(src/'manifest.json'),'sha256':sha(src/'manifest.json')},'runtimeJarSha256':sha(dest/'lib/thc-0.1-experiment.jar'),'files':[{'path':p.relative_to(dest).as_posix(),'sha256':sha(p),'bytes':p.stat().st_size} for p in sorted(dest.rglob('*')) if p.is_file()]}
 (dest/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n');aliases[label]=dest
oracle=r/'results/caller-demand-default/oracle.tsv';java=r/'toolchains/graalvm-25.3.4.1+1.1';flags=['-Xms4g','-Xmx4g','-XX:+UseCompactObjectHeaders','-Dpolyglot.compiler.InliningPolicy=Default']+['-Dthc.'+key+'='+str(key=='classOwnedLayouts').lower() for key in ['classOwnedLayouts','typedCases','leadingCaseReturn','constructorClassIdentity','staticShapeUnchecked','boxedValueCache']]
state={'started':now(),'status':'running','scope':'Sequential diagnostic allocation/JFR and actual worker graphs, not throughput. Full18 input oracle proof is the just-completed unchanged input preflight.','preflightProof':{'path':str(r/'provenance/caller-demand-preflight.json'),'sha256':sha(r/'provenance/caller-demand-preflight.json')},'toolManifestSha256':sha(tool/'manifest.json'),'runs':[]}
def save():(out/'driver.json').write_text(json.dumps(state,indent=2)+'\n')
for mode in ['profile','graph']:
 for label,src in aliases.items():
  target=out/(mode+'-'+label);argv=['taskset','-c','0-15','python3','-u',str(tool/'work/boxed-value-diagnostics-tools/diagnose.py'),mode,str(src),str(target),'--java-home',str(java),'--backend','bytecode','--oracle',str(oracle),'--selected-worker']+['--jvm-option='+x for x in flags]
  record={'mode':mode,'label':label,'argv':argv,'started':now()};state['runs'].append(record);save();print('START_DIAGNOSTIC',mode,label,now(),flush=True)
  with (out/(mode+'-'+label+'-driver.log')).open('w') as log:p=subprocess.run(argv,stdout=log,stderr=subprocess.STDOUT,timeout=900)
  record.update(finished=now(),returncode=p.returncode);save();assert p.returncode==0,(mode,label,p.returncode)
  v=json.loads((target/'validation.json').read_text());record['validation']=v;save();print('VALIDATED_DIAGNOSTIC',mode,label,json.dumps(v.get('allocationSamples',{})),flush=True)
state.update(finished=now(),status='complete');save();print('CALLER_DIAGNOSTICS_COMPLETE',flush=True)
