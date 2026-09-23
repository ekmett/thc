#!/usr/bin/env python3
"""One-second native warmup and two two-second windows; never starts a JVM."""
from pathlib import Path
import argparse,datetime,hashlib,json,statistics,subprocess
p=argparse.ArgumentParser(description=__doc__);p.add_argument('frozen',type=Path);p.add_argument('out',type=Path);p.add_argument('--oracle',type=Path,required=True);a=p.parse_args();f=a.frozen.resolve();out=a.out.resolve();assert not out.exists();out.mkdir(parents=True)
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest();native=f/'map/native-oracle';manifest=json.loads((f/'manifest.json').read_text());row=next(r for r in manifest['files']if r['path']=='map/native-oracle');assert sha(native)==row['sha256']
rows=[line.split('\t')for line in a.oracle.read_text().splitlines()];assert len(rows)==16 and all(r[:2]==['mapAggregate',str(10000+i)]for i,r in enumerate(rows));cycle=sum(int(r[2])for r in rows);signed=lambda n:(n+(1<<63))%(1<<64)-(1<<63)
config={'startedUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'native':str(native),'nativeSha256':sha(native),'oracle':str(a.oracle.resolve()),'oracleSha256':sha(a.oracle),'helperSha256':sha(Path(__file__)),'scope':'Host health screen only, not a throughput comparison. Conservative stop threshold1.875ms is1.5x historical1.25ms; no causal inference about battery.'}
def snapshot(label):
 result={'recordedUtc':datetime.datetime.now(datetime.timezone.utc).isoformat()}
 for tag,cmd in [('battery',['pmset','-g','batt']),('thermal',['pmset','-g','therm']),('cpu',['ps','-axo','pid,pcpu,comm','-r'])]:
  q=subprocess.run(cmd,capture_output=True,text=True,timeout=10);(out/(label+'-'+tag+'.txt')).write_text(q.stdout+q.stderr);result[tag]={'argv':cmd,'exitCode':q.returncode,'raw':label+'-'+tag+'.txt'}
 return result
config['before']=snapshot('before');cmd=[str(native),'--bench-steady','mapAggregate','1','2','2','10000'];config['argv']=cmd
with(out/'native.tsv').open('w')as stdout,(out/'native.log').open('w')as stderr:q=subprocess.run(cmd,stdout=stdout,stderr=stderr,timeout=40)
config['exitCode']=q.returncode;config['after']=snapshot('after');errors=[];windows=[]
if q.returncode:errors.append('native process failed')
for i,line in enumerate((out/'native.tsv').read_text().splitlines(),1):
 fields=line.split('\t')
 if len(fields)!=6:errors.append('wrong field count');continue
 entry,sample,calls,base,checksum,ns=fields;sample,calls,base,checksum,ns=map(int,[sample,calls,base,checksum,ns])
 if entry!='mapAggregate'or sample!=i or calls<=0 or calls%256 or base!=10000 or checksum!=signed(cycle*(calls//16))or ns<2_000_000_000:errors.append('native checksum/duration invalid')
 if calls>0:windows.append(ns/calls)
if len(windows)!=2:errors.append('wrong window count')
assert sha(native)==config['nativeSha256']
config.update(windowNsPerCall=windows,medianNsPerCall=statistics.median(windows)if windows else None,errors=errors)
config['healthy']=not errors and max(windows)<1_875_000 and max(windows)/min(windows)<1.15
(out/'summary.json').write_text(json.dumps(config,indent=2)+'\n');print(json.dumps({'healthy':config['healthy'],'windowNsPerCall':windows,'out':str(out)}),flush=True)
raise SystemExit(0 if config['healthy']else 2)
