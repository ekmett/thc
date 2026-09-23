from pathlib import Path
import json,hashlib,collections
import sys
base=Path(sys.argv[1]);out=Path(sys.argv[2]);out.mkdir(parents=True,exist_ok=True)
def h(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def review(p):
 g=json.loads(p.read_text());ns={n['id']:n for n in g['nodes']};es=g['edges'];blocks={b['name']:b for b in g['blocks']};kind=lambda i:ns[i]['nodeClass'].split('.')[-1]
 inp=lambda i:[e for e in es if e['to']==i and e['type']=='Value']
 succ=lambda i:[e for e in es if e['from']==i and e['type']=='Value']
 def expression(i,seen=frozenset(),depth=0):
  n=ns[i];k=kind(i);q=n['properties']
  if k=='ConstantNode':return q.get('value',q.get('rawvalue'))
  if i in seen or depth>7:return f'{k}#{i}'
  return f'{k}#{i}('+','.join(f'{e["label"]}:{expression(e["from"],seen|{i},depth+1)}' for e in inp(i))+')'
 loops=[n for n in ns.values() if kind(n['id'])=='LoopBeginNode'];ends=[n for n in ns.values() if kind(n['id'])=='LoopEndNode'];assert len(loops)==len(ends)==1
 header=loops[0]['block'];end=ends[0]['block']
 # Blocks that both reach and are reachable from the loop header comprise its SCC.
 def reach(start,reverse=False):
  seen=set();todo=[start]
  while todo:
   v=todo.pop()
   if v in seen:continue
   seen.add(v);todo.extend([b for b in blocks if v in blocks[b]['successors']] if reverse else blocks[v]['successors'])
  return seen
 cyc=reach(header)&reach(header,True);assert end in cyc
 phis=[e['to'] for e in es if e['from']==loops[0]['id'] and e['label']=='merge'];phis=[i for i in phis if kind(i)=='ValuePhiNode']
 arith=[n for n in ns.values() if n['block'] in cyc and kind(n['id']) in ['AddNode','SubNode','IntegerLessThanNode','IntegerEqualsNode']]
 boxes=[n for n in ns.values() if 'BoxNode' in kind(n['id']) and 'Unbox' not in kind(n['id'])]
 assert all(n['block'] not in cyc for n in boxes)
 forbidden=[n for n in ns.values() if kind(n['id']) in ['CommitAllocationNode','NewInstanceNode','NewArrayNode','InvokeNode','InvokeWithExceptionNode','StoreFieldNode','LoadIndexedNode','StoreIndexedNode'] and n['block'] in cyc]
 assert not forbidden
 fields=[n for n in ns.values() if kind(n['id']) in ['LoadFieldNode','StoreFieldNode']];assert all(n['properties'].get('field','').startswith('thc.runtime.Closure.') and n['block'] not in cyc for n in fields)
 for box in boxes:
  # Box value may feed only return or reference merge that ultimately feeds return.
  todo=[box['id']];seen=set();returns=[]
  while todo:
   i=todo.pop()
   if i in seen:continue
   seen.add(i)
   for e in succ(i):
    j=e['to'];k=kind(j)
    if k in ['FrameState','VirtualObjectState']:continue
    assert k in ['ReturnNode','ValuePhiNode'],(p,box['id'],j,k)
    if k=='ReturnNode':returns.append(j)
    else:todo.append(j)
  assert returns
 return {'path':str(p),'sha256':h(p),'nodes':len(ns),'loop':{'header':loops[0]['id'],'end':ends[0]['id'],'cyclicBlocks':sorted(cyc,key=int)},
  'phis':[{'id':i,'stamp':ns[i]['properties'].get('stamp'),'inputs':[{'index':e['listIndex'],'expression':expression(e['from'])} for e in inp(i)]} for i in phis],
  'loopArithmetic':[{'id':n['id'],'block':n['block'],'expression':expression(n['id'])} for n in arith],
  'boxes':[{'id':n['id'],'block':n['block'],'kind':kind(n['id']),'stamp':n['properties'].get('stamp')} for n in boxes],
  'fields':[{'id':n['id'],'block':n['block'],'field':n['properties']['field']} for n in fields]}
result=[]
for p in sorted(base.glob('*-changing-*/parsed/graph-*.json')):
 r=review(p);result.append(r)
 print(p.parent.parent.name,r['nodes'],'loop',r['loop'],'boxes',r['boxes'])
 print('main phis',[v for v in r['phis'] if v['stamp']=='i64'])
(out/'high-tier-review.json').write_text(json.dumps(result,indent=2)+'\n')
