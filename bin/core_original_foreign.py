# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

"""Closed original GHC 9.14.1 foreign declarations and pinned library aliases.

Recognition is separate from capability admission and runtime frame-kind checks.
These declarations alone do not enable complete decoding or remote capture.
"""

import re
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

STACK_CLONE = 'stg_cloneMyStackzh'
PROCESS_OPERATIONS = {
    'runInteractiveProcess': ('ccall', 'unsafe', ('AddrRep', 'AddrRep', 'AddrRep', 'Int32Rep', 'Int32Rep',
        'Int32Rep', 'AddrRep', 'AddrRep', 'AddrRep', 'AddrRep', 'AddrRep', 'Int32Rep', 'AddrRep', None), (None, 'Int32Rep')),
    'getProcessExitCode': ('ccall', 'unsafe', ('Int32Rep', 'AddrRep', None), (None, 'Int32Rep')),
    'waitForProcess': ('ccall', 'interruptible', ('Int32Rep', 'AddrRep', None), (None, 'Int32Rep')),
    'terminateProcess': ('ccall', 'unsafe', ('Int32Rep', None), (None, 'Int32Rep')),
}
WINDOWS_DIRECTORY_OPERATIONS = {
    'FindFirstFileW': ('ccall', 'unsafe', ('AddrRep', 'AddrRep', None), (None, 'AddrRep')),
    'FindNextFileW': ('ccall', 'unsafe', ('AddrRep', 'AddrRep', None), (None, 'IntRep')),
    'FindClose': ('ccall', 'unsafe', ('AddrRep', None), (None, 'IntRep')),
    'GetLastError': ('ccall', 'unsafe', (None,), (None, 'Word32Rep')),
}
WINDOWS_ENCODING_OPERATIONS = {
    'GetACP': ('ccall', 'unsafe', (None,), (None, 'Word32Rep')),
    'GetConsoleCP': ('ccall', 'unsafe', (None,), (None, 'Word32Rep')),
    'GetCPInfo': ('ccall', 'unsafe', ('Word32Rep', 'AddrRep', None), (None, 'IntRep')),
    'IsDBCSLeadByteEx': ('ccall', 'unsafe', ('Word32Rep', 'Word8Rep', None), (None, 'IntRep')),
    'MultiByteToWideChar': ('ccall', 'unsafe', ('Word32Rep', 'Word32Rep', 'AddrRep', 'Int32Rep', 'AddrRep', 'Int32Rep', None), (None, 'Int32Rep')),
    'WideCharToMultiByte': ('ccall', ('unsafe', 'safe'), ('Word32Rep', 'Word32Rep', 'AddrRep', 'Int32Rep', 'AddrRep', 'Int32Rep', 'AddrRep', 'AddrRep', None), (None, 'Int32Rep')),
    'maperrno': ('ccall', 'unsafe', (None,), (None,)),
    'maperrno_func': ('ccall', 'unsafe', ('Word32Rep', None), (None, 'Int32Rep')),
    'base_getErrorMessage': ('ccall', 'unsafe', ('Word32Rep', None), (None, 'AddrRep')),
    'LocalFree': ('ccall', 'unsafe', ('AddrRep', None), (None, 'AddrRep')),
}
DIRECTORY_STREAM_OPERATIONS = {
    'ghczuwrapperZC0ZCunixzm2zi8zi8zi0zminplaceZCSystemziPosixziDirectoryziPosixPathZCopendir':
        ('capi', 'unsafe', ('AddrRep', None), (None, 'AddrRep')),
    'ghczuwrapperZC0ZCunixzm2zi8zi8zi0zminplaceZCSystemziPosixziDirectoryziCommonZCfdopendir':
        ('capi', 'unsafe', ('Int32Rep', None), (None, 'AddrRep')),
    'closedir': ('ccall', 'unsafe', ('AddrRep', None), (None, 'Int32Rep')),
    '__hscore_readdir': ('ccall', 'unsafe', ('AddrRep', 'AddrRep', None), (None, 'Int32Rep')),
    '__hscore_d_name': ('ccall', 'unsafe', ('AddrRep', None), (None, 'AddrRep')),
    '__hscore_free_dirent': ('ccall', 'unsafe', ('AddrRep', None), (None,)),
}
WAIT_STATUS_OPERATIONS = {
    f'ghczuwrapperZC{index}ZCunixzm2zi8zi8zi0zminplaceZCSystemziPosixziProcessziInternalsZC{name}':
        ('capi', 'unsafe', ('Int32Rep', None), (None, 'Int32Rep'))
    for index, name in enumerate(('WCOREDUMP', 'WSTOPSIG', 'WIFSTOPPED', 'WTERMSIG',
                                  'WIFSIGNALED', 'WEXITSTATUS', 'WIFEXITED'))
}
TEXT_OPERATIONS = {
    '_hs_text_reverse': ('ccall', 'unsafe', ('BoxedRep (Just Unlifted)', 'BoxedRep (Just Unlifted)', 'Word64Rep', 'Word64Rep', None), (None,)),
    '_hs_text_memchr': ('ccall', 'unsafe', ('BoxedRep (Just Unlifted)', 'Word64Rep', 'Word64Rep', 'Word8Rep', None), (None, 'Int64Rep')),
    '_hs_text_measure_off': ('ccall', 'unsafe', ('BoxedRep (Just Unlifted)', 'Word64Rep', 'Word64Rep', 'Word64Rep', None), (None, 'Int64Rep')),
}
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
    'integer_gmp_gcd_word': (('WordRep', 'WordRep', None), (None, 'WordRep')),
    'integer_gmp_mpn_gcd_1': ((GMP_ARRAY, 'IntRep', 'WordRep', None), (None, 'WordRep')),
    'integer_gmp_mpn_gcd': ((GMP_ARRAY, GMP_ARRAY, 'IntRep', GMP_ARRAY, 'IntRep', None), (None, 'IntRep')),
    'integer_gmp_mpn_lshift': ((GMP_ARRAY, GMP_ARRAY, 'IntRep', 'WordRep', None), (None, 'WordRep')),
    'integer_gmp_mpn_and_n': ((GMP_ARRAY, GMP_ARRAY, GMP_ARRAY, 'IntRep', None), (None,)),
    'integer_gmp_mpn_andn_n': ((GMP_ARRAY, GMP_ARRAY, GMP_ARRAY, 'IntRep', None), (None,)),
    'integer_gmp_mpn_ior_n': ((GMP_ARRAY, GMP_ARRAY, GMP_ARRAY, 'IntRep', None), (None,)),
    'integer_gmp_mpn_xor_n': ((GMP_ARRAY, GMP_ARRAY, GMP_ARRAY, 'IntRep', None), (None,)),
    '__gmpn_popcount': ((GMP_ARRAY, 'IntRep', None), (None, 'WordRep')),
}
GMP_SYMBOLS = frozenset(GMP_OPERATIONS)
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

def unix_declaration(declaration, result, *arguments):
    wrapper = declaration.split(':')
    symbol = declaration if len(wrapper) == 1 else (
        'ghczuwrapperZC' + wrapper[0] + 'ZCunixzm2zi8zi8zi0zminplaceZCSystemziPosixzi' + wrapper[1] + 'ZC' + wrapper[2])
    return symbol, ('ccall' if len(wrapper) == 1 else 'capi',
                    wrapper[3] if len(wrapper) == 4 else 'unsafe', (*arguments, None), (None, result))


# Original installed declarations; wrapper names retain their owner and C ABI.
UNIX_NATIVE_OPERATIONS = dict(unix_declaration(*row) for row in (
    ('chown', 'Int32Rep', 'AddrRep', 'Word32Rep', 'Word32Rep'),
    ('lchown', 'Int32Rep', 'AddrRep', 'Word32Rep', 'Word32Rep'),
    ('pathconf', 'Int64Rep', 'AddrRep', 'Int32Rep'),
    ('0:FilesziPosixString:truncate', 'Int32Rep', 'AddrRep', 'Int64Rep'),
    ('1:FilesziPosixString:mknod', 'Int32Rep', 'AddrRep', 'Word32Rep', 'Word64Rep'),
    ('4:FilesziCommon:utimes', 'Int32Rep', 'AddrRep', 'AddrRep'),
    ('3:FilesziCommon:lutimes', 'Int32Rep', 'AddrRep', 'AddrRep'),
    ('fchmod', 'Int32Rep', 'Int32Rep', 'Word32Rep'),
    ('fchown', 'Int32Rep', 'Int32Rep', 'Word32Rep', 'Word32Rep'),
    ('fpathconf', 'Int64Rep', 'Int32Rep', 'Int32Rep'),
    ('2:FilesziCommon:futimes', 'Int32Rep', 'Int32Rep', 'AddrRep'),
    ('5:FilesziCommon:futimens', 'Int32Rep', 'Int32Rep', 'AddrRep'),
    ('1:Unistd:fsync:safe', 'Int32Rep', 'Int32Rep'),
    ('0:Unistd:fdatasync:safe', 'Int32Rep', 'Int32Rep'),
    ('0:Fcntl:posixzufallocate:safe', 'Int32Rep', 'Int32Rep', 'Int64Rep', 'Int64Rep'),
    ('1:Fcntl:posixzufadvise:safe', 'Int32Rep', 'Int32Rep', 'Int64Rep', 'Int64Rep', 'Int32Rep'),
    ('0:TerminalziCommon:tcflow', 'Int32Rep', 'Int32Rep', 'Int32Rep'),
    ('1:TerminalziCommon:tcflush', 'Int32Rep', 'Int32Rep', 'Int32Rep'),
    ('2:TerminalziCommon:tcdrain:safe', 'Int32Rep', 'Int32Rep'),
    ('3:TerminalziCommon:tcsendbreak', 'Int32Rep', 'Int32Rep', 'Int32Rep'),
    ('tcgetpgrp', 'Int32Rep', 'Int32Rep'),
    ('getuid', 'Word32Rep'), ('getgid', 'Word32Rep'), ('getegid', 'Word32Rep'),
    ('getppid', 'Int32Rep'), ('getpgrp', 'Int32Rep'), ('getpgid', 'Int32Rep', 'Int32Rep'),
    ('sysconf', 'Int64Rep', 'Int32Rep'),
    ('0:FilesziCommon:makedev', 'Word64Rep', 'Word32Rep', 'Word32Rep'),
    ('0:Time:time', 'Int64Rep', 'AddrRep'), ('1:ProcessziCommon:times', 'Int64Rep', 'AddrRep'),
    ('uname', 'Int32Rep', 'AddrRep'), ('1:Resource:getrlimit', 'Int32Rep', 'Int32Rep', 'AddrRep'),
    ('1:Signals:sigfillset', 'Int32Rep', 'AddrRep'),
    ('2:Signals:sigdelset', 'Int32Rep', 'AddrRep', 'Int32Rep'),
    ('0:Signals:sigismember', 'Int32Rep', 'AddrRep', 'Int32Rep'),
    ('9:TerminalziCommon:cfgetispeed', 'Word32Rep', 'AddrRep'),
    ('7:TerminalziCommon:cfgetospeed', 'Word32Rep', 'AddrRep'),
    ('8:TerminalziCommon:cfsetispeed', 'Int32Rep', 'AddrRep', 'Word32Rep'),
    ('6:TerminalziCommon:cfsetospeed', 'Int32Rep', 'AddrRep', 'Word32Rep'),
))

UNIX_ENVIRONMENT_OPERATIONS = {
    'setenv': ('ccall', 'unsafe', ('AddrRep', 'AddrRep', 'Int32Rep', None), (None, 'Int32Rep')),
    'clearenv': ('ccall', 'unsafe', (None,), (None, 'IntRep')),
    '__hsunix_get_environ': ('ccall', 'unsafe', (None,), (None, 'AddrRep')),
    **dict(unix_declaration('0:' + module + ':unsetenv', 'Int32Rep', 'AddrRep')
           for module in ('Env', 'EnvziByteString', 'EnvziPosixString')),
}

COMPILER_HOST_WAYS = ('rts_isDynamic', 'rts_isProfiled', 'rts_isThreaded', 'rts_isDebugged', 'rts_isTracing')

OPERATIONS = {
    **UNIX_NATIVE_OPERATIONS,
    **UNIX_ENVIRONMENT_OPERATIONS,
    **{name: ('ccall', 'safe', ('Int32Rep', 'AddrRep', 'Word64Rep', None), (None, 'Int64Rep'))
       for name in ('read', 'write')},
    **PROCESS_OPERATIONS,
    **DIRECTORY_STREAM_OPERATIONS,
    **WINDOWS_DIRECTORY_OPERATIONS,
    **WINDOWS_ENCODING_OPERATIONS,
    **TEXT_OPERATIONS,
    **WAIT_STATUS_OPERATIONS,
    'getProgArgv': ('ccall', 'unsafe', ('AddrRep', 'AddrRep', None), (None,)),
    'setProgArgv': ('ccall', 'unsafe', ('Int32Rep', 'AddrRep', None), (None,)),
    'stg_sig_install': ('ccall', 'unsafe', ('Int32Rep', 'Int32Rep', 'AddrRep', None), (None, 'Int32Rep')),
    TCSETATTR_SYMBOL:
        ('capi', 'unsafe', ('Int32Rep', 'Int32Rep', 'AddrRep', None), (None, 'Int32Rep')),
    TCGETATTR_SYMBOL:
        ('capi', 'unsafe', ('Int32Rep', 'AddrRep', None), (None, 'Int32Rep')),

    'malloc': ('ccall', 'unsafe', ('Word64Rep', None), (None, 'AddrRep')),
    'realloc': ('ccall', 'unsafe', ('AddrRep', 'Word64Rep', None), (None, 'AddrRep')),
    'free': ('ccall', 'unsafe', ('AddrRep', None), (None,)),
    'memmove': ('ccall', 'unsafe', ('AddrRep', 'AddrRep', 'Word64Rep', None), (None, 'AddrRep')),
    'memcpy': ('ccall', 'unsafe', ('AddrRep', 'AddrRep', 'Word64Rep', None), (None, 'AddrRep')),
    'memcmp': ('ccall', 'unsafe', ('AddrRep', 'AddrRep', 'Word64Rep', None), (None, 'Int32Rep')),
    'bytestring_is_valid_utf8': ('ccall', ('safe', 'unsafe'), ('AddrRep', 'Word64Rep', None), (None, 'Int32Rep')),
    'memset': ('ccall', 'unsafe', ('AddrRep', 'Int32Rep', 'Word64Rep', None), (None, 'AddrRep')),
    'memchr': ('ccall', 'unsafe', ('AddrRep', 'Int32Rep', 'Word64Rep', None), (None, 'AddrRep')),
    'strlen': ('ccall', 'unsafe', ('AddrRep', None), (None, 'IntRep')),
    'getenv': ('ccall', 'unsafe', ('AddrRep', None), (None, 'AddrRep')),
    'unlink': ('ccall', 'unsafe', ('AddrRep', None), (None, 'Int32Rep')),
    'putenv': ('ccall', 'unsafe', ('AddrRep', None), (None, 'Int32Rep')),
    '__hsbase_unsetenv': ('ccall', 'unsafe', ('AddrRep', None), (None, 'Int32Rep')),
    '__hscore_environ': ('ccall', 'unsafe', (None,), (None, 'AddrRep')),
    'rtsSupportsBoundThreads': ('ccall', 'unsafe', (None,), (None, 'IntRep')),
    # These observe THC-owned guest lifetimes; no native TSO pointer ABI is admitted.
    'rts_getThreadId': ('ccall', 'unsafe', ('BoxedRep (Just Unlifted)', None), (None, 'Word64Rep')),
    'eq_thread': ('ccall', 'unsafe', ('BoxedRep (Just Unlifted)', 'BoxedRep (Just Unlifted)', None), (None, 'Word8Rep')),
    'cmp_thread': ('ccall', 'unsafe', ('BoxedRep (Just Unlifted)', 'BoxedRep (Just Unlifted)', None), (None, 'Int32Rep')),
    'getRTSStatsEnabled': ('ccall', 'safe', (None,), (None, 'IntRep')),
    'getRTSStats': ('ccall', 'safe', ('AddrRep', None), (None,)),
    **{symbol: ('ccall', 'safe', (None,), (None,))
       for symbol in ('performGC', 'performMajorGC', 'performBlockingMajorGC')},
    'getMonotonicNSec': ('ccall', 'unsafe', (None,), (None, 'Word64Rep')),
    'setHeapSize': ('ccall', 'unsafe', ('IntRep', None), (None,)),
    'getNumberOfProcessors': ('ccall', 'unsafe', (None,), (None, 'Word32Rep')),
    'setNumCapabilities': ('ccall', 'safe', ('Word32Rep', None), (None,)),
    '__hscore_sizeof_siginfo_t': ('ccall', 'safe', (None,), (None, 'Word64Rep')),
    '__hscore_f_setfd': ('ccall', 'unsafe', (None,), (None, 'Int32Rep')),
    '__hscore_fd_cloexec': ('ccall', 'unsafe', (None,), (None, 'Int64Rep')),
    'stg_getThreadAllocationCounterzh': ('prim', 'safe', (None,), (None, 'Int64Rep')),
    **{symbol: ('ccall', 'unsafe', (None,), (None, 'IntRep')) for symbol in COMPILER_HOST_WAYS},
    'reportStackOverflow': ('ccall', 'unsafe', ('BoxedRep (Just Unlifted)', None), (None,)),
    'reportHeapOverflow': ('ccall', 'unsafe', (None,), (None,)),
    'errorBelch2': ('ccall', 'unsafe', ('AddrRep', 'AddrRep', None), (None,)),
    'debugBelch2': ('ccall', 'unsafe', ('AddrRep', 'AddrRep', None), (None,)),
    'shutdownHaskellAndExit': ('ccall', 'safe', ('Int32Rep', 'Int32Rep', None), (None,)),
    'shutdownHaskellAndSignal': ('ccall', 'safe', ('Int32Rep', 'Int32Rep', None), (None,)),
    **{symbol: ('ccall', 'unsafe', arguments, output)
       for symbol, (arguments, output) in TERMIOS_OPERATIONS.items()},
    **{symbol: ('ccall', 'unsafe', arguments, output)
       for symbol, (arguments, output) in GMP_OPERATIONS.items()},
    '__hscore_sizeof_stat': ('ccall', 'unsafe', (None,), (None, 'IntRep')),
    'chdir': ('ccall', 'unsafe', ('AddrRep', None), (None, 'Int32Rep')),
    'getcwd': ('ccall', 'unsafe', ('AddrRep', 'Word64Rep', None), (None, 'AddrRep')),
    'symlink': ('ccall', 'unsafe', ('AddrRep', 'AddrRep', None), (None, 'Int32Rep')),
    'rename': ('ccall', 'unsafe', ('AddrRep', 'AddrRep', None), (None, 'Int32Rep')),
    'rmdir': ('ccall', 'unsafe', ('AddrRep', None), (None, 'Int32Rep')),
    'readlink': ('ccall', 'unsafe', ('AddrRep', 'AddrRep', 'Word64Rep', None), (None, 'Int32Rep')),
    'mkdir': ('ccall', 'unsafe', ('AddrRep', 'Word32Rep', None), (None, 'Int32Rep')),
    'ghczuwrapperZC1ZCdirectoryzm1zi3zi10zi0zminplaceZCSystemziDirectoryziInternalziPosixZCfstatat': ('capi', 'safe', ('Int32Rep', 'AddrRep', 'AddrRep', 'Int32Rep', None), (None, 'Int32Rep')),
    'unlinkat': ('ccall', 'safe', ('Int32Rep', 'AddrRep', 'Int32Rep', None), (None, 'Int32Rep')),
    'access': ('ccall', 'unsafe', ('AddrRep', 'Int32Rep', None), (None, 'Int32Rep')),
    'chmod': ('ccall', 'unsafe', ('AddrRep', 'Word32Rep', None), (None, 'Int32Rep')),
    '__hscore_stat': ('ccall', 'unsafe', ('AddrRep', 'AddrRep', None), (None, 'Int32Rep')),
    '__hscore_lstat': ('ccall', 'unsafe', ('AddrRep', 'AddrRep', None), (None, 'Int32Rep')),
    'ghczuwrapperZC2ZCunixzm2zi8zi8zi0zminplaceZCSystemziPosixziFilesziPosixStringZClstat':
        ('capi', 'unsafe', ('AddrRep', 'AddrRep', None), (None, 'Int32Rep')),
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
    'getpid': ('ccall', 'unsafe', (None,), (None, 'Int32Rep')),
    'geteuid': ('ccall', 'unsafe', (None,), (None, 'Word32Rep')),
    '__hscore_get_errno': ('ccall', 'unsafe', (None,), (None, 'Int32Rep')),
    '__hscore_set_errno': ('ccall', 'unsafe', ('Int32Rep', None), (None,)),
    **{symbol: ('ccall', 'unsafe', (None,), (None, 'Int32Rep')) for symbol in (
        '__hscore_o_append', '__hscore_o_creat', '__hscore_o_excl', '__hscore_o_binary', '__hscore_o_trunc',
        '__hscore_o_noctty', '__hscore_o_nonblock',
        '__hscore_o_rdonly', '__hscore_o_rdwr', '__hscore_o_wronly', '__hscore_f_getfl', '__hscore_f_setfl')},
    'ghczuwrapperZC17ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCfcntl':
        ('capi', 'unsafe', ('Int32Rep', 'Int32Rep', None), (None, 'Int32Rep')),
    'ghczuwrapperZC16ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCfcntl':
        ('capi', 'unsafe', ('Int32Rep', 'Int32Rep', 'Int64Rep', None), (None, 'Int32Rep')),
    **{symbol: ('capi', 'unsafe', (None,), (None, 'Int32Rep')) for symbol in SEEK_CONSTANTS},
    'close': ('ccall', 'unsafe', ('Int32Rep', None), (None, 'Int32Rep')),
    'eventfd': ('ccall', 'unsafe', ('Int32Rep', 'Int32Rep', None), (None, 'Int32Rep')),
    'eventfd_write': ('ccall', 'unsafe', ('Int32Rep', 'Word64Rep', None), (None, 'Int32Rep')),
    'pipe': ('ccall', 'unsafe', ('AddrRep', None), (None, 'Int32Rep')),
    'epoll_create': ('ccall', 'unsafe', ('Int32Rep', None), (None, 'Int32Rep')),
    'epoll_ctl': ('ccall', 'unsafe', ('Int32Rep', 'Int32Rep', 'Int32Rep', 'AddrRep', None), (None, 'Int32Rep')),
    'epoll_wait': ('ccall', ('safe', 'unsafe'), ('Int32Rep', 'AddrRep', 'Int32Rep', 'Int32Rep', None), (None, 'Int32Rep')),
    'poll': ('ccall', ('safe', 'unsafe'), ('AddrRep', 'Word64Rep', 'Int32Rep', None), (None, 'Int32Rep')),
    'setIOManagerWakeupFd': ('ccall', 'unsafe', ('Int32Rep', None), (None,)),
    'setIOManagerControlFd': ('ccall', 'unsafe', ('Word32Rep', 'Int32Rep', None), (None,)),
    'setTimerManagerControlFd': ('ccall', 'unsafe', ('Int32Rep', None), (None,)),
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
    'getOrSetSystemEventThreadIOManagerThreadStore': ('ccall', 'unsafe', ('AddrRep', None), (None, 'AddrRep')),
    'getOrSetSystemTimerThreadEventManagerStore': ('ccall', 'unsafe', ('AddrRep', None), (None, 'AddrRep')),
    'getOrSetSystemTimerThreadIOManagerThreadStore': ('ccall', 'unsafe', ('AddrRep', None), (None, 'AddrRep')),
    'getOrSetGHCConcSignalSignalHandlerStore': ('ccall', 'unsafe', ('AddrRep', None), (None, 'AddrRep')),
    'getOrSetLibHSghcFastStringTable': ('ccall', 'unsafe', ('AddrRep', None), (None, 'AddrRep')),
    'getOrSetLibHSghcGlobalHasPprDebug': ('ccall', 'unsafe', ('AddrRep', None), (None, 'AddrRep')),
    'getOrSetLibHSghcGlobalHasNoDebugOutput': ('ccall', 'unsafe', ('AddrRep', None), (None, 'AddrRep')),
    'getOrSetLibHSghcGlobalHasNoStateHack': ('ccall', 'unsafe', ('AddrRep', None), (None, 'AddrRep')),
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
    **{('ghc-9.14.1-inplace', symbol): OPERATIONS[symbol] for symbol in COMPILER_HOST_WAYS},
    **{('unix-2.8.8.0-inplace', symbol): signature for symbol, signature in UNIX_NATIVE_OPERATIONS.items()},
    **{('unix-2.8.8.0-inplace', symbol): signature for symbol, signature in UNIX_ENVIRONMENT_OPERATIONS.items()},
    **{('unix-2.8.8.0-inplace', symbol): OPERATIONS[symbol] for symbol in ('read', 'write', 'getpid', 'putenv', 'getProgArgv')},
    **{('unix-2.8.8.0-inplace', symbol): signature for symbol, signature in DIRECTORY_STREAM_OPERATIONS.items()},
    **{('unix-2.8.8.0-inplace', symbol): OPERATIONS[symbol] for symbol in ('symlink', 'rename', 'readlink', 'chdir', 'getcwd', 'rmdir')},
    ('unix-2.8.8.0-inplace', 'geteuid'): OPERATIONS['geteuid'],
    ('unix-2.8.8.0-inplace', 'mkdir'): OPERATIONS['mkdir'],
    **{('unix-2.8.8.0-inplace', symbol): OPERATIONS[symbol]
       for symbol in ('close', 'dup', 'isatty', 'getenv')},
    ('unix-2.8.8.0-inplace', 'ghczuwrapperZC2ZCunixzm2zi8zi8zi0zminplaceZCSystemziPosixziFilesziPosixStringZClstat'):
        OPERATIONS['ghczuwrapperZC2ZCunixzm2zi8zi8zi0zminplaceZCSystemziPosixziFilesziPosixStringZClstat'],
    ('unix-2.8.8.0-inplace', 'stg_sig_install'): OPERATIONS['stg_sig_install'],
    **{('unix-2.8.8.0-inplace', symbol): operation for symbol, operation in WAIT_STATUS_OPERATIONS.items()},
    **{('text-2.1.3-inplace', symbol): operation for symbol, operation in TEXT_OPERATIONS.items()},
    ('ghc-9.14.1-inplace', 'getOrSetLibHSghcFastStringTable'): OPERATIONS['getOrSetLibHSghcFastStringTable'],
    ('ghc-9.14.1-inplace', 'getOrSetLibHSghcGlobalHasPprDebug'): OPERATIONS['getOrSetLibHSghcGlobalHasPprDebug'],
    ('ghc-9.14.1-inplace', 'getOrSetLibHSghcGlobalHasNoDebugOutput'): OPERATIONS['getOrSetLibHSghcGlobalHasNoDebugOutput'],
    ('ghc-9.14.1-inplace', 'getOrSetLibHSghcGlobalHasNoStateHack'): OPERATIONS['getOrSetLibHSghcGlobalHasNoStateHack'],
    ('ghc-9.14.1-inplace', 'keepCAFsForGHCi'): OPERATIONS['keepCAFsForGHCi'],
    ('ghc-9.14.1-inplace', 'setHeapSize'): OPERATIONS['setHeapSize'],
    ('array-0.5.8.0-inplace', 'memcpy'):
        ('ccall', 'unsafe', (GMP_ARRAY, GMP_ARRAY, 'Word64Rep', None), (None, 'AddrRep')),
    ('bytestring-0.12.2.0-inplace', 'strlen'):
        ('ccall', 'unsafe', ('AddrRep', None), (None, 'Word64Rep')),
    ('bytestring-0.12.2.0-inplace', 'memcmp'): OPERATIONS['memcmp'],
    ('bytestring-0.12.2.0-inplace', 'memchr'): OPERATIONS['memchr'],
}


def directory_unit(unit):
    return isinstance(unit, str) and re.fullmatch(r'directory-1\.3\.10\.0-(?:inplace|[0-9a-f]+)', unit) is not None


def unix_libc_unit(unit):
    return isinstance(unit, str) and re.fullmatch(r'unix-2\.8\.8\.0-(?:inplace|[0-9a-f]+)', unit) is not None


def process_unit(unit):
    return isinstance(unit, str) and re.fullmatch(r'process-1\.6\.26\.1-(?:inplace|[0-9a-f]+)', unit) is not None


DIRECTORY_FSTATAT = 'ghczuwrapperZC1ZCdirectoryzm1zi3zi10zi0zminplaceZCSystemziDirectoryziInternalziPosixZCfstatat'

UNIX_LSTAT = 'ghczuwrapperZC2ZCunixzm2zi8zi8zi0zminplaceZCSystemziPosixziFilesziPosixStringZClstat'


def operation_symbol(target):
    """Identify the closed operation without rewriting its captured symbol."""
    symbol = target.get('symbol')
    if isinstance(symbol, str):
        canonical = re.sub(r'unixzm2zi8zi8zi0zm(?:inplace|[0-9a-f]+)ZC',
                           'unixzm2zi8zi8zi0zminplaceZC', symbol)
        canonical = re.sub(r'ZCSystemziPosixziFiles(?:ziByteString)?ZC', 'ZCSystemziPosixziFilesziPosixStringZC', canonical)
        canonical = re.sub(r'ZCSystemziPosixziDirectory(?:ziByteString)?ZC', 'ZCSystemziPosixziDirectoryziPosixPathZC', canonical)
        if canonical in UNIX_NATIVE_OPERATIONS or canonical in UNIX_ENVIRONMENT_OPERATIONS or canonical == UNIX_LSTAT or canonical in WAIT_STATUS_OPERATIONS or canonical in DIRECTORY_STREAM_OPERATIONS:
            return canonical
        if canonical == 'ghczuwrapperZC5ZCunixzm2zi8zi8zi0zminplaceZCSystemziPosixziTerminalziCommonZCtcgetattr':
            return TCGETATTR_SYMBOL
        if canonical == 'ghczuwrapperZC4ZCunixzm2zi8zi8zi0zminplaceZCSystemziPosixziTerminalziCommonZCtcsetattr':
            return TCSETATTR_SYMBOL
        canonical_directory = re.sub(r'directoryzm1zi3zi10zi0zm(?:inplace|[0-9a-f]+)ZC',
                                     'directoryzm1zi3zi10zi0zminplaceZC', symbol)
        if canonical_directory == DIRECTORY_FSTATAT:
            return canonical_directory
    return symbol


def bytestring_unit(unit):
    # Match the installed-unit rule in prepare-short-bytes-slices.py without
    # rewriting the original FCall's provenance to a source-build unit.
    return isinstance(unit, str) and re.fullmatch(r'bytestring-0\.12\.2\.0(?:-[A-Za-z0-9]+)?', unit) is not None


def ram_unit(unit):
    return isinstance(unit, str) and re.fullmatch(r'ram-0\.22\.1(?:-[A-Za-z0-9]+)?', unit) is not None


def text_unit(unit):
    return isinstance(unit, str) and re.fullmatch(r'text-2\.1\.3-(?:inplace|[0-9a-f]+)', unit) is not None


def win32_unit(unit):
    return isinstance(unit, str) and re.fullmatch(r'Win32-2\.14\.2\.1-(?:inplace|[0-9a-f]+)', unit) is not None


def native_profile_operation(target):
    # The driver and runtime share these exact THC-owned descriptors. Import
    # lazily because package manifests also use the ordinary foreign validators.
    from core_package_manifest import _core_native_overrides
    for call in _core_native_overrides():
        owner = call['target']
        if (target.get('unit'), target.get('symbol')) == (owner['unit'], owner['symbol']):
            def carrier(rep):
                reps = rep['primReps']
                require(len(reps) <= 1, 'scalar native override carrier')
                return reps[0] if reps else None
            return (call['convention'], call['safety'],
                    tuple(map(carrier, call['argumentReps'])),
                    tuple(map(carrier, call['resultRep']['components'])))
    return None


def has_operation(target):
    return operation_symbol(target) in OPERATIONS or native_profile_operation(target) is not None


def operation(target, declared=None):
    unit, symbol = target.get('unit'), operation_symbol(target)
    profile = native_profile_operation(target)
    if profile is not None:
        return profile
    if symbol == '__hscore_open' and isinstance(declared, list) and len(declared) == 4 and scalar(declared[2], 'Word16Rep', True):
        convention, safety, _, output = OPERATIONS[symbol]
        return convention, safety, ('AddrRep', 'Int32Rep', 'Word16Rep', None), output
    if symbol == 'strlen' and bytestring_unit(unit):
        return LIBRARY_OPERATIONS['bytestring-0.12.2.0-inplace', symbol]
    return (LIBRARY_OPERATIONS.get((unit, symbol), OPERATIONS[symbol])
            if isinstance(unit, str) else OPERATIONS[symbol])



class ForeignOwnership:
    """One cold batch through the selected runtime's actual operation selector.

    Ownership only chooses a validator; it never grants native execution or
    replaces that validator's ABI, head, operand or provenance checks.
    """
    @staticmethod
    def key(call):
        target = call.get('target') if isinstance(call, dict) else None
        if not isinstance(target, dict) or not isinstance(target.get('symbol'), str):
            return None
        return json.dumps(target, sort_keys=True, separators=(',', ':'))

    def __init__(self, calls, runtime=None, command_file=None):
        selected = {}
        for call in calls:
            key = self.key(call)
            if key is not None: selected.setdefault(key, call)
        self.owners, self.provenance = {}, None
        if not selected: return
        command_file = command_file or (os.environ.get('THC_FOREIGN_OWNERSHIP') if runtime is None else None)
        if command_file:
            prepared = Path(command_file).resolve()
            if not prepared.is_file():
                raise ValueError('Missing prepared foreign ownership command: ' + str(prepared) +
                    '; run ./gradlew foreignOwnershipCommand explicitly')
            command = json.loads(prepared.read_text())['command']
            if not isinstance(command, list) or not command or any(not isinstance(arg, str) for arg in command):
                raise ValueError('Invalid prepared foreign ownership command')
            producer = dict(path=str(prepared), sha256=hashlib.sha256(prepared.read_bytes()).hexdigest())
        else:
            executable = Path(runtime or os.environ.get('THC_RUNTIME') or
                Path(__file__).resolve().parents[1] / 'build/install/thc/bin' /
                ('thc.bat' if os.name == 'nt' else 'thc')).resolve()
            if not executable.is_file():
                raise ValueError('Foreign ownership classification requires a built runtime: ' + str(executable) +
                    '; build the runtime explicitly or supply --runtime / --ownership-command')
            command = [str(executable), '--classify-foreign-calls']
            producer = dict(path=str(executable), sha256=hashlib.sha256(executable.read_bytes()).hexdigest())
        request = json.dumps(list(selected.values()), ensure_ascii=True, allow_nan=False).encode('ascii')
        result = subprocess.run(command, input=request, stdout=subprocess.PIPE, stderr=sys.stderr, check=False)
        if result.returncode:
            raise ValueError('Foreign ownership classifier failed with exit ' + str(result.returncode) + ': ' + str(command))
        response = json.loads(result.stdout)
        if (not isinstance(response, dict) or response.get('schema') != 1 or
                not isinstance(response.get('runtime'), dict)):
            raise ValueError('Invalid foreign ownership classifier response')
        identity = response['runtime']
        if (identity.get('algorithm') not in ('sha256', 'sha256-path-manifest-v1') or
                not isinstance(identity.get('path'), str) or
                not isinstance(identity.get('sha256'), str) or not re.fullmatch('[0-9a-f]{64}', identity['sha256'])):
            raise ValueError('Foreign ownership classifier did not identify its runtime')
        owners = response.get('owners')
        if (not isinstance(owners, list) or len(owners) != len(selected) or
                any(owner is not None and (not isinstance(owner, str) or not owner) for owner in owners)):
            raise ValueError('Invalid foreign ownership classifier response')
        self.owners = dict(zip(selected, owners))
        self.provenance = dict(command=command, requests=len(selected),
            producer=producer, runtime=identity,
            requestSha256=hashlib.sha256(request).hexdigest(),
            responseSha256=hashlib.sha256(result.stdout).hexdigest())

    def __call__(self, call):
        key = self.key(call)
        if key is None: return False
        if key not in self.owners:
            raise ValueError('Foreign ownership was not classified in the audit batch: ' + key)
        return self.owners[key] is not None


def boxed_owned_call(call):
    target = call.get('target', {}) if isinstance(call, dict) else {}
    return target.get('unit') == 'ghc-internal' and target.get('symbol') in (
        'rts_getThreadId', 'eq_thread', 'cmp_thread', 'rts_setMainThread', 'reportStackOverflow')


def validate_boxed_declaration(declaration, calls):
    """Mirror the cold exact nominal boundary of CoreBoxedForeignDeclarations."""
    emitted = declaration['emitted']
    symbol = emitted['symbol']
    if emitted['unit'] != 'ghc-internal' or symbol not in (
            'rts_getThreadId', 'eq_thread', 'cmp_thread', 'rts_setMainThread', 'reportStackOverflow'):
        return
    def ty(module, name, *arguments, namespace='type'):
        return dict(kind='tycon', name=dict(unit='ghc-internal', module=module,
            occurrence=name, namespace=namespace), arguments=list(arguments))
    def arrow(argument, result):
        return dict(kind='function', multiplicity=ty('GHC.Internal.Types', 'Many', namespace='data'),
                    argument=argument, result=result)
    thread = ty('GHC.Internal.Prim', 'ThreadId#')
    if symbol in ('rts_getThreadId', 'eq_thread', 'cmp_thread'):
        c_name, module, name, rep, count = {
            'rts_getThreadId': ('CULLong', 'GHC.Internal.Word', 'Word64', 'Word64Rep', 1),
            'eq_thread': ('CBool', 'GHC.Internal.Word', 'Word8', 'Word8Rep', 2),
            'cmp_thread': ('CInt', 'GHC.Internal.Int', 'Int32', 'Int32Rep', 2)}[symbol]
        declared, normalized = ty('GHC.Internal.Foreign.C.Types', c_name), ty(module, name)
        for _ in range(count): declared, normalized = arrow(thread, declared), arrow(thread, normalized)
        outputs = ['void', rep]
    else:
        argument = (ty('GHC.Internal.Prim', 'Weak#', ty('GHC.Internal.Types', 'Lifted', namespace='data'),
                       ty('GHC.Internal.Conc.Sync', 'ThreadId')) if symbol == 'rts_setMainThread' else thread)
        declared = normalized = arrow(argument, ty('GHC.Internal.Types', 'IO', ty('GHC.Internal.Tuple', 'Unit')))
        count, outputs = 1, ['void']
    require(declaration['symbol'] == symbol and declaration['unit'] == 'ghc-internal' and
        declaration['header'] is None and declaration['isFunction'] is True and
        declaration['convention'] == emitted['convention'] == 'ccall' and
        declaration['safety'] == emitted['safety'] == 'unsafe' and
        declaration['normalizationRole'] == 'representational', 'boxed static declaration identity')
    require(declaration['declaredType'] == declared and declaration['normalizedType'] == normalized,
            'exact boxed declared and normalized nominal types')
    require(emitted['arguments'] == ['BoxedRep (Just Unlifted)'] * count + ['void'] and
            emitted['result'] == outputs, 'boxed emitted carriers')
    for call in calls:
        target = call.get('target', {})
        if target.get('unit') != emitted['unit'] or target.get('symbol') != symbol: continue
        require(type(call.get('schema')) is int and call['schema'] == 1 and
            call.get('target') == dict(kind='static', symbol=symbol, unit='ghc-internal', isFunction=True) and
            call.get('arity') == count + 1 and call.get('suppliedArity') == count + 1 and
            call.get('convention') == emitted['convention'] and call.get('safety') == emitted['safety'] and
            all(scalar(raw, primitive, True) for raw, primitive in zip(call['argumentReps'],
                ['BoxedRep (Just Unlifted)'] * count + [None])) and
            len(call['argumentReps']) == count + 1 and
            result(call['resultRep'], tuple(None if rep == 'void' else rep for rep in outputs), True),
            'boxed call inventory differs from nominal declaration')


def validate_prim_declaration(declaration, calls):
    """Associate concrete stock prim nominal types with recursive Core reps.

    This is retained proof, not a native adapter or an execution capability.
    Unknown nominal carriers remain unsupported instead of guessing their ABI.
    """
    emitted = declaration['emitted']
    require(declaration['header'] is None and declaration['isFunction'] is True and
        declaration['symbol'] == emitted['symbol'] and declaration['unit'] == emitted['unit'] and
        declaration['convention'] == emitted['convention'] == 'prim' and
        declaration['safety'] == emitted['safety'] == 'safe', 'stock primitive identity')
    if emitted['unit'] == 'ghc-internal' and emitted['symbol'] in STACK_INFO:
        _, _, parameters, returns = OPERATIONS[emitted['symbol']]
        def ty(module, name, *arguments, namespace='type'):
            return dict(kind='tycon', name=dict(unit='ghc-internal', module=module,
                occurrence=name, namespace=namespace), arguments=list(arguments))
        def primitive(rep):
            if rep == 'BoxedRep (Just Unlifted)': return ty('GHC.Internal.Prim', 'StackSnapshot#')
            if rep == 'BoxedRep (Just Lifted)': return ty('GHC.Internal.Types', 'Any', ty('GHC.Internal.Types', 'Type'))
            return ty('GHC.Internal.Prim', rep[:-3] + '#')
        if isinstance(returns, tuple):
            prefix = [ty('GHC.Internal.Types', 'UnliftedRep') if rep == 'BoxedRep (Just Unlifted)'
                      else ty('GHC.Internal.Types', rep, namespace='data') for rep in returns]
            normalized = ty('GHC.Internal.Types', 'Tuple' + str(len(returns)) + '#',
                            *(prefix + list(map(primitive, returns))))
        else: normalized = primitive(returns)
        for rep in reversed(parameters):
            normalized = dict(kind='function', multiplicity=ty('GHC.Internal.Types', 'Many', namespace='data'),
                              argument=primitive(rep), result=normalized)
        symbol = emitted['symbol']
        alias = ('LargeBitmapGetter' if symbol in ('getLargeBitmapzh', 'getBCOLargeBitmapzh', 'getRetFunLargeBitmapzh')
                 else 'SmallBitmapGetter' if symbol in ('getSmallBitmapzh', 'getRetFunSmallBitmapzh') else None)
        declared = ty('GHC.Internal.Stack.Decode', alias) if alias else normalized
        require(declaration['declaredType'] == declared and declaration['normalizedType'] == normalized,
                'exact stock Stack declared and normalized nominal types')
    def named(value, module, name, namespace='type'):
        return value.get('kind') == 'tycon' and value.get('name') == dict(
            unit='ghc-internal', module=module, occurrence=name, namespace=namespace)
    def representation(value, evaluated):
        require(value.get('kind') == 'tycon', 'concrete primitive nominal type')
        args, name = value['arguments'], value['name']
        if name['unit'] == 'ghc-internal' and name['module'] == 'GHC.Internal.Types':
            require(name['namespace'] == 'type', 'primitive nominal namespace')
            if name['occurrence'] == 'Unit#' and not args: fields = []
            elif re.fullmatch(r'Tuple[1-9][0-9]*#', name['occurrence']):
                count = int(name['occurrence'][5:-1])
                require(len(args) == count * 2, 'primitive tuple nominal arity')
                fields = [representation(field, True) for field in args[count:]]
                for nominal_rep, field in zip(args[:count], fields):
                    reps = field['primReps']
                    rep = ('ZeroBitRep' if not reps else 'UnliftedRep' if reps == ['BoxedRep (Just Unlifted)']
                           else 'LiftedRep' if reps == ['BoxedRep (Just Lifted)'] else reps[0])
                    require(len(reps) <= 1 and named(nominal_rep, 'GHC.Internal.Types', rep,
                        'type' if rep in ('ZeroBitRep', 'UnliftedRep', 'LiftedRep') else 'data') and
                        not nominal_rep['arguments'], 'primitive tuple runtime-rep identity')
            elif name['occurrence'] == 'Any' and len(args) == 1:
                require(named(args[0], 'GHC.Internal.Types', 'Type') and not args[0]['arguments'], 'primitive Any kind')
                return dict(kind='object', primReps=['BoxedRep (Just Lifted)'], evaluated=evaluated)
            else: raise ValueError('Unsupported concrete primitive nominal type')
            return dict(kind='unknown', aggregate='unboxed-tuple',
                primReps=[rep for field in fields for rep in field['primReps']], components=fields, evaluated=evaluated)
        require(name['unit'] == 'ghc-internal' and name['module'] == 'GHC.Internal.Prim' and
                name['namespace'] == 'type', 'primitive nominal owner')
        occurrence = name['occurrence']
        if occurrence == 'State#':
            require(len(args) == 1 and named(args[0], 'GHC.Internal.Prim', 'RealWorld') and
                    not args[0]['arguments'], 'primitive State identity')
            primitive = None
        elif occurrence in ('StackSnapshot#', 'ThreadId#'):
            require(not args, 'primitive boxed nominal arity'); primitive = 'BoxedRep (Just Unlifted)'
        elif occurrence == 'StablePtr#':
            require(len(args) == 2, 'primitive stable pointer nominal arity'); primitive = 'AddrRep'
        else:
            primitives = {rep[:-3] + '#': rep for rep in ('IntRep', 'WordRep', 'Int8Rep', 'Word8Rep',
                'Int16Rep', 'Word16Rep', 'Int32Rep', 'Word32Rep', 'Int64Rep', 'Word64Rep', 'AddrRep', 'FloatRep', 'DoubleRep')}
            require(not args and occurrence in primitives, 'primitive scalar nominal identity')
            primitive = primitives[occurrence]
        return dict(kind=scalar_kind(primitive), primReps=[] if primitive is None else [primitive], evaluated=evaluated)
    arguments, value = [], declaration['normalizedType']
    while value.get('kind') == 'function':
        require(named(value['multiplicity'], 'GHC.Internal.Types', 'Many', 'data') and
                not value['multiplicity']['arguments'], 'primitive arrow multiplicity')
        arguments.append(representation(value['argument'], False)); value = value['result']
    output = representation(value, False)
    def carrier(rep):
        require(len(rep['primReps']) <= 1, 'primitive immediate scalar carrier')
        return rep['primReps'][0] if rep['primReps'] else 'void'
    require(emitted['arguments'] == list(map(carrier, arguments)) and
        emitted['result'] == list(map(carrier, output['components'] if 'components' in output else [output])),
        'primitive emitted/nominal carriers differ')
    for call in calls:
        if call.get('target', {}).get('unit') != emitted['unit'] or call['target'].get('symbol') != emitted['symbol']: continue
        require(call.get('schema') == 1 and call.get('target') == dict(kind='static', isFunction=True,
            unit=emitted['unit'], symbol=emitted['symbol']) and call.get('convention') == 'prim' and
            call.get('safety') == 'safe' and call.get('arity') == len(arguments) and
            call.get('suppliedArity') == len(arguments) and call.get('argumentReps') == arguments and
            call.get('resultRep') == output, 'primitive recursive call/nominal ABI differs')


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
    symbol = operation_symbol(target)
    if not has_operation(target):
        return None
    convention, safety, expected, output = operation(target, descriptor.get('argumentReps'))
    if 'ZCunixzm' in target['symbol']:
        unit = target.get('unit')
        require(unix_libc_unit(unit) and 'ZC' + unit.replace('-', 'zm').replace('.', 'zi') + 'ZC' in target['symbol'],
                'matching installed Unix wrapper owner')
    if symbol in UNIX_NATIVE_OPERATIONS or symbol in UNIX_ENVIRONMENT_OPERATIONS or symbol in ('read', 'write'):
        unit = target.get('unit')
        require(unix_libc_unit(unit), 'matching installed Unix native declaration owner')
    if symbol in PROCESS_OPERATIONS:
        require(process_unit(target.get('unit')), 'supported installed process unit')
    if symbol in ('memchr', 'memset', 'bytestring_is_valid_utf8'):
        require(bytestring_unit(target.get('unit')), 'supported installed bytestring unit')
    if symbol in TEXT_OPERATIONS:
        require(text_unit(target.get('unit')), 'supported installed text unit')
    if symbol == UNIX_LSTAT:
        unit = target.get('unit')
        require(unix_libc_unit(unit), 'matching installed unix path-stat unit and symbol')
    if symbol == DIRECTORY_FSTATAT:
        unit = target.get('unit')
        require(directory_unit(unit) and target['symbol'] == symbol.replace(
                'directoryzm1zi3zi10zi0zminplace', unit.replace('-', 'zm').replace('.', 'zi')),
                'matching installed directory fstatat unit and symbol')
    if symbol == 'unlinkat':
        require(directory_unit(target.get('unit')), 'supported installed directory unlinkat unit')
    if symbol in ('symlink', 'rename', 'chdir', 'getcwd', 'rmdir'):
        require(unix_libc_unit(target.get('unit')), 'supported installed unix pathname unit')
    if symbol == 'readlink':
        require(target.get('unit') == 'ghc-internal' or unix_libc_unit(target.get('unit')),
                'supported original ghc-internal or installed unix readlink unit')
    if symbol == 'geteuid':
        require(unix_libc_unit(target.get('unit')), 'supported installed unix effective UID unit')
    if symbol == 'mkdir':
        require(unix_libc_unit(target.get('unit')), 'supported installed unix mkdir unit')
    if symbol in WAIT_STATUS_OPERATIONS:
        unit = target.get('unit')
        require(unix_libc_unit(unit) and target['symbol'] == symbol.replace(
                'unixzm2zi8zi8zi0zminplace', unit.replace('-', 'zm').replace('.', 'zi')),
                'matching installed unix wait-status unit and symbol')
    if symbol in DIRECTORY_STREAM_OPERATIONS:
        unit = target.get('unit')
        require(unix_libc_unit(unit), 'matching installed unix directory-stream unit and symbol')
    if symbol in WINDOWS_DIRECTORY_OPERATIONS:
        require(win32_unit(target.get('unit')) or symbol == 'GetLastError' and target.get('unit') == 'ghc-internal',
                'pinned original Win32 or ghc-internal GetLastError unit')
    if symbol in WINDOWS_ENCODING_OPERATIONS:
        require(target.get('unit') == 'ghc-internal', 'original ghc-internal Windows encoding unit')
    if symbol in COMPILER_HOST_WAYS and symbol != 'rts_isThreaded':
        require(target.get('unit') == 'ghc-9.14.1-inplace', 'exact compiler unit')
    if symbol in ('getOrSetLibHSghcFastStringTable', 'getOrSetLibHSghcGlobalHasPprDebug',
                  'getOrSetLibHSghcGlobalHasNoDebugOutput', 'getOrSetLibHSghcGlobalHasNoStateHack', 'keepCAFsForGHCi', 'setHeapSize'):
        require(target.get('unit') == 'ghc-9.14.1-inplace', 'exact compiler unit')
    require(descriptor.keys() == DESCRIPTOR_KEYS and type(descriptor.get('schema')) is int and descriptor['schema'] == 1,
            'descriptor schema')
    require(target.keys() == {'kind', 'symbol', 'unit', 'isFunction'} and target.get('kind') == 'static'
            and target.get('isFunction') is True
            and (target.get('unit') == 'ghc-internal' or
                 symbol in UNIX_NATIVE_OPERATIONS and unix_libc_unit(target.get('unit')) or
                 symbol in UNIX_ENVIRONMENT_OPERATIONS and unix_libc_unit(target.get('unit')) or
                 symbol in ('read', 'write', 'getpid', 'putenv', 'getProgArgv', TCGETATTR_SYMBOL, TCSETATTR_SYMBOL) and unix_libc_unit(target.get('unit')) or
                 symbol in PROCESS_OPERATIONS and process_unit(target.get('unit')) or
                 symbol in TEXT_OPERATIONS and text_unit(target.get('unit')) or
                 symbol in WINDOWS_DIRECTORY_OPERATIONS and win32_unit(target.get('unit')) or
                 symbol in ('memcmp', 'memchr', 'memset', 'strlen', 'bytestring_is_valid_utf8') and bytestring_unit(target.get('unit')) or
                 symbol in ('close', 'dup', 'dup2', 'pipe', 'isatty', 'getenv', 'symlink', 'rename', 'readlink', 'chdir', 'getcwd', 'rmdir', 'geteuid', 'mkdir', UNIX_LSTAT, *WAIT_STATUS_OPERATIONS, *DIRECTORY_STREAM_OPERATIONS) and unix_libc_unit(target.get('unit')) or
                 symbol == 'memcpy' and ram_unit(target.get('unit')) or
                 symbol in ('unlinkat', DIRECTORY_FSTATAT) and directory_unit(target.get('unit')) or
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
    return target['symbol']
