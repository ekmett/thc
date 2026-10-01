-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- Run in the selected fixture Cabal environment with:
-- cabal exec -- runghc -it/haskell-fixtures -isrc/driver t/haskell-fixtures/InstalledCoreSelectionTests.hs
module Main (main) where

import Control.Exception (bracket)
import Control.Monad (unless)
import InstalledCoreFixtures (installedCoreSource)
import System.Environment (lookupEnv, setEnv, unsetEnv)

main :: IO ()
main = bracket (lookupEnv key) restore $ \_ -> do
  setEnv key "/selected/configured-ghc"
  check "ordinary acquisition retains the explicitly selected source"
    (Just "/selected/configured-ghc") =<< installedCoreSource Nothing
  check "an explicit foreign profile wins over the environment"
    (Just "/explicit/configured-ghc") =<< installedCoreSource (Just "/explicit/configured-ghc")
  unsetEnv key
  check "ordinary acquisition without a source preserves stock selection"
    Nothing =<< installedCoreSource Nothing
  check "an explicit foreign profile does not require environment selection"
    (Just "/explicit/configured-ghc") =<< installedCoreSource (Just "/explicit/configured-ghc")
  putStrLn "PASS: installed Core source selection (4 controls)"
  where
    key = "THC_INSTALLED_CORE_GHC_SOURCE"
    restore Nothing = unsetEnv key
    restore (Just value) = setEnv key value
    check label expected actual = unless (expected == actual) $
      fail (label ++ ": expected " ++ show expected ++ ", got " ++ show actual)
