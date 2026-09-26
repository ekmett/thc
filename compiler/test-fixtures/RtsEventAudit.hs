-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module RtsEventAudit where

import GHC.Exts
import GHC.IO (IO(..))

-- Consumers of the exact installed private FCallIds, not replacement imports.
type Processors = State# RealWorld -> (# State# RealWorld, Word32# #)
type Capabilities = Word32# -> State# RealWorld -> (# State# RealWorld #)
type Siginfo = State# RealWorld -> (# State# RealWorld, Word64# #)
type Setfd = State# RealWorld -> (# State# RealWorld, Int32# #)
type Cloexec = State# RealWorld -> (# State# RealWorld, Int64# #)
type Store = Addr# -> State# RealWorld -> (# State# RealWorld, Addr# #)

originalProcessors :: Processors -> Int# -> Int#
originalProcessors call _ = case call realWorld# of (# _, n #) -> word2Int# (word32ToWord# n)
originalCapabilities :: Capabilities -> Int# -> Int#
originalCapabilities call n = case call (wordToWord32# (int2Word# n)) realWorld# of (# _ #) -> n
originalSiginfo :: Siginfo -> Int# -> Int#
originalSiginfo call _ = case call realWorld# of (# _, n #) -> word2Int# (word64ToWord# n)
originalSetfd :: Setfd -> Int# -> Int#
originalSetfd call _ = case call realWorld# of (# _, n #) -> int32ToInt# n
originalCloexec :: Cloexec -> Int# -> Int#
originalCloexec call _ = case call realWorld# of (# _, n #) -> int64ToInt# n
originalStore :: Store -> Int# -> Int#
originalStore call x = case call nullAddr# realWorld# of
  (# s, before #) -> case eqAddr# before nullAddr# of
    0# -> case call nullAddr# s of (# _, after #) -> eqAddr# before after
    _ -> case makeStablePtr# (I# x) s of
      (# s1, candidate #) -> case call (unsafeCoerce# candidate) s1 of
        (# s2, installed #) -> case call nullAddr# s2 of
          (# _, after #) -> eqAddr# installed after *# eqAddr# installed (unsafeCoerce# candidate)

nativeProcessors :: Processors -> Int -> IO Int
nativeProcessors call _ = IO (\s -> case call s of (# next, n #) -> (# next, I# (word2Int# (word32ToWord# n)) #))
nativeCapabilities :: Capabilities -> Int -> IO Int
nativeCapabilities call (I# n) = IO (\s -> case call (wordToWord32# (int2Word# n)) s of (# next #) -> (# next, I# n #))
nativeSiginfo :: Siginfo -> Int -> IO Int
nativeSiginfo call _ = IO (\s -> case call s of (# next, n #) -> (# next, I# (word2Int# (word64ToWord# n)) #))
nativeSetfd :: Setfd -> Int -> IO Int
nativeSetfd call _ = IO (\s -> case call s of (# next, n #) -> (# next, I# (int32ToInt# n) #))
nativeCloexec :: Cloexec -> Int -> IO Int
nativeCloexec call _ = IO (\s -> case call s of (# next, n #) -> (# next, I# (int64ToInt# n) #))
-- Never install a test object in the native RTS's real event-manager slot.
-- Query the existing value twice; runtime tests exercise first-writer ownership.
nativeStore :: Store -> Int -> IO Int
nativeStore call _ = IO (\s -> case call nullAddr# s of
  (# s1, before #) -> case call nullAddr# s1 of
    (# next, after #) -> (# next, I# (eqAddr# before after) #))
