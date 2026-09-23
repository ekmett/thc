#!/usr/bin/env python3
"""Freeze the capture's actual inputs and validate its checksum and attribution."""
from pathlib import Path
import datetime, hashlib, json, re, sys
ROOT=Path(__file__).resolve().parents[2]
def artifact(path):
    path=Path(path)
    return {'path':str(path.resolve()),'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'bytes':path.stat().st_size}
def now():return datetime.datetime.now(datetime.timezone.utc).isoformat()
def dump(path,value):path.write_text(json.dumps(value,indent=2)+'\n')
mode,out=sys.argv[1:3];out=Path(out)
if mode=='pre':
    frozen,backend,notes,jdk,*command=sys.argv[3:];frozen=Path(frozen)
    manifest=frozen/'manifest.json';data=json.loads(manifest.read_text())
    jars=[artifact(p)for p in sorted((frozen/'lib').glob('*.jar'))]
    expected=data.get('jars',data.get('runtimeJars'))
    assert expected,'Frozen manifest must identify its classpath'
    assert {Path(x['path']).name:x['sha256']for x in jars}=={Path(x['path']).name:x['sha256']for x in expected},'Frozen classpath hash mismatch'
    fixture=frozen/'core/RepresentationAudit.json';core=json.loads(fixture.read_text())
    binding=next(b for b in core['bindings']if b['name']=='joinLoop')
    assert core.get('sourceSpans') and core.get('sourceFiles'),'Expected the source-note export'
    assert 'joinValueArity' in json.dumps(binding),'Expected a genuine exported local join'
    tools=[ROOT/p for p in ['tools/GraphInspect.java','tools/audit-call-packets.py','work/source-notes-graph-review/capture.sh','work/source-notes-graph-review/provenance.py','work/source-notes-graph-review/audit.py']]
    dump(out/'provenance.json',{'status':'prepared','startedAt':now(),'backend':backend,'sourceNotesEnabled':notes=='true',
        'entry':'joinLoop','inputBase':10000,'instrument':False,'warmSeconds':10,'minimumWarmCalls':256,
        'command':command,'workingDirectory':str(ROOT),'runtimeManifest':{'file':artifact(manifest),'contents':data},
        'runtimeJars':jars,'fixture':artifact(fixture),'jdkRelease':artifact(Path(jdk)/'release'),'tools':[artifact(p)for p in tools]})
elif mode=='post':
    p=out/'provenance.json';data=json.loads(p.read_text())
    for item in [data['fixture'],data['runtimeManifest']['file'],data['jdkRelease']]+data['runtimeJars']+data['tools']:
        assert artifact(item['path'])['sha256']==item['sha256'],('Capture input changed',item['path'])
    row=(out/'run.tsv').read_text().strip().split('\t');assert len(row)==6,row
    calls=int(row[2]);base=int(row[3]);assert calls>0 and calls%16==0
    total=sum(max(base+i,0)*(max(base+i,0)+1)//2 for i in range(16))*(calls//16)
    expected=(total+(1<<63))%(1<<64)-(1<<63)
    assert row[0]=='joinLoop'and int(row[4])==expected,(row,expected)
    log=(out/'run.log').read_text();assert 'guestLastTierInstalled=true'in log
    diagnostics=json.loads(next(line[len('diagnostics='):]for line in log.splitlines()if line.startswith('diagnostics=')))
    assert diagnostics['backend']==data['backend'] and diagnostics['sourceNotesEnabled']==data['sourceNotesEnabled']
    if data['backend']=='bytecode':assert diagnostics['localJoinCount']>0
    if data['sourceNotesEnabled']:
        assert diagnostics['sourceSpanCount']>0 and diagnostics['sourceRootCount']>0
    else:assert diagnostics['sourceSpanCount']==0 and diagnostics['sourceRootCount']==0
    source_events=[line for line in log.splitlines()if 'opt done'in line and '|Src 'in line and 'RepresentationAudit.hs'in line]
    if data['sourceNotesEnabled']:assert source_events,'No compiled source location in Truffle trace'
    graph_files=sorted(out.glob('*.bgv'));assert graph_files,'No raw Graal graphs captured'
    data.update(status='complete',finishedAt=now(),checksumVerified=True,checksum=expected,measurementCalls=calls,
        diagnostics=diagnostics,compiledSourceEvents=source_events,rawGraphs=[artifact(p)for p in graph_files],
        reports=[artifact(out/f)for f in ['packet-audit.json','join-graph-audit.json','run.tsv','run.log','command.sh']])
    dump(p,data)
    print('Verified frozen inputs, joinLoop checksum, installed code, source metadata, and raw BGV hashes.')
else:raise SystemExit('Expected pre or post')
