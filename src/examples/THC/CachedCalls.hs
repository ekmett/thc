-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}

-- | Ordinary out-of-line Core calls for the experimental selected code cache.
module THC.CachedCalls (affine) where

import GHC.Exts (Int#, (+#), (*#))

{-# NOINLINE scale #-}
scale :: Int# -> Int# -> Int#
scale x factor = x *# factor

{-# NOINLINE offset #-}
offset :: Int# -> Int# -> Int#
offset x amount = x +# amount

-- | Compute @x * factor + amount@ through two ordinary guest functions.
{-# NOINLINE affine #-}
affine :: Int# -> Int# -> Int# -> Int#
affine x factor amount = offset (scale x factor) amount
