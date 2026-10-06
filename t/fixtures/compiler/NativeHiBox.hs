-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
-- Input: an Int# supplied by NativeHiOracle.
-- Purpose: dispatch on two constructors through polymorphic identity in another native module.
-- Output: -7 for negative input; otherwise 3*(x+2), checked by both backends.
module NativeHiBox (entry) where

import GHC.Exts (Int#, (*#))
import NativeHiBoxType (Box(..), box, identityBox)

{-# OPAQUE entry #-}
entry :: Int# -> Int#
entry x = case identityBox (box x) of
  Empty -> -7#
  Box y -> y *# 3#
