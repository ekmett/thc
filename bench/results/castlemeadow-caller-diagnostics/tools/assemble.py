from pathlib import Path
import collections, datetime, hashlib, json, shutil, tarfile

R=Path('/home/ekmett/thc-benchmarks/2026-09-23-083914')
S=R/'diagnostics/caller-demand-default'
O=R/'diagnostics/caller-demand-default-publication'
T=R/'diagnostics/caller-demand-tools'
O.mkdir(exist_ok=False)
def sha(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
 return h.hexdigest()
def read(p):return json.loads(p.read_text())
def write(p,v):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(v,indent=2)+'\n')
def cp(a,b):b.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(a,b)
def topology(g):
 v={'name':g['name'],'group':g['group'],'graphType':g['graphType'],
    'nodes':sorted([[n['id'],n['nodeClass'],n.get('block')] for n in g['nodes']],key=lambda n:n[0]),
    'edges':sorted(g['edges'],key=lambda e:json.dumps(e,sort_keys=True)),
    'blocks':sorted(g['blocks'],key=lambda b:str(b['name']))}
 return {'sha256':hashlib.sha256(json.dumps(v,sort_keys=True,separators=(',',':')).encode()).hexdigest(),'nodes':len(g['nodes']),'edges':len(g['edges']),'blocks':len(g['blocks'])}

driver=read(S/'driver.json');assert driver['status']=='complete' and len(driver['runs'])==4
cp(S/'driver.json',O/'execution/driver.json')
cp(R/'sources/thc-castlemeadow-caller-diagnostics.py',O/'tools/capture-driver.py')
shutil.copytree(T,O/'tools/capture')
for n in ['call-demand-structural-proof.json','caller-demand-preflight.json','call-demand-transfer-manifest.json','call-demand-map-checks.json','call-demand-core-equivalence.json']:
 cp(R/'provenance'/n,O/'provenance'/n)
cp(R/'results/caller-demand-default/oracle.tsv',O/'provenance/oracle.tsv')
cp(R/'toolchains/graalvm-25.3.4.1+1.1/release',O/'provenance/graal-release')
selected=[];rows=[];raw=[]
for side in ['control','enabled']:
 cp(S/('input-'+side)/'manifest.json',O/'provenance'/('input-'+side+'-manifest.json'))
 for mode in ['profile','graph']:
  src=S/(mode+'-'+side);dest=O/(mode+'-'+side)
  config=read(src/'capture-config.json')
  for p in src.iterdir():
   if p.is_file() and p.suffix!='.bgv':cp(p,dest/p.name)
  for f in config['outputs']:
   p=src/f['path'];assert p.stat().st_size==f['bytes'] and sha(p)==f['sha256'],p
  for f in config['inputs']:assert sha(Path(f['path']))==f['sha256'],f
  for p in src.glob('*.bgv'):raw.append({'path':str(p),'sha256':sha(p),'bytes':p.stat().st_size,'selected':False})
  cp(S/(mode+'-'+side+'-driver.log'),O/'execution'/(mode+'-'+side+'-driver.log'))
 src=S/('graph-'+side);summary=read(src/'summary.json')['roots'];assert len(summary)==1
 row=summary[0];identity=read(src/'root-identities.json')['roots'];assert len(identity)==1
 ident=identity[0];assert not ident['roleAmbiguous'] and ident['roleKey']=='lambda ww @ n/a'
 parsed=src/('parsed-'+Path(ident['bgv']['path']).stem);idx=read(parsed/'index.json')
 entry={'capture':side,'identity':ident,'originalPath':str(src/ident['bgv']['path']),'archiveMember':side+'/'+ident['bgv']['path'],'phases':[]}
 hot=None;children=[]
 for phase in idx['graphs']:
  if not phase.get('file'):continue
  p=parsed/phase['file'];g=read(p)
  entry['phases'].append({'name':g['name'],'file':phase['file'],'bytes':p.stat().st_size,'sha256':sha(p),'topology':topology(g)})
  if 'After Inline' in phase['name']:
   cp(p,O/('graph-'+side)/'call-tree.json')
   nodes={n['id']:n for n in g['nodes']}
   for e in g['edges']:
    if e['from']==0 and e['label'].startswith('children'):
     n=nodes[e['to']];q=n['properties']
     children.append({'id':n['id'],'target':q.get('name'),'state':str(q.get('state')).split('.')[-1],
       'frequency':q.get('Frequency'),'irNodes':q.get('IR Nodes'),'callDiff':q.get('call diff'),'recursionDepth':q.get('Recursion Depth')})
   hot=[x for x in children if x['target']=='lambda sc, x, ds1' and x['frequency']>1000]
   assert len(hot)==1 and hot[0]['state']=='Inlined',hot
  del g
 assert len(entry['phases'])==4 and hot
 selected.append(entry)
 for v in raw:
  if v['path']==entry['originalPath']:v['selected']=True;v['archiveMember']=entry['archiveMember']
 allocation=read(S/('profile-'+side)/'validation.json')['allocationSamples']
 assert len(allocation)==3 and len({x['bytesPerCall'] for x in allocation})==1
 rows.append({'side':side,'root':ident,'hotInsert':hot[0],'rootChildren':children,'exactAllocationSamples':allocation,
  'graph':{k:row[k] for k in ['peNodes','beforeHighNodes','afterMidNodes','guestCalls','constantGuestTargets','lateArrays','lateLongArrayPackets','allResidualReferenceArrayPackets','lateLongBoxes','materializedClasses','frontierStateCounts']}})
write(O/'graph-comparison.json',{'scope':'Actual selected latest worker graphs and separate exact allocated-byte counters; static sites are not dynamic execution counts. Default policy both sides.','variants':rows})
write(O/'raw-artifacts.json',{'scope':'All original raw BGV files remain on the server. The two selected latest worker BGVs are archived here; profile JFRs are included directly.','files':raw})
archive=O/'selected-worker-bgv.tar.xz'
with tarfile.open(archive,'w:xz',preset=3,format=tarfile.PAX_FORMAT) as tar:
 for item in selected:
  p=Path(item['originalPath']);assert sha(p)==item['identity']['bgv']['sha256']
  info=tarfile.TarInfo(item['archiveMember']);info.size=p.stat().st_size;info.mode=0o444;info.mtime=0
  with p.open('rb') as f:tar.addfile(info,f)
write(O/'selected-worker-bgv-manifest.json',{'archive':{'path':archive.name,'sha256':sha(archive),'bytes':archive.stat().st_size},'selected':selected,
 'topologyScope':'Graph name/group/type, node IDs/classes/block membership, every edge and every block. Node properties and elapsed timings are excluded from the topology digest; full properties remain in original BGV and included call-tree JSON.',
 'reparseStatus':'Original captures parsed once successfully. Archive extraction/hash and independent reparse verification pending.'})
write(O/'assembly.json',{'recordedUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'source':str(S),'allOriginalInputsOutputsRehashed':True,'selectedGraphs':2,'selectedPhases':8,'archiveBytes':archive.stat().st_size})
print('PUBLICATION_ASSEMBLED',O,archive.stat().st_size,flush=True)
