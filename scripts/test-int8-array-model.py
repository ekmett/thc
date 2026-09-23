#!/usr/bin/env python3
import importlib.util
from pathlib import Path
import unittest
spec=importlib.util.spec_from_file_location('int8_arrays',Path(__file__).with_name('prepare-int8-arrays.py'))
model=importlib.util.module_from_spec(spec);spec.loader.exec_module(model)
class Int8ArrayModelTest(unittest.TestCase):
    def test_public_models_against_sequential_cell_updates(self):
        for unsigned in (False,True):
            def norm(x): return x & 255
            def wide(x): return x if unsigned or x<128 else x-256
            for raw in model.inputs():
                cells=[norm(raw)]*8
                for i,value in [(-3,3),(0,5),(-3,-2),(4,norm(raw))]:cells[i+3]=norm(cells[i+3]+value)
                st=[norm(raw)]*8;st[3]=norm(st[0]+7);st[7]=norm(3*st[3]-st[0])
                for suffix,storage in [('Accum',cells),('ST',st)]:
                    expected=sum(w*wide(storage[i]) for w,i in [(7,0),(11,3),(13,7)])
                    self.assertEqual(expected,model.mathematical('unboxed'+('Word8' if unsigned else 'Int8')+suffix,raw))
    def test_alias_model_against_bytearray_snapshots(self):
        for raw in model.inputs():
            storage=bytearray([raw%256,(raw^0x55)%256])
            signed=lambda i:int.from_bytes(storage[i:i+1],'big',signed=True)
            before,before_u=signed(0),storage[0]
            storage[0]=(raw+101)%256;storage[1]=(raw+37)%256
            expected=3*before+5*before_u+7*signed(0)+11*storage[1]+13*signed(0)+17*signed(1)+19*storage[0]+23*storage[1]
            self.assertEqual(expected,model.mathematical('aliasBytes',raw))
            self.assertEqual(raw,model.mathematical('emptyBytes',raw))
            initial=bytes([raw%256])
            for name in ("rawSignedRead","rawSignedIndex","rawUnsignedRead"):
                self.assertEqual(int.from_bytes(initial,"big",signed=name!="rawUnsignedRead"),model.mathematical(name,raw))
    def test_every_byte_pattern_and_fullwidth_boundary(self):
        values=set(model.inputs());self.assertTrue(set(range(-256,256))<=values)
        for bit in range(64):
            self.assertTrue({model.signed(sign*((1<<bit)+d)) for sign in (-1,1) for d in (-1,0,1)}<=values)
        for name in [n for n in model.ENTRIES if n != "emptyBytes"]:
            for x in range(256):self.assertEqual(model.mathematical(name,x),model.mathematical(name,x+256))
        self.assertNotEqual(model.mathematical('unboxedInt8ST',128),model.mathematical('unboxedWord8ST',128))
    def test_oracle_completeness_is_fail_closed(self):
        values=model.inputs();text=''.join(f'{n}\t{x}\t{model.mathematical(n,x)}\n' for n in model.ENTRIES for x in values)
        self.assertEqual(len(values)*len(model.ENTRIES),len(model.parse_rows(text,values)))
        for bad in [text+text.splitlines()[0]+'\n','\n'.join(text.splitlines()[1:]),text+'bad\t0\t0\n']:
            with self.assertRaises(AssertionError):model.parse_rows(bad,values)
if __name__=='__main__':unittest.main()
