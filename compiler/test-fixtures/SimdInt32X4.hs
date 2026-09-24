-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module SimdInt32X4 where
import GHC.Exts

-- OPAQUE retains the scalar entry; vectors stay local to this first slice.
{-# OPAQUE vectorCase #-}
vectorCase :: Int# -> Int# -> Int# -> Int# -> Int#
vectorCase a b c d =
  case packInt32X4# (# intToInt32# a, intToInt32# b, intToInt32# c, intToInt32# d #) of
    x -> case broadcastInt32X4# (intToInt32# (a +# 91#)) of
      y -> case plusInt32X4# x y of
        z -> case unpackInt32X4# z of
          (# p, q, r, s #) -> (int32ToInt# p *# 7#) `xorI#` (int32ToInt# q *# 11#) `xorI#` (int32ToInt# r *# 13#) `xorI#` (int32ToInt# s *# 17#)

{-# OPAQUE subtractCase #-}
subtractCase :: Int# -> Int# -> Int# -> Int# -> Int#
subtractCase a b c d =
  case packInt32X4# (# intToInt32# a, intToInt32# b, intToInt32# c, intToInt32# d #) of
    x -> case minusInt32X4# x (negateInt32X4# (broadcastInt32X4# (intToInt32# (b -# 19#)))) of
      z -> case unpackInt32X4# z of
        (# p, q, r, s #) -> (int32ToInt# p *# 13#) `xorI#` (int32ToInt# q *# 17#) `xorI#` (int32ToInt# r *# 19#) `xorI#` (int32ToInt# s *# 23#)

{-# OPAQUE branchCase #-}
branchCase :: Int# -> Int# -> Int# -> Int# -> Int#
branchCase a b c d =
  case packInt32X4# (# intToInt32# a, intToInt32# b, intToInt32# c, intToInt32# d #) of
    x -> case (case a ># b of 0# -> negateInt32X4# x; _ -> x) of
      z -> case unpackInt32X4# (plusInt32X4# z x) of
        (# p, q, r, s #) -> (int32ToInt# p *# 23#) `xorI#` (int32ToInt# q *# 31#) `xorI#` (int32ToInt# r *# 37#) `xorI#` (int32ToInt# s *# 41#)
