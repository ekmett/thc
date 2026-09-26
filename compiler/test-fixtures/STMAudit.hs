-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples, UnliftedDatatypes, StandaloneKindSignatures #-}
module STMAudit where
import GHC.Exts

data Box = Box Int#
type UBox :: UnliftedType
data UBox = UBox Int#

{-# OPAQUE basic #-}
basic :: Int# -> Int#
basic x = runRW# (\s0 -> case newTVar# (Box x) s0 of
  (# s1, v #) -> case atomically# (\s2 -> case readTVar# v s2 of
    (# s3, Box old #) -> case writeTVar# v (Box (old +# 3#)) s3 of
      s4 -> case readTVarIO# v s4 of
        (# s5, Box committed #) -> case readTVar# v s5 of
          (# s6, Box buffered #) -> (# s6, Box (committed *# 17# +# buffered) #)) s1 of
    (# s7, Box result #) -> case readTVarIO# v s7 of
      (# _, Box final #) -> result *# 31# +# final)

{-# OPAQUE rollback #-}
rollback :: Int# -> Int#
rollback x = runRW# (\s0 -> case newTVar# (Box x) s0 of
  (# s1, v #) -> case atomically# (\s2 -> case writeTVar# v (Box (x +# 1#)) s2 of
    s3 -> catchSTM# (\s4 -> case writeTVar# v (Box 999#) s4 of
      s5 -> raiseIO# (Box (x +# 2#)) s5)
      (\(Box e) s4 -> case readTVar# v s4 of
        (# s5, Box old #) -> case writeTVar# v (Box (old +# e)) s5 of
          s6 -> (# s6, Box (old *# 17# +# e) #)) s3) s1 of
    (# s7, Box result #) -> case readTVarIO# v s7 of
      (# _, Box final #) -> result *# 31# +# final)

{-# OPAQUE alternative #-}
alternative :: Int# -> Int#
alternative x = runRW# (\s0 -> case newTVar# (Box x) s0 of
  (# s1, v #) -> case atomically# (\s2 -> catchRetry#
    (\s3 -> case writeTVar# v (Box 999#) s3 of s4 -> retry# s4)
    (\s3 -> case readTVar# v s3 of
      (# s4, Box old #) -> case writeTVar# v (Box (old +# 7#)) s4 of
        s5 -> (# s5, Box old #)) s2) s1 of
    (# s6, Box result #) -> case readTVarIO# v s6 of
      (# _, Box final #) -> result *# 31# +# final)

{-# OPAQUE lazyPayload #-}
lazyPayload :: Int# -> Int#
lazyPayload x = runRW# (\s0 -> case newTVar# (raise# (Box 666#) :: Box) s0 of
  (# s1, v #) -> case atomically# (\s2 -> case readTVar# v s2 of
    (# s3, _ #) -> case writeTVar# v (Box (x +# 42#)) s3 of
      s4 -> (# s4, raise# (Box 777#) :: Box #)) s1 of
    (# s5, _ #) -> case readTVarIO# v s5 of (# _, Box final #) -> final)

{-# OPAQUE nestedAtomic #-}
nestedAtomic :: Int# -> Int#
nestedAtomic x = runRW# (\s0 -> case atomically# (\s1 -> catchSTM#
  (\s2 -> atomically# (\s3 -> (# s3, Box 999# #)) s2)
  (\_ s2 -> (# s2, Box (x +# 31#) #)) s1) s0 of (# _, Box result #) -> result)

{-# OPAQUE unliftedPayload #-}
unliftedPayload :: Int# -> Int#
unliftedPayload x = runRW# (\s0 -> case newTVar# (UBox x) s0 of
  (# s1, v #) -> case atomically# (\s2 -> case readTVar# v s2 of
    (# s3, UBox old #) -> case writeTVar# v (UBox (old +# 9#)) s3 of
      s4 -> readTVar# v s4) s1 of (# _, UBox result #) -> result)

{-# OPAQUE newCell #-}
newCell :: Int# -> TVar# RealWorld Box
newCell x = runRW# (\s -> case newTVar# (Box x) s of (# _, v #) -> v)
{-# OPAQUE readCell #-}
readCell :: TVar# RealWorld Box -> Int#
readCell v = runRW# (\s -> case readTVarIO# v s of (# _, Box x #) -> x)
{-# OPAQUE bumpCell #-}
bumpCell :: TVar# RealWorld Box -> Int# -> Int#
bumpCell v n = runRW# (\s0 -> case atomically# (bumpAction v n) s0 of (# _, Box result #) -> result)
{-# OPAQUE bumpAction #-}
bumpAction :: TVar# RealWorld Box -> Int# -> State# RealWorld -> (# State# RealWorld, Box #)
bumpAction v n s1 = case readTVar# v s1 of
  (# s2, Box x #) -> case writeTVar# v (Box (x +# n)) s2 of
    s3 -> (# s3, Box (x +# n) #)
{-# OPAQUE awaitCell #-}
awaitCell :: TVar# RealWorld Box -> Int# -> Int#
awaitCell v threshold = runRW# (\s0 -> case atomically# (\s1 -> case readTVar# v s1 of
  (# s2, Box x #) -> case x <# threshold of
    1# -> retry# s2
    _ -> (# s2, Box x #)) s0 of (# _, Box result #) -> result)
{-# OPAQUE awaitEither #-}
awaitEither :: TVar# RealWorld Box -> TVar# RealWorld Box -> Int#
awaitEither a b = runRW# (\s0 -> case atomically# (\s1 -> catchRetry#
  (\s2 -> case readTVar# a s2 of
    (# s3, Box x #) -> case x ==# 0# of
      1# -> retry# s3
      _ -> (# s3, Box (x *# 17#) #))
  (\s2 -> case readTVar# b s2 of
    (# s3, Box y #) -> case y ==# 0# of
      1# -> retry# s3
      _ -> (# s3, Box y #)) s1) s0 of (# _, Box result #) -> result)
