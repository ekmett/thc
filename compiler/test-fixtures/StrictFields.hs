-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, NoImplicitPrelude #-}

-- |
-- Module      : StrictFields
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Compiler fixture for strict fields Core and metadata.
module StrictFields where
import GHC.Exts (Int#)

data Box = Box Int#
data Lazy = Lazy Box
data Strict = Strict {-# NOUNPACK #-} !Box

{-# OPAQUE lazyConstruct #-}
lazyConstruct :: Box -> Lazy
lazyConstruct x = Lazy x

{-# OPAQUE strictConstruct #-}
strictConstruct :: Box -> Strict
strictConstruct x = Strict x
