-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC 9.14.1 compiler API
--
-- Open an installed GHC session and inspect its configured flags.
module Main (main) where

import Control.Monad.IO.Class (liftIO)
import GHC (getSessionDynFlags, runGhc)
import GHC.Driver.Session (verbosity)
import System.Environment (getArgs)
import System.Exit (die)

main :: IO ()
main = do
  args <- getArgs
  case args of
    [libdir] -> runGhc (Just libdir) $ do
      flags <- getSessionDynFlags
      liftIO (putStrLn ("GHC session ready; verbosity=" ++ show (verbosity flags)))
    _ -> die "usage: ghc-session GHC_LIBDIR"
