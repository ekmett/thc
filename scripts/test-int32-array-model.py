#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Independent cell/byte models and fail-closed actual-root structure controls."""
import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('int32_arrays', Path(__file__).with_name('prepare-int32-arrays.py'))
model = importlib.util.module_from_spec(spec)
spec.loader.exec_module(model)


def root_fixture(body):
    void = dict(primReps=[], kind='void', evaluated=True)
    state = dict(id='s', type='State# RealWorld', rep=copy.deepcopy(void), lifted=False, coercion=False)
    entry = dict(id='root', name='unboxedInt32ST', expr=['lam', [dict(rep=dict(primReps=['IntRep']))],
                 ['app', ['lam', [state], body, {}], [['void', dict(rep=void)]], [False], False, False, {}]])
    return dict(roots=['root'], reachableBindings=[dict(id='root')]), [('test', dict(bindings=[entry]))]


class Int32ArrayModelTest(unittest.TestCase):
    def test_public_models_against_explicit_32_bit_cell_updates(self):
        for unsigned in (False, True):
            kind = 'Word32' if unsigned else 'Int32'
            norm = lambda x: x % (1 << 32)
            widen = lambda x: x if unsigned or x < (1 << 31) else x-(1 << 32)
            for raw in model.inputs():
                u = norm(raw)
                accum = [u]*8
                for index, value in [(-3, 3), (0, 5), (-3, -2), (4, u)]:
                    accum[index+3] = norm(accum[index+3]+value)
                st = [u]*8
                st[3] = norm(st[0]+7)
                st[7] = norm(norm(3*st[3])-u)
                for suffix, cells in [('Accum', accum), ('ST', st)]:
                    answer = 7*widen(cells[0])+11*widen(cells[3])+13*widen(cells[7])
                    self.assertEqual(model.mathematical('unboxed'+kind+suffix, raw), answer)

    def test_byte_aliases_against_independent_mask_and_shift_model(self):
        for order in ('little', 'big'):
            for unsigned in (False, True):
                name = 'aliasWord32Bytes' if unsigned else 'aliasInt32Bytes'
                widen = lambda x: x if unsigned or x < (1 << 31) else x-(1 << 32)
                for raw in model.inputs():
                    u, other = raw & 0xffffffff, (raw ^ 0x55aa55aa) & 0xffffffff
                    shift_a, shift_b = (24, 0) if order == 'little' else (0, 24)
                    a = (u & ~(255 << shift_a)) | (((raw+101) & 255) << shift_a)
                    b = (other & ~(255 << shift_b)) | (((raw+37) & 255) << shift_b)
                    byte0 = a & 255 if order == 'little' else a >> 24
                    byte7 = b >> 24 if order == 'little' else b & 255
                    answer = (3*widen(u)+16*widen(a)+20*widen(b)+17*byte0
                              +19*((raw+101)&255)+23*((raw+37)&255)+29*byte7)
                    self.assertEqual(model.mathematical(name, raw, order), answer)
        self.assertNotEqual(model.mathematical('aliasInt32Bytes', 0, 'little'),
                            model.mathematical('aliasInt32Bytes', 0, 'big'))

    def test_narrowing_and_sign_extension_are_observable(self):
        for name in model.ENTRIES:
            for x in (0, 1, 0x7fffffff, 0x80000000, 0xffffffff):
                for order in ('little', 'big'):
                    self.assertEqual(model.mathematical(name, x, order), model.mathematical(name, x+(1 << 32), order))
                    self.assertEqual(model.mathematical(name, x, order), model.mathematical(name, x-(1 << 32), order))
        for signed, unsigned in [('unboxedInt32Accum', 'unboxedWord32Accum'),
                                 ('unboxedInt32ST', 'unboxedWord32ST'),
                                 ('aliasInt32Bytes', 'aliasWord32Bytes')]:
            self.assertNotEqual(model.mathematical(signed, 0x80000000), model.mathematical(unsigned, 0x80000000))

    def test_fullwidth_boundaries_and_rows(self):
        values = model.inputs()
        self.assertEqual(values, sorted(set(values)))
        for bit in range(64):
            self.assertTrue({model.signed((1 << bit)+delta) for delta in (-1, 0, 1)} <= set(values))
        rows = ''.join(f'{name}\t{x}\t{model.mathematical(name,x)}\n' for name in model.ENTRIES for x in values)
        self.assertEqual(len(model.parse_rows(rows, values)), len(values)*6)
        for changed in (rows+rows.splitlines()[0]+'\n', '\n'.join(rows.splitlines()[1:]), rows+'bad\t0\t0\n'):
            with self.assertRaises(AssertionError): model.parse_rows(changed, values)

    def test_strict_required_and_exact_alias_primitive_counts(self):
        for name, counts in model.EXACT_ALIAS.items():
            report = dict(accepted=True, primitives=[dict(name=n, uses=[{}]*v) for n, v in counts.items()])
            model.check_report(name, report)
            for mutation in ('rejected', 'missing', 'count', 'extra'):
                bad = copy.deepcopy(report)
                if mutation == 'rejected': bad['accepted'] = False
                elif mutation == 'missing': bad['primitives'].pop()
                elif mutation == 'count': bad['primitives'][0]['uses'].pop()
                else: bad['primitives'].append(dict(name='readIntArray#', uses=[{}]))
                with self.assertRaises(AssertionError): model.check_report(name, bad)

    def test_exact_immediate_state_lambda_and_no_hidden_extra_guest(self):
        report, modules = root_fixture(['lit', 'int', '0'])
        self.assertEqual(model.check_structure('unboxedInt32ST', report, modules), 2)
        for mutation in ('conditional', 'formal_type', 'formal_rep', 'arg', 'flags', 'hidden_lambda', 'global'):
            with self.subTest(mutation=mutation):
                bad = copy.deepcopy(modules)
                expr = bad[0][1]['bindings'][0]['expr']; call = expr[2]; local = call[1]
                if mutation == 'conditional': expr[2] = ['case', ['lit', 'int', '0'], 'v', [['default', None, [], call]]]
                elif mutation == 'formal_type': local[1][0]['type'] = 'Proxy# RealWorld'
                elif mutation == 'formal_rep': local[1][0]['rep']['primReps'] = ['IntRep']
                elif mutation == 'arg': call[2].clear()
                elif mutation == 'flags': call[3] = [True]
                elif mutation == 'hidden_lambda':
                    local[2] = ['let', False, [dict(id='hidden', expr=['lam', [{}], local[2]])], local[2]]
                else: local[2] = ['var', 'root']
                with self.assertRaises(AssertionError): model.check_structure('unboxedInt32ST', report, bad)

    def test_only_complete_proven_join_prefix_is_not_a_guest_root(self):
        leaf = ['lit', 'int', '0']
        result = dict(primReps=['IntRep'], kind='long', evaluated=False)
        join = dict(id='j', joinValueArity=1, joinResultRep=result, info=dict(joinArity=1),
                    expr=['lam', [{}], leaf, dict(resultRep=copy.deepcopy(result))])
        report, modules = root_fixture(['let', False, [join], ['app', ['var', 'j'], [leaf]]])
        self.assertEqual(model.check_structure('unboxedInt32ST', report, modules), 2)
        for mutation in ('diagnostic_only', 'partial', 'oversized', 'zero', 'boolean', 'result', 'nested_lambda'):
            with self.subTest(mutation=mutation):
                bad = copy.deepcopy(modules)
                binding = bad[0][1]['bindings'][0]['expr'][2][1][2][2][0]
                if mutation == 'diagnostic_only': binding.pop('joinValueArity')
                elif mutation == 'partial': binding['expr'][1].append({})
                elif mutation == 'oversized': binding['joinValueArity'] = 2
                elif mutation == 'zero': binding['joinValueArity'] = 0
                elif mutation == 'boolean': binding['joinValueArity'] = True
                elif mutation == 'result': binding['joinResultRep']['primReps'] = ['WordRep']
                else: binding['expr'][2] = ['lam', [{}], leaf]
                with self.assertRaises(AssertionError): model.check_structure('unboxedInt32ST', report, bad)


if __name__ == '__main__':
    unittest.main()
