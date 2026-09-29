-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
-- |
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC 9.14.1 full-Core installation; Cabal 3.16
--
-- Run two genuine Backpack instantiations through Cabal and the public driver.
module BackpackTests (tests) where

import Control.Monad (forM_)
import Data.Aeson (Value(Null))
import qualified Data.ByteString as BS
import Data.List (nub)
import System.Environment (lookupEnv)
import System.FilePath ((</>), takeExtension)
import System.IO (IOMode(ReadMode), withBinaryFile)
import Test.HUnit (Test(..), assertEqual)
import TestSupport

tests :: Env -> Test
tests env = TestLabel "Cabal Backpack keeps two client instantiations distinct" $ TestCase $
  withFixtureNamed env "t/fixtures/run-backpack" "backpack project" $ \project -> do
    ghc <- lookupEnv "THC_INSTALLED_CORE_GHC"
    pkg <- lookupEnv "THC_INSTALLED_CORE_GHC_PKG"
    source <- lookupEnv "THC_INSTALLED_CORE_GHC_SOURCE"
    cabal <- maybe "cabal" id <$> lookupEnv "CABAL"
    let output = scratch env </> "backpack-full-core"
        target = "run-backpack:exe:backpack"
        configuration = ["--offline", "--project-dir", project, "--builddir", output </> "oracle"] ++
          maybe [] (\path -> ["--with-compiler", path]) ghc ++
          maybe [] (\path -> ["--with-hc-pkg", path]) pkg
        command = ["run", target, "--project-dir", project, "--installed-core", "required",
          "--thc-root", thcRoot env, "--runtime", runtime env, "--dist-dir", output </> "guest"] ++
          maybe [] (\path -> ["--with-ghc", path]) ghc ++
          maybe [] (\path -> ["--with-ghc-pkg", path]) pkg ++
          maybe [] (\path -> ["--ghc-source", path]) source
    assertSuccess =<< runExe env project Nothing 180 cabal (["build", target] ++ configuration)
    selected <- runExe env project Nothing 60 cabal (["list-bin", target] ++ configuration)
    assertSuccess selected
    executable <- case lines (out selected) of
      [path] -> pure path
      _ -> fail "Cabal did not return exactly one Backpack executable"
    native <- runExe env project Nothing 60 executable []
    assertSuccess native
    assertEqual "additive, multiplicative, additive model" "(10,21,5)\n" (out native)
    forM_ ["ast", "bytecode"] $ \backend -> do
      guest <- run env project (Just backend) 900 command
      assertSuccess guest
      assertEqual (backend ++ " native/THC") (out native) (out guest)
      assertContains ("\"backend\":\"" ++ backend ++ "\"") (err guest)
    manifest <- readJson (output </> "guest/packages.json")
    forM_ (objects manifest "units") $ \unit -> do
      assertEqual "no JSON runtime payload" Null (field unit "json")
      assertEqual "no text symbol index" Null (field unit "symbols")
      forM_ (objects unit "modules") $ \ref -> do
        let compact = field ref "compact"
            path = string (field compact "path")
        assertEqual "published Core format" "thc-cbd-v1" (string (field compact "format"))
        assertEqual "published Core extension" ".cbd" (takeExtension path)
        signature <- withBinaryFile path ReadMode (`BS.hGet` 4)
        assertEqual "published Core is a CBD archive" (BS.pack [0x50, 0x4b, 0x03, 0x04]) signature
    let clients = [string (field unit "id") | unit <- objects manifest "units",
          any ((== "Client") . string . (`field` "name")) (objects unit "modules")]
    assertEqual "two distinct concrete client owners" 2 (length (nub clients))
    -- Retained build-info for another target must not force its signatures.
    writeText (project </> "client/Number.hsig") "signature Number where\ninvalid signature syntax\n"
    independent <- run env project (Just "bytecode") 900
      (map (\argument -> if argument == target then "run-backpack:exe:independent" else argument) command)
    assertSuccess independent
    assertEqual "unrelated broken signature is not compiled" "independent\n" (out independent)
