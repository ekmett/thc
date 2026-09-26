-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

module Main (main) where

import Control.Monad.IO.Class (liftIO)
import GHC
import System.Environment (getArgs)
import System.Exit (die)

main :: IO ()
main = do
  args <- getArgs
  case args of
    [libdir, source] -> runGhc (Just libdir) $ do
      flags <- getSessionDynFlags
      _ <- setSessionDynFlags flags {backend = noBackend, ghcLink = NoLink}
      target <- guessTarget source Nothing Nothing
      setTargets [target]
      result <- load LoadAllTargets
      liftIO $ case result of
        Succeeded -> putStrLn "GHC module load/typecheck succeeded"
        Failed -> die "GHC module load/typecheck failed"
    _ -> die "usage: ghc-load GHC_LIBDIR SOURCE.hs"
