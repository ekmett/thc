-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, TemplateHaskell #-}
module InterfaceCacheRoot (entry) where
import GHC.Exts
import InterfaceCacheDependency (marker)

{-# ANN module ("cache-original" :: String) #-}
{-# OPAQUE entry #-}
entry :: Int# -> Int#
entry value = marker value +# 7#
