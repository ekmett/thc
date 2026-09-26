#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Exact logical result shapes and known lifted Array# element contracts."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('audit_core',ROOT/'audit-core.py')
audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)
CAP=json.loads((ROOT/'core-capabilities.json').read_text())

def fixture(name,element_kind='data'):
    def role(r):
        if r=='state':return dict(kind='void',primReps=[],evaluated=True)
        if r=='int':return dict(kind='long',primReps=['IntRep'],evaluated=True)
        return dict(kind='object' if r=='array' else element_kind,
                    primReps=['BoxedRep (Just Unlifted)' if r=='array' else 'BoxedRep (Just Lifted)'],evaluated=r=='array')
    contract=CAP['managedArrayPrimitives'][name]
    parameters=[dict(id=f'x{i}',lifted=r=='element',rep=role(r)) for i,r in enumerate(contract['arguments'])]
    result=contract['result']
    if isinstance(result,list):
        fields=[role(r) for r in result]
        result=dict(kind='unknown',aggregate='unboxed-tuple',components=fields,primReps=[p for r in fields for p in r['primReps']],evaluated=True)
    else:result=role(result)
    body=['app',['prim',name],[['var',p['id'],dict(rep=copy.deepcopy(p['rep']))] for p in parameters],
          [p['lifted'] for p in parameters],False,False,dict(rep=copy.deepcopy(result))]
    scalar=role('int');closure=dict(kind='closure',primReps=['BoxedRep (Just Lifted)'],evaluated=True)
    returned=['case',body,'r',[['default',None,[],['lit','int','0',dict(rep=scalar)]]],dict(rep=scalar,binder=dict(id='r',lifted=False,rep=result))]
    module=dict(schema=1,ghc='9.14.1',constructors=[],bindings=[dict(id='root',name='root',lifted=True,arity=len(parameters),
        expr=['lam',parameters,returned,dict(rep=closure,resultRep=scalar)])])
    return module,body

def report(module):return audit.Audit([('array.json',module)],CAP).run(['root'])
def codes(module):return {i['code'] for i in report(module)['issues']}

class ArrayContracts(unittest.TestCase):
    def test_reads_transport_unlifted_object_elements_but_not_scalar_or_forged_boxed_values(self):
        for name in ('readArray#', 'indexArray#'):
            module, app = fixture(name, 'object')
            proof = app[6]['rep']
            proof['components'][-1].update(primReps=['BoxedRep (Just Unlifted)'], evaluated=True)
            proof['primReps'] = ['BoxedRep (Just Unlifted)']
            module['bindings'][0]['expr'][2][-1]['binder']['rep'] = copy.deepcopy(proof)
            self.assertTrue(report(module)['accepted'], report(module)['issues'])
            for kind in ('long', 'data', 'closure', 'unknown'):
                changed = copy.deepcopy(module)
                changed['bindings'][0]['expr'][2][1][6]['rep']['components'][-1]['kind'] = kind
                self.assertIn('primitive-representation', codes(changed), (name, kind))

    def test_all_lifted_reference_classes(self):
        for name in CAP['managedArrayPrimitives']:
            for kind in ('data','closure','object'):
                result=report(fixture(name,kind)[0]);self.assertTrue(result['accepted'],(name,kind,result['issues']))

    def test_arity_flags_and_absent_proofs(self):
        for name in CAP['managedArrayPrimitives']:
            for change in ('partial','over','flag','absent','unknown'):
                module,app=fixture(name)
                if change=='partial':app[2].pop();app[3].pop()
                elif change=='over':app[2].append(copy.deepcopy(app[2][0]));app[3].append(False)
                elif change=='flag':app[3][0]=not app[3][0]
                elif change=='absent':app[6].pop('rep')
                else:app[2][0][2]['rep']['kind']='unknown'
                self.assertIn('primitive-representation',codes(module),(name,change))

    def test_index_singleton_and_state_pair_are_not_interchangeable(self):
        for name in ('newArray#','readArray#','unsafeFreezeArray#','freezeArray#','thawArray#','indexArray#'):
            module,app=fixture(name);fields=app[6]['rep']['components']
            if name=='indexArray#':fields.insert(0,dict(kind='void',primReps=[]))
            else:fields.pop(0)
            self.assertIn('primitive-representation',codes(module),name)

    def test_state_is_not_empty_aggregate_and_payload_is_not_flat_scalar(self):
        for name in ('newArray#','readArray#','unsafeFreezeArray#','freezeArray#','thawArray#'):
            module,app=fixture(name)
            app[6]['rep']['components'][0].update(kind='unknown',aggregate='unboxed-tuple',components=[])
            self.assertIn('primitive-representation',codes(module),name)
        for name in ('readArray#','indexArray#'):
            module,app=fixture(name);app[6]['rep']['components'][-1]=dict(kind='long',primReps=['IntRep'])
            app[6]['rep']['primReps']=['IntRep'];self.assertIn('primitive-representation',codes(module))

    def test_element_levity_is_known_lifted_and_indices_are_exact_int(self):
        for name,contract in CAP['managedArrayPrimitives'].items():
            for index,role in enumerate(contract['arguments']):
                for bad in (['WordRep'] if role=='int' else ['BoxedRep Nothing','BoxedRep (Just Unlifted)'] if role=='element' else []):
                    module,app=fixture(name);app[2][index][2]['rep']['primReps']=[bad]
                    self.assertIn('primitive-representation',codes(module),(name,index,bad))
            if name in ('readArray#','indexArray#'):
                module,app=fixture(name);app[6]['rep']['components'][-1]['primReps']=['BoxedRep (Just Unlifted)']
                app[6]['rep']['primReps']=['BoxedRep (Just Unlifted)'];self.assertIn('primitive-representation',codes(module))

    def test_lexical_lifted_storage_proof_cannot_be_relabelled(self):
        for name in ('readArray#','writeArray#','unsafeFreezeArray#','indexArray#','cloneArray#','freezeArray#','thawArray#'):
            module,_=fixture(name)
            module['bindings'][0]['expr'][1][0]['rep']['primReps']=['BoxedRep (Just Lifted)']
            self.assertIn('scalar-representation',codes(module),name)

    def test_clone_scalar_result_is_not_a_singleton_tuple_or_lifted_reference(self):
        for change in ('tuple','lifted','unknown','vector'):
            module,app=fixture('cloneArray#');proof=app[6]['rep']
            if change=='tuple':app[6]['rep']=dict(kind='unknown',aggregate='unboxed-tuple',components=[copy.deepcopy(proof)],primReps=proof['primReps'])
            elif change=='lifted':proof['primReps']=['BoxedRep (Just Lifted)']
            elif change=='unknown':proof['primReps']=['BoxedRep Nothing']
            else:proof.update(kind='vector',primReps=['VecRep 2 Int64ElemRep'],vector=dict(lanes=2,element='Int64ElemRep'))
            self.assertIn('primitive-representation',codes(module),change)

    def test_first_class_and_atomic_operations_remain_unsupported(self):
        for name in CAP['managedArrayPrimitives']:
            module,app=fixture(name);app[:]=['prim',name]
            self.assertFalse(report(module)['accepted'])
        self.assertNotIn('casArray#',CAP['primitives'])

    def test_raw_flags_and_extra_scalar_fields_cannot_be_erased(self):
        for name in CAP['managedArrayPrimitives']:
            for field in ('components', 'aggregate', 'vector', 'alternatives', 'tagSlot'):
                module, app = fixture(name)
                app[2][0][2]['rep'][field] = None
                self.assertIn('primitive-representation', codes(module), (name, field))
            for value in (0, 1, None, 'false'):
                module, app = fixture(name)
                app[3][0] = value
                self.assertIn('primitive-representation', codes(module), (name, value))

    def test_extensions_exact_results_state_and_full_width_offsets(self):
        names = ('sizeofArray#', 'sizeofMutableArray#', 'cloneMutableArray#',
                 'copyArray#', 'copyMutableArray#', 'unsafeThawArray#')
        for name in names:
            contract = CAP['managedArrayPrimitives'][name]
            for index, role in enumerate(contract['arguments']):
                for wrong in (['Int64Rep', 'WordRep', 'Word64Rep'] if role == 'int' else
                              ['BoxedRep Nothing', 'BoxedRep (Just Lifted)'] if role == 'array' else ['IntRep']):
                    module, app = fixture(name)
                    app[2][index][2]['rep']['primReps'] = [wrong]
                    self.assertIn('primitive-representation', codes(module), (name, index, wrong))
            module, app = fixture(name)
            app[6]['rep']['primReps'] = ['WordRep']
            self.assertIn('primitive-representation', codes(module), name)

if __name__=='__main__':unittest.main()
