-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}

-- |
-- Module      : ProxyVoidAudit
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Compiler fixture for proxy void audit Core and metadata.
module ProxyVoidAudit where
import GHC.Exts

data Failure = Failure

-- The same empty-tuple input / scalar-void result as containers' $wbogus.
{-# NOINLINE token #-}
token :: (# #) -> Proxy# ()
token _ = proxy#

{-# NOINLINE consume #-}
consume :: Proxy# a -> Int# -> Int#
consume _ x = x +# 7#

{-# NOINLINE direct #-}
direct :: Int# -> Int#
direct x = through consume x

{-# NOINLINE through #-}
through :: (Proxy# Int -> Int# -> Int#) -> Int# -> Int#
through f x = f proxy# x

{-# NOINLINE returned #-}
returned :: Int# -> Int#
returned x = consume (token (# #)) x

{-# NOINLINE pair #-}
pair :: Int# -> (# Proxy# (), (# #), Int# #)
pair x = (# proxy#, (# #), x +# 3# #)

{-# NOINLINE tupleCase #-}
tupleCase :: Int# -> Int#
tupleCase x = case pair x of (# p, _, y #) -> consume p y

-- Only the wired constant may be erased. A call producing Proxy# still runs.
{-# NOINLINE required #-}
required :: Int# -> Proxy# ()
required x = case x <# 0# of 1# -> raise# Failure; _ -> proxy#

{-# NOINLINE effect #-}
effect :: Int# -> Int#
effect x = consume (required x) x
