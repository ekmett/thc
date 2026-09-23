#!/usr/bin/env python3
"""Bounded summary of latest raw graph captures; no guest execution."""
from pathlib import Path
import collections,hashlib,importlib.util,json,re,sys
ROOT=Path(__file__).resolve().parents[2];capture=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else ROOT/'work/perf-sprint-v2/ast-graphs'
spec=importlib.util.spec_from_file_location('compare',ROOT/'work/typed-graph-review/compare.py');mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)
audit=json.loads((capture/'packet-audit.json').read_text());rows=[]
for row in audit['graphs']:
 if audit['latestCompilationByRoot'][row['rootKey']]!=row['compilationId']:continue
 r=mod.inspect(capture,row)
 for key in ['boxingOperationNodes','guestTargetNodes']:r.pop(key,None)
 folder=next(p for p in capture.iterdir()if p.name.startswith(f"parsed-TruffleHotSpotCompilation-{row['compilationId']}["));idx=json.loads((folder/'index.json').read_text())
 item=next(g for g in idx['graphs']if 'After mid tier'in g['name']);graph=json.loads((folder/item['file']).read_text())
 allocations=[]
 for n in graph['nodes']:
  if n['nodeClass'].endswith(('NewInstanceNode','NewArrayNode')):
   p=n['properties'];source=p.get('nodeSourcePosition','');origin=next((line for line in source.splitlines()if line.startswith('thc.runtime.')),'unknown')
   allocations.append({'node':n['id'],'block':n['block'],'class':p.get('instanceClass',str(p.get('elementType'))+'[]'),'origin':origin.split('(truffle:')[0],'source':[line.split('(truffle:')[0]for line in source.splitlines()[:8]],'guestLocations':list(dict.fromkeys(re.findall(r'\(truffle:([^)]*)\)',source)))[:6]})
 r['lateLongArrayPackets']=sum(a['class']=='java.lang.Long[]' for a in allocations);r['allResidualReferenceArrayPackets']=r['lateArrays']+r['lateLongArrayPackets'];r['materializedAllocations']=allocations;r['materializedClasses']=dict(collections.Counter(a['class']for a in allocations));r['frontierStateCounts']=dict(collections.Counter(x['state']for x in r['activeFrontier']));rows.append(r)
result={'claimScope':'Static latest scheduled graph sites; dynamic allocation attributed separately with JFR. Labels/root IDs are new Core export and cannot be matched blindly to older exports.','roots':rows}
(capture/'summary.json').write_text(json.dumps(result,indent=2)+'\n')
for r in rows:
 print(r['root'],r['compilationId'],'PE/mid',r['peNodes'],r['afterMidNodes'],'calls',r['guestCalls'],'arrays/Long',r['lateArrays'],r['lateLongBoxes'],'alloc',r['materializedClasses'],'frontier',r['frontierStateCounts'])
