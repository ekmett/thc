-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}

-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Native GHC observer for the compact serialized fixture.
module Main where

import CompactSerializedAudit
import Control.Monad (forM_)
import GHC.Exts (Int(..))

main :: IO ()
main = forM_ [("roundTrip", roundTrip), ("cycleRoundTrip", cycleRoundTrip),
  ("multipleBlocks", multipleBlocks), ("emptyRoundTrip", emptyRoundTrip)] $ \(name, action) ->
  forM_ [-31, 0, 17, 4097] $ \input@(I# raw) ->
    putStrLn (name ++ "\t" ++ show input ++ "\t" ++ show (I# (action raw)))
