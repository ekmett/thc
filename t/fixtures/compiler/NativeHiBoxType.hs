-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
-- Input: an Int# supplied by NativeHiOracle.
-- Purpose: retain parameterized lazy fields and a recursive boxed field across modules.
-- Output: Empty for negative input; otherwise a singleton Box with integer and floating-point payload fields.
module NativeHiBoxType (Payload(..), Box(..), box, payload, identity, identityBox) where

import GHC.Exts (Int#, Float#, Double#, (+#), (<#))
import NativeHiScalar (floatValue, doubleValue)

data Payload = Payload Int# Float# Double#
data Box a = Empty | Box a (Box a)

-- Keep a suspended call in the lazy field rather than an already built Payload.
{-# OPAQUE payload #-}
payload :: Int# -> Payload
payload x = Payload (x +# 2#) (floatValue x) (doubleValue x)

{-# OPAQUE box #-}
box :: Int# -> Box Payload
box x = case x <# 0# of
  1# -> Empty
  _ -> Box (payload x) (box (-1#))

{-# OPAQUE identity #-}
identity :: a -> a
identity x = x

{-# OPAQUE identityBox #-}
identityBox :: Box Payload -> Box Payload
identityBox = identity
