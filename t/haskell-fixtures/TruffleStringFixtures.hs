-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module TruffleStringFixtures (prepareTruffleStrings) where

import Data.Aeson (object, (.=))
import FixtureSupport (run, hashes, writeJson)
import System.Directory (createDirectoryIfMissing, listDirectory)
import System.Environment (lookupEnv)
import System.FilePath ((</>), takeExtension)

prepareTruffleStrings :: FilePath -> IO ()
prepareTruffleStrings root = do
  let output = root </> "build/truffle-strings"
  createDirectoryIfMissing True (output </> "native")
  _ <- run root [("THC_CORE_OUT", output </> "core"), ("THC_GHC_OUT", output </> "ghc")]
    "bin/export-core.sh" ["-fplugin-opt=THC.Plugin:post-tidy", "src/examples/StringPrimitives.hs",
      "t/fixtures/core/IntrinsicOperands.hs", "t/fixtures/compiler/TruffleStringExceptions.hs"] ""
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  _ <- run root [] ghc ["--make", "-O2", "-fforce-recomp", "-outputdir", output </> "native",
    "t/fixtures/compiler/StringScalar.hs", "-o", output </> "native/oracle"] ""
  result <- run root [] (output </> "native/oracle") [] ""
  writeFile (output </> "oracle.json") result
  compiler <- listDirectory (root </> "src/compiler/THC")
  inputHashes <- hashes root (["src/runtime/THC/Prim.hs", "src/examples/StringPrimitives.hs",
    "t/fixtures/compiler/StringScalar.hs", "t/fixtures/core/IntrinsicOperands.hs",
    "t/fixtures/compiler/TruffleStringExceptions.hs", "src/runtime/THC/Exception.hs", "src/runtime/THC/Internal/Exception.hs",
    "t/haskell-fixtures/TruffleStringFixtures.hs",
    "t/haskell-fixtures/FixtureSupport.hs", "bin/export-core.sh", "bin/build-compiler.sh",
    "bin/toolchain.sh", "bin/plugin.py", "thc.cabal"] ++
    ["src/compiler/THC" </> file | file <- compiler, takeExtension file == ".hs"])
  artifactHashes <- hashes root ["build/truffle-strings" </> file | file <-
    ["core/THC.Prim.cbd", "core/StringPrimitives.cbd", "core/IntrinsicOperands.cbd",
     "core/TruffleStringExceptions.cbd", "core/THC.Exception.cbd", "core/THC.Internal.Exception.cbd",
     "oracle.json", "native/oracle"]]
  writeJson (output </> "manifest.json") $ object
    ["schema" .= (1 :: Int), "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes]
  putStrLn "Prepared immutable TruffleString examples and native code-point oracle"
