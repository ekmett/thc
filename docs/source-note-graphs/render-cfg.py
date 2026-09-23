#!/usr/bin/env python3
"""Generate an actual scheduled CFG from retained nodes, edges and blocks."""
import collections,json,pathlib,sys
path=pathlib.Path(sys.argv[1]);graph=json.loads(path.read_text());nodes={n['id']:n for n in graph['nodes']};incoming=collections.defaultdict(list)
for e in graph['edges']:incoming[e['to']].append(e)
succ={b['name']:b['successors']for b in graph['blocks']}
def cyclic(start):
    pending=list(succ[start]);seen=set()
    while pending:
        at=pending.pop()
        if at==start:return True
        if at not in seen:seen.add(at);pending.extend(succ.get(at,[]))
    return False
visible={'StartNode','LoopBeginNode','LoopEndNode','LoopExitNode','ValuePhiNode','AddNode','SubNode','NegateNode',
    'IntegerLessThanNode','IntegerEqualsNode','IntegerSwitchNode','LoadIndexedNode','FixedGuardNode','IfNode','MergeNode',
    'ReturnNode','UnwindNode','CommitAllocationNode','AllocatedObjectNode','BoxNode$AllocatingBoxNode','UnboxNode','MethodCallTargetNode'}
lines=['digraph actual_cfg {','graph [rankdir=TB,bgcolor="white",labelloc=t,label='+json.dumps(path.parent.name+': actual scheduled CFG before HighTierLowering\nNode IDs and all block edges preserved; debug/constant/bookkeeping labels omitted')+'];',
    'node [shape=box,fontname="Menlo",fontsize=10];']
for block in graph['blocks']:
    if block['name']=='(no block)':continue
    labels=['B'+block['name']]
    for nid in block['nodes']:
        n=nodes[nid];kind=n['nodeClass'].split('.')[-1];p=n['properties']
        if kind not in visible:continue
        desc=f"{nid} {kind} {p.get('stamp','')}"
        operands=[]
        for e in incoming[nid]:
            if e['type']not in ['Value','Condition']:continue
            dep=nodes[e['from']];d=e['label']+'='+str(e['from'])
            if dep['nodeClass'].endswith('ConstantNode'):d+=':'+str(dep['properties'].get('value',''))
            operands.append(d)
        if operands:desc+=' ['+', '.join(operands)+']'
        if 'keys'in p:desc+=' keys='+str(p['keys'])
        labels.append(desc)
    color='#fff0ca'if any(nodes[n]['nodeClass'].endswith('IntegerSwitchNode')for n in block['nodes'])else'#def4e9'if cyclic(block['name'])else'#f4f5f7'
    lines.append(json.dumps('B'+block['name'])+' [style=filled,fillcolor='+json.dumps(color)+',label='+json.dumps('\n'.join(labels))+'];')
    for target in block['successors']:lines.append(json.dumps('B'+block['name'])+' -> '+json.dumps('B'+target)+';')
print('\n'.join(lines+['}']))
