-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE BangPatterns, MagicHash #-}

-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Native GHC observer and timing driver for the example entry points.
module Main (main) where

import GHC.Exts (Int#)
import NativeTiming (mainFor)
import qualified Fixtures as T
import qualified MapWorkload as M

entries :: [(String, Int# -> Int#)]
entries =
  [ ("sumLoop", T.sumLoop), ("fib", T.fib), ("captured", T.captured)
  , ("exact", T.exact), ("under", T.under), ("over", T.over)
  , ("unknown", T.unknown), ("shared", T.shared)
  , ("lazyArgument", T.lazyArgument), ("lazyField", T.lazyField)
  , ("recursiveCaf", T.recursiveCaf), ("caseList", T.caseList)
  , ("multiModule", T.multiModule)
  , ("cacheSaturation", T.cacheSaturation), ("mutualTail", T.mutualTail)
  , ("selfMutualTail", T.selfMutualTail)
  , ("capturedChangingEnv", T.capturedChangingEnv)
  , ("localMutualClosures", T.localMutualClosures)
  , ("nestedCaptureThunk", T.nestedCaptureThunk)
  , ("mapAggregate", M.mapAggregate)
  ]

main :: IO ()
main = mainFor entries
