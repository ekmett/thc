-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module HintTraceAudit where
import GHC.Exts

{-# OPAQUE hints #-}
hints :: Int# -> Int#
hints x = runRW# (\s0 -> case newPinnedByteArray# 8# s0 of
  (# s1, array #) -> case writeIntArray# array 0# x s1 of
    s2 -> case prefetchMutableByteArray0# array 0# s2 of
      s3 -> case prefetchMutableByteArray1# array 1# s3 of
        s4 -> case prefetchMutableByteArray2# array 2# s4 of
          s5 -> case prefetchMutableByteArray3# array 3# s5 of
            s6 -> case unsafeFreezeByteArray# array s6 of
              (# s7, frozen #) -> case prefetchByteArray0# frozen 0# s7 of
                s8 -> case prefetchByteArray1# frozen 1# s8 of
                  s9 -> case prefetchByteArray2# frozen 2# s9 of
                    s10 -> case prefetchByteArray3# frozen 3# s10 of
                      s11 -> case byteArrayContents# frozen of
                        address -> case prefetchAddr0# address 0# s11 of
                          s12 -> case prefetchAddr1# address 1# s12 of
                            s13 -> case prefetchAddr2# address 2# s13 of
                              s14 -> case prefetchAddr3# address 3# s14 of
                                s15 -> case prefetchValue0# (raise# (I# 999#) :: Int) s15 of
                                  s16 -> case prefetchValue1# (raise# (I# 999#) :: Int) s16 of
                                    s17 -> case prefetchValue2# (raise# (I# 999#) :: Int) s17 of
                                      s18 -> case prefetchValue3# (raise# (I# 999#) :: Int) s18 of
                                        _ -> indexIntArray# frozen 0# +# 1#)

{-# OPAQUE traces #-}
traces :: Int# -> Int#
traces x = runRW# (\s0 -> case traceEvent# "hint-trace-event"# s0 of
  s1 -> case traceMarker# "hint-trace-marker"# s1 of
    s2 -> case traceBinaryEvent# "A\0B\0"# 4# s2 of _ -> x +# 19#)

{-# OPAQUE event #-}
event :: Addr# -> Int# -> Int#
event address x = runRW# (\s -> case traceEvent# address s of _ -> x)
{-# OPAQUE marker #-}
marker :: Addr# -> Int# -> Int#
marker address x = runRW# (\s -> case traceMarker# address s of _ -> x)
{-# OPAQUE binary #-}
binary :: Addr# -> Int# -> Int#
binary address count = runRW# (\s -> case traceBinaryEvent# address count s of _ -> count)
{-# OPAQUE addressHints #-}
addressHints :: Addr# -> Int# -> Int#
addressHints address offset = runRW# (\s0 -> case prefetchAddr0# address offset s0 of
  s1 -> case prefetchAddr1# address offset s1 of
    s2 -> case prefetchAddr2# address offset s2 of
      s3 -> case prefetchAddr3# address offset s3 of _ -> offset)
