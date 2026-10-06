-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
-- Native .hi execution checks private retained RHSs, cross-module calls and
-- recursive scalar cases against NativeHiOracle, without a conversion helper.
module NativeHiScalar (entry, recursive) where

import GHC.Exts (Int#, (+#), (-#), (*#), (<=#))
import NativeHiDependency (marker)

{-# OPAQUE privateWorker #-}
privateWorker :: Int# -> Int#
privateWorker x = x *# 3# +# 1#

{-# OPAQUE entry #-}
entry :: Int# -> Int#
entry x = privateWorker x +# marker x

{-# OPAQUE recursive #-}
recursive :: Int# -> Int#
recursive x = case x <=# 0# of
  1# -> 0#
  _ -> x +# recursive (x -# 1#)
