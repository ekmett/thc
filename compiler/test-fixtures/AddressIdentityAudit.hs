-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
{-# LANGUAGE UnboxedTuples #-}

-- |
-- Module      : AddressIdentityAudit
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Compiler fixture for address identity audit Core and metadata.
module AddressIdentityAudit (probe) where

import GHC.Exts

{-# OPAQUE bottomDummy #-}
bottomDummy :: Int
bottomDummy = bottomDummy

-- The dummy must stay lazy. In a non-profiling build getCurrentCCS# returns
-- nullAddr#, which is compared with both null and a real literal allocation.
{-# OPAQUE probe #-}
probe :: Int# -> Int#
probe selector = runRW# (\state ->
  case getCurrentCCS# bottomDummy state of { (# _, current #) ->
  case selector of
    0# -> eqAddr# current nullAddr#
    1# -> neAddr# current nullAddr#
    2# -> eqAddr# current current
    3# -> neAddr# current "A"#
    _  -> eqAddr# current "A"#
  })
