#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Independent model, finite corpus, and strict preparation gate controls."""
import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('int_arrays',Path(__file__).with_name('prepare-int-arrays.py'))
model = importlib.util.module_from_spec(spec)
spec.loader.exec_module(model)


class IntArrayModelTest(unittest.TestCase):
    def test_public_models_against_cell_updates(self):
        for x in model.inputs():
            accum = [x]*8
            for index,value in [(-3,3),(0,5),(-3,-2),(4,x)]:
                accum[index+3] = model.signed(accum[index+3]+value)
            self.assertEqual(model.mathematical('unboxedAccum',x),
                             model.signed(7*accum[0]+11*accum[3]+13*accum[7]))
            st = [x]*8
            st[3] = model.signed(st[0]+7)
            st[7] = model.signed(3*st[3]-x)
            self.assertEqual(model.mathematical('unboxedST',x),
                             model.signed(7*st[0]+11*st[3]+13*st[7]))
            self.assertEqual(model.mathematical('unboxedEmpty',x),model.signed(x+7))

    def test_ordered_read_and_independent_storage(self):
        for x in (-2**63,-1,0,1,2**63-1):
            # Expanded algebra has distinct weights for old, final and isolated values.
            answer = 3*(x+1)+16*(x+17)+7*x+13*model.signed(x^model.PATTERN)+17*(x+71)+32
            self.assertEqual(model.mathematical('orderedInts',x),model.signed(answer))

    def test_byte_alias_model_uses_recorded_endianness_and_signed_ints(self):
        for order in ('little','big'):
            for x in (-2**63,-1,0,1,2**63-1):
                # Alternate integer masks/shifts, independent of the model's bytearray operations.
                y = (x ^ model.PATTERN) & model.MASK
                shift0,shift1 = (56,0) if order=='little' else (0,56)
                a = ((x & model.MASK) & ~(255<<shift0)) | (((x+101)&255)<<shift0)
                b = (y & ~(255<<shift1)) | (((x+37)&255)<<shift1)
                byte0 = a & 255 if order=='little' else a>>56
                byte15 = b>>56 if order=='little' else b&255
                answer = 10*model.signed(a)+16*model.signed(b)+17*byte0+19*((x+101)&255)+23*((x+37)&255)+29*byte15
                self.assertEqual(model.mathematical('aliasIntBytes',x,order),model.signed(answer))
        self.assertNotEqual(model.mathematical('aliasIntBytes',0,'little'),model.mathematical('aliasIntBytes',0,'big'))

    def test_fullwidth_boundaries_and_rows(self):
        values = model.inputs()
        self.assertEqual(values,sorted(set(values)))
        self.assertTrue({-2**63,-1,0,1,2**63-1} <= set(values))
        for bit in range(64):
            self.assertTrue({model.signed((1<<bit)+d) for d in (-1,0,1)} <= set(values))
        rows = ''.join(f'{name}\t{x}\t{model.mathematical(name,x)}\n' for name in model.ENTRIES for x in values)
        self.assertEqual(len(model.parse_rows(rows,values)),len(values)*len(model.ENTRIES))
        for changed in (rows+rows.splitlines()[0]+'\n','\n'.join(rows.splitlines()[1:])):
            with self.assertRaises(AssertionError):model.parse_rows(changed,values)

    def test_strict_gate_and_required_primitive_counts(self):
        counts = {'newByteArray#':2,'readIntArray#':2,'writeIntArray#':5,
                  'unsafeFreezeByteArray#':2,'indexIntArray#':4,'sizeofByteArray#':2}
        report = dict(accepted=True,primitives=[dict(name=n,uses=[{}]*count) for n,count in counts.items()])
        model.check_report('orderedInts',report)
        for mutation in ('rejected','missing','count'):
            bad = copy.deepcopy(report)
            if mutation=='rejected':bad['accepted']=False
            elif mutation=='missing':bad['primitives']=[p for p in bad['primitives'] if p['name']!='readIntArray#']
            else:bad['primitives'][0]['uses'].pop()
            with self.assertRaises(AssertionError):model.check_report('orderedInts',bad)


if __name__ == '__main__':
    unittest.main()
