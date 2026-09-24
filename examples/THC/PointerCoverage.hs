-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module THC.PointerCoverage (pointerShortcut, pointerLazyPayload) where

import Data.Bits ((.&.))
import GHC.Exts (Int(I#), Int#, reallyUnsafePtrEquality#, (+#), (*#))
import THC.CoverageSupport (neverInt)

-- The second field stays lazy and is deliberately irrelevant to key equality.
data Box = Box Int Int

{-# OPAQUE makeBox #-}
makeBox :: Int -> Int -> Box
makeBox = Box

-- A positive identity result is a sound shortcut. The fallback also accepts
-- separately allocated equal keys, so values never depend on allocation choices.
{-# OPAQUE sameKey #-}
sameKey :: Box -> Box -> Int#
sameKey left right = case reallyUnsafePtrEquality# left right of
  1# -> 1#
  _ -> case left of
    Box x _ -> case right of
      Box y _ -> if x == y then 1# else 0#

pointerShortcut :: Int# -> Int#
pointerShortcut raw =
  let n = I# raw
      shared = makeBox n 0
      equal = makeBox n 1
      other = makeBox (n + (n .&. 1)) 2
  in sameKey shared shared *# 10000000000# +#
     sameKey shared equal *# 17# +# sameKey shared other

pointerLazyPayload :: Int# -> Int#
pointerLazyPayload raw =
  let n = I# raw
      left = makeBox n neverInt
      right = makeBox (n + (n .&. 1)) neverInt
  in sameKey left right *# 3000000007# +# raw
