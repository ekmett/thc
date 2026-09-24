-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, ExtendedLiterals #-}
module Explicit64PrimopsAudit where
import GHC.Exts

-- Typed entry boundaries retain each exact Int64Rep/Word64Rep proof.

{-# OPAQUE int64ToWord64 #-}
int64ToWord64 :: Int64# -> Word64#
int64ToWord64 x = int64ToWord64# x

{-# OPAQUE word64ToInt64 #-}
word64ToInt64 :: Word64# -> Int64#
word64ToInt64 x = word64ToInt64# x

{-# OPAQUE wordToWord64 #-}
wordToWord64 :: Word# -> Word64#
wordToWord64 x = wordToWord64# x

{-# OPAQUE word64ToWord #-}
word64ToWord :: Word64# -> Word#
word64ToWord x = word64ToWord# x

{-# OPAQUE plusInt64 #-}
plusInt64 :: Int64# -> Int64# -> Int64#
plusInt64 x y = plusInt64# x y

{-# OPAQUE subInt64 #-}
subInt64 :: Int64# -> Int64# -> Int64#
subInt64 x y = subInt64# x y

{-# OPAQUE timesInt64 #-}
timesInt64 :: Int64# -> Int64# -> Int64#
timesInt64 x y = timesInt64# x y

{-# OPAQUE quotInt64 #-}
quotInt64 :: Int64# -> Int64# -> Int64#
quotInt64 x y = quotInt64# x y

{-# OPAQUE remInt64 #-}
remInt64 :: Int64# -> Int64# -> Int64#
remInt64 x y = remInt64# x y

{-# OPAQUE eqInt64 #-}
eqInt64 :: Int64# -> Int64# -> Int#
eqInt64 x y = eqInt64# x y

{-# OPAQUE neInt64 #-}
neInt64 :: Int64# -> Int64# -> Int#
neInt64 x y = neInt64# x y

{-# OPAQUE ltInt64 #-}
ltInt64 :: Int64# -> Int64# -> Int#
ltInt64 x y = ltInt64# x y

{-# OPAQUE leInt64 #-}
leInt64 :: Int64# -> Int64# -> Int#
leInt64 x y = leInt64# x y

{-# OPAQUE gtInt64 #-}
gtInt64 :: Int64# -> Int64# -> Int#
gtInt64 x y = gtInt64# x y

{-# OPAQUE geInt64 #-}
geInt64 :: Int64# -> Int64# -> Int#
geInt64 x y = geInt64# x y

{-# OPAQUE plusWord64 #-}
plusWord64 :: Word64# -> Word64# -> Word64#
plusWord64 x y = plusWord64# x y

{-# OPAQUE subWord64 #-}
subWord64 :: Word64# -> Word64# -> Word64#
subWord64 x y = subWord64# x y

{-# OPAQUE timesWord64 #-}
timesWord64 :: Word64# -> Word64# -> Word64#
timesWord64 x y = timesWord64# x y

{-# OPAQUE quotWord64 #-}
quotWord64 :: Word64# -> Word64# -> Word64#
quotWord64 x y = quotWord64# x y

{-# OPAQUE remWord64 #-}
remWord64 :: Word64# -> Word64# -> Word64#
remWord64 x y = remWord64# x y

{-# OPAQUE eqWord64 #-}
eqWord64 :: Word64# -> Word64# -> Int#
eqWord64 x y = eqWord64# x y

{-# OPAQUE neWord64 #-}
neWord64 :: Word64# -> Word64# -> Int#
neWord64 x y = neWord64# x y

{-# OPAQUE ltWord64 #-}
ltWord64 :: Word64# -> Word64# -> Int#
ltWord64 x y = ltWord64# x y

{-# OPAQUE leWord64 #-}
leWord64 :: Word64# -> Word64# -> Int#
leWord64 x y = leWord64# x y

{-# OPAQUE gtWord64 #-}
gtWord64 :: Word64# -> Word64# -> Int#
gtWord64 x y = gtWord64# x y

{-# OPAQUE geWord64 #-}
geWord64 :: Word64# -> Word64# -> Int#
geWord64 x y = geWord64# x y

{-# OPAQUE negateInt64 #-}
negateInt64 :: Int64# -> Int64#
negateInt64 x = negateInt64# x

{-# OPAQUE and64 #-}
and64 :: Word64# -> Word64# -> Word64#
and64 x y = and64# x y

{-# OPAQUE or64 #-}
or64 :: Word64# -> Word64# -> Word64#
or64 x y = or64# x y

{-# OPAQUE xor64 #-}
xor64 :: Word64# -> Word64# -> Word64#
xor64 x y = xor64# x y

{-# OPAQUE not64 #-}
not64 :: Word64# -> Word64#
not64 x = not64# x

{-# OPAQUE uncheckedIShiftL64 #-}
uncheckedIShiftL64 :: Int64# -> Int# -> Int64#
uncheckedIShiftL64 x y = uncheckedIShiftL64# x y

{-# OPAQUE uncheckedIShiftRA64 #-}
uncheckedIShiftRA64 :: Int64# -> Int# -> Int64#
uncheckedIShiftRA64 x y = uncheckedIShiftRA64# x y

{-# OPAQUE uncheckedIShiftRL64 #-}
uncheckedIShiftRL64 :: Int64# -> Int# -> Int64#
uncheckedIShiftRL64 x y = uncheckedIShiftRL64# x y

{-# OPAQUE uncheckedShiftL64 #-}
uncheckedShiftL64 :: Word64# -> Int# -> Word64#
uncheckedShiftL64 x y = uncheckedShiftL64# x y

{-# OPAQUE uncheckedShiftRL64 #-}
uncheckedShiftRL64 :: Word64# -> Int# -> Word64#
uncheckedShiftRL64 x y = uncheckedShiftRL64# x y

{-# OPAQUE word64Literals #-}
word64Literals :: Int# -> Word64#
word64Literals x = case x of
  0# -> 0#Word64
  1# -> 9223372036854775807#Word64
  2# -> 18446744073709551615#Word64
  _ -> 9223372036854775808#Word64

{-# OPAQUE word64Case #-}
word64Case :: Word64# -> Int#
word64Case x = case x of
  0#Word64 -> 11#
  9223372036854775808#Word64 -> 13#
  18446744073709551615#Word64 -> 17#
  _ -> 19#
