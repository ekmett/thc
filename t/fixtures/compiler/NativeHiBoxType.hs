-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
-- Input: an Int# supplied by NativeHiOracle.
-- Purpose: retain parameterized lazy fields, constructor alternatives and an opaque identity.
-- Output: Empty for negative input; otherwise Box containing a Payload of the input plus two.
module NativeHiBoxType (Payload(..), Box(..), box, identity, identityBox) where

import GHC.Exts (Int#, (+#), (<#))

data Payload = Payload Int#
data Box a = Empty | Box a

-- Keep a suspended call in the lazy field rather than an already built Payload.
{-# OPAQUE payload #-}
payload :: Int# -> Payload
payload x = Payload (x +# 2#)

{-# OPAQUE box #-}
box :: Int# -> Box Payload
box x = case x <# 0# of
  1# -> Empty
  _ -> Box (payload x)

{-# OPAQUE identity #-}
identity :: a -> a
identity x = x

{-# OPAQUE identityBox #-}
identityBox :: Box Payload -> Box Payload
identityBox = identity
