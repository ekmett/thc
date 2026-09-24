-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module UncaughtSelfAudit where

import GHC.Exts
import GHC.IO (IO(..))

data Box = Box Int#

-- A self-directed throwTo with no catch# must leave the public entry as a
-- guest exception, never as a bytecode continuation or a successful result.
{-# OPAQUE selfUncaught #-}
selfUncaught :: Int# -> Int#
selfUncaught token =
  case myThreadId# realWorld# of { (# s1, tid #) ->
  case killThread# tid (Box token) s1 of { _ -> token +# 99# } }

-- The erased IO () action exercises the public runIO boundary independently
-- of the scalar execute boundary above.
{-# OPAQUE selfUncaughtIO #-}
selfUncaughtIO :: IO ()
selfUncaughtIO = IO $ \s0 ->
  case myThreadId# s0 of { (# s1, tid #) ->
  case killThread# tid (Box 0#) s1 of { s2 -> (# s2, () #) } }
