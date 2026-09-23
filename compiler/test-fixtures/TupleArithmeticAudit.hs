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
