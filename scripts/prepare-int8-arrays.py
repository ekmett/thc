#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Fresh GHC9.14.1 public Int8/Word8 arrays, alias and empty-storage evidence."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / 'build/int8-arrays'

def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

# The sibling's general Core/root inventory is shared, not its lane model.
structure = load('int8_array_structure', ROOT/'scripts/prepare-int16-arrays.py')
check = structure.check
GROUPS = [dict(source='examples/THC/Unboxed8Arrays.hs', module='THC.Unboxed8Arrays',
               entries=['unboxedInt8Accum', 'unboxedInt8ST', 'unboxedWord8Accum', 'unboxedWord8ST']),
          dict(source='compiler/test-fixtures/Int8ArrayAudit.hs', module='Int8ArrayAudit', entries=['aliasBytes', 'emptyBytes', 'rawSignedRead', 'rawUnsignedRead', 'rawSignedIndex'])]
ENTRIES = [n for g in GROUPS for n in g['entries']]
NEW = {'readInt8Array#', 'writeInt8Array#', 'indexInt8Array#', 'readWord8Array#'}
REQUIRED = {'aliasBytes': NEW | {'newByteArray#', 'unsafeFreezeByteArray#', 'writeWord8Array#', 'indexWord8Array#'},
            'emptyBytes': {'newByteArray#', 'unsafeFreezeByteArray#', 'sizeofByteArray#'}}
for name, read in [('rawSignedRead', 'readInt8Array#'), ('rawUnsignedRead', 'readWord8Array#'), ('rawSignedIndex', 'indexInt8Array#')]:
    REQUIRED[name] = {'newByteArray#', 'writeInt8Array#', read}
    if name == 'rawSignedIndex': REQUIRED[name].add('unsafeFreezeByteArray#')
for kind in ('Int8', 'Word8'):
    for suffix in ('Accum', 'ST'):
        REQUIRED['unboxed'+kind+suffix] = {'newByteArray#', 'unsafeFreezeByteArray#', 'read'+kind+'Array#',
                                         'write'+kind+'Array#', 'index'+kind+'Array#', 'plus'+kind+'#'}
    REQUIRED['unboxed'+kind+'ST'] |= {'sub'+kind+'#', 'times'+kind+'#'}

def signed(value):
    return (value+(1 << 63)) % (1 << 64)-(1 << 63)

def lane(value, unsigned=False):
    bits = value % 256
    return bits if unsigned or bits < 128 else bits-256

def inputs():
    values = set(range(-256, 256))
    values |= {signed(sign*((1 << bit)+d)) for bit in range(64) for d in (-1,0,1) for sign in (-1,1)}
    values |= {signed(x) for x in (0x5555555555555555, 0xaaaaaaaaaaaaaaaa, 0x0123456789abcdef, 0xfedcba9876543210)}
    return sorted(values)

def mathematical(name, raw):
    check(name in ENTRIES, 'Unknown entry')
    check(-(1 << 63) <= raw < (1 << 63), 'Requires 64-bit carrier')
    if name == 'emptyBytes': return raw
    if name.startswith('raw'): return lane(raw, name == 'rawUnsignedRead')
    if name == 'aliasBytes':
        a, b = (raw+101) % 256, (raw+37) % 256
        return 3*lane(raw)+5*(raw%256)+20*lane(a)+34*b+17*lane(b)+19*a
    unsigned = 'Word8' in name
    if name.endswith('Accum'): cells = [raw+1, raw+5, 2*raw]
    else: cells = [raw, raw+7, 2*raw+21]
    return sum(weight*lane(x, unsigned) for weight,x in zip((7,11,13), cells))

def parse_rows(text, values):
    expected = {(n,x) for n in ENTRIES for x in values}; rows = {}
    for line in text.splitlines():
        fields = line.split('\t'); check(len(fields)==3, 'Malformed native row')
        name, raw, answer = fields; key = (name,int(raw))
        check(key in expected and key not in rows, 'Unexpected or duplicate native row')
        rows[key] = int(answer)
    check(rows.keys()==expected, 'Missing native row')
    return rows

def hashes(paths):
    return {str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(set(paths))}

def main():
    BUILD.mkdir(parents=True,exist_ok=True); manifest=BUILD/'manifest.json'; manifest.unlink(missing_ok=True)
    ghc=os.environ.get('GHC','ghc'); pkg=os.environ.get('GHC_PKG','ghc-pkg'); commands=[]
    def run(args, env=None, **kwargs):
        args=list(map(str,args)); commands.append(dict(argv=args,environment=env or {}))
        return subprocess.run(args,cwd=ROOT,env=dict(os.environ,**(env or {})),check=True,**kwargs)
    check(run([ghc,'--numeric-version'],text=True,capture_output=True).stdout.strip()=='9.14.1','Requires pinnedGHC9.14.1')
    check(run([pkg,'field','array','version','--simple-output'],text=True,capture_output=True).stdout.strip()=='0.5.8.0','Requires array0.5.8.0')
    audit=load('int8_audit', ROOT/'scripts/audit-core.py'); capabilities=json.loads((ROOT/'scripts/core-capabilities.json').read_text())
    paths_by_stage={}; artifacts=[]; counts={}; calls={}; closures={}
    for stage,boundary in structure.BOUNDARIES.items():
        paths=[]
        for index,group in enumerate(GROUPS):
            folder=BUILD/stage/str(index); core=folder/'core'
            options=['-fplugin-opt=THC.Plugin:post-tidy'] if stage=='post' else []
            run([ROOT/'compiler/export.sh',*options,*['-fplugin-opt=THC.Plugin:closure='+n for n in group['entries']],group['source']],
                env=dict(THC_CORE_OUT=str(core),THC_GHC_OUT=str(folder/'ghc'),THC_SOURCE_NOTES='true'))
            p=core/(group['module']+'.json');check(json.loads(p.read_text())['boundary']==boundary,'Wrong boundary')
            paths.extend([p,core/'THC.InterfaceClosure.json'])
        modules=[(str(p.relative_to(ROOT)),json.loads(p.read_text())) for p in paths]
        paths_by_stage[stage]=[p for p,_ in modules];artifacts+=paths
        for name in ENTRIES:
            report=audit.Audit(modules,capabilities).run([name]);p=BUILD/stage/(name+'.audit.json')
            p.write_text(json.dumps(report,indent=2)+'\n');artifacts.append(p)
            check(report['accepted'],name+': strict audit failed')
            used={p['name']:len(p['uses']) for p in report['primitives']}
            check(REQUIRED[name]<=used.keys(),name+': required primitive missing: '+str(REQUIRED[name]-used.keys()))
            counts[stage+'/'+name]=used;closures[stage+'/'+name]=report['reachableBindings']
            calls[stage+'/'+name]=structure.check_structure(name,report,modules)
    check(all(calls['pre/'+n]==calls['post/'+n] for n in ENTRIES),'Changed calls across Tidy')
    source=BUILD/'NativeInt8Array.hs';native=BUILD/'native';native.mkdir(exist_ok=True);binary=native/'int8-array-oracle'
    driver=['{-# LANGUAGE MagicHash #-}','module Main where','import GHC.Exts (Int(I#),Int#,int8ToInt#,word8ToWord#,word2Int#)',
            'import Data.Bits (finiteBitSize)','import qualified THC.Unboxed8Arrays as U','import qualified Int8ArrayAudit as P',
            'emit :: String -> (Int# -> Int#) -> Int -> IO ()',
            'emit n f x@(I# a) = putStrLn (n ++ "\\t" ++ show x ++ "\\t" ++ show (I# (f a)))',
            'dispatch [name,x] = case name of']
    for group in GROUPS:
        for n in group['entries']:
            target=('U.' if group['module'].startswith('THC.') else 'P.')+n
            if n.startswith('raw'):
                widen='word2Int# (word8ToWord# (P.rawUnsignedRead a))' if n=='rawUnsignedRead' else 'int8ToInt# ('+target+' a)'
                target='(\\a -> '+widen+')'
            driver.append(f'  "{n}" -> emit name '+target+' (read x)')
    driver+=['  _ -> error "unknown entry"','dispatch _ = error "bad input"',
             'main = if finiteBitSize (0::Int) /= 64 then error "Requires 64-bit Int" else getContents >>= mapM_ (dispatch . words) . lines']
    source.write_text('\n'.join(driver)+'\n')
    run([ghc,'--make','-O2','-fforce-recomp','-dcore-lint','-dstg-lint','-i'+str(ROOT/'examples'),'-i'+str(ROOT/'compiler/test-fixtures'),'-odir',native,'-hidir',native,source,'-o',binary])
    values=inputs(); text=run([binary],input=''.join(f'{n}\t{x}\n' for n in ENTRIES for x in values),text=True,capture_output=True,timeout=60).stdout
    actual=parse_rows(text,values); wanted={(n,x):mathematical(n,x) for n in ENTRIES for x in values}
    check(actual==wanted,'Native/model mismatch: '+str(next(((k,actual[k],v) for k,v in wanted.items() if actual[k]!=v),None)))
    (BUILD/'oracle.tsv').write_text(text);(BUILD/'expected.tsv').write_text(''.join(f'{n}\t{x}\t{v}\n' for (n,x),v in wanted.items()))
    artifacts += [source,binary,BUILD/'oracle.tsv',BUILD/'expected.tsv']
    sources=[ROOT/g['source'] for g in GROUPS]+[Path(__file__),ROOT/'scripts/test-int8-array-model.py',ROOT/'scripts/prepare-int16-arrays.py',ROOT/'scripts/core-capabilities.json',ROOT/'scripts/audit-core.py',ROOT/'src/main/resources/thc/scalar-primop-signatures.json',*sorted((ROOT/'scripts').glob('core_*.py')),*sorted((ROOT/'compiler/THC').glob('*.hs')),*[ROOT/'compiler'/n for n in ('build.sh','export.sh','toolchain.sh')]]
    manifest.write_text(json.dumps(dict(schema=1,ghc='9.14.1',array='0.5.8.0',wordBits=64,elementBits=8,entries=ENTRIES,inputs=values,stages=paths_by_stage,nativeRows=len(actual),
        allNativeResultsMatchIndependentModels=True,expectedGuestCallsByEntry={n:calls['pre/'+n] for n in ENTRIES},checkedGuestCallsByStage=calls,
        requiredPrimitivesByEntry={n:sorted(v) for n,v in REQUIRED.items()},primitiveCounts=counts,reachableBindings=closures,
        commands=commands,installedArray=run([pkg,'describe','array'],text=True,capture_output=True).stdout,
        ghcInfo=run([ghc,'--info'],text=True,capture_output=True).stdout,inputHashes=hashes(sources),artifactHashes=hashes(artifacts),
        claim='Fresh native/model, genuine public source/interface Core and whole-closure strict audits; runtime checked separately.',
        limitations=['Checked public bounds/indices are constants; dynamic error paths not claimed.','Native tests use only initialized in-bounds cells. Managed invalid bounds fail before narrowing.','Single-byte elements are independent of endianness; unsafe mutation after freeze is excluded.']),indent=2)+'\n')
    print(f'Prepared {len(ENTRIES)} byte-array entries / {len(actual)} native/model rows / strict pre+post Core')
if __name__=='__main__': main()
