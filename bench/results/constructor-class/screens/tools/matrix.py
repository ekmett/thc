#!/usr/bin/env python3
"""Seven serial default-policy constructor-class screens after a passing native health check."""
from pathlib import Path
import argparse,datetime,hashlib,json,subprocess,sys
p=argparse.ArgumentParser(description=__doc__);p.add_argument('frozen',type=Path);p.add_argument('out',type=Path);p.add_argument('--java-home',type=Path,required=True);p.add_argument('--oracle',type=Path,required=True);p.add_argument('--health',type=Path,required=True);a=p.parse_args();f=a.frozen.resolve();out=a.out.resolve();assert not out.exists();out.mkdir(parents=True)
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest();frozen=json.loads((f/'manifest.json').read_text());health=json.loads(a.health.read_text());assert health['healthy']and health['nativeSha256']==sha(f/'map/native-oracle')
for row in frozen['files']:assert sha(f/row['path'])==row['sha256'],row['path']
configs=[('ast',False,False,True),('ast',False,True,True),('ast',True,True,True),('bytecode',False,False,True),('bytecode',False,True,True),('bytecode',True,True,True),('bytecode',False,True,False)]
entries=[]
for backend,unchecked,identity,argspec in configs:
 name=f'{backend}-'+('unchecked'if unchecked else 'checked')+'-class'+('on'if identity else 'off')+'-args'+('on'if argspec else 'off')
 flags=[f'-Dthc.staticShapeUnchecked={str(unchecked).lower()}',f'-Dthc.constructorClassIdentity={str(identity).lower()}',f'-Dpolyglot.engine.ArgumentTypeSpeculation={str(argspec).lower()}','-Dpolyglot.engine.ReturnTypeSpeculation=true']
 entries.append({'name':name,'backend':backend,'jvmOptions':flags})
report={'recordedUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'scope':'Seven one-process screens; same frozenJAR/Core. Depth2, both budgets12000. Full18inputs before and after requested compilation precede timings. Not a three-fork claim.','frozenManifestSha256':sha(f/'manifest.json'),'health':str(a.health.resolve()),'healthSha256':sha(a.health),'configs':entries,'checks':[],'runs':[]}
def save():(out/'matrix.json').write_text(json.dumps(report,indent=2)+'\n')
save()
for cfg in entries:
 name='check-'+cfg['name'];cmd=[str(a.java_home/'bin/java'),'--enable-native-access=ALL-UNNAMED','-Xss2m',*cfg['jvmOptions'],f"-Dthc.backend={cfg['backend']}",'-Dthc.diagnosticUnsupported=true','-Dthc.sourceNotesEnabled=true','-cp',str(f/'lib/*'),'thc.MapCheckKt',str(f/'map/modules.txt'),str(f/'map/oracle.tsv')]
 print('CHECK',name,flush=True)
 with(out/(name+'.tsv')).open('w')as stdout,(out/(name+'.log')).open('w')as stderr:q=subprocess.run(cmd,stdout=stdout,stderr=stderr,timeout=180)
 report['checks'].append({'name':name,'argv':cmd,'exitCode':q.returncode});save();assert q.returncode==0,name
 text=(out/(name+'.tsv')).read_text();assert sum(x.startswith('VERIFIED_MAP\t')for x in text.splitlines())==36
 diag=json.loads(next(x.split(' ',1)[1]for x in text.splitlines()if x.startswith('MAP_DIAGNOSTICS ')));assert diag['unsupportedTraps']==0 and diag['backend']==cfg['backend'];print('PASS',name,flush=True)
for cfg in entries:
 cmd=[sys.executable,str(Path(__file__).with_name('sweep.py')),str(f),str(out/cfg['name']),'--java-home',str(a.java_home),'--backend',cfg['backend'],'--depths','2','--budgets','12000','--oracle',str(a.oracle.resolve()),*['--jvm-option='+v for v in cfg['jvmOptions']]]
 print('START',cfg['name'],flush=True);q=subprocess.run(cmd);row={'name':cfg['name'],'argv':cmd,'exitCode':q.returncode};report['runs'].append(row);save();assert q.returncode==0
 summary=json.loads((out/cfg['name']/'summary.json').read_text());assert len(summary['runs'])==1 and summary['runs'][0]['valid'],summary
 print('RESULT',cfg['name'],summary['runs'][0]['medianNsPerCall'],summary['runs'][0]['latestGuestCodeBytes'],flush=True)
for row in frozen['files']:assert sha(f/row['path'])==row['sha256'],row['path']
print('Seven guarded default-policy screens complete',flush=True)
