#!/usr/bin/env python3
import unittest
from mutable_bytearray_model import *
class MutableByteArrayModelTest(unittest.TestCase):
    def test_fill_carriers_and_complete_range_domains(self):
        pairs=set(inputs())
        self.assertTrue({(n,72) for n in range(-256,512)}<=pairs)
        self.assertTrue({(-2**63,72),(2**63-1,72)}<=pairs)
        moves={copy_range(c) for _,c in pairs}
        self.assertTrue({(a,b,n) for a in range(9) for b in range(9) for n in range(min(8-a,8-b)+1)}<=moves)
        disjoint={disjoint_range(c) for _,c in pairs}
        self.assertTrue(any(n>0 and a<b for a,b,n in disjoint));self.assertTrue(any(n>0 and a>b for a,b,n in disjoint))
        for a,b,n in disjoint:self.assertTrue(n==0 or a+n<=b or b+n<=a)
    def test_move_is_a_snapshot_in_both_overlap_directions(self):
        for start,target,count in ((0,1,7),(1,0,7),(0,0,8),(8,8,0)):
            code=start+9*target+81*count
            source=[(127+17*i)%256 for i in range(8)];before=source[:]
            for i in range(count):source[target+i]=before[start+i]
            self.assertEqual(signed(fingerprint(source)),mathematical('movedBytes',127,code))
    def test_copy_variants_agree_on_distinct_storage(self):
        for raw,code in inputs():self.assertEqual(mathematical('copiedMutableBytes',raw,code),mathematical('copiedDisjointBytes',raw,code))
    def test_oracle_requires_exact_order_and_complete_rows(self):
        expected=rows();text=''.join(f'{n}\t{x}\t{k}\t{y}\n' for n,x,k,y in expected)
        self.assertEqual(expected,verify(text))
        for bad in ('\n'.join(text.splitlines()[1:]),text+text.splitlines()[0]+'\n','\n'.join(reversed(text.splitlines()))):
            with self.assertRaises(AssertionError):verify(bad)
if __name__=='__main__':unittest.main()
