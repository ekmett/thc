-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}

-- | A selected, genuinely exported Core entry for the experimental native cache.
-- Arguments remain dynamic; neither export nor cache preparation executes it.
module THC.CachedScalar (affine) where

import GHC.Exts (Int#, (+#), (*#))

-- | Compute @x * scale + offset@ using machine-word arithmetic.
{-# NOINLINE affine #-}
affine :: Int# -> Int# -> Int# -> Int#
affine x scale offset = x *# scale +# offset
