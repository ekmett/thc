#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
"""Model/contract checks; synthetic test JSON is never supplied as guest Core."""

import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('prepare_mvars', Path(__file__).with_name('prepare-managed-mvars.py'))
recipe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(recipe)


def application(name, lifted):
    def proof(role):
        if role == 'state':
            return dict(kind='void', primReps=[], evaluated=True)
        if role == 'flag':
            return dict(kind='long', primReps=['IntRep'], evaluated=True)
        return dict(kind='object' if role == 'mvar' else 'data',
                    primReps=['BoxedRep (Just ' + ('Lifted' if role == 'boxed' and lifted else 'Unlifted') + ')'],
                    evaluated=not (role == 'boxed' and lifted))
    arguments, result = recipe.CONTRACTS[name]
    args = [['var', str(index), dict(rep=proof(role))] for index, role in enumerate(arguments)]
    if isinstance(result, list):
        fields = [proof(role) for role in result]
        returned = dict(kind='unknown', aggregate='unboxed-tuple', components=fields,
                        primReps=[rep for field in fields for rep in field['primReps']], evaluated=True)
    else:
        returned = proof(result)
    return ['app', ['prim', name], args, [role == 'boxed' and lifted for role in arguments], False, False, dict(rep=returned)]


class ManagedMVarFixtureTests(unittest.TestCase):
    def test_exact_contracts_at_both_boxed_levities(self):
        for name in recipe.CONTRACTS:
            for lifted in (False, True):
                with self.subTest(name=name, lifted=lifted):
                    self.assertEqual(recipe.validate_application(application(name, lifted))[0], name)

    def test_state_is_not_an_empty_tuple_and_flags_are_full_width(self):
        app = application('tryReadMVar#', True)
        app[2][-1][-1]['rep'].update(aggregate='unboxed-tuple', components=[])
        with self.assertRaisesRegex(ValueError, 'state argument'):
            recipe.validate_application(app)
        app = application('tryReadMVar#', True)
        app[-1]['rep']['components'][1]['primReps'] = ['Int32Rep']
        app[-1]['rep']['primReps'][0] = 'Int32Rep'
        with self.assertRaisesRegex(ValueError, 'flag result'):
            recipe.validate_application(app)
        app = application('takeMVar#', True)
        app[-1]['rep']['components'].pop(0)
        with self.assertRaisesRegex(ValueError, 'logical State#'):
            recipe.validate_application(app)

    def test_arity_boxed_payload_and_lifted_argument_flags(self):
        for change in ('arity', 'boxed', 'lifted'):
            app = application('putMVar#', True)
            if change == 'arity':
                app[2].pop()
            elif change == 'boxed':
                app[2][1][-1]['rep'] = dict(kind='long', primReps=['IntRep'])
            else:
                app[3][1] = False
            with self.subTest(change=change), self.assertRaises(ValueError):
                recipe.validate_application(app)

    def test_signed_model_boundaries_and_independent_cells(self):
        self.assertEqual(recipe.mathematical('lazyPayload', 2**63 - 1), -2**63 + 29)
        self.assertEqual(recipe.mathematical('closurePayload', -2**63), 17)
        self.assertEqual(recipe.mathematical('aliasRoundTrip', -1), -65565)
        self.assertEqual(recipe.mathematical('aliasRoundTrip', 0), 65537)
        self.assertEqual(recipe.mathematical('transitions', 0), 17 * 16777259 + 96)
        for value in (-2**63, -1, 0, 1, 2**63 - 1):
            self.assertEqual(recipe.mathematical('transitions', value), recipe.mathematical('unliftedPayload', value))
            self.assertEqual(recipe.mathematical('nativeWaitTake', value), value)
            self.assertEqual(recipe.mathematical('nativeWaitRead', value), value)

    def test_native_protocol_rejects_missing_duplicate_and_wrong_rows(self):
        self.assertEqual(recipe.validate_rows('lazyPayload\t0\t30\n', ['lazyPayload'], [0]), [['lazyPayload', '0', '30']])
        for text in ('', 'lazyPayload\t0\t31\n', 'lazyPayload\t1\t31\n', 'lazyPayload\t0\t30\nlazyPayload\t0\t30\n'):
            with self.subTest(text=text), self.assertRaises(ValueError):
                recipe.validate_rows(text, ['lazyPayload'], [0])


if __name__ == '__main__':
    unittest.main()
