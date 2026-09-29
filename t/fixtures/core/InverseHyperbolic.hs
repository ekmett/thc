-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}

-- |
-- Module      : InverseHyperbolic
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Example entry points exercising inverse hyperbolic.
module InverseHyperbolic (asinhExample) where
import GHC.Exts

-- Accept and return binary64 bits so the ordinary integer CLI preserves zeros,
-- infinities and subnormals. The mathematical operation is the genuine primop.
{-# OPAQUE asinhExample #-}
asinhExample :: Int# -> Int#
asinhExample bits = word2Int# (word64ToWord# (castDoubleToWord64#
  (asinhDouble# (castWord64ToDouble# (wordToWord64# (int2Word# bits))))))
