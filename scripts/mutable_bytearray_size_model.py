"""Independent size/state-observation model: no retired native references."""
ENTRIES=('freshSize','pureSize','resizedSizes','pureAfterResize','orderedSize')
REQUIRED={name:('sizeofMutableByteArray#' if name in ('pureSize','pureAfterResize') else 'getSizeofMutableByteArray#') for name in ENTRIES}
WORKERS={name:('pureSizeWorker' if name in ('pureSize','pureAfterResize') else 'getSizeWorker') for name in ENTRIES}
def signed(n):return (n+2**63)%2**64-2**63

def inputs():
    pairs={(signed(code*0x123456789abcdef),code) for code in range(17*17)}
    pairs|={(seed,8+17*16) for seed in range(256)}
    pairs|={(seed,code) for seed in (-2**63,-257,-1,0,255,256,2**63-1)
            for code in (-2**63,-1,0,16,17,288,1023,4095,4096,2**63-1)}
    return sorted(pairs)

def mathematical(name,raw,code):
    key=code&1023;old=key%17;middle=key//17%17
    if name in ('freshSize','pureSize'):return code&4095
    if name=='resizedSizes':return old*65536+middle*256+(middle+7)%17
    if name=='pureAfterResize':return middle
    assert name=='orderedSize'
    before=old+1;after=middle+1
    return before+after*257+((raw+before)%256)*65537

def rows():return [(n,x,c,mathematical(n,x,c)) for n in ENTRIES for x,c in inputs()]
def verify(text):
    actual=[(n,int(x),int(c),int(y)) for n,x,c,y in (line.split('\t') for line in text.splitlines())]
    if actual!=rows():
        raise AssertionError('Mutable size native/model mismatch, missing, duplicated or reordered row')
    return actual
