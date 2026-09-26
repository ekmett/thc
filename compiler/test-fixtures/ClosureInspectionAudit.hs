-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module ClosureInspectionAudit where
import GHC.Exts
import GHC.Prim (closureSize#, unpackClosure#, getApStackVal#, getCCSOf#, clearCCS#, whereFrom#)

data Box = Box Int#
data Pair = Pair Int Int

{-# OPAQUE payload #-}
payload :: Int# -> Int#
payload x = case unpackClosure# (Box x) of
  (# _, bytes, _ #) -> indexIntArray# bytes 1#

{-# OPAQUE sizeConsistent #-}
sizeConsistent :: Int# -> Int#
sizeConsistent x = let box = Box x in case unpackClosure# box of
  (# _, bytes, _ #) -> closureSize# box -# quotInt# (sizeofByteArray# bytes) 8#

{-# OPAQUE pointerCount #-}
pointerCount :: Int# -> Int#
pointerCount x = case unpackClosure# (Pair (I# x) (raise# (I# 91#))) of
  (# _, _, pointers #) -> sizeofArray# pointers

{-# OPAQUE notStack #-}
notStack :: Int# -> Int#
notStack x = case getApStackVal# (raise# (I# 92#) :: Int) x of
  (# flag, _ #) -> flag

{-# OPAQUE noCCS #-}
noCCS :: Int# -> Int#
noCCS x = runRW# (\s -> case getCCSOf# (raise# (I# x) :: Int) s of
  (# _, address #) -> eqAddr# address nullAddr#)

{-# OPAQUE noProvenance #-}
noProvenance :: Int# -> Int#
noProvenance x = runRW# (\s0 -> case newPinnedByteArray# 128# s0 of
  (# s1, bytes #) -> case writeIntArray# bytes 0# 123# s1 of
    s2 -> case whereFrom# (Box x) (mutableByteArrayContents# bytes) s2 of
      (# s3, found #) -> case readIntArray# bytes 0# s3 of
        (# _, sentinel #) -> found +# sentinel)

{-# OPAQUE cleared #-}
cleared :: Int# -> Int#
cleared x = runRW# (\s -> case clearCCS# (\t -> (# t, I# (x +# 1#) #)) s of
  (# _, I# result #) -> result)
