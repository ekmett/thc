# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Closed original GHC 9.14.1 foreign declarations, never wrapper-pattern aliases.

Recognition is separate from capability admission and runtime frame-kind checks.
These declarations alone do not enable complete decoding or remote capture.
"""

STACK_CLONE = 'stg_cloneMyStackzh'
GMP_ARRAY = 'BoxedRep (Just Unlifted)'
# Actual ghc-internal primitive FCallId shapes, not the source IO wrapper types.
# Even source-pure cmp/mod carry State; q/r return the singleton State tuple.
GMP_OPERATIONS = {
    '__gmpn_add': ((GMP_ARRAY, GMP_ARRAY, 'IntRep', GMP_ARRAY, 'IntRep', None), (None, 'WordRep')),
    '__gmpn_add_1': ((GMP_ARRAY, GMP_ARRAY, 'IntRep', 'WordRep', None), (None, 'WordRep')),
    '__gmpn_cmp': ((GMP_ARRAY, GMP_ARRAY, 'IntRep', None), (None, 'IntRep')),
    '__gmpn_divrem_1': ((GMP_ARRAY, 'IntRep', GMP_ARRAY, 'IntRep', 'WordRep', None), (None, 'WordRep')),
    '__gmpn_mod_1': ((GMP_ARRAY, 'IntRep', 'WordRep', None), (None, 'WordRep')),
    '__gmpn_mul': ((GMP_ARRAY, GMP_ARRAY, 'IntRep', GMP_ARRAY, 'IntRep', None), (None, 'WordRep')),
    '__gmpn_mul_1': ((GMP_ARRAY, GMP_ARRAY, 'IntRep', 'WordRep', None), (None, 'WordRep')),
    '__gmpn_sub': ((GMP_ARRAY, GMP_ARRAY, 'IntRep', GMP_ARRAY, 'IntRep', None), (None, 'WordRep')),
    '__gmpn_tdiv_qr': ((GMP_ARRAY, GMP_ARRAY, 'IntRep', GMP_ARRAY, 'IntRep', GMP_ARRAY, 'IntRep', None), (None,)),
    'integer_gmp_mpn_tdiv_q': ((GMP_ARRAY, GMP_ARRAY, 'IntRep', GMP_ARRAY, 'IntRep', None), (None,)),
    'integer_gmp_mpn_tdiv_r': ((GMP_ARRAY, GMP_ARRAY, 'IntRep', GMP_ARRAY, 'IntRep', None), (None,)),
}
GMP_SYMBOLS = frozenset(GMP_OPERATIONS)
# The managed runtime has no native DWARF backend, matching RTS USE_LIBDW=0.
LIBDW_UNAVAILABLE = {
    'libdwPoolTake': ((None,), (None, 'AddrRep')),
    'libdwGetBacktrace': (('AddrRep', None), (None, 'AddrRep')),
    'libdwLookupLocation': (('AddrRep', 'AddrRep', 'AddrRep', None), (None, 'Int32Rep')),
    'libdwPoolClear': ((None,), (None,)),
}
SEEK_CONSTANTS = frozenset((
    'ghczuwrapperZC1ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCSEEKzuSET',
    'ghczuwrapperZC2ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCSEEKzuCUR',
    'ghczuwrapperZC0ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCSEEKzuEND'))
STACK_INFO = frozenset(('getStackInfoTableAddrzh', 'getInfoTableAddrszh', 'lookupIPE',
    'getUnderflowFrameNextChunkzh', 'getWordzh', 'isArgGenBigRetFunTypezh',
    'getLargeBitmapzh', 'getBCOLargeBitmapzh', 'getRetFunLargeBitmapzh',
    'getSmallBitmapzh', 'getRetFunSmallBitmapzh', 'getStackClosurezh',
    'getStackFieldszh', 'advanceStackFrameLocationzh'))

OPERATIONS = {
    **{symbol: ('ccall', 'unsafe', arguments, output)
       for symbol, (arguments, output) in LIBDW_UNAVAILABLE.items()},
    **{symbol: ('ccall', 'unsafe', arguments, output)
       for symbol, (arguments, output) in GMP_OPERATIONS.items()},
    '__hscore_sizeof_stat': ('ccall', 'unsafe', (None,), (None, 'IntRep')),
    '__hscore_fstat': ('ccall', 'unsafe', ('Int32Rep', 'AddrRep', None), (None, 'Int32Rep')),
    '__hscore_open': ('ccall', 'unsafe', ('AddrRep', 'Int32Rep', 'Word32Rep', None), (None, 'Int32Rep')),
    'lockFile': ('ccall', 'unsafe', ('Word64Rep', 'Word64Rep', 'Word64Rep', 'Int32Rep', None), (None, 'Int32Rep')),
    'unlockFile': ('ccall', 'unsafe', ('Word64Rep', None), (None, 'Int32Rep')),
    '__hscore_st_dev': ('ccall', 'unsafe', ('AddrRep', None), (None, 'Word64Rep')),
    '__hscore_st_ino': ('ccall', 'unsafe', ('AddrRep', None), (None, 'Word64Rep')),
    '__hscore_st_mode': ('ccall', 'unsafe', ('AddrRep', None), (None, 'Word32Rep')),
    '__hscore_st_size': ('ccall', 'unsafe', ('AddrRep', None), (None, 'Int64Rep')),
    'ghczuwrapperZC8ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCSzuISREG':
        ('capi', 'unsafe', ('Word32Rep', None), (None, 'Int32Rep')),
    'ghczuwrapperZC7ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCSzuISCHR':
        ('capi', 'unsafe', ('Word32Rep', None), (None, 'Int32Rep')),
    'ghczuwrapperZC6ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCSzuISBLK':
        ('capi', 'unsafe', ('Word32Rep', None), (None, 'Int32Rep')),
    'ghczuwrapperZC5ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCSzuISDIR':
        ('capi', 'unsafe', ('Word32Rep', None), (None, 'Int32Rep')),
    'ghczuwrapperZC4ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCSzuISFIFO':
        ('capi', 'unsafe', ('Word32Rep', None), (None, 'Int32Rep')),
    'ghczuwrapperZC3ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCSzuISSOCK':
        ('capi', 'unsafe', ('Word32Rep', None), (None, 'Int32Rep')),
    'ghczuwrapperZC22ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCread':
        ('capi', 'safe', ('Int32Rep', 'AddrRep', 'Word64Rep', None), (None, 'Int64Rep')),
    'ghczuwrapperZC23ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCread':
        ('capi', 'unsafe', ('Int32Rep', 'AddrRep', 'Word64Rep', None), (None, 'Int64Rep')),
    'ghczuwrapperZC20ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCwrite':
        ('capi', 'safe', ('Int32Rep', 'AddrRep', 'Word64Rep', None), (None, 'Int64Rep')),
    'ghczuwrapperZC21ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCwrite':
        ('capi', 'unsafe', ('Int32Rep', 'AddrRep', 'Word64Rep', None), (None, 'Int64Rep')),
    '__hscore_get_errno': ('ccall', 'unsafe', (None,), (None, 'Int32Rep')),
    **{symbol: ('capi', 'unsafe', (None,), (None, 'Int32Rep')) for symbol in SEEK_CONSTANTS},
    'close': ('ccall', 'unsafe', ('Int32Rep', None), (None, 'Int32Rep')),
    'dup': ('ccall', 'unsafe', ('Int32Rep', None), (None, 'Int32Rep')),
    'dup2': ('ccall', 'unsafe', ('Int32Rep', 'Int32Rep', None), (None, 'Int32Rep')),
    'ghczuwrapperZC19ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZClseek':
        ('capi', 'unsafe', ('Int32Rep', 'Int64Rep', 'Int32Rep', None), (None, 'Int64Rep')),
    '__hscore_ftruncate': ('ccall', 'unsafe', ('Int32Rep', 'Int64Rep', None), (None, 'Int32Rep')),
    'isatty': ('ccall', 'unsafe', ('Int32Rep', None), (None, 'Int32Rep')),
    'fdReady': ('ccall', ('safe', 'unsafe'), ('Int32Rep', 'Word8Rep', 'Int64Rep', 'Word8Rep', None), (None, 'Int32Rep')),
    'localeEncoding': ('ccall', 'unsafe', (None,), (None, 'AddrRep')),
    'hs_iconv_open': ('ccall', 'unsafe', ('AddrRep', 'AddrRep', None), (None, 'Int64Rep')),
    'hs_iconv_close': ('ccall', 'unsafe', ('Int64Rep', None), (None, 'Int32Rep')),
    'hs_iconv': ('ccall', 'unsafe', ('Int64Rep', 'AddrRep', 'AddrRep', 'AddrRep', 'AddrRep', None), (None, 'Word64Rep')),
    'base_strerror_r': ('ccall', 'safe', ('Int32Rep', 'AddrRep', 'Word64Rep', None), (None, 'Int32Rep')),
    'hs_free_stable_ptr': ('ccall', 'unsafe', ('AddrRep', None), (None,)),
    STACK_CLONE: ('prim', 'safe', (None,), (None, 'BoxedRep (Just Unlifted)')),
    'getStackInfoTableAddrzh': ('prim', 'safe', ('BoxedRep (Just Unlifted)',), 'AddrRep'),
    'getInfoTableAddrszh': ('prim', 'safe', ('BoxedRep (Just Unlifted)', 'WordRep'), ('AddrRep', 'AddrRep')),
    'lookupIPE': ('ccall', 'safe', ('AddrRep', 'AddrRep', None), (None, 'Word8Rep')),
    'getUnderflowFrameNextChunkzh': ('prim', 'safe', ('BoxedRep (Just Unlifted)', 'WordRep'), 'BoxedRep (Just Unlifted)'),
    'getWordzh': ('prim', 'safe', ('BoxedRep (Just Unlifted)', 'WordRep'), 'WordRep'),
    'isArgGenBigRetFunTypezh': ('prim', 'safe', ('BoxedRep (Just Unlifted)', 'WordRep'), 'IntRep'),
    'getLargeBitmapzh': ('prim', 'safe', ('BoxedRep (Just Unlifted)', 'WordRep'), ('AddrRep', 'WordRep')),
    'getBCOLargeBitmapzh': ('prim', 'safe', ('BoxedRep (Just Unlifted)', 'WordRep'), ('AddrRep', 'WordRep')),
    'getRetFunLargeBitmapzh': ('prim', 'safe', ('BoxedRep (Just Unlifted)', 'WordRep'), ('AddrRep', 'WordRep')),
    'getSmallBitmapzh': ('prim', 'safe', ('BoxedRep (Just Unlifted)', 'WordRep'), ('WordRep', 'WordRep')),
    'getRetFunSmallBitmapzh': ('prim', 'safe', ('BoxedRep (Just Unlifted)', 'WordRep'), ('WordRep', 'WordRep')),
    'getStackClosurezh': ('prim', 'safe', ('BoxedRep (Just Unlifted)', 'WordRep'), 'BoxedRep (Just Lifted)'),
    'getStackFieldszh': ('prim', 'safe', ('BoxedRep (Just Unlifted)',), 'Word32Rep'),
    'advanceStackFrameLocationzh': ('prim', 'safe', ('BoxedRep (Just Unlifted)', 'WordRep'),
                                   ('BoxedRep (Just Unlifted)', 'WordRep', 'IntRep')),
}
STAT_IMAGE = frozenset(symbol for symbol in OPERATIONS
    if symbol.startswith('__hscore_st_') or symbol == '__hscore_sizeof_stat' or 'ZCSzuIS' in symbol)
SCALAR_KEYS = {'kind', 'primReps', 'evaluated'}
TUPLE_KEYS = SCALAR_KEYS | {'aggregate', 'components'}
DESCRIPTOR_KEYS = {'schema', 'target', 'convention', 'safety', 'arity', 'suppliedArity', 'argumentReps', 'resultRep'}


def require(condition, detail):
    if not condition:
        raise ValueError('Invalid original foreign call: ' + detail)


def scalar_kind(primitive):
    return ('void' if primitive is None else 'address' if primitive == 'AddrRep'
            else 'object' if primitive in ('BoxedRep (Just Unlifted)', 'BoxedRep (Just Lifted)') else 'long')


def scalar(raw, primitive, declared=False):
    kind = scalar_kind(primitive)
    return (isinstance(raw, dict) and raw.keys() == SCALAR_KEYS and raw.get('kind') == kind
            and raw.get('primReps') == ([] if primitive is None else [primitive])
            and type(raw.get('evaluated')) is bool and (not declared or raw['evaluated'] is False))


def result(raw, output, declared=False):
    if not isinstance(output, tuple):
        return scalar(raw, output, declared)
    if not isinstance(raw, dict):
        return False
    fields = raw.get('components')
    return (raw.keys() == TUPLE_KEYS and raw.get('kind') == 'unknown' and raw.get('aggregate') == 'unboxed-tuple'
            and raw.get('primReps') == [p for p in output if p is not None] and type(raw.get('evaluated')) is bool
            and (not declared or raw['evaluated'] is False) and isinstance(fields, list) and len(fields) == len(output)
            and all(scalar(field, primitive) and field['evaluated'] is True for field, primitive in zip(fields, output)))


def validate_head(function, defined):
    proof = function[2].get('rep') if (isinstance(function, list) and len(function) == 3
                                     and isinstance(function[2], dict)) else None
    require(isinstance(function, list) and len(function) == 3 and function[0] == 'var'
            and isinstance(function[1], str) and bool(function[1]) and not defined
            and isinstance(proof, dict) and proof.keys() == SCALAR_KEYS
            and proof['kind'] == 'closure' and proof['primReps'] == ['BoxedRep (Just Lifted)']
            and proof['evaluated'] is True, 'unresolved declared foreign variable required')


def raw_rep(expression):
    """Foreign provenance uses the raw certificate, never intrinsic fallback."""
    if not isinstance(expression, list) or not expression:
        return None
    index = {'var': 2, 'lit': 3, 'app': 6, 'lam': 3, 'let': 4,
             'case': 4, 'con': 3, 'prim': 2, 'void': 1}.get(expression[0])
    metadata = expression[index] if index is not None and len(expression) > index else None
    return metadata.get('rep') if isinstance(metadata, dict) else None


def validate_state_binding(raw):
    """Match CoreStackForeign: a State occurrence cannot erase a stored value."""
    if raw is not None:
        require(isinstance(raw, dict) and raw.get('kind') in ('void', 'unknown')
                and raw.get('primReps') in (None, []) and 'aggregate' not in raw and 'vector' not in raw,
                'stored State argument')


def validate_operand_binding(raw, primitive, detail):
    """Keep unknown stored proofs refinable, but never erase known carrier/reps."""
    if raw is not None:
        require(isinstance(raw, dict) and raw.get('kind') in (scalar_kind(primitive), 'unknown')
                and raw.get('primReps') in (None, [] if primitive is None else [primitive])
                and 'aggregate' not in raw and 'vector' not in raw, detail)


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
    allowed_safety = safety if isinstance(safety, tuple) else (safety,)
    require(descriptor.get('convention') == convention and descriptor.get('safety') in allowed_safety, 'calling convention/safety')
    require(all(type(descriptor.get(k)) is int and descriptor[k] == len(expected) for k in ('arity', 'suppliedArity')),
            'saturated arity')
    declared = descriptor.get('argumentReps')
    require(isinstance(declared, list) and len(declared) == len(expected)
            and all(scalar(p, e, True) for p, e in zip(declared, expected)), 'declared argument representations')
    require(isinstance(argument_reps, list) and len(argument_reps) == len(expected)
            and all(scalar(p, e) for p, e in zip(argument_reps, expected)), 'actual argument representations')
    require(isinstance(flags, list) and len(flags) == len(expected) and all(f is False for f in flags), 'unlifted argument flags')
    require(result(descriptor.get('resultRep'), output, True) and result(metadata.get('rep'), output) and result(result_rep, output),
            'exact scalar/tuple result representations')
    return symbol
