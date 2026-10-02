-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : PublicPackageTests
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; HUnit and driver test dependencies
--
-- Compare public package operations with native GHC.
module PublicPackageTests (tests) where

import Control.Monad (forM_)
import System.Environment (lookupEnv)
import System.FilePath ((</>))
import Test.HUnit (Test (..), assertEqual)
import TestSupport

tests :: Env -> Test
tests environment = TestLabel "public package arithmetic, text and memory" $ TestCase $ do
  installedCore <- maybe "pinned" id <$> lookupEnv "THC_TEST_INSTALLED_CORE"
  installedGhc <- lookupEnv "THC_INSTALLED_CORE_GHC"
  installedPkg <- lookupEnv "THC_INSTALLED_CORE_GHC_PKG"
  ghcSource <- lookupEnv "THC_INSTALLED_CORE_GHC_SOURCE"
  requireFile (runtime environment)
  let project = root environment </> "t/fixtures/run-library-memory"
      output = scratch environment </> "public-packages"
      manifest = output </> "packages.json"
      entry = "main::Main.main"
      shutdown = "ghc-internal:GHC.Internal.TopHandler.flushStdHandles"
      command = ["build", "--project-dir", project, "exe:library-memory", "--installed-core", installedCore,
        "--thc-root", thcRoot environment, "--dist-dir", output] ++
        maybe [] (\path -> ["--with-ghc", path]) installedGhc ++
        maybe [] (\path -> ["--with-ghc-pkg", path]) installedPkg ++
        maybe [] (\path -> ["--ghc-source", path]) ghcSource
      expected = unlines
        [ "([2,3,5,7],[2,88,5,7])"
        , "[65,66]"
        -- 2^64 + 64 carries into a second limb. Truncating the negative
        -- square plus 65 gives quotient -(2^64 + 64) and remainder -65.
        , "(18446744073709551680,(-18446744073709551680,-65),18446744073709551680)"
        -- Conversion keeps the low 64 bits; Int interprets the top bit as sign.
        , "([9223372036854775807,-9223372036854775808,-1,0,9223372036854775807,-9223372036854775808,65,-65],[0,18446744073709551615,0,65])"
        , "([233,128512,955,66,65],[128512,955,66],True)"
        ]
  prepared <- runPreparation environment project command
  assertSuccess prepared
  assertNoStdout prepared
  plan <- readJson (output </> "native/cache/plan.json")
  let component = case filter (\candidate ->
        string (field candidate "pkg-name") == "run-library-memory" &&
        string (field candidate "component-name") == "exe:library-memory") (objects plan "install-plan") of
          [candidate] -> candidate
          _ -> error "expected one library-memory executable"
      executable = string (field component "bin-file")
  native <- runExe environment project Nothing 60 executable []
  assertSuccess native
  assertEqual "native model" expected (out native)
  forM_ ["ast", "bytecode"] $ \backend -> forM_ [False, True] $ \dense -> do
    result <- runExe environment project (Just backend) 180 "env"
      ["THC_OPTS=-Dthc.handoffSlabs=" ++ if dense then "true" else "false", runtime environment,
       "--verify-artifacts", "--run-executable", '@' : manifest, entry, shutdown, "--", "library-memory"]
    assertSuccess result
    assertEqual (backend ++ "/dense=" ++ show dense) (out native) (out result)
