-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, NoImplicitPrelude #-}
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
