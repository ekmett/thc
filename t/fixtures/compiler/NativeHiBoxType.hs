-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
{-# LANGUAGE UnboxedTuples #-}
-- Keep generated selector calls in importing modules instead of their unfoldings.
{-# OPTIONS_GHC -fomit-interface-pragmas #-}
-- Input: an Int# supplied by NativeHiOracle.
-- Purpose: consume an imported unboxed pair through newtype casts and lazy record fields.
-- Output: Empty for negative input; otherwise a singleton Box with integer and floating-point payload fields.
module NativeHiBoxType (Payload(..), PayloadRef, Box(..), box, payload, unpayload, identity, identityBox) where

import GHC.Exts (Int#, Float#, Double#, (+#), (<#))
import NativeHiScalar (floatingPair)

data Payload = Payload
  { payloadInt :: Int#
  , payloadFloat :: Float#
  , payloadDouble :: Double#
  }
newtype PayloadRef = PayloadRef Payload
data Box a = Empty | Box a (Box a)

-- Keep a suspended call in the lazy field rather than an already built Payload.
{-# OPAQUE payload #-}
payload :: Int# -> PayloadRef
payload x = case floatingPair x of
  (# float, double #) -> PayloadRef (Payload (x +# 2#) float double)

{-# OPAQUE unpayload #-}
unpayload :: PayloadRef -> Payload
unpayload (PayloadRef value) = value

{-# OPAQUE box #-}
box :: Int# -> Box PayloadRef
box x = case x <# 0# of
  1# -> Empty
  _ -> Box (payload x) (box (-1#))

{-# OPAQUE identity #-}
identity :: a -> a
identity x = x

{-# OPAQUE identityBox #-}
identityBox :: Box PayloadRef -> Box PayloadRef
identityBox = identity
