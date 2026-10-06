-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
-- Input: an Int# supplied by NativeHiOracle.
-- Purpose: case an opaque boxed result defined in another native module.
-- Output: 3*(x+2), independently checked by both THC backends.
module NativeHiBox (entry) where

import GHC.Exts (Int#, (*#))
import NativeHiBoxType (Box(..), box)

{-# OPAQUE entry #-}
entry :: Int# -> Int#
entry x = case box x of Box y -> y *# 3#
