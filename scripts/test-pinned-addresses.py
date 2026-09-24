#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Independent mask/shift controls and strict portable-corpus validation."""
from collections import Counter
import copy
import importlib.util
from pathlib import Path
import unittest
import pinned_address_model as model

spec = importlib.util.spec_from_file_location('pinned_prepare', Path(__file__).with_name('prepare-pinned-addresses.py'))
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)

def synthetic_structure():
    void = dict(kind='void', primReps=[], evaluated=True)
    result = dict(kind='unknown', primReps=['Word8Rep'], evaluated=False, aggregate='unboxed-tuple',
                  components=[copy.deepcopy(void),dict(kind='long',primReps=['Word8Rep'],evaluated=True)])
    state = lambda name:dict(id=name,name=name,type='State# RealWorld',rep=copy.deepcopy(void),lifted=False,coercion=False)
    continuation = ['lam',[state('s')],['void',dict(rep=copy.deepcopy(void))],dict(resultRep=copy.deepcopy(result))]
    keep = ['app',['prim','keepAlive#'],[['var','bytes'],['void',dict(rep=copy.deepcopy(void))],continuation],
            [False,False,True],False,False,dict(rep=copy.deepcopy(result))]
    state_lambda = ['lam',[state('s0')],keep,dict(resultRep=copy.deepcopy(result))]
    call = ['app',state_lambda,[['void',dict(rep=copy.deepcopy(void))]],[False],False,False,{}]
    formal = dict(id='raw',name='raw',rep=dict(kind='long',primReps=['IntRep']),lifted=False,coercion=False)
    root = dict(id='root',name='keepAliveWord8',expr=['lam',[formal],call,{}])
    return dict(roots=['root'],reachableBindings=[dict(id='root')]), [('test',dict(bindings=[root]))]

class PinnedAddressModelTest(unittest.TestCase):
    def test_aliases_match_independent_cell_formula(self):
        for name, arguments in model.cases(False):
            if name not in ('pinnedBytes', 'alignedBytes'): continue
            size, offset, raw = arguments if name == 'pinnedBytes' else (arguments[0], arguments[2], arguments[3])
            if size == 0: result = 0
            else:
                before = raw % 256
                after = before+128 if before < 128 else before-128
                first = after if offset == 0 else 11
                last = after if offset == size-1 else 13
                result = size*19+before*257+after*65537+first*17+last*23
            self.assertEqual(model.expected(name, arguments), result)

    def test_all_fingerprint_bytes_against_shift_model(self):
        for high in model.WORDS:
            for low in model.WORDS:
                for index in range(16):
                    word = high if index < 8 else low
                    want = (word >> (8*(7-index%8))) & 255
                    for name in ('fingerprintByte', 'publicFingerprintByte'):
                        self.assertEqual(model.expected(name, (high, low, index)), want)
                for index, want in enumerate((high, low)):
                    self.assertEqual(model.expected('publicFingerprintRoundtrip',(high,low,index)),want)

    def test_keepalive_every_byte_once_and_order_are_distinct(self):
        for name, delta in [('keepAliveWord8',7),('keepAliveLazy',11)]:
            for x in range(256):
                answer=model.expected(name,(x,))
                self.assertEqual(answer, x*257+(x+delta)%256)
                self.assertNotEqual(answer, x*258)  # continuation skipped
                self.assertNotEqual(answer, ((x+delta)%256)*257+(x+2*delta)%256)  # run twice

    def test_exact_unique_domains_and_defined_bounds(self):
        domain=list(model.cases());self.assertEqual(len(domain),len(set(domain)))
        self.assertEqual(set(Counter(n for n,_ in domain)),model.ENTRIES.keys()|model.PUBLIC_FRONTIERS.keys())
        for name,args in domain:
            answer=model.expected(name,args);self.assertTrue(-(1<<63)<=answer<(1<<63))
        for size in model.SIZES:
            for alignment in (8,16):
                for offset in range(size) if size else (0,):
                    self.assertIn(('alignedBytes',(size,alignment,offset,0)),domain)

    def test_invalid_native_cases_are_not_silently_normalized(self):
        for name,args in [('pinnedBytes',(-1,0,0)),('pinnedBytes',(0,1,0)),('pinnedBytes',(1,1,0)),
                          ('alignedBytes',(1,3,0,0)),('alignedBytes',(1,0,0,0)),
                          ('fingerprintByte',(0,0,16)),('fingerprintByte',(0,0,-1)),
                          ('keepAliveWord8',(1<<63,)),('keepAliveLazy',(True,))]:
            with self.assertRaises(AssertionError):model.expected(name,args)

    def test_native_rows_fail_closed_missing_duplicate_mismatch(self):
        domain=list(model.cases());text=''.join(n+'\t'+'\t'.join(map(str,(*a,model.expected(n,a))))+'\n' for n,a in domain)
        self.assertEqual(len(model.parse_rows(text,domain)),len(domain))
        for bad in (text+text.splitlines()[0]+'\n','\n'.join(text.splitlines()[1:]),'unknown\t0\t0\n'):
            with self.assertRaises(AssertionError):model.parse_rows(bad,domain)
        first,rest=text.split('\n',1)
        with self.assertRaises(AssertionError):model.parse_rows(first.rsplit('\t',1)[0]+'\t99999\n'+rest,domain)

    def test_source_count_rejects_extra_lambdas_and_wrong_state_shape(self):
        report, modules = synthetic_structure()
        self.assertEqual(prepare.guest_structure('keepAliveWord8',report,modules)['guestCalls'],3)
        for mutation in ('host_arity','state_rep','state_flag','continuation_rep','result','hidden_lambda','global'):
            changed = copy.deepcopy(modules)
            root=changed[0][1]['bindings'][0]['expr'];call=root[2];keep=call[1][2];continuation=keep[2][2]
            if mutation=='host_arity':root[1].append(copy.deepcopy(root[1][0]))
            elif mutation=='state_rep':call[1][1][0]['rep']['kind']='long'
            elif mutation=='state_flag':call[3]=[True]
            elif mutation=='continuation_rep':continuation[1][0]['rep']['primReps']=['IntRep']
            elif mutation=='result':keep[-1]['rep']['primReps']=['WordRep']
            elif mutation=='hidden_lambda':
                continuation[2]=['let',False,[dict(id='hidden',expr=['lam',[{}],['void'],{}])],continuation[2]]
            else:changed[0][1]['bindings'][0]['name']='different'
            with self.subTest(mutation=mutation), self.assertRaises(AssertionError):
                prepare.guest_structure('keepAliveWord8',report,changed)

    def test_proof_mutations_require_an_accepted_genuine_baseline(self):
        class Rejected:
            class Audit:
                def __init__(self,*args):pass
                def run(self,*args):return dict(accepted=False)
        with self.assertRaisesRegex(AssertionError,'Negative baseline rejected'):
            prepare.proof_negatives([],{},Rejected)

if __name__ == '__main__': unittest.main()
