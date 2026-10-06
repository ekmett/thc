-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
-- A separately compiled retained module makes native .hi execution resolve a
-- real cross-module call. Consumed with NativeHiScalar.hi; no plugin or CBD.
module NativeHiDependency (marker) where

import GHC.Exts (Int#, (+#))

{-# OPAQUE marker #-}
marker :: Int# -> Int#
marker x = x +# 7#
