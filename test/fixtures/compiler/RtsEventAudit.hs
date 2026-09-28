-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}

-- |
-- Module      : RtsEventAudit
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Compiler fixture for rts event audit Core and metadata.
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

type EpollCreate = Int32# -> State# RealWorld -> (# State# RealWorld, Int32# #)
type EpollControl = Int32# -> Int32# -> Int32# -> Addr# -> State# RealWorld -> (# State# RealWorld, Int32# #)
type EpollWait = Int32# -> Addr# -> Int32# -> Int32# -> State# RealWorld -> (# State# RealWorld, Int32# #)
type Poll = Addr# -> Word64# -> Int32# -> State# RealWorld -> (# State# RealWorld, Int32# #)
type ControlFd = Int32# -> State# RealWorld -> (# State# RealWorld #)
type CapabilityFd = Word32# -> Int32# -> State# RealWorld -> (# State# RealWorld #)

{-# INLINE epollCycle #-}
epollCycle :: EpollCreate -> EpollControl -> EpollWait -> EventCreate -> NativeClose
           -> Int# -> State# RealWorld -> (# State# RealWorld, Int# #)
epollCycle create control wait eventfd close x s0 = case newPinnedByteArray# 12# s0 of
  (# s1, image #) -> case create (intToInt32# 1#) s1 of
    (# s2, ep #) -> case eventfd (intToInt32# 1#) (intToInt32# 0#) s2 of
      (# s3, fd #) -> case writeWord32Array# image 0# (wordToWord32# 1##) s3 of
        s4 -> case writeWord64OffAddr# (plusAddr# (mutableByteArrayContents# image) 4#) 0# (wordToWord64# (int2Word# (x +# 31#))) s4 of
          s5 -> case control ep (intToInt32# 1#) fd (mutableByteArrayContents# image) s5 of
            (# s6, added #) -> case wait ep (mutableByteArrayContents# image) (intToInt32# 1#) (intToInt32# 0#) s6 of
              (# s7, ready #) -> case readWord64OffAddr# (plusAddr# (mutableByteArrayContents# image) 4#) 0# s7 of
                (# s8, value #) -> case control ep (intToInt32# 2#) fd nullAddr# s8 of
                  (# s9, deleted #) -> case wait ep (mutableByteArrayContents# image) (intToInt32# 1#) (intToInt32# 0#) s9 of
                    (# s10, empty #) -> case close fd s10 of
                      (# s11, closed #) -> case close ep s11 of
                        (# s12, finished #) -> (# s12, word2Int# (word64ToWord# value) +# int32ToInt# added
                          +# int32ToInt# ready +# int32ToInt# deleted +# int32ToInt# empty
                          +# int32ToInt# closed +# int32ToInt# finished -# 1# #)

originalEpollCycle :: EpollCreate -> EpollControl -> EpollWait -> EventCreate -> NativeClose -> Int# -> Int#
originalEpollCycle create control wait eventfd close x = runRW# (\s ->
  case epollCycle create control wait eventfd close x s of (# _, result #) -> result)
nativeEpollCycle :: EpollCreate -> EpollControl -> EpollWait -> EventCreate -> NativeClose -> Int -> IO Int
nativeEpollCycle create control wait eventfd close (I# x) = IO (\s ->
  case epollCycle create control wait eventfd close x s of (# next, result #) -> (# next, I# result #))

{-# INLINE pollCycle #-}
pollCycle :: Poll -> EventCreate -> NativeClose -> Int# -> State# RealWorld -> (# State# RealWorld, Int# #)
pollCycle poll create close x s0 = case newPinnedByteArray# 8# s0 of
  (# s1, image #) -> case create (intToInt32# 1#) (intToInt32# 0#) s1 of
    (# s2, fd #) -> case writeInt32Array# image 0# fd s2 of
      s3 -> case writeWord16Array# image 2# (wordToWord16# 1##) s3 of
        s4 -> case poll (mutableByteArrayContents# image) (wordToWord64# 1##) (intToInt32# 0#) s4 of
          (# s5, ready #) -> case readWord16Array# image 3# s5 of
            (# s6, events #) -> case poll nullAddr# (wordToWord64# 0##) (intToInt32# 0#) s6 of
              (# s7, empty #) -> case close fd s7 of
                (# s8, closed #) -> (# s8, x +# 17# +# int32ToInt# ready +# word2Int# (word16ToWord# events)
                  +# int32ToInt# empty +# int32ToInt# closed -# 2# #)

originalPollCycle :: Poll -> EventCreate -> NativeClose -> Int# -> Int#
originalPollCycle poll create close x = runRW# (\s -> case pollCycle poll create close x s of (# _, result #) -> result)
nativePollCycle :: Poll -> EventCreate -> NativeClose -> Int -> IO Int
nativePollCycle poll create close (I# x) = IO (\s -> case pollCycle poll create close x s of (# next, result #) -> (# next, I# result #))

{-# INLINE controlCycle #-}
controlCycle :: ControlFd -> CapabilityFd -> ControlFd -> EventCreate -> PipeCreate -> FcntlWrite -> Setfd -> Setfd -> NativeClose
             -> Int# -> State# RealWorld -> (# State# RealWorld, Int# #)
controlCycle wake manager timer eventfd pipe fcntl setfl nonblock close x s0 = case newPinnedByteArray# 8# s0 of
  (# s1, image #) -> case nonblock s1 of
    (# s2, flags #) -> case eventfd (intToInt32# 0#) flags s2 of
      (# s3, ev #) -> case pipe (mutableByteArrayContents# image) s3 of
        (# s4, created #) -> case readInt32Array# image 0# s4 of
          (# s5, rd #) -> case readInt32Array# image 1# s5 of
            (# s6, wr #) -> case setfl s6 of
              (# s7, command #) -> case fcntl wr command (intToInt64# (int32ToInt# flags)) s7 of
                (# s8, changed #) -> case wake ev s8 of
                  (# s9 #) -> case manager (wordToWord32# 0##) wr s9 of
                    (# s10 #) -> case timer wr s10 of
                      (# s11 #) -> case wake (intToInt32# -1#) s11 of
                        (# s12 #) -> case manager (wordToWord32# 0##) (intToInt32# -1#) s12 of
                          (# s13 #) -> case timer (intToInt32# -1#) s13 of
                            (# s14 #) -> case close ev s14 of
                              (# s15, a #) -> case close rd s15 of
                                (# s16, b #) -> case close wr s16 of
                                  (# s17, c #) -> (# s17, x +# int32ToInt# created +# int32ToInt# changed
                                    +# int32ToInt# a +# int32ToInt# b +# int32ToInt# c #)

originalControlCycle :: ControlFd -> CapabilityFd -> ControlFd -> EventCreate -> PipeCreate -> FcntlWrite -> Setfd -> Setfd -> NativeClose -> Int# -> Int#
originalControlCycle wake manager timer eventfd pipe fcntl setfl nonblock close x = runRW# (\s ->
  case controlCycle wake manager timer eventfd pipe fcntl setfl nonblock close x s of (# _, result #) -> result)
nativeControlCycle :: ControlFd -> CapabilityFd -> ControlFd -> EventCreate -> PipeCreate -> FcntlWrite -> Setfd -> Setfd -> NativeClose -> Int -> IO Int
nativeControlCycle wake manager timer eventfd pipe fcntl setfl nonblock close (I# x) = IO (\s ->
  case controlCycle wake manager timer eventfd pipe fcntl setfl nonblock close x s of (# next, result #) -> (# next, I# result #))
