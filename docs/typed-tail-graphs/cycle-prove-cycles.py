#!/usr/bin/env python3
"""Extract proof anchors from scheduled compiler graphs, not a model of the source."""
from pathlib import Path
import collections,hashlib,json
HERE=Path(__file__).resolve().parent
CASES=[('old-inner',2267,'old bytecode'),('new-inner',2254,'new bytecode'),('ast-inner',2115,'new AST')]

def short(n):return n['nodeClass'].split('.')[-1]
def cyclic_blocks(graph):
 edges={b['name']:b['successors']for b in graph['blocks']}
 def returns(start):
  todo=list(edges[start]);seen=set()
  while todo:
   at=todo.pop()
   if at==start:return True
   if at not in seen:seen.add(at);todo.extend(edges.get(at,[]))
  return False
 return {name for name in edges if returns(name)}

def summarize(capture,cid,label):
 p=next((HERE/capture).glob(f'parsed-TruffleHotSpotCompilation-{cid}[*'))
 idx=json.loads((p/'index.json').read_text())
 def read(phase):return json.loads((p/next(g['file']for g in idx['graphs']if phase in g['name'])).read_text())
 high=read('Before phase HighTierLowering');mid=read('After mid tier');inline=read('After Inline');pe=read('After PE Tier')
 nodes={n['id']:n for n in high['nodes']};incoming=collections.defaultdict(list)
 for e in high['edges']:incoming[e['to']].append(e)
 def info(n):return {'id':n['id'],'class':short(n),'block':n['block'],'stamp':n['properties'].get('stamp'),'value':n['properties'].get('value'),'source':n['properties'].get('nodeSourcePosition','').splitlines()[:4]}
 def inputs(nid):return [{'edge':e['label'],'index':e['listIndex'],'node':info(nodes[e['from']])}for e in incoming[nid]if e['type']!='Successor']
 loopids={n['id']for n in high['nodes']if short(n)=='LoopBeginNode'}
 phis=[]
 for n in high['nodes']:
  if short(n)=='ValuePhiNode'and any(e['from']in loopids and e['label']=='merge'for e in incoming[n['id']]):
   phis.append({'phi':info(n),'inputs':inputs(n['id']),'inputOperands':{str(e['from']):inputs(e['from'])for e in incoming[n['id']]if e['label']=='values'}})
 hc=cyclic_blocks(high);mc=cyclic_blocks(mid)
 allocs=[{**info(n),'instanceClass':n['properties'].get('instanceClass'),'elementType':n['properties'].get('elementType'),'inCyclicBlock':n['block']in mc}for n in mid['nodes']if short(n).endswith(('NewInstanceNode','NewArrayNode'))]
 for n in high['nodes']:
  if short(n).startswith('BoxNode$'):
   assert n['block']not in hc,(label,'box in hot loop',n['id'])
 # The output is an exact CFG with selected real node labels; hidden debug nodes are disclosed.
 visible={'StartNode','ParameterNode','LoopBeginNode','LoopEndNode','LoopExitNode','ValuePhiNode','AddNode','IntegerLessThanNode','IfNode','MergeNode','ReturnNode','UnwindNode','CommitAllocationNode','AllocatedObjectNode','BoxNode$AllocatingBoxNode','UnboxNode'}
 lines=['digraph actual_cfg {','graph [rankdir=TB, bgcolor="white", label="'+label+' C: actual scheduled CFG before HighTierLowering\\nDebug state, constants and bookkeeping labels omitted; node IDs and block edges preserved", labelloc=t];','node [shape=box,fontname="Menlo",fontsize=10];']
 for block in high['blocks']:
  if block['name']=='(no block)':continue
  labels=['B'+block['name']]
  for nid in block['nodes']:
   n=nodes[nid];s=short(n)
   if s not in visible:continue
   desc=str(nid)+' '+s+' '+str(n['properties'].get('stamp',''))
   if s in ('ValuePhiNode','AddNode','IntegerLessThanNode','BoxNode$AllocatingBoxNode','ReturnNode','UnwindNode'):
    desc+=' ['+', '.join(e['label']+'='+str(e['from'])+((':'+str(nodes[e['from']]['properties']['value']))if 'value'in nodes[e['from']]['properties']else '')for e in incoming[nid]if e['type']!='Successor'and e['label']not in ('merge','stateAfter'))+']'
   if s=='CommitAllocationNode':desc+=' '+str({k:v for k,v in n['properties'].items()if k.startswith('object(')})
   labels.append(desc)
  color='#def4e9'if block['name']in hc else '#f4f5f7'
  lines.append('"B'+block['name']+'" [style=filled,fillcolor="'+color+'",label='+json.dumps('\n'.join(labels)) +'];')
  for target in block['successors']:lines.append('"B'+block['name']+'" -> "B'+target+'";')
 lines.append('}')
 (HERE/(capture+'-C-cfg.dot')).write_text('\n'.join(lines)+'\n')
 return {'label':label,'capture':capture,'compilationId':cid,'graphDirectory':str(p.relative_to(HERE)),'nodeCounts':{'afterPE':len(pe['nodes']),'beforeHigh':len(high['nodes']),'afterMid':len(mid['nodes'])},'inlineTargets':[{'name':n['properties'].get('name'),'state':n['properties'].get('state')}for n in inline['nodes']],'loops':[info(n)for n in high['nodes']if short(n)in ('LoopBeginNode','LoopEndNode','LoopExitNode')],'loopPhis':phis,'beforeHighCyclicBlocks':sorted(hc),'afterMidCyclicBlocks':sorted(mc),'lateAllocations':allocs,'calls':[info(n)for n in high['nodes']if short(n)=='MethodCallTargetNode'],'exceptionEscapes':[{'node':info(n),'inputs':inputs(n['id'])}for n in high['nodes']if short(n)=='UnwindNode'],'commits':[{**info(n),'objects':{k:v for k,v in n['properties'].items()if k.startswith('object(')}}for n in high['nodes']if short(n)=='CommitAllocationNode']}
rows=[summarize(*case)for case in CASES]
for row in rows[1:]:
 assert not row['calls'] and not row['exceptionEscapes']
 assert len(row['lateAllocations'])==1 and row['lateAllocations'][0]['instanceClass']=='java.lang.Long'and not row['lateAllocations'][0]['inCyclicBlock']
for capture,_,_ in CASES:
 provenance=json.loads((HERE/capture/'provenance.json').read_text())
 actual={Path(p['path']).name:p['sha256']for p in provenance['runtimeJars']}
 expected={Path(p['path']).name:p['sha256']for p in provenance['frozenManifest']['contents']['runtimeJars']}
 assert actual==expected,(capture,'frozen JAR mismatch')
 for artifact in provenance['rawGraphs']:
  assert hashlib.sha256(Path(artifact['path']).read_bytes()).hexdigest()==artifact['sha256']
(HERE/'graph-proof.json').write_text(json.dumps({'scope':'Actual scheduled graphs for synthetic cycleInner. Assertions concern static graph shape and allocations, not throughput. Cyclic-block membership is computed from scheduled successor edges.','roots':rows},indent=2)+'\n')
print('Verified three frozen classpaths, raw BGV hashes, and candidate/AST zero-call/zero-packet loops with only noncyclic result boxes.')
