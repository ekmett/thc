-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module Main where

import Control.Exception (AsyncException(ThreadKilled), SomeException, toException)
import GHC.Exts

-- The native oracle uses a genuine RTS exception object. The separate Core
-- boundary fixture keeps its lifted payload opaque; a host protocol test
-- separately checks that exact payload identity survives uncaught delivery.
selfUncaughtNative :: Int# -> Int#
selfUncaughtNative token =
  case myThreadId# realWorld# of { (# s1, tid #) ->
  case killThread# tid (toException ThreadKilled :: SomeException) s1 of { _ -> token +# 99# } }

main :: IO ()
main = print (I# (selfUncaughtNative 0#))
