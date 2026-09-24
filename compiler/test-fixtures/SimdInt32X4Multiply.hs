-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module SimdInt32X4Multiply where

import GHC.Exts

-- Vectors stay local. Signed products keep the low 32 bits and sign-extend
-- before the distinct-weight checksum, safely within the required 64-bit Int.
-- The third left lane folds a genuine Int32# literal 1 into exported Core.

timesCase :: Int# -> Int# -> Int#
timesCase a b =
  case packInt32X4# (# intToInt32# a, intToInt32# b, plusInt32# (intToInt32# a) (intToInt32# 1#), intToInt32# (b -# 1#) #) of
    x -> case packInt32X4# (# intToInt32# (b +# 2#), intToInt32# (a -# 3#), intToInt32# (b *# 7# +# 13#), intToInt32# (a *# 11# -# 17#) #) of
      y -> case unpackInt32X4# (timesInt32X4# x y) of
        (# p0, p1, p2, p3 #) -> (int32ToInt# p0 *# 3#) +# (int32ToInt# p1 *# 5#) +# (int32ToInt# p2 *# 7#) +# (int32ToInt# p3 *# 11#)

-- Exactly the four lane selectors 0..3 belong to the declared input domain.
laneCase :: Int# -> Int# -> Int# -> Int#
laneCase lane a b =
  case packInt32X4# (# intToInt32# a, intToInt32# b, plusInt32# (intToInt32# a) (intToInt32# 1#), intToInt32# (b -# 1#) #) of
    x -> case packInt32X4# (# intToInt32# (b +# 2#), intToInt32# (a -# 3#), intToInt32# (b *# 7# +# 13#), intToInt32# (a *# 11# -# 17#) #) of
      y -> case unpackInt32X4# (timesInt32X4# x y) of
        (# p0, p1, p2, p3 #) -> case lane of { 0# -> int32ToInt# p0; 1# -> int32ToInt# p1; 2# -> int32ToInt# p2; _ -> int32ToInt# p3 }

{-# OPAQUE scalarWorker #-}
scalarWorker :: Int# -> Int# -> Int#
scalarWorker a b =
  case packInt32X4# (# intToInt32# a, intToInt32# b, plusInt32# (intToInt32# a) (intToInt32# 1#), intToInt32# (b -# 1#) #) of
    x -> case packInt32X4# (# intToInt32# (b +# 2#), intToInt32# (a -# 3#), intToInt32# (b *# 7# +# 13#), intToInt32# (a *# 11# -# 17#) #) of
      y -> case unpackInt32X4# (timesInt32X4# x y) of
        (# p0, p1, p2, p3 #) -> (int32ToInt# p0 *# 3#) +# (int32ToInt# p1 *# 5#) +# (int32ToInt# p2 *# 7#) +# (int32ToInt# p3 *# 11#) +# 31#

scalarHelperCase :: Int# -> Int# -> Int#
scalarHelperCase a b = case scalarWorker a b of result -> result +# 17#

{-# OPAQUE tupleWorker #-}
tupleWorker :: Int# -> Int# -> (# Int32#, Int32#, Int32#, Int32# #)
tupleWorker a b =
  case packInt32X4# (# intToInt32# a, intToInt32# b, plusInt32# (intToInt32# a) (intToInt32# 1#), intToInt32# (b -# 1#) #) of
    x -> case packInt32X4# (# intToInt32# (b +# 2#), intToInt32# (a -# 3#), intToInt32# (b *# 7# +# 13#), intToInt32# (a *# 11# -# 17#) #) of
      y -> unpackInt32X4# (timesInt32X4# x y)

tupleHelperCase :: Int# -> Int# -> Int#
tupleHelperCase a b = case tupleWorker a b of
  (# p0, p1, p2, p3 #) -> (int32ToInt# p0 *# 3#) +# (int32ToInt# p1 *# 5#) +# (int32ToInt# p2 *# 7#) +# (int32ToInt# p3 *# 11#)

-- Explicit negative control: vector formals remain unsupported.
{-# OPAQUE vectorArgument #-}
vectorArgument :: Int32X4# -> Int#
vectorArgument value = case unpackInt32X4# value of
  (# p0, p1, p2, p3 #) -> (int32ToInt# p0 *# 3#) +# (int32ToInt# p1 *# 5#) +# (int32ToInt# p2 *# 7#) +# (int32ToInt# p3 *# 11#)
