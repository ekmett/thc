-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module InterfaceCacheDependency (marker) where
import GHC.Exts

{-# OPAQUE marker #-}
marker :: Int# -> Int#
marker value = value +# 5#
