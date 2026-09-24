# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Independent byte-list semantics on the defined GHC mutable-copy domains."""
ENTRIES = ('filledBytes', 'movedBytes', 'disjointBytes', 'copiedMutableBytes', 'copiedDisjointBytes', 'publicReplicate')
REQUIRED = dict(filledBytes='setByteArray#', movedBytes='copyMutableByteArray#',
                disjointBytes='copyMutableByteArrayNonOverlapping#', copiedMutableBytes='copyMutableByteArray#',
                copiedDisjointBytes='copyMutableByteArrayNonOverlapping#', publicReplicate='setByteArray#')
def signed(value): return (value + 2**63) % 2**64 - 2**63

def inputs():
    values = set(range(-256, 512)) | {signed(s*((1 << bit)+d)) for s in (-1,1) for bit in range(64) for d in (-1,0,1)}
    # Each fill carrier sees a full-range fill; independent range selectors cover
    # every contained interval, both overlapping move directions and disjoint halves.
    pairs = {(x,72) for x in values} | {(signed(code*0x123456789abcdef-2**63),code) for code in range(1024)}
    pairs |= {(x,code) for x in (-2**63,-257,-1,0,255,256,2**63-1) for code in (-2**63,-1,0,2**63-1)}
    return sorted(pairs)

def copy_range(code):
    key=code&1023;start=key%9;target=key//9%9;count=min(key//81%9,8-start,8-target)
    return start,target,count

def disjoint_range(code):
    key=code&1023;lo=key%5;hi=4+key//5%5;count=min(key//25%5,4-lo,8-hi)
    return (hi,lo,count) if key//125%2 else (lo,hi,count)

def fingerprint(bytes): return sum(value*257**i for i,value in enumerate(bytes))

def mathematical(name,raw,code):
    source=[(raw+17*i)%256 for i in range(8)]
    if name=='publicReplicate':
        length=code&15;answer=length
        for _ in range(length): answer=answer*257+(raw%256)
        return signed(answer)
    if name=='filledBytes':
        key=code&1023;start=key%9;count=min(key//9%9,8-start)
        source[start:start+count]=[raw%256]*count
        return signed(fingerprint(source))
    if name=='disjointBytes': start,target,count=disjoint_range(code)
    else: start,target,count=copy_range(code)
    if name in ('movedBytes','disjointBytes'):
        # Snapshot first; sequential per-byte copying in one fixed direction
        # would be wrong for one of the native overlapping-move controls.
        before=tuple(source);source[target:target+count]=before[start:start+count]
        return signed(fingerprint(source))
    assert name in ('copiedMutableBytes','copiedDisjointBytes')
    destination=[(raw+101+29*i)%256 for i in range(8)]
    destination[target:target+count]=source[start:start+count]
    source[0]=(raw+93)%256
    return signed(fingerprint(source)+65537*fingerprint(destination))

def rows(): return [(name,x,code,mathematical(name,x,code)) for name in ENTRIES for x,code in inputs()]

def verify(text):
    actual=[(name,int(x),int(code),int(value)) for name,x,code,value in (line.split('\t') for line in text.splitlines())]
    if actual!=rows():
        raise AssertionError('Mutable ByteArray native/model mismatch or incomplete/reordered rows')
    return actual
