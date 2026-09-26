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

type EventCreate = Int32# -> Int32# -> State# RealWorld -> (# State# RealWorld, Int32# #)
type EventWrite = Int32# -> Word64# -> State# RealWorld -> (# State# RealWorld, Int32# #)
type NativeBytes = Int32# -> Addr# -> Word64# -> State# RealWorld -> (# State# RealWorld, Int64# #)
type PipeCreate = Addr# -> State# RealWorld -> (# State# RealWorld, Int32# #)
type NativeClose = Int32# -> State# RealWorld -> (# State# RealWorld, Int32# #)
type FcntlWrite = Int32# -> Int32# -> Int64# -> State# RealWorld -> (# State# RealWorld, Int32# #)

-- Real original imported declarations flow through a complete descriptor
-- lifecycle. The oracle never compares unstable native descriptor numbers.
{-# INLINE eventfdCycle #-}
eventfdCycle :: EventCreate -> EventWrite -> NativeBytes -> NativeClose -> Int# -> State# RealWorld
             -> (# State# RealWorld, Int# #)
eventfdCycle create write read close x s0 = case newPinnedByteArray# 8# s0 of
  (# s1, buffer #) -> case create (intToInt32# x) (intToInt32# 0#) s1 of
    (# s2, fd #) -> case write fd (wordToWord64# (int2Word# (x +# 5#))) s2 of
      (# s3, wrote #) -> case read fd (mutableByteArrayContents# buffer) (wordToWord64# 8##) s3 of
        (# s4, bytes #) -> case readWord64Array# buffer 0# s4 of
          (# s5, value #) -> case close fd s5 of
            (# s6, closed #) -> (# s6, word2Int# (word64ToWord# value) +# int32ToInt# wrote
              +# int32ToInt# closed +# int64ToInt# bytes -# 8# #)

originalEventfdCycle :: EventCreate -> EventWrite -> NativeBytes -> NativeClose -> Int# -> Int#
originalEventfdCycle create write read close x = runRW# (\s ->
  case eventfdCycle create write read close x s of (# _, result #) -> result)
nativeEventfdCycle :: EventCreate -> EventWrite -> NativeBytes -> NativeClose -> Int -> IO Int
nativeEventfdCycle create write read close (I# x) = IO (\s ->
  case eventfdCycle create write read close x s of (# next, result #) -> (# next, I# result #))

{-# INLINE pipeCycle #-}
pipeCycle :: PipeCreate -> NativeBytes -> NativeBytes -> NativeClose -> FcntlWrite -> Setfd -> Cloexec
          -> Int# -> State# RealWorld -> (# State# RealWorld, Int# #)
pipeCycle create read write close fcntl setfd cloexec x s0 = case newPinnedByteArray# 8# s0 of
  (# s1, descriptors #) -> case newPinnedByteArray# 8# s1 of
    (# s2, buffer #) -> case create (mutableByteArrayContents# descriptors) s2 of
      (# s3, created #) -> case readInt32Array# descriptors 0# s3 of
        (# s4, rd #) -> case readInt32Array# descriptors 1# s4 of
          (# s5, wr #) -> case setfd s5 of
            (# s6, command #) -> case cloexec s6 of
              (# s7, flag #) -> case fcntl rd command flag s7 of
                (# s8, flags #) -> case writeWord64Array# buffer 0# (wordToWord64# (int2Word# (x +# 13#))) s8 of
                  s9 -> case write wr (mutableByteArrayContents# buffer) (wordToWord64# 8##) s9 of
                    (# s10, wrote #) -> case read rd (mutableByteArrayContents# buffer) (wordToWord64# 8##) s10 of
                      (# s11, received #) -> case readWord64Array# buffer 0# s11 of
                        (# s12, value #) -> case close rd s12 of
                          (# s13, closedRead #) -> case close wr s13 of
                            (# s14, closedWrite #) -> (# s14, word2Int# (word64ToWord# value)
                              +# int32ToInt# created +# int32ToInt# flags +# int32ToInt# closedRead
                              +# int32ToInt# closedWrite +# int64ToInt# wrote +# int64ToInt# received -# 16# #)

originalPipeCycle :: PipeCreate -> NativeBytes -> NativeBytes -> NativeClose -> FcntlWrite -> Setfd -> Cloexec -> Int# -> Int#
originalPipeCycle create read write close fcntl setfd cloexec x = runRW# (\s ->
  case pipeCycle create read write close fcntl setfd cloexec x s of (# _, result #) -> result)
nativePipeCycle :: PipeCreate -> NativeBytes -> NativeBytes -> NativeClose -> FcntlWrite -> Setfd -> Cloexec -> Int -> IO Int
nativePipeCycle create read write close fcntl setfd cloexec (I# x) = IO (\s ->
  case pipeCycle create read write close fcntl setfd cloexec x s of (# next, result #) -> (# next, I# result #))
