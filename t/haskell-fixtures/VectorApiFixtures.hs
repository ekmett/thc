-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
-- |
-- Module      : VectorApiFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC
--
-- Export the readable Vector API examples and run their independent scalar oracle.
module VectorApiFixtures (prepareVectorApi) where

import FixtureSupport (run)
import System.Directory (createDirectoryIfMissing)
import System.Environment (lookupEnv)
import System.FilePath ((</>))

prepareVectorApi :: FilePath -> IO ()
prepareVectorApi root = do
  let directory = "build/vector-api"
      output = root </> directory
      core = output </> "core"
  createDirectoryIfMissing True (output </> "native")
  _ <- run root [("THC_CORE_OUT", core), ("THC_GHC_OUT", output </> "ghc")]
    "bin/export-core.sh" ["-fplugin-opt=THC.Plugin:post-tidy", "src/examples/VectorLoops.hs"] ""
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  _ <- run root [] ghc ["--make", "-O2", "-fforce-recomp", "-outputdir", output </> "native",
    "t/fixtures/compiler/VectorScalar.hs", "-o", output </> "native/oracle"] ""
  rows <- run root [] (output </> "native/oracle") [] ""
  writeFile (output </> "oracle.tsv") rows
  putStrLn "Prepared Vector API Haskell loops and native scalar results"
