-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- Fixture rationale (120 strict-fields)
-- Purpose: Check strict constructor fields force values at the correct time.
-- Produces/consumed result: StrictFields.cbd consumed directly by StrictFieldsTest.
-- Cost and overlap: Keep laziness/exception-sensitive constructor semantics. One Core
--   export is proportionate; no native harness or extra receipt is needed for its checked
--   model.
-- Build status: Value review only; admission still requires explicit inputs and single-
--   owner outputs.
-- Detailed file inputs/outputs: docs/fixture-inputs.log, entry 120.

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
