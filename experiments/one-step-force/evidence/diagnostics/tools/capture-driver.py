from pathlib import Path
import datetime,json,os,subprocess,hashlib
r=Path('/home/ekmett/thc-benchmarks/2026-09-23-083914');tool=r/'diagnostics/caller-demand-tools';out=r/'diagnostics/one-step-force-agnostic';out.mkdir(exist_ok=False)
def sha(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(1048576),b''):h.update(b)
 return h.hexdigest()
def now():return datetime.datetime.now(datetime.timezone.utc).isoformat()
assert json.loads((r/'results/one-step-force-agnostic/validation.json').read_text())['passed']
assert json.loads((r/'provenance/one-step-force-preflights.json').read_text())['passed']
for f in json.loads((tool/'manifest.json').read_text())['files']:assert sha(tool/f['path'])==f['sha256']
proof=json.loads((r/'provenance/call-demand-structural-proof.json').read_text());assert proof['passed'];expected={x['file']:x for x in proof['modules']};aliases={}
for label,source,mode in [('control','release-main-5af98789','on'),('candidate','one-step-force-v1','on')]:
 src=r/'sources'/source;expectedJar={'control':'5af987892c35fedc0f170d245574ad704609e9747c7993f970991191d71a773c','candidate':'c2c2d12e10beb636c830f45c77f7b8998fbb98109af7a74263d64835695b52d2'}[label];assert sha(src/'lib/thc-0.1-experiment.jar')==expectedJar;dest=out/('input-'+label);(dest/'lib').mkdir(parents=True);(dest/'map').mkdir()
 for p in (src/'lib').glob('*.jar'):os.link(p,dest/'lib'/p.name)
 for p in (src/'map').glob('*.json'):assert sha(p)==expected[p.name][mode];os.link(p,dest/'map'/p.name)
 (dest/'map/modules.txt').write_text('\n'.join(str(dest/'map'/Path(x).name) for x in (src/'map/modules.txt').read_text().splitlines())+'\n')
 manifest={'schema':1,'scope':'Immutable diagnostic aliases of the exact completed benchmark runtime/Core; original frozen source manifest retained separately.','originalManifest':{'path':str(src/('manifest.json' if (src/'manifest.json').exists() else 'local-proof.json')),'sha256':sha(src/('manifest.json' if (src/'manifest.json').exists() else 'local-proof.json'))},'runtimeJarSha256':sha(dest/'lib/thc-0.1-experiment.jar'),'files':[{'path':p.relative_to(dest).as_posix(),'sha256':sha(p),'bytes':p.stat().st_size} for p in sorted(dest.rglob('*')) if p.is_file()]}
 (dest/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n');aliases[label]=dest
oracle=r/'results/one-step-force-agnostic/oracle.tsv';java=r/'toolchains/graalvm-25.3.4.1+1.1';flags=['-Xms4g','-Xmx4g','-XX:+UseCompactObjectHeaders','-Dpolyglot.compiler.InliningPolicy=Agnostic']+['-Dthc.'+key+'='+str(key=='classOwnedLayouts').lower() for key in ['classOwnedLayouts','typedCases','leadingCaseReturn','constructorClassIdentity','staticShapeUnchecked','boxedValueCache']]
for key in ['JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS']:os.environ.pop(key,None)
state={'started':now(),'status':'running','scope':'Sequential diagnostic allocation/JFR and actual worker graphs, not throughput. Full18 input oracle proof is the just-completed unchanged input preflight.','preflightProof':{'path':str(r/'provenance/one-step-force-preflights.json'),'sha256':sha(r/'provenance/one-step-force-preflights.json')},'toolManifestSha256':sha(tool/'manifest.json'),'runs':[]}
def save():(out/'driver.json').write_text(json.dumps(state,indent=2)+'\n')
for mode in ['profile','graph']:
 for label,src in aliases.items():
  target=out/(mode+'-'+label);argv=['taskset','-c','0-15','python3','-u',str(tool/'work/boxed-value-diagnostics-tools/diagnose.py'),mode,str(src),str(target),'--java-home',str(java),'--backend','bytecode','--oracle',str(oracle),'--selected-worker']+['--jvm-option='+x for x in flags]
  record={'mode':mode,'label':label,'argv':argv,'started':now()};state['runs'].append(record);save();print('START_DIAGNOSTIC',mode,label,now(),flush=True)
  with (out/(mode+'-'+label+'-driver.log')).open('w') as log:p=subprocess.run(argv,stdout=log,stderr=subprocess.STDOUT,timeout=900)
  record.update(finished=now(),returncode=p.returncode);save();assert p.returncode==0,(mode,label,p.returncode)
  v=json.loads((target/'validation.json').read_text());record['validation']=v;save();print('VALIDATED_DIAGNOSTIC',mode,label,json.dumps(v.get('allocationSamples',{})),flush=True)
state.update(finished=now(),status='complete');save();print('FORCE_DIAGNOSTICS_COMPLETE',flush=True)
