#!/usr/bin/env python3
"""Publish bounded evidence extracted from the original BGV captures; no JVM work."""
from pathlib import Path
import collections, hashlib, json, os, re, shutil, tarfile
ROOT=Path(__file__).resolve().parents[2];WORK=ROOT/'work/source-notes-graph-review';OUT=ROOT/'docs/source-note-graphs'
CASES=['ast-on','ast-off','bytecode-on','bytecode-off','ast-v2-on']
PHASES={'pe':'After PE Tier','before-high':'Before phase HighTierLowering','after-mid':'After mid tier'}
PROPS=['stamp','value','rawvalue','targetMethod','field','instanceClass','elementType','keys','keySuccessors','negated','reason','action']
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def write(p,x):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(x,indent=2)+'\n')
def read(case,phase):
    d=WORK/case;r=json.loads((d/'join-graph-audit.json').read_text())['roots'][0];p=d/r['graphDirectory'];idx=json.loads((p/'index.json').read_text())
    return json.loads((p/next(g['file']for g in idx['graphs']if PHASES[phase]in g['name'])).read_text())
def topology(g):
    return {'nodes':sorted((n['id'],n['nodeClass'],n['block'])for n in g['nodes']),
        'edges':sorted((e['from'],e['to'],e['label'],e['type'],e['fromIndex'],e['toIndex'],e['listIndex'])for e in g['edges']),
        'blocks':g['blocks']}
def digest(x):return hashlib.sha256(json.dumps(x,sort_keys=True,separators=(',',':')).encode()).hexdigest()
def compact_node(n):
    p=n['properties'];source=str(p.get('nodeSourcePosition',''))
    return {k:n[k]for k in ['id','nodeClass','block']}|{'properties':{k:p[k]for k in PROPS if k in p},
        'javaSourcePosition':[re.sub(r'\(truffle:[^)]*\)','',line)for line in source.splitlines()[:4]],
        'guestSourcePositions':list(dict.fromkeys(re.findall(r'\(truffle:([^)]*)\)',source)))}
def prune(value):
    if isinstance(value,dict):return {k:prune(v)for k,v in value.items()if k not in ['sourcePosition','sourceEvidence','valueExpressions']}
    if isinstance(value,list):return [prune(v)for v in value]
    return value
def expression_proof(g,ids):
    ns={n['id']:n for n in g['nodes']};incoming=collections.defaultdict(list)
    for e in g['edges']:incoming[e['to']].append(e)
    visited=set();pending=list(ids)
    while pending:
        nid=pending.pop()
        if nid in visited:continue
        visited.add(nid)
        for e in incoming[nid]:
            if e['type']=='Value'and ns[e['from']]['nodeClass'].split('.')[-1]not in ['FrameState','VirtualObjectState','MaterializedObjectState']:
                pending.append(e['from'])
    return {'selectedPhis':ids,'nodes':[compact_node(ns[n])for n in sorted(visited)],
        'edges':[e for e in g['edges']if e['from']in visited and e['to']in visited]}
OUT.mkdir(parents=True,exist_ok=True)
comparisons=[];rows=[];archive=[]
for case in CASES:
    d=WORK/case;a=json.loads((d/'join-graph-audit.json').read_text())['roots'][0];pr=json.loads((d/'provenance.json').read_text())
    assert pr['status']=='complete'
    r=prune(a);r['capture']=case
    r['diagnostics']=pr['diagnostics'];r['compiledSourceEvents']=pr['compiledSourceEvents']
    high=read(case,'before-high');mid=read(case,'after-mid')
    r['sourceEvidenceExamples']=[compact_node(n)for n in high['nodes']if n['nodeClass'].endswith(('.LoopBeginNode','.AddNode'))and 'RepresentationAudit.hs'in n['properties'].get('nodeSourcePosition','')][:3]
    r['primitiveRecurrence']=expression_proof(high,[1979,1984]if case=='ast-v2-on'else[8186,8191]if case.startswith('ast')else[2305,2306])
    rows.append(r)
    target=OUT/'captures'/case;target.mkdir(parents=True,exist_ok=True)
    for name in ['provenance.json','run.tsv','run.log','command.sh']:
        shutil.copyfile(d/name,target/name)
    bg=d/a['rawGraph'];archive.append((bg,f'{case}/{bg.name}'))
    if case.endswith('-on'):
        for phase in PHASES:
            g=read(case,phase)
            compact={k:g[k]for k in ['name','group','ordinal','dumpId','graphType']}
            compact.update(nodes=[compact_node(n)for n in g['nodes']],edges=g['edges'],blocks=g['blocks'])
            write(target/f'{phase}.json',compact)
        shutil.copyfile(d/a['cfg'],target/'before-high-cfg.dot')
        # The retained graph labels explicitly disclose omitted debug/bookkeeping labels.
for backend in ['ast','bytecode']:
    on_provenance=json.loads((WORK/(backend+'-on')/'provenance.json').read_text())
    off_provenance=json.loads((WORK/(backend+'-off')/'provenance.json').read_text())
    assert on_provenance['fixture']['sha256']==off_provenance['fixture']['sha256']
    assert {Path(x['path']).name:x['sha256']for x in on_provenance['runtimeJars']}=={Path(x['path']).name:x['sha256']for x in off_provenance['runtimeJars']}
    for phase in PHASES:
        on=read(backend+'-on',phase);off=read(backend+'-off',phase)
        onprops={n['id']:{k:n['properties'][k]for k in PROPS if k in n['properties']}for n in on['nodes']}
        offprops={n['id']:{k:n['properties'][k]for k in PROPS if k in n['properties']}for n in off['nodes']}
        comparisons.append({'backend':backend,'phase':phase,'onNodes':len(on['nodes']),'offNodes':len(off['nodes']),
            'onBlocks':len(on['blocks']),'offBlocks':len(off['blocks']),'onEdges':len(on['edges']),'offEdges':len(off['edges']),
            'exactLabeledTopologyEqual':topology(on)==topology(off),'selectedSemanticPropertiesEqual':onprops==offprops,
            'onTopologySha256':digest(topology(on)),'offTopologySha256':digest(topology(off)),
            'comparedProperties':PROPS,
            'propertyDifferences':[{'node':nid,'property':k,'on':onprops[nid].get(k),'off':offprops[nid].get(k)}for nid in onprops for k in PROPS if onprops[nid].get(k)!=offprops[nid].get(k)]})
assert all(r['exactLabeledTopologyEqual']for r in comparisons)
write(OUT/'topology-comparison.json',{'scope':'Same frozen JARs and identical exported Core; only sourceNotesEnabled differs. Exact node IDs/classes/block membership, every labeled/indexed edge, and scheduled blocks compare equal, not merely histograms. Selected bytecode node properties also compare equal. AST retains a per-process root bloom mask: its Constant150 value/stamp and dependent Or151 stamp differ and are reported explicitly. Source positions, compilation identifiers, machine code addresses and timing/profile annotations are excluded. This comparison proves equality only for these captured phases and this fixture.', 'comparisons':comparisons})
write(OUT/'join-graph-audit.json',{'scope':'Selected real guest-root graphs. Host bridge captures are intentionally excluded; their allocation sites are not the local join. All materialization counts are static sites; primitiveRecurrence stores exact SSA nodes/edges reachable from the stated counter/accumulator phis. Large recursive duplicated source strings are omitted; full original source positions remain in the BGV archive.', 'roots':rows})
# Specific frozen enum-lowering evidence, independent from high-level source speculation.
high=read('ast-on','before-high');ids={5,253,254,255,7846,7847,7848}
write(OUT/'ast-enum-barrier.json',{'compilationId':1910,'source':'frozen FunctionBody.execute, Program.kt:514',
    'chain':'constant ordinal 0 (5) -> synthetic enum mapping int[] (254) -> LoadIndexed (253) -> IntegerSwitch (255)',
    'nodes':[compact_node(n)for n in high['nodes']if n['id']in ids],
    'edges':[e for e in high['edges']if e['from']in ids and e['to']in ids],
    'loopEntries':[compact_node(n)for n in high['nodes']if n['nodeClass'].endswith('.LoopBeginNode')],
    'status':'Fixed in the separate v2 capture: direct enum identity comparisons remove the indexed array load and IntegerSwitch, reducing before-high nodes from 431 to 86 and loops from 9 to 1. Performance validation is recorded separately.'})
shutil.copyfile(WORK/'FunctionBody.javap.txt',OUT/'FunctionBody.javap.txt')
shutil.copyfile(ROOT/'work/source-notes-v1/manifest.json',OUT/'runtime-manifest.json')
shutil.copyfile(ROOT/'work/source-notes-v2/manifest.json',OUT/'runtime-v2-manifest.json')
shutil.copyfile(ROOT/'work/source-notes-v1/core/RepresentationAudit.json',OUT/'RepresentationAudit.json')
for name in ['capture.sh','provenance.py','audit.py','publish.py']:
    shutil.copyfile(WORK/name,OUT/('capture-source.sh'if name=='capture.sh'else name.replace('.py','-source.py')))
if os.environ.get('THC_ARCHIVE', '1') == '1':
    with tarfile.open(OUT/'selected-bgv.tar.xz','w:xz',preset=6)as tar:
        for p,name in archive:tar.add(p,arcname=name)
        p=WORK/'ast-on/tools/provenance-at-capture.py';tar.add(p,arcname='ast-on/provenance-at-capture.py')
    with tarfile.open(OUT/'frozen-runtime-sources.tar.xz','w:xz',preset=6)as tar:
        for version in ['v1','v2']:
            frozen=ROOT/('work/source-notes-'+version)
            for p in sorted((frozen/'src').rglob('*')):
                if p.is_file():tar.add(p,arcname=version+'/'+str(p.relative_to(frozen)))
    # Check decompressed compiler payloads against the actual selected capture bytes.
    with tarfile.open(OUT/'selected-bgv.tar.xz','r:xz')as tar:
        for p,name in archive:assert hashlib.sha256(tar.extractfile(name).read()).hexdigest()==sha(p)
manifest={'runtime':json.loads((OUT/'runtime-manifest.json').read_text()),
    'runtimeV2':json.loads((OUT/'runtime-v2-manifest.json').read_text()),
    'selectedGraphs':[{'capture':name.split('/')[0],'archiveMember':name,'sha256':sha(p),'bytes':p.stat().st_size}for p,name in archive],
    'artifacts':[{'path':str(p.relative_to(OUT)),'sha256':sha(p),'bytes':p.stat().st_size}for p in sorted(OUT.rglob('*'))if p.is_file()and p.name!='manifest.json']}
write(OUT/'manifest.json',manifest)
print('Prepared five selected captures, compact phase graphs, exact topology comparisons, and SSA audits; archive mode='+os.environ.get('THC_ARCHIVE','1'))
