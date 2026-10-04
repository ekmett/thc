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

import Control.Monad (forM_, when)
import GHC.Exts (Int(I#), Int#)
import System.Environment (getArgs)
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
  , ("scalarCastEntry", T.scalarCastEntry)
  , ("mapAggregate", M.mapAggregate)
  ]

main :: IO ()
main = do
  args <- getArgs
  mainFor entries
  -- Only identity receives machine-width boundaries; recursive corpus entries
  -- keep their existing bounded inputs.
  when (null args) $ forM_ [minBound, -4294967311, -4097, 4097, 4294967311, maxBound] $ \input@(I# n) ->
    putStrLn ("scalarCastEntry\t" ++ show input ++ "\t" ++ show (I# (T.scalarCastEntry n)))
