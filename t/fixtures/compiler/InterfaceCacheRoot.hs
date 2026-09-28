-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, TemplateHaskell #-}

-- |
-- Module      : InterfaceCacheRoot
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC Template Haskell
--
-- Compiler fixture for interface cache root Core and metadata.
module InterfaceCacheRoot (entry) where
import GHC.Exts
import InterfaceCacheDependency (marker)

{-# ANN module ("cache-original" :: String) #-}
{-# OPAQUE entry #-}
entry :: Int# -> Int#
entry value = marker value +# 7#
