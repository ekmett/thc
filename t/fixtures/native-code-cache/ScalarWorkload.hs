-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}

-- | A selected, genuinely exported Core entry for code-cache tests.
-- Arguments remain dynamic; neither export nor cache preparation executes it.
module ScalarWorkload (affine) where

import GHC.Exts (Int#, (+#), (*#))

-- | Compute @x * scale + offset@ using machine-word arithmetic.
{-# NOINLINE affine #-}
affine :: Int# -> Int# -> Int# -> Int#
affine x scale offset = x *# scale +# offset
