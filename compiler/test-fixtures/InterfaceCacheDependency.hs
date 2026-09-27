-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}

-- |
-- Module      : InterfaceCacheDependency
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Compiler fixture for interface cache dependency Core and metadata.
module InterfaceCacheDependency (marker) where
import GHC.Exts

{-# OPAQUE marker #-}
marker :: Int# -> Int#
marker value = value +# 5#
