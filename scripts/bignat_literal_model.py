# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Independent integer arithmetic model of GHC's 64-bit BigNat constant bytes."""
import sys
VALUES = (0, 1, -1, (1<<63)-1, 1<<63, (1<<64)-1, 1<<64, (1<<64)+1,
          (1<<127)-1, 1<<127, 1<<128, (1<<128)+(1<<63)+1,
          -((1<<128)+(1<<64)-1), (1<<192)-1, (1<<192)+1, (1<<255)+(1<<128)+3)
ENTRIES = ('integerRoundTrip','naturalRoundTrip','integerLiteral','naturalLiteral',
           'magnitudeSize','magnitudeByte','magnitudeWord','magnitudeSign')
SEEDS = tuple(dict.fromkeys((-(1<<63), (1<<63)-1, -1000, -17, -1, *range(16), 16, 31, 1<<32)))
def signed(n):
    return (n+(1<<63)) % (1<<64) - (1<<63)
def magnitude(seed):
    return abs(VALUES[seed & 15])
def size(n):
    return ((n.bit_length()+63)//64)*8
def expected(name, seed, index):
    n=magnitude(seed)
    if name in ('integerRoundTrip','naturalRoundTrip'): return seed
    if name=='integerLiteral': return signed(VALUES[seed & 15])
    if name=='naturalLiteral': return signed(n)
    if name=='magnitudeSize': return size(n)
    if name=='magnitudeSign': return int(VALUES[seed & 15] < 0)
    if name=='magnitudeWord': return signed((n >> (64*index)) & ((1<<64)-1)) if 0<=index<size(n)//8 else -1
    if name=='magnitudeByte':
        if not 0<=index<size(n): return -1
        byte=index if sys.byteorder=='little' else (index//8)*8+7-index%8
        return (n >> (8*byte)) & 255
    raise AssertionError(name)
def requests():
    for name in ENTRIES:
        for seed in SEEDS:
            indices=range(-1,size(magnitude(seed))+1) if name=='magnitudeByte' else range(-1,size(magnitude(seed))//8+1) if name=='magnitudeWord' else (0,)
            for index in indices: yield name,seed,index
def verify(text):
    actual=[tuple([p[0],*map(int,p[1:])]) for line in text.splitlines() if (p:=line.split('\t'))]
    wanted=[(*r,expected(*r)) for r in requests()]
    if actual!=wanted: raise AssertionError('BigNat native rows differ from the independent full-byte/limb model')
    return actual
