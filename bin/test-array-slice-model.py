#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

import importlib.util
from pathlib import Path
import unittest
spec=importlib.util.spec_from_file_location('array_slices',Path(__file__).with_name('prepare-array-slices.py'))
model=importlib.util.module_from_spec(spec);spec.loader.exec_module(model)
class ArraySliceModelTest(unittest.TestCase):
    def test_list_snapshots_match_independent_linear_model(self):
        coefficients={'sliceSnapshots':(56,1119),'lazySlices':(8,116),'closureSlices':(2,1),'zeroSlices':(1,0),'publicSlices':(15,207),'publicFreezeThaw':(15,184)}
        for x in model.inputs():
            for name,(a,b) in coefficients.items():self.assertEqual(model.signed(a*x+b),model.mathematical(name,x))
    def test_domain_contains_machine_edges_and_signs(self):
        values=set(model.inputs())
        self.assertTrue({-(1<<63),(1<<63)-1,0,-1,1}<=values)
        for b in range(64):self.assertTrue({model.signed(s*((1<<b)+d)) for s in (-1,1) for d in (-1,0,1)}<=values)
    def test_oracle_is_complete_and_fail_closed(self):
        rows=[f'{n}\t{x}\t{y}\n' for n,x,y in model.rows()]
        self.assertEqual(len(rows),len(model.verify(''.join(rows))))
        for bad in (''.join(rows[1:]),''.join(rows+rows[:1]),''.join(reversed(rows)),''.join(rows[:-1])+rows[-1].rsplit('\t',1)[0]+'\t17\n'):
            with self.assertRaises(AssertionError):model.verify(bad)
if __name__=='__main__':unittest.main()
