-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
-- Input: an Int# supplied by NativeHiOracle.
-- Purpose: retain a boxed scalar result across an opaque module boundary.
-- Output: Box containing the input plus two.
module NativeHiBoxType (Box(..), box) where

import GHC.Exts (Int#, (+#))

data Box = Box Int#

{-# OPAQUE box #-}
box :: Int# -> Box
box x = Box (x +# 2#)
