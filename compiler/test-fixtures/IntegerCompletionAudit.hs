-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module IntegerCompletionAudit where
import GHC.Exts

-- A dynamic field selector retains both components in genuine saturated Core.
{-# OPAQUE quotRemInt8 #-}
quotRemInt8 :: Int# -> Int# -> Int# -> Int# -> Int#
quotRemInt8 x y _ field = case quotRemInt8# (intToInt8# x) (intToInt8# y) of
  (# q, r #) -> case field of 0# -> int8ToInt# q; _ -> int8ToInt# r

{-# OPAQUE quotRemInt16 #-}
quotRemInt16 :: Int# -> Int# -> Int# -> Int# -> Int#
quotRemInt16 x y _ field = case quotRemInt16# (intToInt16# x) (intToInt16# y) of
  (# q, r #) -> case field of 0# -> int16ToInt# q; _ -> int16ToInt# r

{-# OPAQUE quotRemInt32 #-}
quotRemInt32 :: Int# -> Int# -> Int# -> Int# -> Int#
quotRemInt32 x y _ field = case quotRemInt32# (intToInt32# x) (intToInt32# y) of
  (# q, r #) -> case field of 0# -> int32ToInt# q; _ -> int32ToInt# r

{-# OPAQUE quotRemWord8 #-}
quotRemWord8 :: Int# -> Int# -> Int# -> Int# -> Int#
quotRemWord8 x y _ field = case quotRemWord8# (wordToWord8# (int2Word# x)) (wordToWord8# (int2Word# y)) of
  (# q, r #) -> case field of 0# -> word2Int# (word8ToWord# q); _ -> word2Int# (word8ToWord# r)

{-# OPAQUE quotRemWord16 #-}
quotRemWord16 :: Int# -> Int# -> Int# -> Int# -> Int#
quotRemWord16 x y _ field = case quotRemWord16# (wordToWord16# (int2Word# x)) (wordToWord16# (int2Word# y)) of
  (# q, r #) -> case field of 0# -> word2Int# (word16ToWord# q); _ -> word2Int# (word16ToWord# r)

{-# OPAQUE quotRemWord32 #-}
quotRemWord32 :: Int# -> Int# -> Int# -> Int# -> Int#
quotRemWord32 x y _ field = case quotRemWord32# (wordToWord32# (int2Word# x)) (wordToWord32# (int2Word# y)) of
  (# q, r #) -> case field of 0# -> word2Int# (word32ToWord# q); _ -> word2Int# (word32ToWord# r)

{-# OPAQUE shiftRLInt8 #-}
shiftRLInt8 :: Int# -> Int# -> Int# -> Int# -> Int#
shiftRLInt8 x amount _ _ = int8ToInt# (uncheckedShiftRLInt8# (intToInt8# x) amount)

{-# OPAQUE shiftRLInt16 #-}
shiftRLInt16 :: Int# -> Int# -> Int# -> Int# -> Int#
shiftRLInt16 x amount _ _ = int16ToInt# (uncheckedShiftRLInt16# (intToInt16# x) amount)

{-# OPAQUE shiftRLInt32 #-}
shiftRLInt32 :: Int# -> Int# -> Int# -> Int# -> Int#
shiftRLInt32 x amount _ _ = int32ToInt# (uncheckedShiftRLInt32# (intToInt32# x) amount)

{-# OPAQUE quotRemWord2 #-}
quotRemWord2 :: Int# -> Int# -> Int# -> Int# -> Int#
quotRemWord2 high low divisor field = case quotRemWord2# (int2Word# high) (int2Word# low) (int2Word# divisor) of
  (# q, r #) -> case field of 0# -> word2Int# q; _ -> word2Int# r

-- GHC permits any nonzero conservative overflow indication, not only 1.
{-# OPAQUE mulMay #-}
mulMay :: Int# -> Int# -> Int# -> Int# -> Int#
mulMay x y _ _ = case mulIntMayOflo# x y of 0# -> 0#; _ -> 1#
