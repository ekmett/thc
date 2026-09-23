from pathlib import Path
import re,json,hashlib
import sys
base=Path(sys.argv[1]);out=Path(sys.argv[2]);out.mkdir(parents=True,exist_ok=True)
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def inspect(p):
 s=p.read_text(); start=s.index('begin_cfg\n  name "Final HIR schedule"');end=s.index('end_cfg',start);part=s[start:end]
 blocks={}
 for b in re.findall(r'begin_block(.*?)end_block',part,re.S):
  name=re.search(r'name "(B\d+)"',b).group(1); succ=re.findall(r'"(B\d+)"',re.search(r'    successors (.*)',b).group(1)); nodes=[]
  for v in re.split(r'\nf <@',b)[1:]:
   tid=re.search(r'\ntid ([^ ]+)',v); kind=re.search(r'instruction <@([^|]+)\|@([^>]+)>@',v)
   if not tid or not kind:continue
   node={'id':tid.group(1),'kind':kind.group(1),'class':kind.group(2)}
   for key in ['location','field','nodeSourcePosition','stamp']:
    x=re.search('^'+key+': (.*)',v,re.M)
    if x:node[key]=x.group(1)
   node['instruction']=re.search(r'^instruction (.*)',v,re.M).group(1)
   nodes.append(node)
  blocks[name]={'successors':succ,'nodes':nodes}
 index=0;indices={};low={};stack=[];on=set();components=[]
 def visit(v):
  nonlocal index
  indices[v]=low[v]=index;index+=1;stack.append(v);on.add(v)
  for w in blocks[v]['successors']:
   if w not in indices:visit(w);low[v]=min(low[v],low[w])
   elif w in on:low[v]=min(low[v],indices[w])
  if low[v]==indices[v]:
   c=[]
   while True:
    w=stack.pop();on.remove(w);c.append(w)
    if w==v:break
   if len(c)>1 or v in blocks[v]['successors']:components.append(sorted(c,key=lambda a:int(a[1:])))
 for v in blocks:
  if v not in indices:visit(v)
 loops=[]
 for c in components:
  ns=[n|{'block':b} for b in c for n in blocks[b]['nodes']]
  loops.append({'blocks':c,'kinds':{k:sum(n['kind']==k for n in ns) for k in sorted({n['kind'] for n in ns})},
   'memoryOrCalls':[n for n in ns if n['kind'] in ['Read','Write','Invoke','InvokeWithException','ForeignCall','NewInstance','NewArray','CommitAllocation']],
   'arithmetic':[n for n in ns if n['kind'] in ['Add','Sub','ValuePhi','LoopBegin','LoopEnd','IntegerLessThan','IntegerEquals','If']]})
 return {'path':str(p),'sha256':sha(p),'phase':'Final HIR schedule (after lowering; allocation assessment needs memory/call attribution)','loops':loops,
 'loopCount':sum(n['kind']=='LoopBegin' for b in blocks.values() for n in b['nodes']),
 'allFields':[n|{'block':b} for b in blocks for n in blocks[b]['nodes'] if n['kind'] in ['Read','Write'] and ('handoff' in n.get('location','').lower() or 'TailCall' in n.get('location',''))]}
results=[]
for p in sorted(base.glob('*-changing-*/graphs/*EntryRoot*.cfg')):
 r=inspect(p);results.append(r);print(p.parent.parent.name,'loops',r['loopCount'],'SCCs',[x['blocks'] for x in r['loops']],'handoffMemory',len(r['allFields']))
 for l in r['loops']:
  print([(n['id'],n['kind'],n.get('location'),n.get('field'),n.get('nodeSourcePosition')) for n in l['memoryOrCalls']])
(out/'cfg-review.json').write_text(json.dumps(results,indent=2)+'\n')
