-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
-- Retain a real boxed constructor/case boundary behind an opaque function.
-- NativeHiOracle records 3*(x+2), independently checked by both THC backends.
module NativeHiBox (entry) where

import GHC.Exts (Int#, (+#), (*#))

data Box = Box Int#

{-# OPAQUE box #-}
box :: Int# -> Box
box x = Box (x +# 2#)

{-# OPAQUE entry #-}
entry :: Int# -> Int#
entry x = case box x of Box y -> y *# 3#
