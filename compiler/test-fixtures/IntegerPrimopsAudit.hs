-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module IntegerPrimopsAudit where

import GHC.Exts

-- Raw bits cross the integer-only host boundary. Every operand remains dynamic.
-- Deliberately direct primop wrappers: preparation checks surviving Core names.

{-# NOINLINE quotWord #-}
quotWord :: Int# -> Int# -> Int#
quotWord x y = word2Int# (quotWord# (int2Word# x) (int2Word# y))

{-# NOINLINE remWord #-}
remWord :: Int# -> Int# -> Int#
remWord x y = word2Int# (remWord# (int2Word# x) (int2Word# y))

{-# NOINLINE gtWord #-}
gtWord :: Int# -> Int# -> Int#
gtWord x y = gtWord# (int2Word# x) (int2Word# y)

{-# NOINLINE geWord #-}
geWord :: Int# -> Int# -> Int#
geWord x y = geWord# (int2Word# x) (int2Word# y)

{-# NOINLINE quotWord8 #-}
quotWord8 :: Int# -> Int# -> Int#
quotWord8 x y = word2Int# (word8ToWord# (quotWord8# (wordToWord8# (int2Word# x)) (wordToWord8# (int2Word# y))))

{-# NOINLINE quotWord16 #-}
quotWord16 :: Int# -> Int# -> Int#
quotWord16 x y = word2Int# (word16ToWord# (quotWord16# (wordToWord16# (int2Word# x)) (wordToWord16# (int2Word# y))))

{-# NOINLINE quotWord32 #-}
quotWord32 :: Int# -> Int# -> Int#
quotWord32 x y = word2Int# (word32ToWord# (quotWord32# (wordToWord32# (int2Word# x)) (wordToWord32# (int2Word# y))))

{-# NOINLINE remWord8 #-}
remWord8 :: Int# -> Int# -> Int#
remWord8 x y = word2Int# (word8ToWord# (remWord8# (wordToWord8# (int2Word# x)) (wordToWord8# (int2Word# y))))

{-# NOINLINE remWord16 #-}
remWord16 :: Int# -> Int# -> Int#
remWord16 x y = word2Int# (word16ToWord# (remWord16# (wordToWord16# (int2Word# x)) (wordToWord16# (int2Word# y))))

{-# NOINLINE remWord32 #-}
remWord32 :: Int# -> Int# -> Int#
remWord32 x y = word2Int# (word32ToWord# (remWord32# (wordToWord32# (int2Word# x)) (wordToWord32# (int2Word# y))))

{-# NOINLINE eqWord8 #-}
eqWord8 :: Int# -> Int# -> Int#
eqWord8 x y = eqWord8# (wordToWord8# (int2Word# x)) (wordToWord8# (int2Word# y))

{-# NOINLINE eqWord16 #-}
eqWord16 :: Int# -> Int# -> Int#
eqWord16 x y = eqWord16# (wordToWord16# (int2Word# x)) (wordToWord16# (int2Word# y))

{-# NOINLINE eqWord32 #-}
eqWord32 :: Int# -> Int# -> Int#
eqWord32 x y = eqWord32# (wordToWord32# (int2Word# x)) (wordToWord32# (int2Word# y))

{-# NOINLINE neWord8 #-}
neWord8 :: Int# -> Int# -> Int#
neWord8 x y = neWord8# (wordToWord8# (int2Word# x)) (wordToWord8# (int2Word# y))

{-# NOINLINE neWord16 #-}
neWord16 :: Int# -> Int# -> Int#
neWord16 x y = neWord16# (wordToWord16# (int2Word# x)) (wordToWord16# (int2Word# y))

{-# NOINLINE neWord32 #-}
neWord32 :: Int# -> Int# -> Int#
neWord32 x y = neWord32# (wordToWord32# (int2Word# x)) (wordToWord32# (int2Word# y))

{-# NOINLINE gtWord8 #-}
gtWord8 :: Int# -> Int# -> Int#
gtWord8 x y = gtWord8# (wordToWord8# (int2Word# x)) (wordToWord8# (int2Word# y))

{-# NOINLINE gtWord16 #-}
gtWord16 :: Int# -> Int# -> Int#
gtWord16 x y = gtWord16# (wordToWord16# (int2Word# x)) (wordToWord16# (int2Word# y))

{-# NOINLINE gtWord32 #-}
gtWord32 :: Int# -> Int# -> Int#
gtWord32 x y = gtWord32# (wordToWord32# (int2Word# x)) (wordToWord32# (int2Word# y))

{-# NOINLINE geWord8 #-}
geWord8 :: Int# -> Int# -> Int#
geWord8 x y = geWord8# (wordToWord8# (int2Word# x)) (wordToWord8# (int2Word# y))

{-# NOINLINE geWord16 #-}
geWord16 :: Int# -> Int# -> Int#
geWord16 x y = geWord16# (wordToWord16# (int2Word# x)) (wordToWord16# (int2Word# y))

{-# NOINLINE geWord32 #-}
geWord32 :: Int# -> Int# -> Int#
geWord32 x y = geWord32# (wordToWord32# (int2Word# x)) (wordToWord32# (int2Word# y))

{-# NOINLINE andWord8 #-}
andWord8 :: Int# -> Int# -> Int#
andWord8 x y = word2Int# (word8ToWord# (andWord8# (wordToWord8# (int2Word# x)) (wordToWord8# (int2Word# y))))

{-# NOINLINE andWord16 #-}
andWord16 :: Int# -> Int# -> Int#
andWord16 x y = word2Int# (word16ToWord# (andWord16# (wordToWord16# (int2Word# x)) (wordToWord16# (int2Word# y))))

{-# NOINLINE andWord32 #-}
andWord32 :: Int# -> Int# -> Int#
andWord32 x y = word2Int# (word32ToWord# (andWord32# (wordToWord32# (int2Word# x)) (wordToWord32# (int2Word# y))))

{-# NOINLINE orWord8 #-}
orWord8 :: Int# -> Int# -> Int#
orWord8 x y = word2Int# (word8ToWord# (orWord8# (wordToWord8# (int2Word# x)) (wordToWord8# (int2Word# y))))

{-# NOINLINE orWord16 #-}
orWord16 :: Int# -> Int# -> Int#
orWord16 x y = word2Int# (word16ToWord# (orWord16# (wordToWord16# (int2Word# x)) (wordToWord16# (int2Word# y))))

{-# NOINLINE orWord32 #-}
orWord32 :: Int# -> Int# -> Int#
orWord32 x y = word2Int# (word32ToWord# (orWord32# (wordToWord32# (int2Word# x)) (wordToWord32# (int2Word# y))))

{-# NOINLINE xorWord8 #-}
xorWord8 :: Int# -> Int# -> Int#
xorWord8 x y = word2Int# (word8ToWord# (xorWord8# (wordToWord8# (int2Word# x)) (wordToWord8# (int2Word# y))))

{-# NOINLINE xorWord16 #-}
xorWord16 :: Int# -> Int# -> Int#
xorWord16 x y = word2Int# (word16ToWord# (xorWord16# (wordToWord16# (int2Word# x)) (wordToWord16# (int2Word# y))))

{-# NOINLINE xorWord32 #-}
xorWord32 :: Int# -> Int# -> Int#
xorWord32 x y = word2Int# (word32ToWord# (xorWord32# (wordToWord32# (int2Word# x)) (wordToWord32# (int2Word# y))))

{-# NOINLINE notWord8 #-}
notWord8 :: Int# -> Int#
notWord8 x = word2Int# (word8ToWord# (notWord8# (wordToWord8# (int2Word# x))))

{-# NOINLINE notWord16 #-}
notWord16 :: Int# -> Int#
notWord16 x = word2Int# (word16ToWord# (notWord16# (wordToWord16# (int2Word# x))))

{-# NOINLINE notWord32 #-}
notWord32 :: Int# -> Int#
notWord32 x = word2Int# (word32ToWord# (notWord32# (wordToWord32# (int2Word# x))))

{-# NOINLINE uncheckedShiftLWord8 #-}
uncheckedShiftLWord8 :: Int# -> Int# -> Int#
uncheckedShiftLWord8 x y = word2Int# (word8ToWord# (uncheckedShiftLWord8# (wordToWord8# (int2Word# x)) y))

{-# NOINLINE uncheckedShiftLWord16 #-}
uncheckedShiftLWord16 :: Int# -> Int# -> Int#
uncheckedShiftLWord16 x y = word2Int# (word16ToWord# (uncheckedShiftLWord16# (wordToWord16# (int2Word# x)) y))

{-# NOINLINE uncheckedShiftLWord32 #-}
uncheckedShiftLWord32 :: Int# -> Int# -> Int#
uncheckedShiftLWord32 x y = word2Int# (word32ToWord# (uncheckedShiftLWord32# (wordToWord32# (int2Word# x)) y))

{-# NOINLINE uncheckedShiftRLWord8 #-}
uncheckedShiftRLWord8 :: Int# -> Int# -> Int#
uncheckedShiftRLWord8 x y = word2Int# (word8ToWord# (uncheckedShiftRLWord8# (wordToWord8# (int2Word# x)) y))

{-# NOINLINE uncheckedShiftRLWord16 #-}
uncheckedShiftRLWord16 :: Int# -> Int# -> Int#
uncheckedShiftRLWord16 x y = word2Int# (word16ToWord# (uncheckedShiftRLWord16# (wordToWord16# (int2Word# x)) y))

{-# NOINLINE uncheckedShiftRLWord32 #-}
uncheckedShiftRLWord32 :: Int# -> Int# -> Int#
uncheckedShiftRLWord32 x y = word2Int# (word32ToWord# (uncheckedShiftRLWord32# (wordToWord32# (int2Word# x)) y))

{-# NOINLINE pdepWord8 #-}
pdepWord8 :: Int# -> Int# -> Int#
pdepWord8 x y = word2Int# (pdep8# (int2Word# x) (int2Word# y))

{-# NOINLINE pdepWord16 #-}
pdepWord16 :: Int# -> Int# -> Int#
pdepWord16 x y = word2Int# (pdep16# (int2Word# x) (int2Word# y))

{-# NOINLINE pdepWord32 #-}
pdepWord32 :: Int# -> Int# -> Int#
pdepWord32 x y = word2Int# (pdep32# (int2Word# x) (int2Word# y))

{-# NOINLINE pdepWord64 #-}
pdepWord64 :: Int# -> Int# -> Int#
pdepWord64 x y = word2Int# (word64ToWord# (pdep64# (wordToWord64# (int2Word# x)) (wordToWord64# (int2Word# y))))

{-# NOINLINE pdepWord #-}
pdepWord :: Int# -> Int# -> Int#
pdepWord x y = word2Int# (pdep# (int2Word# x) (int2Word# y))

{-# NOINLINE pextWord8 #-}
pextWord8 :: Int# -> Int# -> Int#
pextWord8 x y = word2Int# (pext8# (int2Word# x) (int2Word# y))

{-# NOINLINE pextWord16 #-}
pextWord16 :: Int# -> Int# -> Int#
pextWord16 x y = word2Int# (pext16# (int2Word# x) (int2Word# y))

{-# NOINLINE pextWord32 #-}
pextWord32 :: Int# -> Int# -> Int#
pextWord32 x y = word2Int# (pext32# (int2Word# x) (int2Word# y))

{-# NOINLINE pextWord64 #-}
pextWord64 :: Int# -> Int# -> Int#
pextWord64 x y = word2Int# (word64ToWord# (pext64# (wordToWord64# (int2Word# x)) (wordToWord64# (int2Word# y))))

{-# NOINLINE pextWord #-}
pextWord :: Int# -> Int# -> Int#
pextWord x y = word2Int# (pext# (int2Word# x) (int2Word# y))

-- One dynamic entry covers every primitive branch in each guest backend.
-- Wrappers remain exported for their individual native and Core audits.
{-# OPAQUE composite #-}
composite :: Int# -> Int# -> Int# -> Int#
composite selector x y = case selector of
  0# -> word2Int# (quotWord# (int2Word# x) (int2Word# y))
  1# -> word2Int# (remWord# (int2Word# x) (int2Word# y))
  2# -> gtWord# (int2Word# x) (int2Word# y)
  3# -> geWord# (int2Word# x) (int2Word# y)
  4# -> word2Int# (word8ToWord# (quotWord8# (wordToWord8# (int2Word# x)) (wordToWord8# (int2Word# y))))
  5# -> word2Int# (word16ToWord# (quotWord16# (wordToWord16# (int2Word# x)) (wordToWord16# (int2Word# y))))
  6# -> word2Int# (word32ToWord# (quotWord32# (wordToWord32# (int2Word# x)) (wordToWord32# (int2Word# y))))
  7# -> word2Int# (word8ToWord# (remWord8# (wordToWord8# (int2Word# x)) (wordToWord8# (int2Word# y))))
  8# -> word2Int# (word16ToWord# (remWord16# (wordToWord16# (int2Word# x)) (wordToWord16# (int2Word# y))))
  9# -> word2Int# (word32ToWord# (remWord32# (wordToWord32# (int2Word# x)) (wordToWord32# (int2Word# y))))
  10# -> eqWord8# (wordToWord8# (int2Word# x)) (wordToWord8# (int2Word# y))
  11# -> eqWord16# (wordToWord16# (int2Word# x)) (wordToWord16# (int2Word# y))
  12# -> eqWord32# (wordToWord32# (int2Word# x)) (wordToWord32# (int2Word# y))
  13# -> neWord8# (wordToWord8# (int2Word# x)) (wordToWord8# (int2Word# y))
  14# -> neWord16# (wordToWord16# (int2Word# x)) (wordToWord16# (int2Word# y))
  15# -> neWord32# (wordToWord32# (int2Word# x)) (wordToWord32# (int2Word# y))
  16# -> gtWord8# (wordToWord8# (int2Word# x)) (wordToWord8# (int2Word# y))
  17# -> gtWord16# (wordToWord16# (int2Word# x)) (wordToWord16# (int2Word# y))
  18# -> gtWord32# (wordToWord32# (int2Word# x)) (wordToWord32# (int2Word# y))
  19# -> geWord8# (wordToWord8# (int2Word# x)) (wordToWord8# (int2Word# y))
  20# -> geWord16# (wordToWord16# (int2Word# x)) (wordToWord16# (int2Word# y))
  21# -> geWord32# (wordToWord32# (int2Word# x)) (wordToWord32# (int2Word# y))
  22# -> word2Int# (word8ToWord# (andWord8# (wordToWord8# (int2Word# x)) (wordToWord8# (int2Word# y))))
  23# -> word2Int# (word16ToWord# (andWord16# (wordToWord16# (int2Word# x)) (wordToWord16# (int2Word# y))))
  24# -> word2Int# (word32ToWord# (andWord32# (wordToWord32# (int2Word# x)) (wordToWord32# (int2Word# y))))
  25# -> word2Int# (word8ToWord# (orWord8# (wordToWord8# (int2Word# x)) (wordToWord8# (int2Word# y))))
  26# -> word2Int# (word16ToWord# (orWord16# (wordToWord16# (int2Word# x)) (wordToWord16# (int2Word# y))))
  27# -> word2Int# (word32ToWord# (orWord32# (wordToWord32# (int2Word# x)) (wordToWord32# (int2Word# y))))
  28# -> word2Int# (word8ToWord# (xorWord8# (wordToWord8# (int2Word# x)) (wordToWord8# (int2Word# y))))
  29# -> word2Int# (word16ToWord# (xorWord16# (wordToWord16# (int2Word# x)) (wordToWord16# (int2Word# y))))
  30# -> word2Int# (word32ToWord# (xorWord32# (wordToWord32# (int2Word# x)) (wordToWord32# (int2Word# y))))
  31# -> word2Int# (word8ToWord# (notWord8# (wordToWord8# (int2Word# x))))
  32# -> word2Int# (word16ToWord# (notWord16# (wordToWord16# (int2Word# x))))
  33# -> word2Int# (word32ToWord# (notWord32# (wordToWord32# (int2Word# x))))
  34# -> word2Int# (word8ToWord# (uncheckedShiftLWord8# (wordToWord8# (int2Word# x)) y))
  35# -> word2Int# (word16ToWord# (uncheckedShiftLWord16# (wordToWord16# (int2Word# x)) y))
  36# -> word2Int# (word32ToWord# (uncheckedShiftLWord32# (wordToWord32# (int2Word# x)) y))
  37# -> word2Int# (word8ToWord# (uncheckedShiftRLWord8# (wordToWord8# (int2Word# x)) y))
  38# -> word2Int# (word16ToWord# (uncheckedShiftRLWord16# (wordToWord16# (int2Word# x)) y))
  39# -> word2Int# (word32ToWord# (uncheckedShiftRLWord32# (wordToWord32# (int2Word# x)) y))
  40# -> word2Int# (pdep8# (int2Word# x) (int2Word# y))
  41# -> word2Int# (pdep16# (int2Word# x) (int2Word# y))
  42# -> word2Int# (pdep32# (int2Word# x) (int2Word# y))
  43# -> word2Int# (word64ToWord# (pdep64# (wordToWord64# (int2Word# x)) (wordToWord64# (int2Word# y))))
  44# -> word2Int# (pdep# (int2Word# x) (int2Word# y))
  45# -> word2Int# (pext8# (int2Word# x) (int2Word# y))
  46# -> word2Int# (pext16# (int2Word# x) (int2Word# y))
  47# -> word2Int# (pext32# (int2Word# x) (int2Word# y))
  48# -> word2Int# (word64ToWord# (pext64# (wordToWord64# (int2Word# x)) (wordToWord64# (int2Word# y))))
  49# -> word2Int# (pext# (int2Word# x) (int2Word# y))
  _ -> 0#
