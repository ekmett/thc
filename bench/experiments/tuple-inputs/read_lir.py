"""Read-only final-LIR SCC inventory; no JVM or new compilation."""
from pathlib import Path
import re,json,hashlib
import sys
base=Path(sys.argv[1]);out=Path(sys.argv[2]);out.mkdir(parents=True,exist_ok=True);rows=[]
for p in sorted(base.glob('*-changing-*/graphs/*EntryRoot*.cfg')):
 s=p.read_text();a=s.index('begin_cfg\n  name "After FinalCodeAnalysisStage"');b=s.index('end_cfg',a);bs={}
 for v in re.findall(r'begin_block(.*?)end_block',s[a:b],re.S):
  name=re.search(r'name "([^"]+)"',v).group(1)
  bs[name]={'successors':re.findall(r'"(B\d+)"',re.search(r'    successors (.*)',v).group(1)),
            'lines':[x.strip() for x in v.splitlines() if 'instruction ' in x]}
 def reaches(start):
  seen=set();todo=list(bs[start]['successors'])
  while todo:
   v=todo.pop()
   if v in seen:continue
   seen.add(v);todo.extend(bs[v]['successors'])
  return seen
 cyclic=[b for b in bs if b in reaches(b)];assert cyclic
 lines=[f'{b}: {line}' for b in cyclic for line in bs[b]['lines']]
 assert any(' = ADD ' in x for x in lines)
 assert any('BRANCH ' in x or 'JUMP ' in x for x in lines)
 assert not any(t in '\n'.join(lines).lower() for t in ['new_instance','new_array','tlab','handoff','tailcall','callboundary'])
 calls=[x for x in lines if 'CALL' in x]
 assert all('HotSpotThreadLocalHandshake.doHandshake(Object)' in x for x in calls),calls
 label=p.parent.parent.name;excerpt=out/(label+'-loop-lir.txt');excerpt.write_text('\n'.join(lines)+'\n')
 rows.append({'fixture':label,'cfg':str(p),'cfgSha256':hashlib.sha256(p.read_bytes()).hexdigest(),
              'cyclicBlocks':cyclic,'lines':len(lines),'excerptSha256':hashlib.sha256(excerpt.read_bytes()).hexdigest(),
              'calls':calls,'heapAccessLines':[x for x in lines if ' = LOAD ' in x or 'STORE ' in x]})
(out/'final-lir-review.json').write_text(json.dumps(rows,indent=2)+'\n')
print('Checked',len(rows),'final-LIR cyclic controls')
