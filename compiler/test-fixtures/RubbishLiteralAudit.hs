-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}

-- |
-- Module      : RubbishLiteralAudit
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Compiler fixture for rubbish literal audit Core and metadata.
module RubbishLiteralAudit where
import GHC.Exts

-- There is deliberately no Haskell syntax purporting to mean RUBBISH.
-- The GHC API preparer retains these bodies beneath genuine typed Core
-- DEFAULT cases. The continuation must still execute, including its input.
template :: Int# -> Int#
template n = n +# 17#
nativeTemplate :: Int -> Int
nativeTemplate (I# n) = I# (n +# 17#)
