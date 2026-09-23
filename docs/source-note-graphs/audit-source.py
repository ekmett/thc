#!/usr/bin/env python3
"""Extract real scheduled loop dataflow, materialization and attribution evidence."""
from pathlib import Path
import argparse, collections, importlib.util, json, re
ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('packets',ROOT/'tools/audit-call-packets.py')
packets=importlib.util.module_from_spec(spec);spec.loader.exec_module(packets)
DEBUG={'FrameState','VirtualObjectState','MaterializedObjectState'}
FORBIDDEN=('LocalJoinJump','TailCall','Closure','CapturedFrame')
def short(n):return n['nodeClass'].split('.')[-1]
def cyclic_blocks(graph):
    successors={b['name']:b['successors']for b in graph['blocks']}
    def returns(start):
        pending=list(successors[start]);seen=set()
        while pending:
            at=pending.pop()
            if at==start:return True
            if at not in seen:seen.add(at);pending.extend(successors.get(at,[]))
        return False
    return {b for b in successors if returns(b)}
def graph_summary(graph):
    nodes={n['id']:n for n in graph['nodes']};incoming=collections.defaultdict(list)
    for edge in graph['edges']:incoming[edge['to']].append(edge)
    cyclic=cyclic_blocks(graph)
    def brief(n):
        p=n['properties']
        return {'id':n['id'],'class':short(n),'block':n.get('block'),'inCyclicBlock':n.get('block')in cyclic,
            **{key:p[key]for key in ['stamp','value','instanceClass','elementType','targetMethod']if key in p},
            'sourcePosition':str(p.get('nodeSourcePosition','')).splitlines()[:8]}
    def inputs(nid):
        return [{'edge':e['label'],'index':e['listIndex'],'type':e['type'],'node':brief(nodes[e['from']])}
            for e in incoming[nid]if e['type']!='Successor'and e['label']not in ('stateAfter','stateBefore')]
    def expression(nid,seen=None,depth=0):
        seen=set()if seen is None else seen
        if nid in seen:return {'ref':nid}
        if depth>=6:return {**brief(nodes[nid]),'truncated':True}
        seen=seen|{nid}
        return {**brief(nodes[nid]),'inputs':[{'edge':e['label'],'index':e['listIndex'],
            'node':expression(e['from'],seen,depth+1)}for e in incoming[nid]
            if e['type']=='Value'and short(nodes[e['from']])not in DEBUG]}
    loops=[n for n in graph['nodes']if short(n)=='LoopBeginNode'];loopids={n['id']for n in loops}
    phis=[]
    for n in graph['nodes']:
        if short(n)=='ValuePhiNode'and any(e['from']in loopids and e['label']=='merge'for e in incoming[n['id']]):
            phis.append({'phi':brief(n),'inputs':inputs(n['id']),
                'valueExpressions':[expression(e['from'])for e in incoming[n['id']]if e['label']=='values']})
    blocks={b['name']:b for b in graph['blocks']}
    backedges=[]
    for end in graph['nodes']:
        if short(end)!='LoopEndNode':continue
        headers=[nodes[e['from']]for e in incoming[end['id']]if e['from']in loopids]
        for header in headers:
            backedges.append({'loopBegin':brief(header),'loopEnd':brief(end),
                'scheduledSuccessorVerified':header['block']in blocks.get(end['block'],{}).get('successors',[])})
    allocations=[brief(n)for n in graph['nodes']if short(n).endswith(('NewInstanceNode','NewArrayNode'))]
    commits=[{**brief(n),'objects':{k:v for k,v in n['properties'].items()if k.startswith('object(')}}
        for n in graph['nodes']if short(n)=='CommitAllocationNode']
    boxes=[{**brief(n),'inputs':inputs(n['id'])}for n in graph['nodes']if short(n).startswith('BoxNode$')]
    calls=[{**brief(n),'inputs':inputs(n['id'])}for n in graph['nodes']if short(n)=='MethodCallTargetNode']
    sources=[]
    for n in graph['nodes']:
        for key,value in n['properties'].items():
            if isinstance(value,str)and 'RepresentationAudit.hs'in value:
                sources.append({'node':n['id'],'class':short(n),'property':key,'text':value[:1600]})
    return {'phase':graph['name'],'nodes':len(nodes),'cyclicBlocks':sorted(cyclic),'loops':[brief(n)for n in loops],
        'backedges':backedges,'loopPhis':phis,'allocations':allocations,'commits':commits,'boxes':boxes,'calls':calls,
        'exceptionEscapes':[{**brief(n),'inputs':inputs(n['id'])}for n in graph['nodes']if short(n)=='UnwindNode'],
        'sourceEvidence':sources,'sourceEvidenceCount':len(sources)},nodes,incoming

def write_cfg(path,graph,nodes,incoming,cyclic):
    visible={'StartNode','LoopBeginNode','LoopEndNode','LoopExitNode','ValuePhiNode','AddNode','SubNode','NegateNode',
        'IntegerLessThanNode','IntegerEqualsNode','IfNode','MergeNode','ReturnNode','UnwindNode','CommitAllocationNode',
        'AllocatedObjectNode','BoxNode$AllocatingBoxNode','UnboxNode','MethodCallTargetNode'}
    lines=['digraph actual_cfg {','graph [rankdir=TB,bgcolor="white",labelloc=t,label="joinLoop: actual scheduled CFG before HighTierLowering\\nNode IDs and all block edges preserved; debug/bookkeeping node labels omitted"];',
        'node [shape=box,fontname="Menlo",fontsize=10];']
    for block in graph['blocks']:
        if block['name']=='(no block)':continue
        labels=['B'+block['name']]
        for nid in block['nodes']:
            n=nodes[nid]
            if short(n)not in visible:continue
            desc=f"{nid} {short(n)} {n['properties'].get('stamp','')}"
            edges=[f"{e['label']}={e['from']}"for e in incoming[nid]if e['type']=='Value'and short(nodes[e['from']])not in DEBUG]
            if edges:desc+=' ['+', '.join(edges)+']'
            if short(n)=='CommitAllocationNode':desc+=' '+str({k:v for k,v in n['properties'].items()if k.startswith('object(')})
            labels.append(desc)
        color='#def4e9'if block['name']in cyclic else '#f4f5f7'
        lines.append(json.dumps('B'+block['name'])+' [style=filled,fillcolor='+json.dumps(color)+',label='+json.dumps('\n'.join(labels))+'];')
        for target in block['successors']:lines.append(json.dumps('B'+block['name'])+' -> '+json.dumps('B'+target)+';')
    path.write_text('\n'.join(lines+['}'])+'\n')

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('capture',type=Path)
    parser.add_argument('--root',default=r'lambda_n(?:\(|_|\b)',help='Regex selecting the actual joinLoop guest root label')
    args=parser.parse_args();capture=args.capture
    allrows,latest=packets.read_capture(capture)
    selected=[row for root,row in latest.items()if re.search(args.root,root)]
    assert selected,('No matching guest root',list(latest))
    rows=[]
    for row in selected:
        directory=capture/('parsed-'+Path(row['bgv']).stem)
        index=json.loads((directory/'index.json').read_text())
        def read(phase):return json.loads((directory/next(g['file']for g in index['graphs']if phase in g['name'])).read_text())
        high=read('Before phase HighTierLowering');mid=read('After mid tier')
        hs,nodes,incoming=graph_summary(high);ms,_,_=graph_summary(mid)
        hot_allocations=[a for a in ms['allocations']if a['inCyclicBlock']]
        forbidden=[a for a in ms['allocations']if any(x in a.get('instanceClass','')for x in FORBIDDEN)]
        committed=[c for c in hs['commits']if any(any(x in str(v)for x in FORBIDDEN)for v in c['objects'].values())]
        checks={'hasNativeScheduledBackedge':any(e['scheduledSuccessorVerified']for e in hs['backedges']),
            'hasAtLeastTwoLongLoopPhis':sum(p['phi'].get('stamp','').startswith('i64')for p in hs['loopPhis'])>=2,
            'noMethodCallInCyclicBlocks':not any(c['inCyclicBlock']for c in hs['calls']),
            'noLateAllocationInCyclicBlocks':not hot_allocations,
            'noBoxInCyclicBlocks':not any(b['inCyclicBlock']for b in hs['boxes']),
            'noCommittedJoinJumpTailCallClosure':not committed,
            'noLateJoinJumpTailCallClosure':not forbidden,
            'noLateObjectArray':row['lateObjectArrayCount']==0}
        cfg=f"join-{row['compilationId']}-before-high-cfg.dot"
        write_cfg(capture/cfg,high,nodes,incoming,set(hs['cyclicBlocks']))
        rows.append({'root':row['rootKey'],'compilationId':row['compilationId'],'rawGraph':row['bgv'],
            'graphDirectory':str(directory.relative_to(capture)),'cfg':cfg,'beforeHigh':hs,'afterMid':ms,
            'checks':checks,'automaticChecksPass':all(checks.values()),
            'packetAudit':{k:row[k]for k in ['committedPacketCount','lateObjectArrayCount','lateLongBoxCount','methodTargets']}})
    result={'scope':'Actual scheduled compiler graphs. CFG cycles determine hot-loop membership; this is static evidence, not allocation-rate or throughput measurement. Inspect phi valueExpressions to tie recurrence to guest counter and accumulator, rather than equating any loop with the guest loop.',
        'rootSelection':args.root,'latestRoots':[{k:r[k]for k in ['rootKey','compilationId','bgv']}for r in latest.values()],
        'roots':rows}
    (capture/'join-graph-audit.json').write_text(json.dumps(result,indent=2)+'\n')
    for row in rows:print(row['compilationId'],row['root'],'checks',row['checks'])
if __name__=='__main__':main()
