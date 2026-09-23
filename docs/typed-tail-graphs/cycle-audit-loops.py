#!/usr/bin/env python3
"""Inventory actual cycle graphs; static evidence only, with no guest execution."""
import collections,importlib.util,json,pathlib,re,sys
capture=pathlib.Path(sys.argv[1]);root=pathlib.Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('packets',root/'tools/audit-call-packets.py');packets=importlib.util.module_from_spec(spec);spec.loader.exec_module(packets)
_,latest=packets.read_capture(capture)
rows=[]
for key,row in latest.items():
 directory=capture / ('parsed-'+pathlib.Path(row['bgv']).stem)
 index=json.loads((directory/'index.json').read_text())
 def phase(pattern):
  g=next((g for g in index['graphs']if pattern in g['name']),None)
  return None if g is None else json.loads((directory/g['file']).read_text())
 pe=phase('After PE Tier');high=phase('Before phase HighTierLowering');mid=phase('After mid tier')
 def short(n):return n['nodeClass'].split('.')[-1]
 def src(n):return n['properties'].get('nodeSourcePosition','')
 def brief(n):return {'node':n['id'],'class':short(n),'block':n.get('block'),'source':src(n).splitlines()[:5],**{k:n['properties'][k]for k in ['stamp','instanceClass','targetMethod']if k in n['properties']}}
 loops=[brief(n)for n in high['nodes']if short(n)in('LoopBeginNode','LoopEndNode','LoopExitNode')]
 phis=[brief(n)for n in high['nodes']if short(n)=='ValuePhiNode'and n['properties'].get('stamp','').startswith('i64')]
 instances=collections.Counter(n['properties'].get('instanceClass')for n in mid['nodes']if short(n).endswith('NewInstanceNode'))
 trampoline=[brief(n)for n in high['nodes']if any(x in src(n)for x in ['TailCallRepeatingNode.','TailCallLoop.']) and short(n)not in('FrameState','VirtualObjectState','MaterializedObjectState')]
 r={'root':key,'compilationId':row['compilationId'],'peNodes':None if pe is None else len(pe['nodes']),'beforeHighNodes':row['beforeHighNodes'],'afterMidNodes':row['afterMidNodes'],'lateObjectArrays':row['lateObjectArrayCount'],'lateInstances':dict(instances),'methodTargets':row['methodTargets'],'loops':loops,'primitivePhis':phis,'trampolineSourceNodes':trampoline,'callNodes':[brief(n)for n in high['nodes']if short(n)=='MethodCallTargetNode']}
 rows.append(r)
 print(r['compilationId'],key,'loops',sum(x['class']=='LoopBeginNode'for x in loops),'i64phis',len(phis),'arrays',r['lateObjectArrays'],'instances',r['lateInstances'],'calls',r['methodTargets'],'trampolineNodes',len(trampoline))
(capture/'cycle-graph-audit.json').write_text(json.dumps({'scope':'Actual compiled graph inventory. A loop by itself is not proof of a guest-cycle loop: inspect source positions, phi/backedge data flow, and absence of trampoline frames/calls.','roots':rows},indent=2)+'\n')
