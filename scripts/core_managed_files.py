# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Exact versioned JVM file ABI; these symbols never impersonate POSIX calls."""

PREFIX = 'thc_io_v1_'
OPERATIONS = {
    PREFIX + 'open': (('AddrRep', 'IntRep', None), 'IntRep'),
    PREFIX + 'read': (('IntRep', 'AddrRep', 'IntRep', None), 'IntRep'),
    PREFIX + 'write': (('IntRep', 'AddrRep', 'IntRep', None), 'IntRep'),
    PREFIX + 'close': (('IntRep', None), 'IntRep'),
    PREFIX + 'error_kind': ((None,), 'IntRep'),
    PREFIX + 'error_message': ((None,), 'AddrRep'),
    PREFIX + 'seek': (('IntRep', 'IntRep', 'IntRep', None), 'IntRep'),
    PREFIX + 'size': (('IntRep', None), 'IntRep'),
    PREFIX + 'set_size': (('IntRep', 'IntRep', None), 'IntRep'),
    PREFIX + 'is_terminal': (('IntRep', None), 'IntRep'),
    PREFIX + 'device_type': (('IntRep', None), 'IntRep'),
}
SCALAR_KEYS = {'kind', 'primReps', 'evaluated'}
TUPLE_KEYS = SCALAR_KEYS | {'aggregate', 'components'}
DESCRIPTOR_KEYS = {'schema', 'target', 'convention', 'safety', 'arity', 'suppliedArity', 'argumentReps', 'resultRep'}


def require(condition, detail):
    if not condition:
        raise ValueError('Invalid managed file call: ' + detail)


def scalar(raw, primitive, declared=False):
    kind = 'address' if primitive == 'AddrRep' else 'long' if primitive == 'IntRep' else 'void'
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
    """Return the exact symbol or None; every invalid reserved-prefix call raises.

    Inputs remain raw proof maps. The importing unit may be absent (null), main,
    or a package; the prim/safe versioned declaration establishes the ABI.
    """
    if not isinstance(metadata, dict):
        return None
    descriptor = metadata.get('foreignCall')
    if not isinstance(descriptor, dict):
        return None
    target = descriptor.get('target')
    if not isinstance(target, dict) or not isinstance(target.get('symbol'), str):
        return None
    symbol = target['symbol']
    if not symbol.startswith(PREFIX):
        return None
    require(symbol in OPERATIONS, 'unknown version 1 symbol ' + symbol)
    expected, output = OPERATIONS[symbol]
    require(descriptor.keys() == DESCRIPTOR_KEYS and type(descriptor.get('schema')) is int and descriptor['schema'] == 1,
            'descriptor schema')
    unit = target.get('unit')
    require(target.keys() == {'kind', 'symbol', 'unit', 'isFunction'} and target.get('kind') == 'static'
            and target.get('isFunction') is True and (unit is None or isinstance(unit, str) and bool(unit)),
            'static function target')
    require(descriptor.get('convention') == 'prim' and descriptor.get('safety') == 'safe', 'calling convention/safety')
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
