"""Match roots through compilation logs: IDs identify only one engine, never cross-run roles."""
import collections,json,re
from pathlib import Path

def latest_roots(log):
    roots={}
    pattern=re.compile(r'opt done.*?\bid=(\d+)\s+(.*?)\s+\|.*?CodeSize\s+(\d+).*?\|CompId\s+(\d+).*?\|Src (.*)$')
    for line in Path(log).read_text().splitlines():
        m=pattern.search(line)
        if not m:continue
        rid,name,size,cid,source=m.groups()
        if name.startswith('org.graalvm.polyglot.'):continue
        source=re.sub(r'\s+0x[0-9a-fA-F]+$','',source)
        # Source identity is independent from root creation order and machine addresses.
        source=re.sub(r'^.*?/thc/','',source)
        roots[int(rid)]={'rootId':int(rid),'name':name,'source':source,'compilationId':int(cid),'codeBytes':int(size),'roleKey':name+' @ '+source}
    counts=collections.Counter(r['roleKey']for r in roots.values())
    for r in roots.values():r['roleAmbiguous']=counts[r['roleKey']]>1
    return sorted(roots.values(),key=lambda r:r['rootId'])

def match(old,new):
    byrole=collections.defaultdict(list)
    for r in old:byrole[r['roleKey']].append(r)
    pairs=[];unmatched=[];matched=set()
    for r in new:
        candidates=byrole[r['roleKey']]
        if r['roleAmbiguous']or len(candidates)!=1:unmatched.append({'candidate':r,'reason':'missing or ambiguous name/source identity'});continue
        b=candidates[0];matched.add(b['rootId']);pairs.append({'roleKey':r['roleKey'],'baseline':b,'candidate':r})
    for r in old:
        if r['rootId']not in matched:unmatched.append({'baseline':r,'reason':'no uniquely matching candidate root (may have fully inlined)'})
    return {'scope':'Roles match exact normalized source location plus name; engine root IDs are evidence only. Ambiguities and roots with no separate compilation remain explicit.','pairs':pairs,'unmatched':unmatched}

if __name__=='__main__':
    import argparse
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('log',type=Path);p.add_argument('--baseline-log',type=Path);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    rows=latest_roots(a.log);result={'roots':rows}
    if a.baseline_log:result['comparison']=match(latest_roots(a.baseline_log),rows)
    a.output.write_text(json.dumps(result,indent=2)+'\n')
