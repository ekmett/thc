-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}

-- |
-- Module      : TagToEnumExternal
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Compiler fixture for tag to enum external Core and metadata.
module TagToEnumExternal (External, externalCode) where
import GHC.Exts

data External = Far | Near | Hidden | Last
{-# OPAQUE externalCode #-}
externalCode :: External -> Int#
externalCode Far = 43#
externalCode Near = -7#
externalCode Hidden = 91#
externalCode Last = 1009#
