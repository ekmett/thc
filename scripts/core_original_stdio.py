# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Exact original GHC 9.14.1 stdout write declarations; no wrapper-pattern aliases."""

OPERATIONS = {
    'ghczuwrapperZC20ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCwrite':
        ('capi', 'safe', ('Int32Rep', 'AddrRep', 'Word64Rep', None), 'Int64Rep'),
    'ghczuwrapperZC21ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCwrite':
        ('capi', 'unsafe', ('Int32Rep', 'AddrRep', 'Word64Rep', None), 'Int64Rep'),
    '__hscore_get_errno': ('ccall', 'unsafe', (None,), 'Int32Rep'),
}
SCALAR_KEYS = {'kind', 'primReps', 'evaluated'}
TUPLE_KEYS = SCALAR_KEYS | {'aggregate', 'components'}
DESCRIPTOR_KEYS = {'schema', 'target', 'convention', 'safety', 'arity', 'suppliedArity', 'argumentReps', 'resultRep'}


def require(condition, detail):
    if not condition:
        raise ValueError('Invalid original stdio call: ' + detail)


def scalar(raw, primitive, declared=False):
    kind = 'void' if primitive is None else 'address' if primitive == 'AddrRep' else 'long'
    return (isinstance(raw, dict) and raw.keys() == SCALAR_KEYS and raw.get('kind') == kind
            and raw.get('primReps') == ([] if primitive is None else [primitive])
            and type(raw.get('evaluated')) is bool and (not declared or raw['evaluated'] is False))


def result(raw, primitive, declared=False):
    if not isinstance(raw, dict):
        return False
    fields = raw.get('components')
    return (raw.keys() == TUPLE_KEYS and raw.get('kind') == 'unknown' and raw.get('aggregate') == 'unboxed-tuple'
            and raw.get('primReps') == [primitive] and type(raw.get('evaluated')) is bool
            and (not declared or raw['evaluated'] is False) and isinstance(fields, list) and len(fields) == 2
            and scalar(fields[0], None) and fields[0]['evaluated'] is True
            and scalar(fields[1], primitive) and fields[1]['evaluated'] is True)


def validate_head(function, defined):
    proof = function[2].get('rep') if (isinstance(function, list) and len(function) == 3
                                     and isinstance(function[2], dict)) else None
    require(isinstance(function, list) and len(function) == 3 and function[0] == 'var'
            and isinstance(function[1], str) and bool(function[1]) and not defined
            and isinstance(proof, dict) and proof.keys() == SCALAR_KEYS
            and proof['kind'] == 'closure' and proof['primReps'] == ['BoxedRep (Just Lifted)']
            and proof['evaluated'] is True, 'unresolved declared foreign variable required')


def validate(metadata, argument_reps, flags, result_rep):
    """Return an exact symbol or None; malformed recognized declarations raise."""
    if not isinstance(metadata, dict):
        return None
    descriptor = metadata.get('foreignCall')
    if not isinstance(descriptor, dict):
        return None
    target = descriptor.get('target')
    if not isinstance(target, dict) or not isinstance(target.get('symbol'), str):
        return None
    symbol = target['symbol']
    if symbol not in OPERATIONS:
        return None
    convention, safety, expected, output = OPERATIONS[symbol]
    require(descriptor.keys() == DESCRIPTOR_KEYS and type(descriptor.get('schema')) is int and descriptor['schema'] == 1,
            'descriptor schema')
    require(target.keys() == {'kind', 'symbol', 'unit', 'isFunction'} and target.get('kind') == 'static'
            and target.get('isFunction') is True and target.get('unit') == 'ghc-internal',
            'static ghc-internal function target')
    require(descriptor.get('convention') == convention and descriptor.get('safety') == safety, 'calling convention/safety')
    require(all(type(descriptor.get(k)) is int and descriptor[k] == len(expected) for k in ('arity', 'suppliedArity')),
            'saturated arity')
    declared = descriptor.get('argumentReps')
    require(isinstance(declared, list) and len(declared) == len(expected)
            and all(scalar(p, e, True) for p, e in zip(declared, expected)), 'declared argument representations')
    require(isinstance(argument_reps, list) and len(argument_reps) == len(expected)
            and all(scalar(p, e) for p, e in zip(argument_reps, expected)), 'actual argument representations')
    require(isinstance(flags, list) and len(flags) == len(expected) and all(f is False for f in flags), 'unlifted argument flags')
    require(result(descriptor.get('resultRep'), output, True) and result(metadata.get('rep'), output) and result(result_rep, output),
            'exact State/result tuple')
    return symbol
