{-# LANGUAGE MagicHash #-}
module THC.IntMapPrimops
  ( countLeadingZeros, unsignedLessThanZero, unsignedLessThanMaxSigned
  , unsignedLessThanSignBit, unsignedLessThanAllOnes
  ) where

import GHC.Exts

-- Word bit patterns cross the existing signed Int# embedding boundary without
-- conversion of their representation. clz# returns Word# in GHC 9.14.1.
countLeadingZeros :: Int# -> Int#
countLeadingZeros x = word2Int# (clz# (int2Word# x))

unsignedLessThanZero :: Int# -> Int#
unsignedLessThanZero x = ltWord# (int2Word# x) 0##

unsignedLessThanMaxSigned :: Int# -> Int#
unsignedLessThanMaxSigned x = ltWord# (int2Word# x) 9223372036854775807##

unsignedLessThanSignBit :: Int# -> Int#
unsignedLessThanSignBit x = ltWord# (int2Word# x) 9223372036854775808##

unsignedLessThanAllOnes :: Int# -> Int#
unsignedLessThanAllOnes x = ltWord# (int2Word# x) 18446744073709551615##
