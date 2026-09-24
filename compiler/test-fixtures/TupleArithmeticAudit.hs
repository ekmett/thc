-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module TupleArithmeticAudit where
import GHC.Exts

-- The selector retains both result fields in one genuine saturated primop body.
{-# OPAQUE quotRemInt #-}
quotRemInt :: Int# -> Int# -> Int# -> Int#
quotRemInt x y field = case quotRemInt# x y of
  (# q, r #) -> case field of 0# -> q; _ -> r

{-# OPAQUE quotRemWord #-}
quotRemWord :: Int# -> Int# -> Int# -> Int#
quotRemWord x y field = case quotRemWord# (int2Word# x) (int2Word# y) of
  (# q, r #) -> case field of 0# -> word2Int# q; _ -> word2Int# r

{-# OPAQUE addIntC #-}
addIntC :: Int# -> Int# -> Int# -> Int#
addIntC x y field = case addIntC# x y of
  (# result, overflow #) -> case field of 0# -> result; _ -> overflow

{-# OPAQUE subIntC #-}
subIntC :: Int# -> Int# -> Int# -> Int#
subIntC x y field = case subIntC# x y of
  (# result, overflow #) -> case field of 0# -> result; _ -> overflow

{-# OPAQUE plusWord2 #-}
plusWord2 :: Int# -> Int# -> Int# -> Int#
plusWord2 x y field = case plusWord2# (int2Word# x) (int2Word# y) of
  (# high, low #) -> case field of 0# -> word2Int# high; _ -> word2Int# low

{-# OPAQUE timesWord2 #-}
timesWord2 :: Int# -> Int# -> Int# -> Int#
timesWord2 x y field = case timesWord2# (int2Word# x) (int2Word# y) of
  (# high, low #) -> case field of 0# -> word2Int# high; _ -> word2Int# low

{-# OPAQUE addWordC #-}
addWordC :: Int# -> Int# -> Int# -> Int#
addWordC x y field = case addWordC# (int2Word# x) (int2Word# y) of
  (# result, carry #) -> case field of 0# -> word2Int# result; _ -> carry

{-# OPAQUE subWordC #-}
subWordC :: Int# -> Int# -> Int# -> Int#
subWordC x y field = case subWordC# (int2Word# x) (int2Word# y) of
  (# result, borrow #) -> case field of 0# -> word2Int# result; _ -> borrow

-- Preserve genuine mixed WordRep/IntRep return boundaries as well as direct writers.
-- The reversible value adjustment prevents eta reduction to a first-class primop.
{-# OPAQUE addWordResult #-}
addWordResult :: Word# -> Word# -> (# Word#, Int# #)
addWordResult x y = case addWordC# x y of
  (# value, flag #) -> (# plusWord# value 1##, flag #)
{-# OPAQUE subWordResult #-}
subWordResult :: Word# -> Word# -> (# Word#, Int# #)
subWordResult x y = case subWordC# x y of
  (# value, flag #) -> (# plusWord# value 1##, flag #)
{-# OPAQUE addWordCall #-}
addWordCall :: Int# -> Int# -> Int# -> Int#
addWordCall x y field = case addWordResult (int2Word# x) (int2Word# y) of
  (# result, flag #) -> case field of 0# -> word2Int# (minusWord# result 1##); _ -> flag
{-# OPAQUE subWordCall #-}
subWordCall :: Int# -> Int# -> Int# -> Int#
subWordCall x y field = case subWordResult (int2Word# x) (int2Word# y) of
  (# result, flag #) -> case field of 0# -> word2Int# (minusWord# result 1##); _ -> flag
