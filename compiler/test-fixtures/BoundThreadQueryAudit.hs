-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module BoundThreadQueryAudit (boundThreadQuery) where

import Control.Concurrent (rtsSupportsBoundThreads)
import GHC.Exts (Int#, (+#))

-- Import the original public declaration. No replacement FFI or local answer.
-- The varying input makes the projection testable even if the pure Bool is a CAF.
{-# NOINLINE boundThreadQuery #-}
boundThreadQuery :: Int# -> Int#
boundThreadQuery input = case rtsSupportsBoundThreads of
  False -> input
  True -> input +# 1#
