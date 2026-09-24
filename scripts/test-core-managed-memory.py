#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Precise pinned-memory, keepAlive and closed MD5 proof regressions; no guests."""
import copy
import importlib.util
import json
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))
spec = importlib.util.spec_from_file_location('audit_core_managed_memory', ROOT / 'audit-core.py')
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)
CAP = json.loads((ROOT / 'core-capabilities.json').read_text())


def scalar(kind, *registers, evaluated=True):
    return dict(kind=kind, primReps=list(registers), evaluated=evaluated)


INT = scalar('long', 'IntRep')
WORD = scalar('long', 'WordRep')
WORD8 = scalar('long', 'Word8Rep')
INT32 = scalar('long', 'Int32Rep')
STATE = scalar('void')
ADDRESS = scalar('address', 'AddrRep')
ARRAY = scalar('object', 'BoxedRep (Just Unlifted)')
LIFTED = scalar('object', 'BoxedRep (Just Lifted)', evaluated=False)
CLOSURE = scalar('closure', 'BoxedRep (Just Lifted)')


def tup(*fields, evaluated=True):
    return dict(kind='unknown', aggregate='unboxed-tuple', evaluated=evaluated,
                components=copy.deepcopy(list(fields)), primReps=[r for f in fields for r in f['primReps']])


PINNED = {
    'newPinnedByteArray#': ([INT, STATE], tup(STATE, ARRAY)),
    'newAlignedPinnedByteArray#': ([INT, INT, STATE], tup(STATE, ARRAY)),
    'byteArrayContents#': ([ARRAY], ADDRESS),
    'readWord8OffAddr#': ([ADDRESS, INT, STATE], tup(STATE, WORD8)),
    'writeWord8OffAddr#': ([ADDRESS, INT, WORD8, STATE], STATE),
}
MD5 = {'__hsbase_MD5Init': [ADDRESS, STATE],
       '__hsbase_MD5Update': [ADDRESS, ADDRESS, INT32, STATE],
       '__hsbase_MD5Final': [ADDRESS, ADDRESS, STATE]}


def binder(identity, proof):
    return dict(id=identity, lifted=proof['primReps'] == ['BoxedRep (Just Lifted)'],
                coercion=False, rep=copy.deepcopy(proof))


def var(identity, proof):
    return ['var', identity, dict(rep=copy.deepcopy(proof))]


def literal():
    return ['lit', 'int', '17', dict(rep=copy.deepcopy(INT))]


def lam(formals, body, result):
    return ['lam', formals, body, dict(rep=copy.deepcopy(CLOSURE), resultRep=copy.deepcopy(result))]


def app(head, arguments, result, flags=None):
    return ['app', head, arguments, [False] * len(arguments) if flags is None else flags,
            False, False, dict(rep=copy.deepcopy(result))]


def wrapped(call, formals, result, extra=()):
    constructors = []
    if result.get('aggregate') == 'unboxed-tuple':
        fields = [binder('field' + str(i), p) for i, p in enumerate(result['components'])]
        key = 'T' + str(len(fields))
        constructors.append(dict(id=key, kind='unboxed-tuple', arity=len(fields)))
        alternative = ['data', key, [f['id'] for f in fields], literal(), dict(binders=fields)]
    else:
        alternative = ['default', None, [], literal(), dict(binders=[])]
    body = ['case', call, 'whole', [alternative],
            dict(rep=copy.deepcopy(INT), binder=binder('whole', result))]
    root = dict(id='root', name='root', lifted=True, arity=len(formals), rep=copy.deepcopy(CLOSURE),
                expr=lam(formals, body, INT))
    return dict(schema=1, ghc='9.14.1', constructors=constructors, bindings=[root, *extra])


def pinned(name):
    arguments, result = copy.deepcopy(PINNED[name])
    formals = [binder('p' + str(i), p) for i, p in enumerate(arguments)]
    call = app(['prim', name], [var(f['id'], f['rep']) for f in formals], result)
    return wrapped(call, formals, result), call


def keep_alive(mode='direct', formal=STATE, returned=INT, result=INT, kept=ARRAY, partial=False):
    formals = [binder('kept', kept), binder('state', STATE)]
    body = literal()
    if returned == STATE:
        body = ['void', dict(rep=copy.deepcopy(STATE))]
    elif returned.get('aggregate') == 'unboxed-tuple':
        values = [['void', dict(rep=copy.deepcopy(STATE))] if p == STATE else literal()
                  for p in returned['components']]
        body = app(['con', 'T' + str(len(values)), len(values), dict(rep=copy.deepcopy(CLOSURE))], values, returned)
    elif returned == CLOSURE:
        body = lam([binder('inner', STATE)], literal(), INT)
    continuation_formals = [binder('token', formal)]
    if partial:
        continuation_formals.append(binder('tail', INT))
    continuation = lam(continuation_formals, body, returned)
    extra = []
    if mode in ('global', 'pap'):
        if mode == 'pap':
            continuation[1].insert(0, binder('prefix', INT))
        extra.append(dict(id='continue', name='continue', arity=len(continuation[1]), lifted=True,
                          rep=copy.deepcopy(CLOSURE), expr=continuation))
        expression = var('continue', CLOSURE)
        if mode == 'pap':
            expression = app(expression, [literal()], CLOSURE)
    else:
        expression = continuation
    call = app(['prim', 'keepAlive#'], [var('kept', kept), var('state', STATE), expression], result,
               [kept['primReps'] == ['BoxedRep (Just Lifted)'], False, True])
    return wrapped(call, formals, result, extra), call, continuation


def md5(symbol):
    arguments = copy.deepcopy(MD5[symbol])
    formals = [binder('p' + str(i), p) for i, p in enumerate(arguments)]
    result = tup(STATE)
    call = app(var('foreign-id', CLOSURE), [var(f['id'], f['rep']) for f in formals], result)
    descriptor = dict(schema=1, target=dict(kind='static', symbol=symbol, unit='ghc-internal', isFunction=True),
                      convention='ccall', safety='unsafe', arity=len(arguments), suppliedArity=len(arguments),
                      argumentReps=[dict(p, evaluated=False) for p in arguments], resultRep=tup(STATE, evaluated=False))
    call[6]['foreignCall'] = descriptor
    return wrapped(call, formals, result), call, descriptor


def check(module):
    return audit.Audit([('managed-memory.json', module)], CAP).run(['root'])


class ManagedMemoryProofTest(unittest.TestCase):
    def accepted(self, module):
        report = check(module)
        self.assertEqual([], report['missingGlobals'], report)
        self.assertEqual([], report['issues'], report)
        self.assertTrue(report['accepted'], report)
        return report

    def rejected(self, module, code='primitive-representation', missing=()):
        report = check(module)
        self.assertFalse(report['accepted'], report)
        self.assertEqual(list(missing), [g['id'] for g in report['missingGlobals']], report)
        self.assertIn(code, {issue['code'] for issue in report['issues']}, report)
        self.assertNotIn('malformed-expression', {issue['code'] for issue in report['issues']}, report)
        return report

    def test_all_six_exact_contracts_accept_without_missing_globals(self):
        for name, (arguments, _) in PINNED.items():
            with self.subTest(name=name):
                module, _ = pinned(name)
                report = self.accepted(module)
                self.assertEqual(len(arguments), CAP['primitives'][name])
                self.assertEqual([name], [p['name'] for p in report['primitives']])
        self.accepted(keep_alive()[0])
        self.assertEqual(3, CAP['primitives']['keepAlive#'])

    def test_pinned_argument_kind_arity_and_levity_cannot_be_forged(self):
        for name in PINNED:
            self.accepted(pinned(name)[0])
            for index in range(len(PINNED[name][0])):
                module, call = pinned(name)
                call[2][index][2]['rep']['kind'] = 'unknown'
                self.rejected(module)
                module, call = pinned(name)
                call[3][index] = True
                self.rejected(module)
            for excess in (False, True):
                module, call = pinned(name)
                if excess:
                    call[2].append(literal()); call[3].append(False)
                else:
                    call[2].pop(); call[3].pop()
                self.rejected(module, 'primitive-arity')

    def test_pinned_state_alignment_word8_and_result_identity_are_exact(self):
        for name in PINNED:
            self.accepted(pinned(name)[0])
            module, call = pinned(name)
            call[6]['rep'] = scalar('unknown', 'IntRep')
            self.rejected(module)
            for index, expected in enumerate(PINNED[name][0]):
                if expected == STATE:
                    for wrong in (INT, tup()):
                        module, call = pinned(name)
                        call[2][index][2]['rep'] = copy.deepcopy(wrong)
                        self.rejected(module)
        for wrong in (WORD, WORD8, ADDRESS, STATE):
            module, call = pinned('newAlignedPinnedByteArray#')
            call[2][1][2]['rep'] = copy.deepcopy(wrong)
            self.rejected(module)
        module, call = pinned('writeWord8OffAddr#')
        call[2][2][2]['rep'] = copy.deepcopy(WORD)
        self.rejected(module)
        module, call = pinned('readWord8OffAddr#')
        call[6]['rep'] = tup(STATE, WORD)
        self.rejected(module)
        for name in ('newPinnedByteArray#', 'newAlignedPinnedByteArray#', 'readWord8OffAddr#'):
            for replacement in (tup(), INT):
                module, call = pinned(name)
                fields = call[6]['rep']['components']
                call[6]['rep'] = tup(replacement, fields[1])
                self.rejected(module)

    def test_keepalive_direct_global_and_pap_state_signature_parity(self):
        for mode in ('direct', 'global', 'pap'):
            for kept in (ARRAY, LIFTED):
                with self.subTest(mode=mode, kept=kept):
                    self.accepted(keep_alive(mode, kept=kept)[0])
                    for formal in (INT, WORD, tup()):
                        module, _, _ = keep_alive(mode, formal=formal, kept=kept)
                        report = self.rejected(module)
                        self.assertTrue(any('State continuation input' in str(i['detail']) for i in report['issues']))

    def test_keepalive_known_result_and_partial_continuation_parity(self):
        for mode in ('direct', 'global', 'pap'):
            self.accepted(keep_alive(mode)[0])
            module, _, _ = keep_alive(mode, result=WORD)
            report = check(module)
            self.assertFalse(report['accepted'], report)
            self.assertEqual([], report['missingGlobals'], report)
            self.assertTrue(any('/continuation-result' in i['path'] for i in report['issues']), report)
            self.accepted(keep_alive(mode, partial=True, result=CLOSURE)[0])
            module, _, _ = keep_alive(mode, partial=True, result=INT)
            report = self.rejected(module)
            self.assertTrue(any('partial continuation' in str(i['detail']) for i in report['issues']))

    def test_keepalive_exact_state_tuple_and_closure_results_are_not_scalar_only(self):
        for mode in ('direct', 'global', 'pap'):
            for proof in (STATE, tup(STATE, INT), CLOSURE):
                with self.subTest(mode=mode, proof=proof):
                    module, _, _ = keep_alive(mode, returned=proof, result=proof)
                    self.accepted(module)

    def test_keepalive_unknown_scalar_result_and_operand_shapes_reject(self):
        self.accepted(keep_alive()[0])
        for proof in (dict(kind='unknown', primReps=None, evaluated=False), scalar('unknown', 'IntRep')):
            module, call, _ = keep_alive()
            call[6]['rep'] = proof
            self.rejected(module)
        for position, proof in ((0, INT), (0, ADDRESS), (1, INT), (1, tup()), (2, LIFTED | {'kind': 'data'}), (2, ARRAY)):
            module, call, _ = keep_alive('global')
            call[2][position] = var('kept' if position == 0 else 'state' if position == 1 else 'continue', proof)
            self.rejected(module)
        for index in range(3):
            module, call, _ = keep_alive()
            call[3][index] = not call[3][index]
            self.rejected(module)
        for excess in (False, True):
            module, call, _ = keep_alive()
            if excess:
                call[2].append(literal()); call[3].append(False)
            else:
                call[2].pop(); call[3].pop()
            self.rejected(module, 'primitive-arity')

    def test_three_closed_md5_descriptors_accept_singleton_state_under_scalar_wrapper(self):
        self.assertEqual(set(MD5), set(CAP['managedForeignCalls']))
        self.assertEqual(3, len(CAP['managedForeignCalls']))
        for symbol in MD5:
            report = self.accepted(md5(symbol)[0])
            self.assertEqual([], report['primitives'])
            self.assertEqual(1, report['summary']['reachableBindings'])
            self.assertEqual([], report['dependencies'])

    def test_md5_capability_must_explicitly_enable_each_exact_symbol(self):
        for symbol in MD5:
            module, _, _ = md5(symbol)
            self.accepted(module)
            for missing_key in (False, True):
                capabilities = copy.deepcopy(CAP)
                if missing_key:
                    del capabilities['managedForeignCalls']
                else:
                    capabilities['managedForeignCalls'].remove(symbol)
                report = audit.Audit([('managed-memory.json', module)], capabilities).run(['root'])
                self.assertFalse(report['accepted'], report)
                self.assertEqual([], report['missingGlobals'], report)
                self.assertTrue(any(i['code'] == 'foreign-call' and 'capability disabled' in str(i['detail'])
                                    for i in report['issues']), report)

    def test_forged_md5_descriptor_cannot_bypass_local_or_global_haskell_bindings(self):
        for symbol in MD5:
            self.accepted(md5(symbol)[0])
            for scope in ('global', 'local', 'formal'):
                module, call, _ = md5(symbol)
                fields = [binder('arg' + str(i), p) for i, p in enumerate(MD5[symbol])]
                body = app(['con', 'T1', 1, dict(rep=copy.deepcopy(CLOSURE))],
                           [['void', dict(rep=copy.deepcopy(STATE))]], tup(STATE))
                binding = dict(id='foreign-id', name=symbol, lifted=True, arity=len(fields),
                               rep=copy.deepcopy(CLOSURE), expr=lam(fields, body, tup(STATE)))
                if scope == 'global':
                    module['bindings'].append(binding)
                elif scope == 'local':
                    root = module['bindings'][0]['expr']
                    root[2] = ['let', False, [binding], root[2], dict(rep=copy.deepcopy(INT))]
                else:
                    module['bindings'][0]['expr'][1].append(binder('foreign-id', CLOSURE))
                    module['bindings'][0]['arity'] += 1
                descriptor = call[6].pop('foreignCall')
                self.accepted(module)  # Genuine ordinary Haskell call is supported.
                call[6]['foreignCall'] = descriptor
                report = self.rejected(module, 'foreign-call')
                self.assertTrue(any('Unresolved declared foreign variable' in str(i['detail'])
                                    for i in report['issues']), report)
                if scope == 'global':
                    self.assertEqual(1, report['summary']['reachableBindings'])

    def test_md5_heads_require_nonempty_unresolved_variables_with_exact_closure_proof(self):
        for symbol in MD5:
            self.accepted(md5(symbol)[0])
            for proof in (None, LIFTED, ARRAY, dict(CLOSURE, evaluated=False), dict(CLOSURE, extra=True),
                          dict(kind='unknown', primReps=None, evaluated=False), dict(CLOSURE, evaluated=1)):
                module, call, _ = md5(symbol)
                call[1] = ['var', 'foreign-id'] if proof is None else var('foreign-id', proof)
                self.rejected(module, 'foreign-call')
            module, call, _ = md5(symbol)
            call[1][1] = ''
            self.rejected(module, 'foreign-call')
            module, call, _ = md5(symbol)
            call[1][1] = None
            report = check(module)
            self.assertFalse(report['accepted'], report)
            self.assertEqual([], report['missingGlobals'], report)
            self.assertIn('foreign-call', {i['code'] for i in report['issues']}, report)
            # A null Id also violates the ordinary expression grammar; it must
            # still receive the closed-foreign diagnostic before that fallback.
            module, call, _ = md5(symbol)
            call[1] = ['lit', 'string-bytes', '00', dict(rep=copy.deepcopy(ADDRESS))]
            self.rejected(module, 'foreign-call')

    def test_md5_main_safe_and_descriptor_corruptions_are_specific_rejections(self):
        for symbol in MD5:
            self.accepted(md5(symbol)[0])
            for mutation in ('main', 'safe', 'dynamic', 'data', 'schema-bool', 'schema-extra',
                             'arity', 'supplied-arity', 'convention', 'declared-whnf'):
                module, _, descriptor = md5(symbol)
                if mutation == 'main': descriptor['target']['unit'] = 'main'
                elif mutation == 'safe': descriptor['safety'] = 'safe'
                elif mutation == 'dynamic': descriptor['target']['kind'] = 'dynamic'
                elif mutation == 'data': descriptor['target']['isFunction'] = False
                elif mutation == 'schema-bool': descriptor['schema'] = True
                elif mutation == 'schema-extra': descriptor['unexpected'] = True
                elif mutation == 'arity': descriptor['arity'] -= 1
                elif mutation == 'supplied-arity': descriptor['suppliedArity'] -= 1
                elif mutation == 'convention': descriptor['convention'] = 'stdcall'
                else: descriptor['argumentReps'][0]['evaluated'] = True
                self.rejected(module, 'foreign-call')

    def test_md5_exact_actual_declared_and_singleton_state_shapes(self):
        for symbol in MD5:
            self.accepted(md5(symbol)[0])
            for index in range(len(MD5[symbol])):
                for site in ('actual', 'declared'):
                    module, call, descriptor = md5(symbol)
                    proof = call[2][index][2]['rep'] if site == 'actual' else descriptor['argumentReps'][index]
                    proof['kind'] = 'unknown'
                    self.rejected(module, 'foreign-call')
                module, call, _ = md5(symbol)
                call[3][index] = True
                self.rejected(module, 'foreign-call')
            for site in ('actual', 'declared'):
                for wrong in (STATE, tup(), tup(STATE, STATE), tup(INT)):
                    module, call, descriptor = md5(symbol)
                    if site == 'actual': call[6]['rep'] = copy.deepcopy(wrong)
                    else: descriptor['resultRep'] = dict(copy.deepcopy(wrong), evaluated=False)
                    self.rejected(module, 'foreign-call')
            for mutation in ('state-not-evaluated', 'declared-result-evaluated', 'declared-extra',
                             'actual-extra', 'argument-metadata-missing', 'too-few', 'too-many'):
                module, call, descriptor = md5(symbol)
                if mutation == 'state-not-evaluated': call[6]['rep']['components'][0]['evaluated'] = False
                elif mutation == 'declared-result-evaluated': descriptor['resultRep']['evaluated'] = True
                elif mutation == 'declared-extra': descriptor['argumentReps'][0]['extra'] = 0
                elif mutation == 'actual-extra': call[2][0][2]['rep']['extra'] = 0
                elif mutation == 'argument-metadata-missing': call[2][0].pop()
                elif mutation == 'too-few': call[2].pop(); call[3].pop()
                else: call[2].append(literal()); call[3].append(False)
                self.rejected(module, 'foreign-call')
        for wrong in (INT, WORD, WORD8):
            module, call, _ = md5('__hsbase_MD5Update')
            call[2][2][2]['rep'] = copy.deepcopy(wrong)
            self.rejected(module, 'foreign-call')

    def test_unknown_and_missing_md5_descriptors_remain_exact_external_frontiers(self):
        for symbol in MD5:
            self.accepted(md5(symbol)[0])
            for mode in ('missing', 'unknown', 'not-record'):
                module, call, descriptor = md5(symbol)
                if mode == 'missing': del call[6]['foreignCall']
                elif mode == 'unknown': descriptor['target']['symbol'] = '__hsbase_MD5Other'
                else: call[6]['foreignCall'] = None
                report = check(module)
                self.assertFalse(report['accepted'], report)
                self.assertEqual(['foreign-id'], [g['id'] for g in report['missingGlobals']], report)
                if mode == 'unknown':
                    self.assertTrue(any(i['code'] == 'foreign-call' for i in report['issues']), report)
                else:
                    self.assertEqual([], report['issues'], report)

    def test_ordinary_same_named_globals_are_traversed_not_intercepted(self):
        for symbol in MD5:
            # With no descriptor this is an ordinary user function, even if its
            # exact id looks like one of the three C symbols.
            call = app(var(symbol, CLOSURE), [literal()], INT)
            target = dict(id=symbol, name=symbol, lifted=True, arity=1, rep=copy.deepcopy(CLOSURE),
                          expr=lam([binder('x', INT)], literal(), INT))
            module = wrapped(call, [], INT, [target])
            report = self.accepted(module)
            self.assertEqual(2, report['summary']['reachableBindings'])
            self.assertEqual([symbol], [edge['dependency'] for edge in report['dependencies']])
            target['expr'][2] = var('ordinary-body-missing', INT)
            report = check(module)
            self.assertFalse(report['accepted'], report)
            self.assertEqual([], report['issues'], report)
            self.assertEqual(['ordinary-body-missing'], [g['id'] for g in report['missingGlobals']], report)


if __name__ == '__main__':
    unittest.main()
