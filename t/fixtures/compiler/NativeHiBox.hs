-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
-- Input: an Int# supplied by NativeHiOracle.
-- Purpose: force lazy payload/tail fields and recover typed floating-point fields.
-- Output: -7 for negative input or 3*(x+2), plus Float#/Double# bit patterns.
-- NativeHiOracle and both backends consume all three functions.
module NativeHiBox (entry, floatBits, doubleBits) where

import GHC.Exts
  ( Int#, (*#), castFloatToWord32#, castDoubleToWord64#
  , word32ToWord#, word64ToWord#, word2Int#
  )
import NativeHiBoxType (Payload(..), Box(..), box, payload, identityBox)

{-# OPAQUE entry #-}
entry :: Int# -> Int#
entry x = case identityBox (box x) of
  Empty -> -7#
  Box (Payload y _ _) rest -> case rest of
    Empty -> y *# 3#
    Box _ _ -> -97#

{-# OPAQUE floatBits #-}
floatBits :: Int# -> Int#
floatBits x = case payload x of
  Payload _ value _ -> word2Int# (word32ToWord# (castFloatToWord32# value))

{-# OPAQUE doubleBits #-}
doubleBits :: Int# -> Int#
doubleBits x = case payload x of
  Payload _ _ value -> word2Int# (word64ToWord# (castDoubleToWord64# value))
