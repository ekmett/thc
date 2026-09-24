#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

import copy,importlib.util,json,unittest
from pathlib import Path
from resize_bytearray_model import *
ROOT=Path(__file__).resolve().parent.parent
spec=importlib.util.spec_from_file_location('bytearray_tests',ROOT/'scripts/test-core-bytearrays.py')
contracts=importlib.util.module_from_spec(spec);spec.loader.exec_module(contracts)

class ResizeModelTest(unittest.TestCase):
    def test_all_lengths_and_byte_patterns(self):
        pairs=inputs()
        self.assertEqual({(a,b) for a in range(17) for b in range(17)},{sizes(c) for _,c in pairs})
        for a,b in ((0,16),(16,0),(16,8),(8,16),(8,8)):
            self.assertTrue({(seed,a+17*b) for seed in range(256)}<=set(pairs))
        for name in ENTRIES:
            for raw,code in pairs:
                old,new=sizes(code);result=bytes_model(name,raw,code)
                final=new if name=='resizedBytes' else (new+7)%17
                self.assertEqual(final,len(result))
                kept=min(old,new,final)
                for i in range(kept-(1 if name=='resizedTwiceWrites' and kept==final and final else 0)):
                    self.assertEqual((raw+17*i)%256,result[i])
                if name=='resizedTwiceWrites' and final:self.assertEqual((raw+211)%256,result[-1])
    def test_oracle_rejects_missing_duplicate_reordered_and_wrong_rows(self):
        expected=rows();text=''.join(f'{n}\t{x}\t{c}\t{v}\n' for n,x,c,v in expected)
        self.assertEqual(expected,verify(text))
        for bad in ('\n'.join(text.splitlines()[1:]),text+text.splitlines()[0]+'\n','\n'.join(reversed(text.splitlines())),text.replace('\t0\n','\t1\n',1)):
            if bad!=text:
                with self.assertRaises(AssertionError):verify(bad)
    def test_state_reference_pair_is_exact_and_shrink_stays_disabled(self):
        name='resizeMutableByteArray#'
        self.assertTrue(contracts.check(contracts.fixture(name)[0])['accepted'])
        self.assertNotIn('shrinkMutableByteArray#',contracts.CAP['primitives'])
        for mutation in range(7):
            module,app=contracts.fixture(name);proof=app[6]['rep']
            if mutation==0:proof['components'][0].update(kind='unknown',aggregate='unboxed-tuple',components=[])
            elif mutation==1:proof['components'].pop(0)
            elif mutation==2:
                proof['components'][1]['primReps']=['BoxedRep (Just Lifted)'];proof['primReps']=['BoxedRep (Just Lifted)']
            elif mutation==3:app[2][1][2]['rep']['primReps']=['WordRep']
            elif mutation==4:app[2][2][2]['rep'].update(kind='unknown',aggregate='unboxed-tuple',components=[])
            elif mutation==5:app[2][0][2]['rep']['primReps']=['BoxedRep Nothing']
            else:app[3][2]=True
            self.assertIn('primitive-representation',{x['code'] for x in contracts.check(module)['issues']},mutation)
    def test_genuine_prepared_native_and_both_core_stages(self):
        out=ROOT/'build/resize-bytearrays';manifest=json.loads((out/'manifest.json').read_text())
        self.assertEqual(len(rows()),manifest['nativeRows']);verify((out/'oracle.tsv').read_text())
        for stage,paths in manifest['stages'].items():
            modules=[(p,json.loads((ROOT/p).read_text())) for p in paths]
            for name in ENTRIES:
                report=contracts.audit.Audit(modules,contracts.CAP).run([name])
                self.assertTrue(report['accepted'],(stage,name,report['issues']))
                self.assertTrue(any(b['id'].endswith('.resizeWorker') for b in report['reachableBindings']))
                self.assertIn('resizeMutableByteArray#',{p['name'] for p in report['primitives']})
if __name__=='__main__':unittest.main()
