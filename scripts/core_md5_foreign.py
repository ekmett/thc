# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Closed exact MD5 ForeignCall proof; no synthetic-name parsing/general FFI."""

OPERATIONS = {'__hsbase_MD5Init': ('INIT', ('AddrRep', None)),
              '__hsbase_MD5Update': ('UPDATE', ('AddrRep', 'AddrRep', 'Int32Rep', None)),
              '__hsbase_MD5Final': ('FINAL', ('AddrRep', 'AddrRep', None))}
SCALAR_KEYS = {'kind', 'primReps', 'evaluated'}
TUPLE_KEYS = SCALAR_KEYS | {'aggregate', 'components'}
DESCRIPTOR_KEYS = {'schema', 'target', 'convention', 'safety', 'arity', 'suppliedArity', 'argumentReps', 'resultRep'}


def require(condition, detail):
    if not condition:
        raise ValueError('Invalid MD5 foreign call: ' + detail)


def scalar(raw, primitive, declared=False):
    kind = 'address' if primitive == 'AddrRep' else 'long' if primitive == 'Int32Rep' else 'void'
    return (isinstance(raw, dict) and raw.keys() == SCALAR_KEYS and raw.get('kind') == kind
            and raw.get('primReps') == ([] if primitive is None else [primitive])
            and type(raw.get('evaluated')) is bool and (not declared or raw['evaluated'] is False))


def result(raw, declared=False):
    if not isinstance(raw, dict):
        return False
    fields = raw.get('components')
    return (raw.keys() == TUPLE_KEYS and raw.get('kind') == 'unknown' and raw.get('aggregate') == 'unboxed-tuple'
            and raw.get('primReps') == [] and type(raw.get('evaluated')) is bool
            and (not declared or raw['evaluated'] is False) and isinstance(fields, list) and len(fields) == 1
            and scalar(fields[0], None) and fields[0]['evaluated'] is True)


def validate(metadata, argument_reps, flags, result_rep):
    """Return INIT/UPDATE/FINAL or None; recognized-invalid descriptors raise.

    Caller must establish an app with an ordinary variable head. All proof inputs
    are raw maps, never normalized by a permissive representation parser.
    """
    if not isinstance(metadata, dict):
        return None
    descriptor = metadata.get('foreignCall')
    if not isinstance(descriptor, dict):
        return None
    target = descriptor.get('target')
    if not isinstance(target, dict) or not isinstance(target.get('symbol'), str) or target['symbol'] not in OPERATIONS:
        return None
    operation, expected = OPERATIONS[target['symbol']]
    require(descriptor.keys() == DESCRIPTOR_KEYS and type(descriptor.get('schema')) is int and descriptor['schema'] == 1,
            'descriptor schema')
    require(target.keys() == {'kind', 'symbol', 'unit', 'isFunction'} and target.get('kind') == 'static'
            and target.get('unit') == 'ghc-internal' and target.get('isFunction') is True, 'static ghc-internal function target')
    require(descriptor.get('convention') == 'ccall' and descriptor.get('safety') == 'unsafe', 'calling convention/safety')
    require(all(type(descriptor.get(k)) is int and descriptor[k] == len(expected) for k in ('arity', 'suppliedArity')),
            'saturated arity')
    declared = descriptor.get('argumentReps')
    require(isinstance(declared, list) and len(declared) == len(expected)
            and all(scalar(p, e, True) for p, e in zip(declared, expected)), 'declared argument representations')
    require(isinstance(argument_reps, list) and len(argument_reps) == len(expected)
            and all(scalar(p, e) for p, e in zip(argument_reps, expected)), 'actual argument representations')
    require(isinstance(flags, list) and len(flags) == len(expected) and all(f is False for f in flags), 'unlifted argument flags')
    require(result(descriptor.get('resultRep'), True) and result(metadata.get('rep')) and result(result_rep),
            'singleton State tuple result')
    return operation
