#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Heap AddrRep support must not broaden aggregate result capabilities."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('audit', ROOT/'scripts/audit-core.py')
audit = importlib.util.module_from_spec(spec); spec.loader.exec_module(audit)
CAP = json.loads((ROOT/'scripts/core-capabilities.json').read_text())
ADDRESS = dict(kind='address', primReps=['AddrRep'], evaluated=True)
DATA = dict(kind='data', primReps=['BoxedRep (Just Lifted)'], evaluated=True)
LONG = dict(kind='long', primReps=['IntRep'], evaluated=True)

def fixture(typed=True):
    constructor = dict(id='Box', name='Box', kind='boxed', arity=1, tag=1,
        fieldReps=[['AddrRep']], fieldLifted=[False], strictFields=[False])
    if typed: constructor['fieldTypes'] = [copy.deepcopy(ADDRESS)]
    value = ['app', ['con', 'Box', 1], [['lit', 'string-bytes', '41ff00', dict(rep=ADDRESS)]], [False], True, True, dict(rep=DATA)]
    body = ['case', value, 'b', [['data', 'Box', ['p'], ['lit','int','0',dict(rep=LONG)],
        dict(binders=[dict(id='p',lifted=False,rep=ADDRESS)])]], dict(rep=LONG,binder=dict(id='b',lifted=True,rep=DATA))]
    return dict(schema=1,ghc='9.14.1',constructors=[constructor],bindings=[dict(id='entry',name='entry',lifted=True,arity=1,
        expr=['lam',[dict(id='x',lifted=False,rep=LONG)],body,dict(rep=dict(kind='closure',primReps=DATA['primReps'],evaluated=True),resultRep=LONG)])])

def report(module): return audit.Audit([('fixture',module)],CAP).run(['entry'])

class AddressFields(unittest.TestCase):
    def test_address_capability_covers_heap_and_tuple_leaves(self):
        self.assertIn('AddrRep',CAP['fieldRepresentations'])
        self.assertIn('AddrRep',CAP['aggregateFieldRepresentations'])

    def test_typed_and_legacy_address_fields_are_accepted(self):
        for typed in (False,True):
            r=report(fixture(typed)); self.assertTrue(r['accepted'],r['issues'])

    def test_retained_address_field_proofs_cannot_contradict_storage(self):
        for typed in (False,True):
            for lifted in ([True],None,'invalid'):
                module=fixture(typed);module['constructors'][0]['fieldLifted']=lifted
                self.assertFalse(report(module)['accepted'],(typed,lifted))
        for bad in (dict(ADDRESS,kind='unknown'),dict(ADDRESS,evaluated=False),LONG,None):
            module=fixture();module['constructors'][0]['fieldTypes']=[bad]
            self.assertIn('constructor-field-representation',{i['code'] for i in report(module)['issues']})

    def test_exact_evaluated_address_tuple_and_nested_tuple_are_accepted(self):
        tuple_rep=dict(kind='unknown',aggregate='unboxed-tuple',components=[ADDRESS],primReps=['AddrRep'],evaluated=True)
        for rep in (tuple_rep,dict(tuple_rep,components=[tuple_rep])):
            instance=audit.Audit([],CAP);instance.representation(rep,None,'/rep')
            self.assertEqual([],instance.issues)
        for bad in (dict(ADDRESS,evaluated=False),dict(ADDRESS,kind='unknown'),LONG):
            rep=dict(tuple_rep,components=[bad])
            instance=audit.Audit([],CAP);instance.representation(rep,None,'/rep')
            self.assertTrue(instance.issues)

    def test_address_sum_is_still_rejected(self):
        instance=audit.Audit([],CAP)
        instance.representation(dict(kind='unknown',aggregate='unboxed-sum',alternatives=[ADDRESS,LONG],
            primReps=['WordRep','WordRep'],tagSlot=0,alternativeSlots=[[1],[1]],evaluated=True),None,'/rep')
        self.assertIn('aggregate-representation',{i['code'] for i in instance.issues})

    def test_genuine_pre_and_post_inputs(self):
        manifest_path=ROOT/'build/address-fields/manifest.json'
        if not manifest_path.exists():self.skipTest('Run prepare-address-fields.py for genuine Core')
        manifest=json.loads(manifest_path.read_text())
        for stage,path in manifest['stages'].items():
            module=json.loads((ROOT/path).read_text())
            for name in manifest['entries']:
                r=audit.Audit([(path,module)],CAP).run([name]);self.assertTrue(r['accepted'],(stage,name,r['issues']))
                self.assertFalse(r['missingGlobals'],(stage,name,r['missingGlobals']))

if __name__=='__main__': unittest.main()
