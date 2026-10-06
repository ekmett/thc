-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
-- Input: an Int# supplied by NativeHiOracle.
-- Purpose: retain nullary/boxed alternatives and an opaque lifted identity.
-- Output: Empty for negative input; otherwise Box containing the input plus two.
module NativeHiBoxType (Box(..), box, identity, identityBox) where

import GHC.Exts (Int#, (+#), (<#))

data Box = Empty | Box Int#

{-# OPAQUE box #-}
box :: Int# -> Box
box x = case x <# 0# of
  1# -> Empty
  _ -> Box (x +# 2#)

{-# OPAQUE identity #-}
identity :: a -> a
identity x = x

{-# OPAQUE identityBox #-}
identityBox :: Box -> Box
identityBox = identity
