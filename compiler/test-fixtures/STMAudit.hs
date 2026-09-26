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

-- Deliberately effectful shared thunks exercise the RTS update-frame rule:
-- atomically restarts, while an enclosing thunk's prefix must not repeat.
data AsyncCells = AsyncCells (MVar# RealWorld Box) (MVar# RealWorld Box)
  (TVar# RealWorld Box) (TVar# RealWorld Box) (MutVar# RealWorld Box)

{-# OPAQUE asyncCells #-}
asyncCells :: AsyncCells
asyncCells = case newMVar# realWorld# of { (# s1, ready #) ->
  case newMVar# s1 of { (# s2, gate #) ->
  case newTVar# (Box 0#) s2 of { (# s3, condition #) ->
  case newTVar# (Box 0#) s3 of { (# s4, value #) ->
  case newMutVar# (Box 0#) s4 of { (# _, counter #) ->
    AsyncCells ready gate condition value counter } } } } }

{-# OPAQUE asyncPayload #-}
asyncPayload :: Box
asyncPayload = Box 7#

data Target = Target ThreadId#
data TargetCell = TargetCell (MutVar# RealWorld Target)
{-# OPAQUE targetCell #-}
targetCell :: TargetCell
targetCell = case newMutVar# (raise# asyncPayload :: Target) realWorld# of
  (# _, cell #) -> TargetCell cell
{-# OPAQUE registerTarget #-}
registerTarget :: State# RealWorld -> State# RealWorld
registerTarget s = case targetCell of { TargetCell cell ->
  case myThreadId# s of (# t, thread #) -> writeMutVar# cell (Target thread) t }

{-# OPAQUE sharedRetry #-}
sharedRetry :: Box
sharedRetry = case asyncCells of { AsyncCells ready _ condition value counter ->
  case readMutVar# counter realWorld# of { (# s1, Box old #) ->
  case writeMutVar# counter (Box (old +# 1#)) s1 of { s2 ->
  case atomically# (\s3 -> catchRetry#
    (\s4 -> catchSTM# (\s5 -> case writeTVar# value (Box 99#) s5 of { s6 ->
      case readTVar# condition s6 of { (# s7, Box n #) ->
      case tryPutMVar# ready (Box n) s7 of { (# s8, _ #) ->
      case n ==# 0# of
        1# -> retry# s8
        _ -> case writeTVar# value (Box (n +# 1#)) s8 of s9 -> (# s9, Box (n +# 1#) #) } } })
      (\_ s5 -> case writeTVar# value (Box 600#) s5 of s6 -> (# s6, Box 600# #)) s4)
    (\s4 -> retry# s4) s3) s2 of { (# _, result #) -> result } } } }

-- The inner update frame is saved before atomically is crossed. Its suffix
-- must only run after a new transaction is established, never as the yielded
-- child of the outer restart. This intentionally uses unsafe IO-in-STM style
-- effects; their replay/rollback guarantees are no stronger than native GHC.
{-# OPAQUE innerTransactionThunk #-}
innerTransactionThunk :: Box
innerTransactionThunk = case asyncCells of { AsyncCells ready gate condition _ _ ->
  case readTVar# condition realWorld# of { (# s1, Box before #) ->
  case putMVar# ready (Box before) s1 of { s2 ->
  case takeMVar# gate s2 of { (# s3, _ #) ->
  case readTVar# condition s3 of { (# _, Box after #) -> Box (after +# 2#) } } } } }

{-# OPAQUE sharedInner #-}
sharedInner :: Box
sharedInner = case asyncCells of { AsyncCells _ _ _ value counter ->
  case readMutVar# counter realWorld# of { (# s1, Box old #) ->
  case writeMutVar# counter (Box (old +# 1#)) s1 of { s2 ->
  case atomically# (\s3 -> catchRetry#
    (\s4 -> catchSTM# (\s5 -> case writeTVar# value (Box 88#) s5 of { s6 ->
      case innerTransactionThunk of { Box n ->
      case writeTVar# value (Box n) s6 of s7 -> (# s7, Box n #) } })
      (\_ s5 -> (# s5, Box 600# #)) s4)
    (\s4 -> (# s4, Box 700# #)) s3) s2 of { (# _, result #) -> result } } } }

{-# OPAQUE forceRetry #-}
forceRetry :: Int# -> Int#
forceRetry token = case catch# (\s -> case registerTarget s of t -> case sharedRetry of Box n -> (# t, Box n #))
  (\_ s -> (# s, Box (-1#) #)) realWorld# of (# _, Box n #) -> n +# token
{-# OPAQUE forceInner #-}
forceInner :: Int# -> Int#
forceInner token = case catch# (\s -> case registerTarget s of t -> case sharedInner of Box n -> (# t, Box n #))
  (\_ s -> (# s, Box (-1#) #)) realWorld# of (# _, Box n #) -> n +# token
{-# OPAQUE asyncReady #-}
asyncReady :: Int# -> Int#
asyncReady token = case asyncCells of { AsyncCells ready _ _ _ _ ->
  case takeMVar# ready realWorld# of (# _, Box n #) -> n +# token }
{-# OPAQUE asyncRelease #-}
asyncRelease :: Int# -> Int#
asyncRelease token = case asyncCells of { AsyncCells _ gate _ _ _ ->
  case putMVar# gate (Box 1#) realWorld# of _ -> token }
{-# OPAQUE asyncSet #-}
asyncSet :: Int# -> Int#
asyncSet n = case asyncCells of { AsyncCells _ _ condition _ _ ->
  case atomically# (\s -> case writeTVar# condition (Box n) s of t -> (# t, Box n #)) realWorld# of
    (# _, Box result #) -> result }
{-# OPAQUE asyncValue #-}
asyncValue :: Int# -> Int#
asyncValue token = case asyncCells of { AsyncCells _ _ _ value _ ->
  case readTVarIO# value realWorld# of (# _, Box n #) -> n +# token }
{-# OPAQUE asyncPrefixes #-}
asyncPrefixes :: Int# -> Int#
asyncPrefixes token = case asyncCells of { AsyncCells _ _ _ _ counter ->
  case readMutVar# counter realWorld# of (# _, Box n #) -> n +# token }

-- One public loader entry retains one set of CAFs for all host threads.
{-# OPAQUE asyncEntry #-}
asyncEntry :: Int# -> Int# -> Int#
asyncEntry operation token = case operation of
  0# -> forceRetry token
  1# -> forceInner token
  2# -> asyncReady token
  3# -> asyncRelease token
  4# -> asyncSet token
  5# -> asyncValue token
  6# -> asyncPrefixes token
  _ -> case targetCell of { TargetCell cell ->
    case readMutVar# cell realWorld# of { (# s, Target thread #) ->
      case killThread# thread asyncPayload s of _ -> token } }
