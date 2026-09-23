{-# LANGUAGE MagicHash, UnboxedTuples, UnliftedDatatypes, StandaloneKindSignatures #-}
module MutVarAudit where

import Control.Monad.ST (ST, runST)
import Data.STRef
import GHC.Exts

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
