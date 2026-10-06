-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE GADTs #-}
{-# LANGUAGE MagicHash #-}
{-# LANGUAGE UnboxedSums #-}
{-# LANGUAGE UnboxedTuples #-}
-- Input: an Int# supplied by NativeHiOracle.
-- Purpose: consume imported nested tuple/sum payloads through parameterized newtype casts,
-- GADT equality evidence, an unpacked Int field and lazy record selectors.
-- Output: Empty for negative input; otherwise a singleton Box with integer and floating-point payload fields.
module NativeHiBoxType (Payload(..), PayloadRef, Box(..), box, payload, unpayload, identity, identityBox) where

import GHC.Exts (Int(I#), Int#, Float#, Double#, (+#), (<#))
import NativeHiScalar (floatingPair)

data Payload a where
  Payload ::
    { payloadInt :: {-# UNPACK #-} !Int
    , payloadFloat :: Float#
    , payloadDouble :: Double#
    } -> Payload ()
newtype PayloadRef a = PayloadRef a
data Box a = Empty | Box a (Box a)

-- Keep a suspended call in the lazy field rather than an already built Payload.
{-# OPAQUE payload #-}
payload :: Int# -> PayloadRef (Payload ())
payload x = case floatingPair x of
  (# (# float, double #), value #) -> case value of
    (# I# y | #) -> PayloadRef (Payload (I# (y +# 2#)) float double)
    (# | y #) -> PayloadRef (Payload (I# (y +# 2#)) float double)

{-# OPAQUE unpayload #-}
unpayload :: PayloadRef a -> a
unpayload (PayloadRef value) = value

{-# OPAQUE box #-}
box :: Int# -> Box (PayloadRef (Payload ()))
box x = case x <# 0# of
  1# -> Empty
  _ -> Box (payload x) (box (-1#))

{-# OPAQUE identity #-}
identity :: a -> a
identity x = x

{-# OPAQUE identityBox #-}
identityBox :: Box (PayloadRef (Payload ())) -> Box (PayloadRef (Payload ()))
identityBox = identity
