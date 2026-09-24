-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module SimdInt64X2 where
import GHC.Exts

-- OPAQUE retains the scalar entry; vectors stay local to this first slice.
{-# OPAQUE vectorCase #-}
vectorCase :: Int# -> Int# -> Int#
vectorCase a b =
  case packInt64X2# (# intToInt64# a, intToInt64# b #) of
    x -> case broadcastInt64X2# (intToInt64# (a +# 91#)) of
      y -> case plusInt64X2# x y of
        z -> case unpackInt64X2# z of
          (# p, q #) -> (int64ToInt# p *# 7#) `xorI#` (int64ToInt# q *# 11#)

{-# OPAQUE subtractCase #-}
subtractCase :: Int# -> Int# -> Int#
subtractCase a b =
  case packInt64X2# (# intToInt64# a, intToInt64# b #) of
    x -> case minusInt64X2# x (negateInt64X2# (broadcastInt64X2# (intToInt64# (b -# 19#)))) of
      z -> case unpackInt64X2# z of
        (# p, q #) -> (int64ToInt# p *# 13#) `xorI#` (int64ToInt# q *# 17#)

{-# OPAQUE branchCase #-}
branchCase :: Int# -> Int# -> Int#
branchCase a b =
  case packInt64X2# (# intToInt64# a, intToInt64# b #) of
    x -> case (case a ># b of 0# -> negateInt64X2# x; _ -> x) of
      z -> case unpackInt64X2# (plusInt64X2# z x) of
        (# p, q #) -> (int64ToInt# p *# 23#) `xorI#` (int64ToInt# q *# 31#)
