#!/usr/bin/env python3
"""Read already-parsed selected graphs serially; no JVMs or graph reparsing."""
import collections, hashlib, json, pathlib
ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = pathlib.Path(__file__).resolve().parent
CAP = ROOT / 'work/class-owned-diagnostics'

def digest(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(1048576),b''):h.update(b)
 return h.hexdigest()
def file(p):return {'path':str(p.relative_to(ROOT)), 'sha256':digest(p), 'bytes':p.stat().st_size}
def compact(n):
 q=n['properties'];r={k:v for k,v in q.items() if k in ['stamp','field','location','checkedStamp','targetMethod','reason','action','negated','profileData','value','relativeFrequency']}
 r.update(id=n['id'],nodeClass=n['nodeClass'],block=n.get('block'))
 r['source']=[line.split('(truffle:')[0] for line in q.get('nodeSourcePosition','').splitlines()[:12]]
 return r
report={'scope':'Static selected compiled graph sites, not dynamic allocation counts or throughput. Class-owned off/on, compact headers enabled, same frozen JAR/Core.', 'roots':[], 'sources':[], 'inputs':[], 'slices':[]}
idslices={('off','fold','graph-00005.json'):[549,552,4601,4623,5862],('on','fold','graph-00005.json'):[544,626,629,4552,784,1074,1298,5453,5507,5576,5593,2803,1872,1873,4584,5424],('on','fold','graph-00007.json'):[626,4552,5507,5634,5779,6024,6025,6490,4584,5726]}
for mode in ['off','on']:
 d=CAP/f'graph-{mode}'
 for name in ['capture-config.json','validation.json','root-identities.json','summary.json']:report['inputs'].append(file(d/name))
 identity=json.loads((d/'root-identities.json').read_text())['roots'];summaries=json.loads((d/'summary.json').read_text())['roots']
 config=json.loads((d/'capture-config.json').read_text())
 for role,ident,summary in zip(['lookup','fold','worker'],identity,summaries):
  assert ident['compilationId']==summary['compilationId']
  raw=file(d/ident['bgv']['path']);assert raw['sha256']==ident['bgv']['sha256']
  p=next(d.glob(f"parsed-*{ident['compilationId']}*"))
  entry={'mode':mode,'role':role,'identity':ident,'bgvVerified':raw,'jvmOptions':config['jvmOptions'], 'statistics':{k:summary[k] for k in ['peNodes','beforeHighNodes','afterMidNodes','guestCalls','lateArrays','lateLongBoxes','materializedClasses','packetsByClassification']},'phases':[]}
  for name in ['graph-00005.json','graph-00007.json']:
   g=json.loads((p/name).read_text());nodes=g['nodes'];calls=collections.defaultdict(list);loads=[];checks=collections.defaultdict(list)
   for n in nodes:
    q=n['properties'];kind=n['nodeClass'].split('.')[-1]
    if 'CallTarget' in kind:calls[q.get('targetMethod','?')].append(n['id'])
    if 'Read' in kind or 'Load' in kind:
     if str(q.get('field') or q.get('location') or '').endswith('.layout'):loads.append(n['id'])
    if kind=='InstanceOfNode':checks[q.get('checkedStamp','?')].append(n['id'])
   phase={'file':file(p/name),'phase':g['name'],'nodes':len(nodes),'layoutLoads':loads,'callTargets':dict(calls),'instanceOfNodes':dict(checks)}
   entry['phases'].append(phase)
   if name == 'graph-00007.json':
    phase['materializedSites']=[{k:a[k] for k in ['node','block','class','origin']} for a in summary['materializedAllocations']]
   chosen=set(idslices.get((mode,role,name),[]))
   if chosen:
    report['slices'].append({'mode':mode,'role':role,'file':phase['file'],'nodes':[compact(n) for n in nodes if n['id'] in chosen], 'edgesTouchingSelectedNodes':[e for e in g['edges'] if e['from'] in chosen or e['to'] in chosen]})
  report['roots'].append(entry)
for name in ['DataValues.kt','ClassOwnedLayouts.kt']:
 report['sources'].append(file(ROOT/'work/class-owned-layout-prototype/frozen/src/main/kotlin/thc/runtime'/name))
report['sources'].append(file(ROOT/'work/class-owned-layout-prototype/frozen/src/main/java/thc/runtime/BytecodeRoot.java'))
report['inputs'].append(file(ROOT/'work/class-owned-diagnostic-tools/prior-v7-class-off-fold-proof.json'))
(OUT/'audit.json').write_text(json.dumps(report,indent=2)+'\n')
print('Wrote audit.json:',len(report['roots']),'roots;',len(report['slices']),'exact node slices')
