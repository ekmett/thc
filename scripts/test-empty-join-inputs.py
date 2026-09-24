#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Exact nullary tuple join inputs; retained Typeable exports remain incomplete."""
import copy
import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('ordinary_tuple_tests', ROOT/'scripts/test-tuple-inputs.py')
t = importlib.util.module_from_spec(spec); spec.loader.exec_module(t)
CAP = dict(t.CAP, aggregateJoinInputs=['empty-unboxed-tuple'])
DISABLED = dict(CAP, aggregateJoinInputs=[])


def fixture(formals=None, actuals=None):
    m = t.fixture([t.tup(), t.LONG] if formals is None else formals, actuals)
    worker = m['bindings'].pop()
    worker.update(joinValueArity=len(worker['expr'][1]), joinResultRep=t.LONG,
                  info=dict(joinArity=len(worker['expr'][1])))
    m['bindings'][0]['expr'] = ['let', False, [worker], m['bindings'][0]['expr'], dict(rep=t.LONG)]
    return m


def report(m, cap=CAP): return t.report(m, cap=cap)
def parts(m):
    let = m['bindings'][0]['expr']
    return let[2][0], let[3]


class EmptyJoinInputs(unittest.TestCase):
    def accepts(self, m):
        r = report(m); self.assertTrue(r['accepted'], r['issues']); return r
    def rejects(self, m, code=None, cap=CAP):
        r = report(m, cap); self.assertFalse(r['accepted'])
        if code: self.assertIn(code, {i['code'] for i in r['issues']}, r['issues'])
        return r
    def test_separate_capability_and_mixed_logical_positions(self):
        for reps in ([t.tup()], [t.LONG,t.tup(),t.LONG], [t.tup(),t.STATE,t.tup(),t.LONG]):
            m=fixture(reps);self.accepts(m);self.rejects(m,'aggregate-boundary',DISABLED)
    def test_logical_exact_saturation_counts_empty_parameters(self):
        for count in (0,1,3):
            m=fixture();worker,call=parts(m)
            if count==0:m['bindings'][0]['expr'][3]=t.var('worker')
            elif count==1:call[2]=call[2][:1];call[3]=call[3][:1]
            else:call[2].append(t.lit());call[3].append(False)
            self.rejects(m,'join-arity')
    def test_same_zero_width_does_not_prove_empty_tuple(self):
        for other in (t.STATE,t.REF,t.tup(t.STATE),t.tup(t.tup()),t.tup(t.LONG)):
            self.rejects(fixture([t.tup()],[other]),'aggregate-shape')
            self.rejects(fixture([other],[t.tup()]),'aggregate-shape')
        self.accepts(fixture([t.STATE],[t.STATE]))
    def test_unknown_missing_sum_vector_and_nonempty_formals_remain_rejected(self):
        bad=[dict(t.tup(),components=None),dict(t.tup(),primReps=None),t.tup(t.STATE),t.tup(t.tup()),t.tup(t.LONG),
             dict(kind='unknown',aggregate='unboxed-sum',primReps=None,alternatives=None,evaluated=True),
             dict(kind='vector',primReps=['VecRep 2 Int64ElemRep'],vector=dict(lanes=2,element='Int64ElemRep'),evaluated=True)]
        for rep in bad:
            m=fixture();worker,_=parts(m);worker['expr'][1][0]['rep']=rep;self.rejects(m)
    def test_raw_flags_remain_unlifted_even_when_demanded(self):
        for where in ('formal','actual','missing'):
            m=fixture();worker,call=parts(m);worker['expr'][3]['entryStrict']=[True,True];call[6]['callStrict']=[True,True]
            if where=='formal':worker['expr'][1][0]['lifted']=True
            elif where=='actual':call[3][0]=True
            else:call[3]=[]
            self.rejects(m,'application-levity')
    def test_lexical_empty_proof_may_supply_missing_occurrence(self):
        m=fixture();worker,call=parts(m)
        outer=t.bind('outer',['lam',[dict(id='u',rep=t.tup(),lifted=False)],m['bindings'][0]['expr'],dict(rep=t.CLOSURE,resultRep=t.LONG)])
        call[2][0]=t.var('u',None);m['bindings']=[outer]
        # Exercise the body under an ordinary scalar wrapper, never expose a tuple host input.
        host=t.fixture([t.tup()]);outer['id']='worker';outer['name']='worker';host['bindings'][1]=outer
        # Rename the nested join to avoid shadowing the ordinary worker.
        inner=outer['expr'][2][2][0];inner['id']='join';inner['name']='join';outer['expr'][2][3][1]=t.var('join')
        self.accepts(host)
        outer['expr'][2][3][2][0]=['void'];self.rejects(host,'aggregate-shape')
    def test_cold_mismatch_and_empty_capture_stay_rejected(self):
        m=fixture();worker,call=parts(m);call[2][0]=['void',dict(rep=t.STATE)]
        body=m['bindings'][0]['expr'];m['bindings'][0]['expr']=['case',t.lit(0),'choice',[
            ['lit',['int','0'],[],t.lit()],['default',None,[],body]],dict(rep=t.LONG,binder=dict(id='choice',rep=t.LONG,lifted=False))]
        self.rejects(m,'aggregate-shape')
        m=fixture();worker,_=parts(m)
        worker['expr'][2]=['lam',[],t.var('p0',t.tup()),dict(rep=t.CLOSURE,resultRep=t.tup())]
        worker['expr'][3]['resultRep']=t.CLOSURE;worker['joinResultRep']=t.CLOSURE
        self.rejects(m,'aggregate-boundary')
    def test_full_retained_typeable_frontier_removes_only_six_formals_and_34_calls(self):
        base=ROOT/'compiler/test-fixtures/empty-join-typeable';manifest=json.loads((base/'provenance.json').read_text())
        for row in manifest['sources']:
            self.assertEqual(row['sha256'],hashlib.sha256((base/row['path']).read_bytes()).hexdigest())
        for stage in ('pre','post'):
            modules=[]
            for row in manifest['artifacts']:
                if not row['path'].startswith(stage+'-'):continue
                packed=(base/row['path']).read_bytes();self.assertEqual(row['sha256'],hashlib.sha256(packed).hexdigest())
                raw=gzip.decompress(packed);self.assertEqual(row['rawSha256'],hashlib.sha256(raw).hexdigest());self.assertEqual(row['rawBytes'],len(raw))
                modules.append((row['path'],json.loads(raw)))
            roots=['mkTrCon','fpTYPELiftedRep']
            old=t.audit.Audit(modules,DISABLED).run(roots);new=t.audit.Audit(modules,CAP).run(roots)
            self.assertFalse(old['accepted']);self.assertFalse(new['accepted'])
            self.assertEqual(40,len(old['issues']),old['issues']);self.assertEqual([],new['issues'])
            details=[i['detail'] for i in old['issues']]
            self.assertEqual(6,details.count('unboxed-tuple formal argument'));self.assertEqual(34,details.count('unboxed-tuple argument'))
            self.assertEqual(old['missingGlobals'],new['missingGlobals']);self.assertEqual(28,len(new['missingGlobals']))
            self.assertEqual(old['summary']['reachableBindings'],new['summary']['reachableBindings'])

if __name__=='__main__': unittest.main()
