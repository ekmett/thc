#!/usr/bin/env python3
"""Exact State#/unlifted reference contracts for the managed ByteArray slice."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('audit_core', ROOT / 'audit-core.py')
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)
CAP = json.loads((ROOT / 'core-capabilities.json').read_text())


def evaluated(proof):
    return dict(proof, evaluated=True, **({'components': [evaluated(c) for c in proof['components']]}
                                         if 'components' in proof else {}))


def fixture(name):
    contract = CAP['managedByteArrayPrimitives'][name]
    parameters = [dict(id=f'x{i}', lifted=False, rep=evaluated(proof))
                  for i, proof in enumerate(contract['arguments'])]
    body = ['app', ['prim', name], [['var', p['id'], dict(rep=copy.deepcopy(p['rep']))] for p in parameters],
            [False] * len(parameters), False, False, dict(rep=evaluated(contract['result']))]
    result = evaluated(contract['result'])
    constructors = []
    returned = body
    if audit.Audit.is_tuple(result):
        fields = [dict(id=f'field{i}', lifted=False, rep=copy.deepcopy(proof)) for i, proof in enumerate(result['components'])]
        constructors = [dict(id='Tuple2', kind='unboxed-tuple', arity=2,
                             fieldReps=[p['primReps'] for p in result['components']],
                             fieldLifted=[False, False], strictFields=[False, False])]
        scalar = dict(kind='long', primReps=['IntRep'], evaluated=True)
        returned = ['case', body, 'tuple', [['data', 'Tuple2', [p['id'] for p in fields],
                    ['lit', 'int', '0', dict(rep=scalar)], dict(binders=fields)]],
                    dict(rep=scalar, binder=dict(id='tuple', lifted=False, rep=result))]
        result = scalar
    closure = dict(kind='closure', primReps=['BoxedRep (Just Lifted)'], evaluated=True)
    module = dict(schema=1, ghc='9.14.1', constructors=constructors, bindings=[dict(id='root', name='root',
                  lifted=True, arity=len(parameters), expr=['lam', parameters, returned, dict(rep=closure, resultRep=result)])])
    return module, body


def check(module):
    return audit.Audit([('bytearray.json', module)], CAP).run(['root'])


class ByteArrayContracts(unittest.TestCase):
    def test_exact_contracts(self):
        for name in CAP['managedByteArrayPrimitives']:
            report = check(fixture(name)[0])
            self.assertTrue(report['accepted'], (name, report['issues']))

    def test_missing_wrong_and_partial_signatures(self):
        for name in CAP['managedByteArrayPrimitives']:
            for mutation in ('missing', 'wrong-result', 'unknown-argument', 'lifted', 'partial', 'overapplied'):
                module, app = fixture(name)
                if mutation == 'missing':
                    app[6].pop('rep')
                elif mutation == 'wrong-result':
                    wrong = 'IntRep' if name == 'indexWordArray#' else 'WordRep'
                    app[6]['rep'] = dict(kind='long', primReps=[wrong], evaluated=True)
                elif mutation == 'unknown-argument':
                    app[2][0][2]['rep']['kind'] = 'unknown'
                elif mutation == 'lifted':
                    app[3][0] = True
                elif mutation == 'partial':
                    app[2].pop(); app[3].pop()
                else:
                    app[2].append(copy.deepcopy(app[2][0])); app[3].append(False)
                report = check(module)
                self.assertFalse(report['accepted'], (name, mutation))
                self.assertIn('primitive-representation', {i['code'] for i in report['issues']}, (name, mutation))

    def test_state_is_not_an_empty_tuple_and_reference_is_not_lifted(self):
        for name in ('newByteArray#', 'unsafeFreezeByteArray#', 'readIntArray#', 'readDoubleArray#',
                     'readInt32Array#', 'readWord32Array#', 'readFloatArray#', 'readWordArray#',
                     'readInt16Array#', 'readWord16Array#', 'readInt8Array#', 'readWord8Array#'):
            for mutation in ('empty-tuple', 'missing-state', 'lifted-reference'):
                module, app = fixture(name)
                proof = app[6]['rep']
                if mutation == 'empty-tuple':
                    proof['components'][0].update(aggregate='unboxed-tuple', components=[], kind='unknown')
                elif mutation == 'missing-state':
                    proof['components'].pop(0)
                elif name.startswith('read'):
                    wrong = 'IntRep' if name == 'readWordArray#' else 'WordRep'
                    proof['components'][1]['primReps'] = [wrong]
                    proof['primReps'] = [wrong]
                else:
                    proof['components'][1]['primReps'] = ['BoxedRep (Just Lifted)']
                    proof['primReps'] = ['BoxedRep (Just Lifted)']
                report = check(module)
                self.assertIn('primitive-representation', {i['code'] for i in report['issues']}, (name, mutation))

    def test_lexical_reference_cannot_be_relabelled_by_an_occurrence(self):
        for name in ('writeWord8Array#', 'unsafeFreezeByteArray#', 'sizeofByteArray#', 'indexWord8Array#',
                     'readIntArray#', 'writeIntArray#', 'indexIntArray#', 'copyByteArray#', 'setByteArray#', 'copyMutableByteArray#', 'copyMutableByteArrayNonOverlapping#',
                     'readDoubleArray#', 'writeDoubleArray#', 'indexDoubleArray#',
                     'readInt32Array#', 'writeInt32Array#', 'indexInt32Array#',
                     'readWord32Array#', 'writeWord32Array#', 'indexWord32Array#',
                     'readFloatArray#', 'writeFloatArray#', 'indexFloatArray#',
                     'readWordArray#', 'writeWordArray#', 'indexWordArray#',
                     'readInt16Array#', 'writeInt16Array#', 'indexInt16Array#',
                     'readWord16Array#', 'writeWord16Array#', 'indexWord16Array#',
                     'readInt8Array#', 'writeInt8Array#', 'indexInt8Array#', 'readWord8Array#'):
            module, _ = fixture(name)
            parameter = module['bindings'][0]['expr'][1][0]
            parameter['rep']['primReps'] = ['BoxedRep (Just Lifted)']
            report = check(module)
            self.assertIn('scalar-representation', {i['code'] for i in report['issues']}, name)

    def test_mutable_memory_requires_exact_int_operands_and_scalar_state(self):
        for name in ('setByteArray#', 'copyMutableByteArray#', 'copyMutableByteArrayNonOverlapping#'):
            positions=(1,2,3) if name=='setByteArray#' else (1,3,4)
            for index in positions:
                for bad in ('Word8Rep','Int8Rep','WordRep','Int64Rep'):
                    module,app=fixture(name)
                    app[2][index][2]['rep']['primReps']=[bad]
                    self.assertIn('primitive-representation',{x['code'] for x in check(module)['issues']},(name,index,bad))
            for index in ((0,) if name=='setByteArray#' else (0,2)):
                module,app=fixture(name)
                app[2][index][2]['rep']['primReps']=['BoxedRep (Just Lifted)']
                self.assertIn('primitive-representation',{x['code'] for x in check(module)['issues']},(name,index))
            for result in (False,True):
                module,app=fixture(name)
                proof=app[6]['rep'] if result else app[2][-1][2]['rep']
                proof.update(kind='unknown',aggregate='unboxed-tuple',components=[])
                self.assertIn('primitive-representation',{x['code'] for x in check(module)['issues']},(name,result))

    def test_int_array_requires_machine_int_not_same_width_word_or_int64(self):
        for name in ('readIntArray#', 'writeIntArray#', 'indexIntArray#'):
            for replacement in ('WordRep', 'Int64Rep', 'Word64Rep'):
                for operand in ([1, 2] if name == 'writeIntArray#' else [1]):
                    module, app = fixture(name)
                    module['bindings'][0]['expr'][1][operand]['rep']['primReps'] = [replacement]
                    app[2][operand][2]['rep']['primReps'] = [replacement]
                    report = check(module)
                    self.assertIn('primitive-representation', {i['code'] for i in report['issues']},
                                  (name, operand, replacement))

    def test_double_payload_cannot_be_float_integer_or_unknown(self):
        for name in ('readDoubleArray#', 'writeDoubleArray#', 'indexDoubleArray#'):
            for kind, rep in (('float', 'FloatRep'), ('long', 'IntRep'), ('unknown', 'DoubleRep')):
                module, app = fixture(name)
                wrong = dict(kind=kind, primReps=[rep], evaluated=True)
                if name == 'writeDoubleArray#':
                    module['bindings'][0]['expr'][1][2]['rep'] = copy.deepcopy(wrong)
                    app[2][2][2]['rep'] = wrong
                elif name == 'indexDoubleArray#':
                    app[6]['rep'] = wrong
                else:
                    app[6]['rep']['components'][1] = wrong
                    app[6]['rep']['primReps'] = [rep]
                report = check(module)
                self.assertIn('primitive-representation', {i['code'] for i in report['issues']}, (name, kind, rep))

    def test_narrow_arrays_require_exact_signedness_width_and_machine_index(self):
        for family in ('Int8', 'Word8', 'Int16', 'Word16', 'Int32', 'Word32'):
            for operation in ('read', 'write', 'index'):
                name = operation + family + 'Array#'
                for replacement in ('IntRep', 'WordRep', 'Int8Rep', 'Word8Rep', 'Int16Rep', 'Word16Rep',
                                    'Int32Rep', 'Word32Rep', 'Int64Rep', 'Word64Rep'):
                    if replacement != 'IntRep':
                        module, app = fixture(name)
                        module['bindings'][0]['expr'][1][1]['rep']['primReps'] = [replacement]
                        app[2][1][2]['rep']['primReps'] = [replacement]
                        self.assertIn('primitive-representation', {i['code'] for i in check(module)['issues']},
                                      (name, 'index', replacement))
                    if replacement == family + 'Rep':
                        continue
                    module, app = fixture(name)
                    if operation == 'write':
                        module['bindings'][0]['expr'][1][2]['rep']['primReps'] = [replacement]
                        app[2][2][2]['rep']['primReps'] = [replacement]
                    else:
                        app[6]['rep']['primReps'] = [replacement]
                        if operation == 'read':
                            app[6]['rep']['components'][1]['primReps'] = [replacement]
                    self.assertIn('primitive-representation', {i['code'] for i in check(module)['issues']},
                                  (name, 'payload', replacement))

    def test_float_and_word_arrays_require_exact_payload_and_machine_index(self):
        for family, exact in (('Float', 'FloatRep'), ('Word', 'WordRep')):
            for operation in ('read', 'write', 'index'):
                name = operation + family + 'Array#'
                for rep in ('IntRep', 'Int32Rep', 'Int64Rep', 'WordRep', 'Word32Rep', 'Word64Rep', 'FloatRep', 'DoubleRep'):
                    kind = {'FloatRep': 'float', 'DoubleRep': 'double'}.get(rep, 'long')
                    wrong = dict(kind=kind, primReps=[rep], evaluated=True)
                    if rep != 'IntRep':
                        module, app = fixture(name)
                        module['bindings'][0]['expr'][1][1]['rep'] = copy.deepcopy(wrong)
                        app[2][1][2]['rep'] = copy.deepcopy(wrong)
                        self.assertIn('primitive-representation', {i['code'] for i in check(module)['issues']},
                                      (name, 'index', rep))
                    if rep == exact:
                        continue
                    module, app = fixture(name)
                    if operation == 'write':
                        module['bindings'][0]['expr'][1][2]['rep'] = copy.deepcopy(wrong)
                        app[2][2][2]['rep'] = wrong
                    elif operation == 'read':
                        app[6]['rep']['components'][1] = wrong
                        app[6]['rep']['primReps'] = [rep]
                    else:
                        app[6]['rep'] = wrong
                    self.assertIn('primitive-representation', {i['code'] for i in check(module)['issues']},
                                  (name, 'payload', rep))

    def test_copy_requires_exact_proofs_for_both_references_offsets_count_and_state(self):
        for argument in range(6):
            for mutation in ('missing', 'wrong-rep', 'tuple-state'):
                module, app = fixture('copyByteArray#')
                metadata = app[2][argument][2]
                if mutation == 'missing':
                    metadata.pop('rep')
                elif mutation == 'wrong-rep':
                    metadata['rep']['primReps'] = ['BoxedRep (Just Lifted)'] if argument in (0, 2) else ['WordRep']
                else:
                    metadata['rep'].update(kind='unknown', aggregate='unboxed-tuple', components=[], primReps=[])
                report = check(module)
                self.assertIn('primitive-representation', {i['code'] for i in report['issues']}, (argument, mutation))
        module, app = fixture('copyByteArray#')
        app[6]['rep'].update(kind='unknown', aggregate='unboxed-tuple', components=[])
        self.assertIn('primitive-representation', {i['code'] for i in check(module)['issues']})
        for argument in (0, 2):
            module, _ = fixture('copyByteArray#')
            module['bindings'][0]['expr'][1][argument]['rep']['primReps'] = ['BoxedRep (Just Lifted)']
            self.assertIn('scalar-representation', {i['code'] for i in check(module)['issues']}, argument)


    def test_compare_checks_every_reference_range_and_scalar_result(self):
        for index in range(5):
            for mutation in ('missing', 'wrong', 'lexical'):
                module, app = fixture('compareByteArrays#')
                proof = copy.deepcopy(app[2][index][2]['rep'])
                proof['primReps'] = ['BoxedRep (Just Lifted)'] if index in (0, 2) else ['WordRep']
                if mutation == 'missing': app[2][index][2].pop('rep')
                elif mutation == 'wrong': app[2][index][2]['rep'] = proof
                else: module['bindings'][0]['expr'][1][index]['rep'] = proof
                self.assertFalse(check(module)['accepted'], (index, mutation))
        module, app = fixture('compareByteArrays#')
        app[6]['rep'] = dict(kind='unknown', aggregate='unboxed-tuple', components=[], primReps=[], evaluated=True)
        self.assertFalse(check(module)['accepted'])


if __name__ == '__main__':
    unittest.main()
