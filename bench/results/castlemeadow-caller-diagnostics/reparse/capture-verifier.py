#!/usr/bin/env python3
"""Verify portable caller-demand diagnostic evidence; no guest execution."""
from pathlib import Path
import argparse, hashlib, json, re, tarfile

BASE=Path(__file__).resolve().parents[1]
def need(ok,message):
 if not ok:raise ValueError(message)
def read(p):return json.loads(p.read_text())
def digest(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
 return h.hexdigest()
def topology(g):
 v={'name':g['name'],'group':g['group'],'graphType':g['graphType'],
    'nodes':sorted([[n['id'],n['nodeClass'],n.get('block')] for n in g['nodes']],key=lambda n:n[0]),
    'edges':sorted(g['edges'],key=lambda e:json.dumps(e,sort_keys=True)),
    'blocks':sorted(g['blocks'],key=lambda b:str(b['name']))}
 return {'sha256':hashlib.sha256(json.dumps(v,sort_keys=True,separators=(',',':')).encode()).hexdigest(),'nodes':len(g['nodes']),'edges':len(g['edges']),'blocks':len(g['blocks'])}
def archive(extract=None):
 m=read(BASE/'selected-worker-bgv-manifest.json');a=m['archive'];p=BASE/a['path']
 need(p.stat().st_size==a['bytes'] and digest(p)==a['sha256'],'Archive hash/size mismatch')
 expected={x['archiveMember']:x for x in m['selected']};seen=set()
 if extract is not None:extract.mkdir(exist_ok=False,parents=True)
 with tarfile.open(p,'r:xz') as tar:
  for member in tar:
   need(member.isfile() and member.name in expected and member.name not in seen,'Unexpected/repeated archive member')
   x=expected[member.name]['identity']['bgv'];need(member.size==x['bytes'],'Raw BGV size mismatch')
   h=hashlib.sha256();data=tar.extractfile(member);out=None
   if extract is not None:
    dest=extract/member.name;need(dest.resolve().is_relative_to(extract.resolve()),'Unsafe archive path');dest.parent.mkdir(parents=True,exist_ok=True);out=dest.open('wb')
   with data:
    for b in iter(lambda:data.read(1024*1024),b''):
     h.update(b)
     if out is not None:out.write(b)
   if out is not None:out.close()
   need(h.hexdigest()==x['sha256'],'Raw BGV digest mismatch');seen.add(member.name)
 need(seen==expected.keys(),'Incomplete archive')
 return m
def verify():
 m=read(BASE/'manifest.json')
 for x in m['files']:
  p=BASE/x['path'];need(p.stat().st_size==x['bytes'] and digest(p)==x['sha256'],'Publication changed: '+str(p))
 proof=read(BASE/'provenance/call-demand-structural-proof.json')
 need(proof['passed'] and proof['counts']=={'certificates':7742,'strictPositions':7686},'Wrong demand proof')
 need(len(proof['modules'])==17 and all(x['onlyCallDemandRemoved'] for x in proof['modules']),'Incomplete Core comparison')
 need(proof['balanceREntryStrictUnchanged']==[False]*4,'Changed callee ABI')
 oracle=[x.split('\t') for x in (BASE/'provenance/oracle.tsv').read_text().splitlines()]
 need(len(oracle)==16 and all(x[:2]==['mapAggregate',str(10000+i)] for i,x in enumerate(oracle)),'Wrong oracle cycle')
 cycle=sum(int(x[2]) for x in oracle);signed=lambda x:(x+(1<<63))%(1<<64)-(1<<63)
 rows=read(BASE/'graph-comparison.json')['variants'];need(len(rows)==2,'Missing variants')
 flags=['classOwnedLayouts','typedCases','leadingCaseReturn','constructorClassIdentity','staticShapeUnchecked','boxedValueCache']
 for side,key in [('control','off'),('enabled','on')]:
  for mode in ['graph','profile']:
   root=BASE/(mode+'-'+side);c=read(root/'capture-config.json');v=read(root/'validation.json');log=(root/'run.log').read_text()
   need(c['backend']=='bytecode' and c['depth']==2 and c['expansionBudget']==12000 and c['inliningBudget']==12000,'Wrong backend/budget')
   opts=c['jvmOptions'];need(all(x in opts for x in ['-Xms4g','-Xmx4g','-XX:+UseCompactObjectHeaders','-Dpolyglot.compiler.InliningPolicy=Default']),'Missing pinned JVM options')
   for f in flags:need('-Dthc.'+f+'='+str(f=='classOwnedLayouts').lower() in opts,'Wrong feature flag')
   modules={Path(x['path']).name:x['sha256'] for x in c['inputs'] if '/map/' in x['path'] and x['path'].endswith('.json')}
   need(modules=={x['file']:x[key] for x in proof['modules']},'Wrong Core input hashes')
   jars=[x['sha256'] for x in c['inputs'] if x['path'].endswith('/thc-0.1-experiment.jar')]
   need(jars==[proof['runtimeSha256']],'Wrong runtime JAR')
   d=json.loads(next(x.split('=',1)[1] for x in log.splitlines() if x.startswith('diagnostics=')))
   need(d==v['diagnostics'] and d['backend']=='bytecode' and d['instrumented'] is False and d['unsupportedTraps']==0,'Bad diagnostics')
   need(d['unsupportedPolicy']=='diagnostic-traps' and d['sourceNotesEnabled'] is True and d['sourceRootCount']>0 and d['sourceSpanCount']>0,'Bad source/trap policy')
   need('PHASE VERIFY END guestLastTierInstalled=true' in log,'No installed guest code')
   w=re.search(r'PHASE WARM END calls=(\d+) elapsedNs=(\d+) checksum=(-?\d+)',log)
   need(w and int(w[1])>=30000 and int(w[1])%256==0 and int(w[2])>=45_000_000_000 and int(w[3])==signed(cycle*(int(w[1])//16)),'Warm/checksum gate failed')
   active=False;events=[]
   for line in log.splitlines():
    if line.startswith(('PHASE MEASURE','PHASE ALLOCATION','PHASE JFR','PHASE VERIFY')):active=' BEGIN' in line
    elif active and re.search(r'\bopt\s+(?:done|start|fail|inval\w*|deopt|queued|unqueued)\b|\bdeopt(?:imization)?\b',line,re.I):events.append(line)
   need(not events and not v['forbiddenCompilationEvents'],'Compiler event inside diagnostic measurement')
   if mode=='profile':
    samples=[]
    for i,line in enumerate((root/'run.tsv').read_text().splitlines(),1):
     n,calls,base,checksum,allocated=map(int,line.split('\t'))
     need((n,calls,base,checksum)==(i,256,10000,signed(cycle*16)),'Allocation checksum/sample mismatch')
     samples.append({'bytes':allocated,'calls':calls,'bytesPerCall':allocated/calls})
    need(len(samples)==3 and samples==v['allocationSamples'],'Wrong exact allocation samples')
    need(samples==next(r for r in rows if r['side']==side)['exactAllocationSamples'],'Summary disagrees with raw allocation')
   else:
    f=(root/'run.tsv').read_text().strip().split('\t');need(len(f)==6 and f[0]=='mapAggregate','Bad graph verification row')
    n,calls,base,checksum,ns=map(int,f[1:]);need(n==1 and calls>0 and calls%256==0 and base==10000 and checksum==signed(cycle*(calls//16)) and ns>=10_000_000,'Graph checksum failed')
    g=read(root/'call-tree.json');nodes={x['id']:x for x in g['nodes']};children=[nodes[e['to']] for e in g['edges'] if e['from']==0 and e['label']=='children']
    hot=[x for x in children if x['properties'].get('name')=='lambda sc, x, ds1' and x['properties'].get('Frequency',0)>1000]
    need(len(hot)==1 and hot[0]['properties']['state']=='CallNode$State.Inlined','Hot insert not inlined')
 archive();print('PASS: all publication hashes, exact Core/JAR/options, warmup/checksum/compiler guards, six exact allocation samples, two actual hot insert edges, archive and raw BGV hashes.')
def reparse(root):
 m=archive();results=[]
 for x in m['selected']:
  p=root/x['capture']/('parsed-'+Path(x['archiveMember']).stem);idx=read(p/'index.json')
  for phase in x['phases']:
   matches=[g for g in idx['graphs'] if g['name']==phase['name'] and g.get('file')];need(len(matches)==1,'Missing reparsed phase')
   actual=topology(read(p/matches[0]['file']));need(actual==phase['topology'],'Reparsed topology mismatch')
   results.append({'capture':x['capture'],'phase':phase['name'],'topology':actual,'matchesOriginal':True})
 report={'passed':True,'phasesVerified':len(results),'archiveSha256':m['archive']['sha256'],'results':results}
 (root/'topology-verification.json').write_text(json.dumps(report,indent=2)+'\n');print('PASS:',len(results),'reparsed phase topologies match original captures.')
if __name__=='__main__':
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('command',choices=['verify','extract','verify-reparse'],nargs='?',default='verify');p.add_argument('directory',type=Path,nargs='?');a=p.parse_args()
 if a.command=='verify':verify()
 else:
  need(a.directory is not None,'A new/existing directory is required')
  if a.command=='extract':archive(a.directory.resolve());print('PASS: archive extracted and both BGV hashes verified.')
  else:reparse(a.directory.resolve())
