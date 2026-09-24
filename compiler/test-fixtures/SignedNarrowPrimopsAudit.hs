-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module SignedNarrowPrimopsAudit where

import GHC.Exts

-- Dynamic Int# host operands are truncated by the real GHC narrow conversions.
-- Every requested primop must survive export; no library body is synthesized.

{-# NOINLINE negateInt8 #-}
negateInt8 :: Int# -> Int#
negateInt8 x = int8ToInt# (negateInt8# (intToInt8# x))

{-# NOINLINE negateInt16 #-}
negateInt16 :: Int# -> Int#
negateInt16 x = int16ToInt# (negateInt16# (intToInt16# x))

{-# NOINLINE negateInt32 #-}
negateInt32 :: Int# -> Int#
negateInt32 x = int32ToInt# (negateInt32# (intToInt32# x))

{-# NOINLINE plusInt8 #-}
plusInt8 :: Int# -> Int# -> Int#
plusInt8 x y = int8ToInt# (plusInt8# (intToInt8# x) (intToInt8# y))

{-# NOINLINE plusInt16 #-}
plusInt16 :: Int# -> Int# -> Int#
plusInt16 x y = int16ToInt# (plusInt16# (intToInt16# x) (intToInt16# y))

{-# NOINLINE plusInt32 #-}
plusInt32 :: Int# -> Int# -> Int#
plusInt32 x y = int32ToInt# (plusInt32# (intToInt32# x) (intToInt32# y))

{-# NOINLINE subInt8 #-}
subInt8 :: Int# -> Int# -> Int#
subInt8 x y = int8ToInt# (subInt8# (intToInt8# x) (intToInt8# y))

{-# NOINLINE subInt16 #-}
subInt16 :: Int# -> Int# -> Int#
subInt16 x y = int16ToInt# (subInt16# (intToInt16# x) (intToInt16# y))

{-# NOINLINE subInt32 #-}
subInt32 :: Int# -> Int# -> Int#
subInt32 x y = int32ToInt# (subInt32# (intToInt32# x) (intToInt32# y))

{-# NOINLINE timesInt8 #-}
timesInt8 :: Int# -> Int# -> Int#
timesInt8 x y = int8ToInt# (timesInt8# (intToInt8# x) (intToInt8# y))

{-# NOINLINE timesInt16 #-}
timesInt16 :: Int# -> Int# -> Int#
timesInt16 x y = int16ToInt# (timesInt16# (intToInt16# x) (intToInt16# y))

{-# NOINLINE timesInt32 #-}
timesInt32 :: Int# -> Int# -> Int#
timesInt32 x y = int32ToInt# (timesInt32# (intToInt32# x) (intToInt32# y))

{-# NOINLINE quotInt8 #-}
quotInt8 :: Int# -> Int# -> Int#
quotInt8 x y = int8ToInt# (quotInt8# (intToInt8# x) (intToInt8# y))

{-# NOINLINE quotInt16 #-}
quotInt16 :: Int# -> Int# -> Int#
quotInt16 x y = int16ToInt# (quotInt16# (intToInt16# x) (intToInt16# y))

{-# NOINLINE quotInt32 #-}
quotInt32 :: Int# -> Int# -> Int#
quotInt32 x y = int32ToInt# (quotInt32# (intToInt32# x) (intToInt32# y))

{-# NOINLINE remInt8 #-}
remInt8 :: Int# -> Int# -> Int#
remInt8 x y = int8ToInt# (remInt8# (intToInt8# x) (intToInt8# y))

{-# NOINLINE remInt16 #-}
remInt16 :: Int# -> Int# -> Int#
remInt16 x y = int16ToInt# (remInt16# (intToInt16# x) (intToInt16# y))

{-# NOINLINE remInt32 #-}
remInt32 :: Int# -> Int# -> Int#
remInt32 x y = int32ToInt# (remInt32# (intToInt32# x) (intToInt32# y))

{-# NOINLINE eqInt8 #-}
eqInt8 :: Int# -> Int# -> Int#
eqInt8 x y = eqInt8# (intToInt8# x) (intToInt8# y)

{-# NOINLINE eqInt16 #-}
eqInt16 :: Int# -> Int# -> Int#
eqInt16 x y = eqInt16# (intToInt16# x) (intToInt16# y)

{-# NOINLINE eqInt32 #-}
eqInt32 :: Int# -> Int# -> Int#
eqInt32 x y = eqInt32# (intToInt32# x) (intToInt32# y)

{-# NOINLINE neInt8 #-}
neInt8 :: Int# -> Int# -> Int#
neInt8 x y = neInt8# (intToInt8# x) (intToInt8# y)

{-# NOINLINE neInt16 #-}
neInt16 :: Int# -> Int# -> Int#
neInt16 x y = neInt16# (intToInt16# x) (intToInt16# y)

{-# NOINLINE neInt32 #-}
neInt32 :: Int# -> Int# -> Int#
neInt32 x y = neInt32# (intToInt32# x) (intToInt32# y)

{-# NOINLINE ltInt8 #-}
ltInt8 :: Int# -> Int# -> Int#
ltInt8 x y = ltInt8# (intToInt8# x) (intToInt8# y)

{-# NOINLINE ltInt16 #-}
ltInt16 :: Int# -> Int# -> Int#
ltInt16 x y = ltInt16# (intToInt16# x) (intToInt16# y)

{-# NOINLINE ltInt32 #-}
ltInt32 :: Int# -> Int# -> Int#
ltInt32 x y = ltInt32# (intToInt32# x) (intToInt32# y)

{-# NOINLINE leInt8 #-}
leInt8 :: Int# -> Int# -> Int#
leInt8 x y = leInt8# (intToInt8# x) (intToInt8# y)

{-# NOINLINE leInt16 #-}
leInt16 :: Int# -> Int# -> Int#
leInt16 x y = leInt16# (intToInt16# x) (intToInt16# y)

{-# NOINLINE leInt32 #-}
leInt32 :: Int# -> Int# -> Int#
leInt32 x y = leInt32# (intToInt32# x) (intToInt32# y)

{-# NOINLINE gtInt8 #-}
gtInt8 :: Int# -> Int# -> Int#
gtInt8 x y = gtInt8# (intToInt8# x) (intToInt8# y)

{-# NOINLINE gtInt16 #-}
gtInt16 :: Int# -> Int# -> Int#
gtInt16 x y = gtInt16# (intToInt16# x) (intToInt16# y)

{-# NOINLINE gtInt32 #-}
gtInt32 :: Int# -> Int# -> Int#
gtInt32 x y = gtInt32# (intToInt32# x) (intToInt32# y)

{-# NOINLINE geInt8 #-}
geInt8 :: Int# -> Int# -> Int#
geInt8 x y = geInt8# (intToInt8# x) (intToInt8# y)

{-# NOINLINE geInt16 #-}
geInt16 :: Int# -> Int# -> Int#
geInt16 x y = geInt16# (intToInt16# x) (intToInt16# y)

{-# NOINLINE geInt32 #-}
geInt32 :: Int# -> Int# -> Int#
geInt32 x y = geInt32# (intToInt32# x) (intToInt32# y)

-- Direct primops keep the pre-Tidy exported root independent of wrapper inlining.
-- The dynamic selector keeps all narrow operations in one installed guest root.
{-# NOINLINE int8ToWord8 #-}
int8ToWord8 :: Int# -> Int#
int8ToWord8 x = word2Int# (word8ToWord# (int8ToWord8# (intToInt8# x)))

{-# NOINLINE word8ToInt8 #-}
word8ToInt8 :: Int# -> Int#
word8ToInt8 x = int8ToInt# (word8ToInt8# (wordToWord8# (int2Word# x)))

{-# NOINLINE int16ToWord16 #-}
int16ToWord16 :: Int# -> Int#
int16ToWord16 x = word2Int# (word16ToWord# (int16ToWord16# (intToInt16# x)))

{-# NOINLINE word16ToInt16 #-}
word16ToInt16 :: Int# -> Int#
word16ToInt16 x = int16ToInt# (word16ToInt16# (wordToWord16# (int2Word# x)))

{-# NOINLINE int32ToWord32 #-}
int32ToWord32 :: Int# -> Int#
int32ToWord32 x = word2Int# (word32ToWord# (int32ToWord32# (intToInt32# x)))

{-# NOINLINE word32ToInt32 #-}
word32ToInt32 :: Int# -> Int#
word32ToInt32 x = int32ToInt# (word32ToInt32# (wordToWord32# (int2Word# x)))

{-# NOINLINE uncheckedShiftLInt8 #-}
uncheckedShiftLInt8 :: Int# -> Int# -> Int#
uncheckedShiftLInt8 x n = int8ToInt# (uncheckedShiftLInt8# (intToInt8# x) n)

{-# NOINLINE uncheckedShiftRAInt8 #-}
uncheckedShiftRAInt8 :: Int# -> Int# -> Int#
uncheckedShiftRAInt8 x n = int8ToInt# (uncheckedShiftRAInt8# (intToInt8# x) n)

{-# NOINLINE uncheckedShiftLInt16 #-}
uncheckedShiftLInt16 :: Int# -> Int# -> Int#
uncheckedShiftLInt16 x n = int16ToInt# (uncheckedShiftLInt16# (intToInt16# x) n)

{-# NOINLINE uncheckedShiftRAInt16 #-}
uncheckedShiftRAInt16 :: Int# -> Int# -> Int#
uncheckedShiftRAInt16 x n = int16ToInt# (uncheckedShiftRAInt16# (intToInt16# x) n)

{-# NOINLINE uncheckedShiftLInt32 #-}
uncheckedShiftLInt32 :: Int# -> Int# -> Int#
uncheckedShiftLInt32 x n = int32ToInt# (uncheckedShiftLInt32# (intToInt32# x) n)

{-# NOINLINE uncheckedShiftRAInt32 #-}
uncheckedShiftRAInt32 :: Int# -> Int# -> Int#
uncheckedShiftRAInt32 x n = int32ToInt# (uncheckedShiftRAInt32# (intToInt32# x) n)

{-# OPAQUE signedNarrowDispatch #-}
signedNarrowDispatch :: Int# -> Int# -> Int# -> Int#
signedNarrowDispatch operation x y = case operation of
  0# -> int8ToInt# (negateInt8# (intToInt8# x))
  1# -> int16ToInt# (negateInt16# (intToInt16# x))
  2# -> int32ToInt# (negateInt32# (intToInt32# x))
  3# -> int8ToInt# (plusInt8# (intToInt8# x) (intToInt8# y))
  4# -> int16ToInt# (plusInt16# (intToInt16# x) (intToInt16# y))
  5# -> int32ToInt# (plusInt32# (intToInt32# x) (intToInt32# y))
  6# -> int8ToInt# (subInt8# (intToInt8# x) (intToInt8# y))
  7# -> int16ToInt# (subInt16# (intToInt16# x) (intToInt16# y))
  8# -> int32ToInt# (subInt32# (intToInt32# x) (intToInt32# y))
  9# -> int8ToInt# (timesInt8# (intToInt8# x) (intToInt8# y))
  10# -> int16ToInt# (timesInt16# (intToInt16# x) (intToInt16# y))
  11# -> int32ToInt# (timesInt32# (intToInt32# x) (intToInt32# y))
  12# -> int8ToInt# (quotInt8# (intToInt8# x) (intToInt8# y))
  13# -> int16ToInt# (quotInt16# (intToInt16# x) (intToInt16# y))
  14# -> int32ToInt# (quotInt32# (intToInt32# x) (intToInt32# y))
  15# -> int8ToInt# (remInt8# (intToInt8# x) (intToInt8# y))
  16# -> int16ToInt# (remInt16# (intToInt16# x) (intToInt16# y))
  17# -> int32ToInt# (remInt32# (intToInt32# x) (intToInt32# y))
  18# -> eqInt8# (intToInt8# x) (intToInt8# y)
  19# -> eqInt16# (intToInt16# x) (intToInt16# y)
  20# -> eqInt32# (intToInt32# x) (intToInt32# y)
  21# -> neInt8# (intToInt8# x) (intToInt8# y)
  22# -> neInt16# (intToInt16# x) (intToInt16# y)
  23# -> neInt32# (intToInt32# x) (intToInt32# y)
  24# -> ltInt8# (intToInt8# x) (intToInt8# y)
  25# -> ltInt16# (intToInt16# x) (intToInt16# y)
  26# -> ltInt32# (intToInt32# x) (intToInt32# y)
  27# -> leInt8# (intToInt8# x) (intToInt8# y)
  28# -> leInt16# (intToInt16# x) (intToInt16# y)
  29# -> leInt32# (intToInt32# x) (intToInt32# y)
  30# -> gtInt8# (intToInt8# x) (intToInt8# y)
  31# -> gtInt16# (intToInt16# x) (intToInt16# y)
  32# -> gtInt32# (intToInt32# x) (intToInt32# y)
  33# -> geInt8# (intToInt8# x) (intToInt8# y)
  34# -> geInt16# (intToInt16# x) (intToInt16# y)
  35# -> geInt32# (intToInt32# x) (intToInt32# y)
  36# -> word2Int# (word8ToWord# (int8ToWord8# (intToInt8# x)))
  37# -> int8ToInt# (word8ToInt8# (wordToWord8# (int2Word# x)))
  38# -> word2Int# (word16ToWord# (int16ToWord16# (intToInt16# x)))
  39# -> int16ToInt# (word16ToInt16# (wordToWord16# (int2Word# x)))
  40# -> word2Int# (word32ToWord# (int32ToWord32# (intToInt32# x)))
  41# -> int32ToInt# (word32ToInt32# (wordToWord32# (int2Word# x)))
  42# -> int8ToInt# (uncheckedShiftLInt8# (intToInt8# x) y)
  43# -> int8ToInt# (uncheckedShiftRAInt8# (intToInt8# x) y)
  44# -> int16ToInt# (uncheckedShiftLInt16# (intToInt16# x) y)
  45# -> int16ToInt# (uncheckedShiftRAInt16# (intToInt16# x) y)
  46# -> int32ToInt# (uncheckedShiftLInt32# (intToInt32# x) y)
  47# -> int32ToInt# (uncheckedShiftRAInt32# (intToInt32# x) y)
  _ -> -1#
