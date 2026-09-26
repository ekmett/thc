-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module OriginalErfEntry (erfDouble, erfcDouble, erfFloat, erfcFloat) where

import Data.Number.Erf (erf, erfc)
import GHC.Exts

-- Primitive bit entrypoints only. Every math call comes from the unchanged
-- package's public instances, preserving its original safe foreign import.
{-# NOINLINE erfDouble #-}
erfDouble :: Int# -> Int#
erfDouble bits = case erf (D# (castWord64ToDouble# (wordToWord64# (int2Word# bits)))) of
  D# result -> word2Int# (word64ToWord# (castDoubleToWord64# result))

{-# NOINLINE erfcDouble #-}
erfcDouble :: Int# -> Int#
erfcDouble bits = case erfc (D# (castWord64ToDouble# (wordToWord64# (int2Word# bits)))) of
  D# result -> word2Int# (word64ToWord# (castDoubleToWord64# result))

{-# NOINLINE erfFloat #-}
erfFloat :: Int# -> Int#
erfFloat bits = case erf (F# (castWord32ToFloat# (wordToWord32# (int2Word# bits)))) of
  F# result -> word2Int# (word32ToWord# (castFloatToWord32# result))

{-# NOINLINE erfcFloat #-}
erfcFloat :: Int# -> Int#
erfcFloat bits = case erfc (F# (castWord32ToFloat# (wordToWord32# (int2Word# bits)))) of
  F# result -> word2Int# (word32ToWord# (castFloatToWord32# result))
