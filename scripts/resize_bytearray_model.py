"""Defined-domain list model; retired native references are never observed."""
ENTRIES=('resizedBytes','resizedTwiceWrites')
REQUIRED={n:'resizeMutableByteArray#' for n in ENTRIES}
def signed(n):return (n+2**63)%2**64-2**63

def inputs():
    pairs={(signed(code*0x123456789abcdef),code) for code in range(17*17)}
    pairs|={(seed,old+17*new) for seed in range(256) for old,new in ((0,16),(16,0),(16,8),(8,16),(8,8))}
    pairs|={(seed,code) for seed in (-2**63,-257,-1,0,255,256,2**63-1) for code in (-2**63,-1,0,2**63-1)}
    return sorted(pairs)

def sizes(code):
    key=code&1023
    return key%17,key//17%17

def resized(source,size,seed):
    # Only preserve the retained prefix; write every byte newly introduced.
    return source[:size]+[(seed+17*i)%256 for i in range(len(source),size)]

def bytes_model(name,raw,code):
    old,size=sizes(code);initial=[(raw+17*i)%256 for i in range(old)]
    result=resized(initial,size,raw+91)
    if name=='resizedTwiceWrites':
        final=(size+7)%17;result=resized(result,final,raw+133)
        if result:result[-1]=(raw+211)%256
    else:assert name=='resizedBytes'
    return result

def mathematical(name,raw,code):
    result=bytes_model(name,raw,code);answer=len(result)
    for byte in result:answer=answer*257+byte
    return signed(answer)

def rows():return [(n,x,c,mathematical(n,x,c)) for n in ENTRIES for x,c in inputs()]

def verify(text):
    actual=[(n,int(x),int(c),int(y)) for n,x,c,y in (line.split('\t') for line in text.splitlines())]
    assert actual==rows(),'Resize native/model mismatch, missing, duplicated or reordered row'
    return actual
