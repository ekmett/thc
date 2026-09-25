-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples, UnliftedDatatypes, StandaloneKindSignatures #-}
module MutVarAudit where

import Control.Monad.ST (ST, runST)
import Data.STRef
import Data.IORef
import GHC.Exts
import System.IO.Unsafe (unsafePerformIO)

data Box = Box Int

{-# OPAQUE bottom #-}
bottom :: Box
bottom = bottom

{-# OPAQUE bump #-}
bump :: STRef s Int -> Int -> ST s ()
bump reference delta = modifySTRef reference (+ delta)

-- Public ST/STRef APIs, two independent references, read snapshots, repeated
-- writes, and a captured reference accessed by a local action.
{-# OPAQUE stRef #-}
stRef :: Int# -> Int#
stRef raw = case runST (do
    a <- newSTRef (I# raw)
    b <- newSTRef (I# raw + 71)
    old <- readSTRef a
    bump a 17
    first <- readSTRef a
    writeSTRef a (first * 3)
    lastValue <- readSTRef a
    other <- readSTRef b
    pure (old + first * 257 + lastValue * 65537 + other * 16777259)) of I# value -> value

-- Both storing and reading bottom are lazy. Overwriting it must not enter it;
-- the strict modification is applied only after the terminating write.
{-# OPAQUE lazyRef #-}
lazyRef :: Int# -> Int#
lazyRef raw = case runST (do
    r <- newSTRef bottom
    ignored <- readSTRef r
    writeSTRef r (Box (I# raw))
    modifySTRef r (\(Box n) -> Box (n + 5))
    Box value <- readSTRef r
    pure value) of I# value -> value

-- As in GHC.Internal.IO.Encoding.mkGlobal, unsafePerformIO returns a lazy
-- pair of IO actions. The retained lazy identity must not erase the exact
-- MutVar#/State# evidence inside either action or force the stored bottom.
{-# OPAQUE freshActions #-}
freshActions :: a -> (IO a, a -> IO ())
freshActions initial = unsafePerformIO $ do
    reference <- newIORef initial
    pure (readIORef reference, writeIORef reference)

{-# OPAQUE lazyIORef #-}
lazyIORef :: Int# -> Int#
lazyIORef raw = case freshActions bottom of
    (readAction, writeAction) -> case unsafePerformIO (do
        writeAction (Box (I# raw))
        Box value <- readAction
        pure (value + 17)) of I# value -> value

-- An already-read closure retains its value after overwriting the cell.
{-# OPAQUE closureRef #-}
closureRef :: Int# -> Int#
closureRef raw = case runST (do
    r <- newSTRef (\x -> x + I# raw)
    old <- readSTRef r
    writeSTRef r (\x -> x * 3)
    current <- readSTRef r
    pure (old 11 + current (I# raw))) of I# value -> value

-- The primitive fixture makes the State sequencing and all three operations
-- explicit even if GHC optimizes a public API binding away.
{-# OPAQUE orderedRef #-}
orderedRef :: Int# -> Int#
orderedRef raw = runRW# (\s0 ->
  case newMutVar# (I# raw) s0 of { (# s1, a #) ->
  case newMutVar# (I# (raw +# 71#)) s1 of { (# s2, b #) ->
  case readMutVar# a s2 of { (# s3, old #) ->
  case writeMutVar# a (I# (raw +# 17#)) s3 of { s4 ->
  case readMutVar# a s4 of { (# s5, first #) ->
  case writeMutVar# a (I# (raw *# 3#)) s5 of { s6 ->
  case readMutVar# a s6 of { (# s7, lastValue #) ->
  case readMutVar# b s7 of { (# _, other #) ->
  case old of { I# o -> case first of { I# f -> case lastValue of { I# l -> case other of { I# t ->
    o +# f *# 257# +# l *# 65537# +# t *# 16777259#
  } } } } } } } } } } } })

-- The returned fields are the values displaced by each indivisible exchange.
{-# OPAQUE swapRef #-}
swapRef :: Int# -> Int#
swapRef raw = runRW# (\s0 ->
  case newMutVar# (I# raw) s0 of { (# s1, cell #) ->
  case atomicSwapMutVar# cell (I# (raw +# 17#)) s1 of { (# s2, old #) ->
  case atomicSwapMutVar# cell (I# (raw *# 3#)) s2 of { (# s3, middle #) ->
  case readMutVar# cell s3 of { (# _, current #) ->
  case old of { I# a -> case middle of { I# b -> case current of { I# c ->
    a +# b *# 257# +# c *# 65537#
  } } } } } } })

-- The overwritten bottom must not be forced; the second return is still the
-- first replacement payload.
{-# OPAQUE lazySwapRef #-}
lazySwapRef :: Int# -> Int#
lazySwapRef raw = runRW# (\s0 ->
  case newMutVar# bottom s0 of { (# s1, cell #) ->
  case atomicSwapMutVar# cell (Box (I# raw)) s1 of { (# s2, _ #) ->
  case atomicSwapMutVar# cell (Box (I# (raw +# 7#))) s2 of { (# s3, previous #) ->
  case readMutVar# cell s3 of { (# _, current #) ->
  case previous of { Box (I# a) -> case current of { Box (I# b) ->
    a *# 257# +# b
  } } } } } })

-- An unlifted boxed payload still contains a lazy lifted field.
type Product :: UnliftedType
data Product = Product Int# Box

{-# OPAQUE unliftedRef #-}
unliftedRef :: Int# -> Int#
unliftedRef raw = runRW# (\s0 ->
  case newMutVar# (Product raw bottom) s0 of { (# s1, r #) ->
  case readMutVar# r s1 of { (# s2, old #) ->
  case writeMutVar# r (Product (raw +# 1#) bottom) s2 of { s3 ->
  case readMutVar# r s3 of { (# _, current #) ->
  case old of { Product a _ -> case current of { Product b _ -> a *# 257# +# b }
  } } } } })

{-# OPAQUE stLoop #-}
stLoop :: Int# -> Int#
stLoop raw = case runST (do
    r <- newSTRef (I# raw)
    let go 0 = readSTRef r
        go n = modifySTRef' r (\x -> x * 3 + n) >> go (n - 1)
    go (abs (I# raw `rem` 33))) of I# value -> value

-- Use the public Eq instance, which compares the underlying MutVar# identity.
-- Opaque calls keep both the public wrapper and its pointer comparison reachable.
{-# OPAQUE sameRef #-}
sameRef :: STRef s a -> STRef s a -> Int
sameRef left right = if left == right then 1 else 0

{-# OPAQUE aliasRef #-}
aliasRef :: STRef s a -> STRef s a
aliasRef reference = reference

{-# OPAQUE selectRef #-}
selectRef :: Int# -> STRef s a -> STRef s a -> STRef s a
selectRef raw left right = if isTrue# (raw <# 0#) then left else right

-- Keep the post-write comparison inside an opaque ST action so GHC cannot
-- replace it with the caller's pre-write score by common-subexpression sharing.
{-# OPAQUE writeAndScore #-}
writeAndScore :: STRef s a -> a -> STRef s a -> STRef s a -> ST s Int
writeAndScore selected value left right = do
    writeSTRef selected value
    pure (sameRef left (aliasRef left) + 2 * sameRef left right + 4 * sameRef left selected)

-- Distinct cells start with the same payload. Writes through the selected alias
-- change only that cell's contents, while all identity answers remain unchanged.
{-# OPAQUE stRefEquality #-}
stRefEquality :: Int# -> Int#
stRefEquality raw = case runST (do
    let payload = I# raw
    a <- newSTRef payload
    b <- newSTRef payload
    let alias = aliasRef a
        selected = selectRef raw a b
    case sameRef a alias + 2 * sameRef a b + 4 * sameRef a selected of
      I# before -> do
        after <- writeAndScore selected (payload + 17) a b
        left <- readSTRef a
        right <- readSTRef b
        pure (left * 257 + right * 65537 + I# before + 16 * after)) of I# value -> value

-- Equality must not read/force a cell's payload. Box has no Eq instance, and
-- both cells contain the same bottom before and after the ordered writes.
{-# OPAQUE lazyRefEquality #-}
lazyRefEquality :: Int# -> Int#
lazyRefEquality raw = case runST (do
    a <- newSTRef bottom
    b <- newSTRef bottom
    let alias = aliasRef a
        selected = selectRef raw a b
    case sameRef a alias + 2 * sameRef a b + 4 * sameRef a selected of
      I# before -> do
        writeSTRef a bottom
        after <- writeAndScore selected bottom a b
        pure (I# raw + I# before + 16 * after)) of I# value -> value
