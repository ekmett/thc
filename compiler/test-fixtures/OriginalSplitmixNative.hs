-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Haskell with the imported library dependencies
--
-- Native GHC observer for the original splitmix fixture.
module Main (main) where

import Control.Monad (replicateM_)
import System.Random.SplitMix (initSMGen, nextWord64)

-- Original public API and original configured C initializer. Random bits are
-- observations, not deterministic cross-runtime expected values.
main :: IO ()
main = replicateM_ 8 (initSMGen >>= print . fst . nextWord64)
