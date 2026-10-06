-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE GADTs #-}
{-# LANGUAGE MagicHash #-}
-- Input: an Int# supplied by NativeHiOracle.
-- Purpose: force a wrapped lazy payload/tail and call imported record selectors.
-- Output: -7 for negative input or 3*(x+2), plus Float#/Double# bit patterns.
-- NativeHiOracle and both backends consume all three functions.
module NativeHiBox (entry, floatBits, doubleBits) where

import GHC.Exts
  ( Int(I#), Int#, (*#), castFloatToWord32#, castDoubleToWord64#
  , word32ToWord#, word64ToWord#, word2Int#
  )
import NativeHiBoxType (Payload(..), Box(..), box, payload, unpayload, identityBox)

{-# OPAQUE entry #-}
entry :: Int# -> Int#
entry x = case identityBox (box x) of
  Empty -> -7#
  Box ref rest -> case unpayload ref of
    Payload (I# y) _ _ -> case rest of
      Empty -> y *# 3#
      Box _ _ -> -97#

{-# OPAQUE floatBits #-}
floatBits :: Int# -> Int#
floatBits x = word2Int# (word32ToWord# (castFloatToWord32# (payloadFloat (unpayload (payload x)))))

{-# OPAQUE doubleBits #-}
doubleBits :: Int# -> Int#
doubleBits x = word2Int# (word64ToWord# (castDoubleToWord64# (payloadDouble (unpayload (payload x)))))
