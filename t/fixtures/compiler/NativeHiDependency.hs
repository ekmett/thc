-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
-- A separately compiled retained module makes native .hi execution resolve a
-- real cross-module call and recover an address field. Consumed with NativeHiScalar.hi; no plugin or CBD.
module NativeHiDependency (marker) where

import GHC.Exts (Int#, Addr#, (+#), andI#, indexWord8OffAddr#, word8ToWord#, word2Int#)

data AddrRef = AddrRef Addr#

-- Preserve the constructor boundary so the consumer really reads an Addr# field.
{-# OPAQUE bytes #-}
bytes :: Int# -> AddrRef
bytes _ = AddrRef "A\0\xCE\xBB"#

{-# OPAQUE marker #-}
marker :: Int# -> Int#
-- Primitive literals accept bytes only: UTF-8 A/NUL/lambda is 65, 0, 206, 187.
marker x = case bytes x of
  AddrRef address -> x +# 7# +# word2Int# (word8ToWord# (indexWord8OffAddr# address (andI# x 3#)))
