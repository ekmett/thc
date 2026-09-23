{-# LANGUAGE MagicHash #-}
module THC.IntSetPrimops
  ( populationCount, countTrailingZeros
  , unsignedLessEqualZero, unsignedLessEqualMaxSigned
  , unsignedLessEqualSignBit, unsignedLessEqualAllOnes
  ) where

import GHC.Exts

populationCount :: Int# -> Int#
populationCount x = word2Int# (popCnt# (int2Word# x))

countTrailingZeros :: Int# -> Int#
countTrailingZeros x = word2Int# (ctz# (int2Word# x))

unsignedLessEqualZero :: Int# -> Int#
unsignedLessEqualZero x = leWord# (int2Word# x) 0##

unsignedLessEqualMaxSigned :: Int# -> Int#
unsignedLessEqualMaxSigned x = leWord# (int2Word# x) 9223372036854775807##

unsignedLessEqualSignBit :: Int# -> Int#
unsignedLessEqualSignBit x = leWord# (int2Word# x) 9223372036854775808##

unsignedLessEqualAllOnes :: Int# -> Int#
unsignedLessEqualAllOnes x = leWord# (int2Word# x) 18446744073709551615##
