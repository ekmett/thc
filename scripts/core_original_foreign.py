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
    'integer_gmp_mpn_rshift': ((GMP_ARRAY, GMP_ARRAY, 'IntRep', 'WordRep', None), (None, 'WordRep')),
    'integer_gmp_mpn_rshift_2c': ((GMP_ARRAY, GMP_ARRAY, 'IntRep', 'WordRep', None), (None, 'WordRep')),
    'integer_gmp_mpn_get_d': ((GMP_ARRAY, 'IntRep', 'IntRep', None), (None, 'DoubleRep')),
    '__int_encodeDouble': (('IntRep', 'IntRep', None), (None, 'DoubleRep')),
}
GMP_SYMBOLS = frozenset(GMP_OPERATIONS)
# The managed runtime has no native DWARF backend, matching RTS USE_LIBDW=0.
LIBDW_UNAVAILABLE = {
    'libdwPoolTake': ((None,), (None, 'AddrRep')),
    'libdwGetBacktrace': (('AddrRep', None), (None, 'AddrRep')),
    'libdwLookupLocation': (('AddrRep', 'AddrRep', 'AddrRep', None), (None, 'Int32Rep')),
    'libdwPoolClear': ((None,), (None,)),
}
TERMIOS_OPERATIONS = {
    '__hscore_get_saved_termios': (('Int32Rep', None), (None, 'AddrRep')),
    '__hscore_set_saved_termios': (('Int32Rep', 'AddrRep', None), (None,)),
    '__hscore_lflag': (('AddrRep', None), (None, 'Word32Rep')),
    '__hscore_poke_lflag': (('AddrRep', 'Word32Rep', None), (None,)),
    '__hscore_ptr_c_cc': (('AddrRep', None), (None, 'AddrRep')),
    '__hscore_sizeof_termios': ((None,), (None, 'IntRep')),
    '__hscore_sizeof_sigset_t': ((None,), (None, 'IntRep')),
    **{f'__hscore_{name}': ((None,), (None, 'Int32Rep'))
       for name in ('echo', 'icanon', 'vmin', 'vtime', 'tcsanow', 'sigttou', 'sig_block', 'sig_setmask')},
}
TERMIOS_SYMBOLS = frozenset(TERMIOS_OPERATIONS)
SIGSET_OPERATIONS = {
    'ghczuwrapperZC13ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCsigemptyset':
        (('AddrRep', None), (None, 'Int32Rep')),
    'ghczuwrapperZC12ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCsigaddset':
        (('AddrRep', 'Int32Rep', None), (None, 'Int32Rep')),
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

TCSETATTR_SYMBOL = 'ghczuwrapperZC9ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCtcsetattr'
TCGETATTR_SYMBOL = 'ghczuwrapperZC10ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCtcgetattr'

OPERATIONS = {
    'getProgArgv': ('ccall', 'unsafe', ('AddrRep', 'AddrRep', None), (None,)),
    'setProgArgv': ('ccall', 'unsafe', ('Int32Rep', 'AddrRep', None), (None,)),
    'stg_sig_install': ('ccall', 'unsafe', ('Int32Rep', 'Int32Rep', 'AddrRep', None), (None, 'Int32Rep')),
    TCSETATTR_SYMBOL:
        ('capi', 'unsafe', ('Int32Rep', 'Int32Rep', 'AddrRep', None), (None, 'Int32Rep')),
    'ghczuwrapperZC11ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCsigprocmask':
        ('capi', 'unsafe', ('Int32Rep', 'AddrRep', 'AddrRep', None), (None, 'Int32Rep')),
    TCGETATTR_SYMBOL:
        ('capi', 'unsafe', ('Int32Rep', 'AddrRep', None), (None, 'Int32Rep')),

    'malloc': ('ccall', 'unsafe', ('Word64Rep', None), (None, 'AddrRep')),
    'realloc': ('ccall', 'unsafe', ('AddrRep', 'Word64Rep', None), (None, 'AddrRep')),
    'free': ('ccall', 'unsafe', ('AddrRep', None), (None,)),
    'memmove': ('ccall', 'unsafe', ('AddrRep', 'AddrRep', 'Word64Rep', None), (None, 'AddrRep')),
    'memcpy': ('ccall', 'unsafe', ('AddrRep', 'AddrRep', 'Word64Rep', None), (None, 'AddrRep')),
    'strlen': ('ccall', 'unsafe', ('AddrRep', None), (None, 'IntRep')),
    'getenv': ('ccall', 'unsafe', ('AddrRep', None), (None, 'AddrRep')),
    'unlink': ('ccall', 'unsafe', ('AddrRep', None), (None, 'Int32Rep')),
    'putenv': ('ccall', 'unsafe', ('AddrRep', None), (None, 'Int32Rep')),
    '__hsbase_unsetenv': ('ccall', 'unsafe', ('AddrRep', None), (None, 'Int32Rep')),
    '__hscore_environ': ('ccall', 'unsafe', (None,), (None, 'AddrRep')),
    **{symbol: ('capi', 'unsafe', arguments, output)
       for symbol, (arguments, output) in SIGSET_OPERATIONS.items()},
    'rtsSupportsBoundThreads': ('ccall', 'unsafe', (None,), (None, 'IntRep')),
    'stg_getThreadAllocationCounterzh': ('prim', 'safe', (None,), (None, 'Int64Rep')),
    'rts_isThreaded': ('ccall', 'unsafe', (None,), (None, 'IntRep')),
    'reportStackOverflow': ('ccall', 'unsafe', ('BoxedRep (Just Unlifted)', None), (None,)),
    'reportHeapOverflow': ('ccall', 'unsafe', (None,), (None,)),
    'errorBelch2': ('ccall', 'unsafe', ('AddrRep', 'AddrRep', None), (None,)),
    'shutdownHaskellAndExit': ('ccall', 'safe', ('Int32Rep', 'Int32Rep', None), (None,)),
    'shutdownHaskellAndSignal': ('ccall', 'safe', ('Int32Rep', 'Int32Rep', None), (None,)),
    **{symbol: ('ccall', 'unsafe', arguments, output)
       for symbol, (arguments, output) in LIBDW_UNAVAILABLE.items()},
    **{symbol: ('ccall', 'unsafe', arguments, output)
       for symbol, (arguments, output) in TERMIOS_OPERATIONS.items()},
    **{symbol: ('ccall', 'unsafe', arguments, output)
       for symbol, (arguments, output) in GMP_OPERATIONS.items()},
    '__hscore_sizeof_stat': ('ccall', 'unsafe', (None,), (None, 'IntRep')),
    '__hscore_fstat': ('ccall', 'unsafe', ('Int32Rep', 'AddrRep', None), (None, 'Int32Rep')),
    '__hscore_open': ('ccall', ('unsafe', 'safe', 'interruptible'), ('AddrRep', 'Int32Rep', 'Word32Rep', None), (None, 'Int32Rep')),
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
    **{symbol: ('ccall', 'unsafe', (None,), (None, 'Int32Rep')) for symbol in (
        '__hscore_o_append', '__hscore_o_creat', '__hscore_o_noctty', '__hscore_o_nonblock',
        '__hscore_o_rdonly', '__hscore_o_rdwr', '__hscore_o_wronly', '__hscore_f_getfl', '__hscore_f_setfl')},
    'ghczuwrapperZC17ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCfcntl':
        ('capi', 'unsafe', ('Int32Rep', 'Int32Rep', None), (None, 'Int32Rep')),
    'ghczuwrapperZC16ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCfcntl':
        ('capi', 'unsafe', ('Int32Rep', 'Int32Rep', 'Int64Rep', None), (None, 'Int32Rep')),
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
    'getOrSetSystemEventThreadEventManagerStore': ('ccall', 'unsafe', ('AddrRep', None), (None, 'AddrRep')),
    'getOrSetGHCConcSignalSignalHandlerStore': ('ccall', 'unsafe', ('AddrRep', None), (None, 'AddrRep')),
    'getOrSetLibHSghcFastStringTable': ('ccall', 'unsafe', ('AddrRep', None), (None, 'AddrRep')),
    'keepCAFsForGHCi': ('ccall', 'unsafe', (None,), (None, 'IntRep')),
    'rts_setMainThread': ('ccall', 'unsafe', ('BoxedRep (Just Unlifted)', None), (None,)),
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

# Original installed library declarations observed in real Alex/Unix closures.
# Same libc symbols, but different physical operands or result ABI from the
# ghc-internal declarations above. Do not infer these from caller binding names.
LIBRARY_OPERATIONS = {
    ('ghc-9.14.1-inplace', 'getOrSetLibHSghcFastStringTable'): OPERATIONS['getOrSetLibHSghcFastStringTable'],
    ('ghc-9.14.1-inplace', 'keepCAFsForGHCi'): OPERATIONS['keepCAFsForGHCi'],
    ('array-0.5.8.0-inplace', 'memcpy'):
        ('ccall', 'unsafe', (GMP_ARRAY, GMP_ARRAY, 'Word64Rep', None), (None, 'AddrRep')),
    ('bytestring-0.12.2.0-inplace', 'strlen'):
        ('ccall', 'unsafe', ('AddrRep', None), (None, 'Word64Rep')),
}


def operation(target):
    unit, symbol = target.get('unit'), target['symbol']
    return (LIBRARY_OPERATIONS.get((unit, symbol), OPERATIONS[symbol])
            if isinstance(unit, str) else OPERATIONS[symbol])


def require(condition, detail):
    if not condition:
        raise ValueError('Invalid original foreign call: ' + detail)


def scalar_kind(primitive):
    return ('void' if primitive is None else 'address' if primitive == 'AddrRep'
            else 'float' if primitive == 'FloatRep' else 'double' if primitive == 'DoubleRep'
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
    convention, safety, expected, output = operation(target)
    if symbol in ('getOrSetLibHSghcFastStringTable', 'keepCAFsForGHCi'):
        require(target.get('unit') == 'ghc-9.14.1-inplace', 'exact compiler unit')
    require(descriptor.keys() == DESCRIPTOR_KEYS and type(descriptor.get('schema')) is int and descriptor['schema'] == 1,
            'descriptor schema')
    require(target.keys() == {'kind', 'symbol', 'unit', 'isFunction'} and target.get('kind') == 'static'
            and target.get('isFunction') is True
            and (target.get('unit') == 'ghc-internal' or
                 isinstance(target.get('unit'), str) and (target['unit'], symbol) in LIBRARY_OPERATIONS),
            'static supported installed-library function target')
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
