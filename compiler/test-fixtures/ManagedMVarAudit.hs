-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples, UnliftedDatatypes, StandaloneKindSignatures #-}
module ManagedMVarAudit where

import GHC.Exts

data Box = Box Int#

-- Internal host-driven context roots, not the public integer-host entry ABI.
-- Host tests can pass the real managed cell and logical State# token to these
-- genuine GHC bodies without importing a guest fork/catch implementation.
{-# OPAQUE makeBox #-}
makeBox :: Int# -> Box
makeBox value = Box value

{-# OPAQUE waitTake #-}
waitTake :: MVar# RealWorld Box -> State# RealWorld -> Int#
waitTake m state = case takeMVar# m state of
  (# _, Box value #) -> value

{-# OPAQUE waitRead #-}
waitRead :: MVar# RealWorld Box -> State# RealWorld -> Int#
waitRead m state = case readMVar# m state of
  (# _, Box value #) -> value

{-# OPAQUE waitPut #-}
waitPut :: MVar# RealWorld Box -> Int# -> State# RealWorld -> Int#
waitPut m value state = case putMVar# m (Box value) state of
  _ -> value +# 17#

{-# OPAQUE bottom #-}
bottom :: Box
bottom = bottom

-- Failure payloads are deliberately ignored. The success flag alone determines
-- whether a try operation supplied a value; an empty result is not a Box.
{-# OPAQUE transitions #-}
transitions :: Int# -> Int#
transitions raw = runRW# (\s0 ->
  case newMVar# s0 of { (# s1, m #) ->
  case isEmptyMVar# m s1 of { (# s2, e0 #) ->
  case tryTakeMVar# m s2 of { (# s3, t0, _ #) ->
  case tryReadMVar# m s3 of { (# s4, r0, _ #) ->
  case tryPutMVar# m (Box raw) s4 of { (# s5, p0 #) ->
  case isEmptyMVar# m s5 of { (# s6, e1 #) ->
  case tryPutMVar# m bottom s6 of { (# s7, p1 #) ->
  case readMVar# m s7 of { (# s8, old #) ->
  case tryReadMVar# m s8 of { (# s9, r1, snapshot #) ->
  case takeMVar# m s9 of { (# s10, taken #) ->
  case isEmptyMVar# m s10 of { (# s11, e2 #) ->
  case putMVar# m (Box (raw +# 17#)) s11 of { s12 ->
  case tryTakeMVar# m s12 of { (# s13, t1, latest #) ->
  case isEmptyMVar# m s13 of { (# _, e3 #) ->
  case old of { Box a -> case snapshot of { Box b ->
  case taken of { Box c -> case latest of { Box d ->
    a +# b *# 257# +# c *# 65537# +# d *# 16777259#
      +# e0 +# t0 *# 3# +# r0 *# 5# +# p0 *# 7# +# e1 *# 11#
      +# p1 *# 13# +# r1 *# 17# +# e2 *# 19# +# t1 *# 23# +# e3 *# 29#
  } } } } } } } } } } } } } } } } } })

-- Storing, reading, and removing a lifted bottom must not force it. The failed
-- put also must not enter its rejected payload. A later real value still works.
{-# OPAQUE lazyPayload #-}
lazyPayload :: Int# -> Int#
lazyPayload raw = runRW# (\s0 ->
  case newMVar# s0 of { (# s1, m #) ->
  case putMVar# m bottom s1 of { s2 ->
  case readMVar# m s2 of { (# s3, _ #) ->
  case tryReadMVar# m s3 of { (# s4, r1, _ #) ->
  case takeMVar# m s4 of { (# s5, _ #) ->
  case tryTakeMVar# m s5 of { (# s6, t0, _ #) ->
  case tryPutMVar# m bottom s6 of { (# s7, p1 #) ->
  case tryPutMVar# m bottom s7 of { (# s8, p0 #) ->
  case tryTakeMVar# m s8 of { (# s9, t1, _ #) ->
  case isEmptyMVar# m s9 of { (# s10, e1 #) ->
  case putMVar# m (Box raw) s10 of { s11 ->
  case readMVar# m s11 of { (# _, value #) ->
  case value of { Box x ->
    x +# r1 +# t0 *# 3# +# p1 *# 5# +# p0 *# 7# +# t1 *# 11# +# e1 *# 13#
  } } } } } } } } } } } } })

{-# OPAQUE alias #-}
alias :: MVar# s Box -> MVar# s Box
alias m = m

{-# OPAQUE select #-}
select :: Int# -> MVar# s Box -> MVar# s Box -> MVar# s Box
select raw a b = case raw <# 0# of
  1# -> a
  _ -> b

-- Two cells begin with the same payload, then an opaque alias mutates only one.
-- A previously read snapshot remains the old value across take/put replacement.
{-# OPAQUE aliasRoundTrip #-}
aliasRoundTrip :: Int# -> Int#
aliasRoundTrip raw = runRW# (\s0 ->
  case newMVar# s0 of { (# s1, a #) ->
  case newMVar# s1 of { (# s2, b #) ->
  case putMVar# a (Box raw) s2 of { s3 ->
  case putMVar# b (Box raw) s3 of { s4 ->
  case alias (select raw a b) of { selected ->
  case readMVar# selected s4 of { (# s5, old #) ->
  case takeMVar# selected s5 of { (# s6, taken #) ->
  case putMVar# selected (Box (raw +# 1#)) s6 of { s7 ->
  case readMVar# a s7 of { (# s8, left #) ->
  case readMVar# b s8 of { (# _, right #) ->
  case left of { Box l -> case right of { Box r ->
  case old of { Box o -> case taken of { Box t ->
    l *# 257# +# r *# 65537# +# o *# 17# +# t *# 11#
  } } } } } } } } } } } } } })

type Product :: UnliftedType
data Product = Product Int# Box

-- The same full primitive family at boxed-unlifted payload levity. Product's
-- lifted field remains lazy, including after a successful read/take projection.
{-# OPAQUE unliftedPayload #-}
unliftedPayload :: Int# -> Int#
unliftedPayload raw = runRW# (\s0 ->
  case newMVar# s0 of { (# s1, m #) ->
  case isEmptyMVar# m s1 of { (# s2, e0 #) ->
  case tryTakeMVar# m s2 of { (# s3, t0, _ #) ->
  case tryReadMVar# m s3 of { (# s4, r0, _ #) ->
  case tryPutMVar# m (Product raw bottom) s4 of { (# s5, p0 #) ->
  case isEmptyMVar# m s5 of { (# s6, e1 #) ->
  case tryPutMVar# m (Product (raw +# 99#) bottom) s6 of { (# s7, p1 #) ->
  case readMVar# m s7 of { (# s8, old #) ->
  case tryReadMVar# m s8 of { (# s9, r1, snapshot #) ->
  case takeMVar# m s9 of { (# s10, taken #) ->
  case isEmptyMVar# m s10 of { (# s11, e2 #) ->
  case putMVar# m (Product (raw +# 17#) bottom) s11 of { s12 ->
  case tryTakeMVar# m s12 of { (# s13, t1, latest #) ->
  case isEmptyMVar# m s13 of { (# _, e3 #) ->
  case old of { Product a _ -> case snapshot of { Product b _ ->
  case taken of { Product c _ -> case latest of { Product d _ ->
    a +# b *# 257# +# c *# 65537# +# d *# 16777259#
      +# e0 +# t0 *# 3# +# r0 *# 5# +# p0 *# 7# +# e1 *# 11#
      +# p1 *# 13# +# r1 *# 17# +# e2 *# 19# +# t1 *# 23# +# e3 *# 29#
  } } } } } } } } } } } } } } } } } })

{-# OPAQUE closurePayload #-}
closurePayload :: Int# -> Int#
closurePayload raw = runRW# (\s0 ->
  case newMVar# s0 of { (# s1, m #) ->
  case putMVar# m (\x -> x +# raw) s1 of { s2 ->
  case readMVar# m s2 of { (# s3, old #) ->
  case takeMVar# m s3 of { (# s4, _ #) ->
  case putMVar# m (\x -> x *# 3#) s4 of { s5 ->
  case readMVar# m s5 of { (# _, current #) -> old 17# +# current raw
  } } } } } })
