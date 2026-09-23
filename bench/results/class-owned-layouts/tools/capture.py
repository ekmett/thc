#!/usr/bin/env python3
"""Prepared only; run after an explicit CPU grant. All guest/parser processes are serial."""
from pathlib import Path
import argparse,datetime,hashlib,json,os,signal,subprocess,sys,time
ROOT=Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('out',type=Path,help='New output directory, normally work/class-owned-diagnostics')
p.add_argument('--java-home',type=Path,default=Path('/Users/ekmett/cadenza/.toolchains/graalvm-25.3.4.1+1.1/Contents/Home'))
p.add_argument('--mode',choices=['all','graph','profile'],default='all')
p.add_argument('--timeout-seconds',type=int,default=300)
a=p.parse_args()
frozen=ROOT/'work/class-owned-layout-prototype/frozen';helper=ROOT/'work/boxed-value-diagnostics-tools/diagnose.py'
sourceOracle=ROOT/'work/boxed-value-cache-comparison/oracle.tsv';sha=lambda x:hashlib.sha256(x.read_bytes()).hexdigest()
expectedJar='1d51bca562a5f7848cd2f9b88cde6102e00509760bf2a70713f0afedc4fcb192';expectedHelper='df8e19c241d3adb5a492309514871fd5c8ecb4d9a6334baa56d2c12ea44d9141'
assert sha(helper)==expectedHelper,'Prepared helper changed; review before proceeding.'
manifest=json.loads((frozen/'manifest.json').read_text());files={x['path']:x['sha256'] for x in manifest['files']}
assert files['lib/thc-0.1-experiment.jar']==expectedJar
assert files['map/native-oracle']=='56db4f037fffadad198c4e6bf367e287266fd9c13f9450f4ec5787b186de9fbf'
assert sha(sourceOracle)=='81dff827f844730a41c6bff3d3f4c8e1c77e0d04fef2770521a30c084bed5277'
old=json.loads((ROOT/'work/boxed-value-cache-v7/manifest.json').read_text());oldfiles={x['path']:x['sha256'] for x in old['files']}
assert {k:v for k,v in files.items() if k.startswith('map/') and k.endswith('.json')}=={k:v for k,v in oldfiles.items() if k.startswith('map/') and k.endswith('.json')}
assert files['map/native-oracle']==oldfiles['map/native-oracle'],'Cannot reuse the native oracle for a changed binary.'
out=a.out.resolve();assert not out.exists(),'Choose a fresh output directory.';out.mkdir(parents=True)
(out/'oracle16.tsv').write_bytes(sourceOracle.read_bytes())
record={'scope':'Same frozen prototype JAR/Core, class-owned layout off/on with compact headers on. Selected lookup/fold/worker graphs and separate exact allocation/JFR diagnostics. No throughput conclusions from these captures.',
 'startedUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'runtimeJarSha256':expectedJar,'frozenManifest':{'path':str(frozen/'manifest.json'),'sha256':sha(frozen/'manifest.json')},'helper':{'path':str(helper),'sha256':expectedHelper},'driver':{'path':str(Path(__file__).resolve()),'sha256':sha(Path(__file__).resolve())},'oracleProvenance':{'copiedFrom':str(sourceOracle),'sha256':sha(sourceOracle),'nativeBinarySha256':files['map/native-oracle'],'sameNativeAndModuleContentsAsV7':True,'moduleManifestDifference':'Frozen manifests list absolute paths in their respective directories; all module JSON contents are identical.'},'commands':[]}
def save():(out/'driver.json').write_text(json.dumps(record,indent=2)+'\n')
save()
try:
 for mode in (['graph','profile'] if a.mode=='all' else [a.mode]):
  for owned in [False,True]:
   label=mode+'-'+('on' if owned else 'off')
   opts=['-Dthc.classOwnedLayouts='+str(owned).lower(),'-XX:+UseCompactObjectHeaders','-Dthc.boxedValueCache=false','-Dthc.constructorClassIdentity=false','-Dthc.staticShapeUnchecked=false']
   cmd=[sys.executable,str(helper),mode,str(frozen),str(out/label),'--java-home',str(a.java_home),'--backend','bytecode','--depth','2','--budget','12000','--oracle',str(out/'oracle16.tsv'),'--selected-map-roots',*['--jvm-option='+s for s in opts]]
   step={'label':label,'argv':cmd,'log':label+'.log','startedUtc':datetime.datetime.now(datetime.timezone.utc).isoformat()};record['commands'].append(step);save();print('START',label,flush=True);start=time.monotonic()
   with (out/step['log']).open('w') as log:
    process=subprocess.Popen(cmd,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
    try:code=process.wait(timeout=a.timeout_seconds)
    except BaseException:
     try:os.killpg(process.pid,signal.SIGTERM)
     except ProcessLookupError:pass
     try:process.wait(timeout=5)
     except subprocess.TimeoutExpired:
      try:os.killpg(process.pid,signal.SIGKILL)
      except ProcessLookupError:pass
      process.wait()
     raise
   step.update(exitCode=code,elapsedSeconds=time.monotonic()-start);save();assert code==0,label+' failed; inspect '+str(out/step['log'])
   validation=json.loads((out/label/'validation.json').read_text());assert validation['installedCode'] and not validation['forbiddenCompilationEvents'] and validation['diagnostics']['unsupportedTraps']==0
   assert sha(helper)==expectedHelper and sha(frozen/'manifest.json')==record['frozenManifest']['sha256']
   print('DONE',label,'seconds',round(step['elapsedSeconds'],2),flush=True)
 record['passed']=True
except BaseException as error:
 record['passed']=False;record['error']=str(error);save();raise
finally:
 record['finishedUtc']=datetime.datetime.now(datetime.timezone.utc).isoformat();save()
print('All requested diagnostics validated:',out,flush=True)
