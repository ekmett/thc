#!/usr/bin/env python3
import copy,hashlib,importlib.util,json,unittest
from pathlib import Path
from mutable_bytearray_size_model import *
ROOT=Path(__file__).resolve().parent.parent
spec=importlib.util.spec_from_file_location('bytearray_size_contracts',ROOT/'scripts/test-core-bytearrays.py')
contracts=importlib.util.module_from_spec(spec);spec.loader.exec_module(contracts)

class MutableSizeTest(unittest.TestCase):
    def test_lengths_resize_boundaries_and_byte_patterns(self):
        pairs=inputs()
        self.assertTrue({i+17*j for i in range(17) for j in range(17)}<=set(c for _,c in pairs))
        self.assertTrue({(i,8+17*16) for i in range(256)}<=set(pairs))
        for x,c in pairs:
            old=(c&1023)%17;new=(c&1023)//17%17
            self.assertEqual(c%4096,mathematical('freshSize',x,c))
            self.assertEqual(c%4096,mathematical('pureSize',x,c))
            self.assertEqual(new,mathematical('pureAfterResize',x,c))
            encoded=mathematical('resizedSizes',x,c)
            self.assertEqual((old,new,(new+7)%17),(encoded//65536,(encoded//256)%256,encoded%256))
            self.assertEqual((old+1)+(new+1)*257+((x+old+1)&255)*65537,mathematical('orderedSize',x,c))
    def test_oracle_rejects_missing_duplicate_reordered_or_wrong_rows(self):
        expected=rows();text=''.join(f'{n}\t{x}\t{c}\t{v}\n' for n,x,c,v in expected)
        self.assertEqual(expected,verify(text));lines=text.splitlines()
        for broken in (lines[1:],lines+[lines[0]],list(reversed(lines)),[lines[0].rsplit('\t',1)[0]+'\t-1']+lines[1:]):
            with self.assertRaises(AssertionError):verify('\n'.join(broken)+'\n')
    def test_mutable_size_exact_contracts_and_boundaries(self):
        for name in set(REQUIRED.values()):
            self.assertTrue(contracts.check(contracts.fixture(name)[0])['accepted'])
            for mutation in range(9):
                module,app=contracts.fixture(name);proof=app[6]['rep'];effectful=name.startswith('get')
                empty=dict(kind='unknown',aggregate='unboxed-tuple',primReps=[],components=[],evaluated=True)
                if mutation==0:app[2][0][2]['rep']['primReps']=['BoxedRep (Just Lifted)']
                elif mutation==1:app[2][0][2]['rep']['primReps']=['BoxedRep Nothing']
                elif mutation==2:app[3][0]=True
                elif mutation==3:app[2].pop();app[3].pop()
                elif mutation==4:app[2].append(copy.deepcopy(app[2][0]));app[3].append(False)
                elif mutation==5:app[6].pop('rep')
                elif effectful:
                    if mutation==6:app[2][1][2]['rep']=empty
                    elif mutation==7:proof['components'][0]=empty
                    else:
                        proof['components'][1]['primReps']=['WordRep'];proof['primReps']=['WordRep']
                elif mutation==6:app[6]['rep']=empty
                else:proof['primReps']=['WordRep' if mutation==7 else 'Int64Rep']
                report=contracts.check(module)
                self.assertIn('primitive-representation',{i['code'] for i in report['issues']},(name,mutation))
        self.assertNotIn('shrinkMutableByteArray#',contracts.CAP['primitives'])
    def test_genuine_native_provenance_and_both_core_stages(self):
        out=ROOT/'build/mutable-bytearray-size';manifest=json.loads((out/'manifest.json').read_text())
        self.assertEqual(len(rows()),manifest['nativeRows']);verify((out/'oracle.tsv').read_text())
        for group in ('inputHashes','artifactHashes'):
            for path,expected in manifest[group].items():self.assertEqual(expected,hashlib.sha256((ROOT/path).read_bytes()).hexdigest(),path)
        for stage,paths in manifest['stages'].items():
            modules=[(p,json.loads((ROOT/p).read_text())) for p in paths]
            for name in ENTRIES:
                report=contracts.audit.Audit(modules,contracts.CAP).run([name])
                self.assertTrue(report['accepted'],(stage,name,report['issues']))
                self.assertIn(REQUIRED[name],{p['name'] for p in report['primitives']})
                self.assertTrue(any(b['id'].endswith('.'+WORKERS[name]) for b in report['reachableBindings']))
if __name__=='__main__':unittest.main()
