-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : THC.Driver.PinnedSetup
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC 9.14.1, Cabal 3.16 and host build tools
--
-- Run the pinned compiler libraries' own configure hooks. Their generated
-- modules come from the selected compiler's settings and upstream generators.
module THC.Driver.PinnedSetup (configurePinnedCustom) where

import Control.Monad (forM_, unless, when)
import qualified Data.Text as Text
import qualified Data.Text.IO as Text
import Distribution.Pretty (prettyShow)
import Distribution.Simple.Utils (cabalVersion)
import System.Directory (copyFile, createDirectoryIfMissing, doesFileExist, listDirectory)
import System.Environment (getEnvironment)
import System.Exit (ExitCode(..))
import System.FilePath ((</>), searchPathSeparator)
import System.IO (stderr)
import System.Process (CreateProcess(..), StdStream(UseHandle), proc)
import THC.Driver.Process (runProducer)

-- | Configure an owned copy of @ghc-boot@ or @ghc@ using its upstream
-- @Setup.hs@. Arguments start with @configure@ and include Cabal's selected
-- compiler, package databases, installed identity and dependency choices.
-- The scratch directory belongs to this acquisition and is retained with it.
configurePinnedCustom :: FilePath -> FilePath -> FilePath -> FilePath -> [String] -> IO ()
configurePinnedCustom release package scratch ghc arguments = do
  unless (take 1 arguments == ["configure"]) (fail "pinned Setup requires configure arguments")
  compiler <- doesFileExist (package </> "ghc.cabal")
  boot <- doesFileExist (package </> "ghc-boot.cabal")
  unless (compiler /= boot) (fail "pinned custom Setup supports ghc and ghc-boot")
  createDirectoryIfMissing True scratch
  upstream <- Text.readFile (package </> "Setup.hs")
  -- GHC's released hooks predate Cabal 3.16's typed paths and temporary-file
  -- API. Adapt those calls only; keep all upstream generation logic intact.
  let substitutions =
        [ ("import System.IO", "import Distribution.Utils.Path (getSymbolicPath)\nimport System.IO")
        , ("takeDirectory <$> pkgDescrFile", "(takeDirectory . getSymbolicPath) <$> pkgDescrFile")
        , ("autogenPackageModulesDir lbi", "getSymbolicPath (autogenPackageModulesDir lbi)")
        , ("buildDir lbi", "getSymbolicPath (buildDir lbi)")
        , ("withTempFile (takeDirectory platformConstantsPath) \"Constants_tmp.hs\" $ \\tmp h -> do\n    hClose h",
           "withTempDirectory verbosity (takeDirectory platformConstantsPath) \"Constants\" $ \\directory -> do\n    let tmp = directory </> \"Constants.hs\"")
        ]
      source = foldl (\text (old, new) -> Text.replace old new text) upstream substitutions
      setup = scratch </> "setup"
      tools = scratch </> "bin"
  Text.writeFile (scratch </> "Setup.hs") source
  execute package Nothing ghc ["--make", "-O0", "-package", "Cabal-" ++ prettyShow cabalVersion,
    "-outputdir", scratch </> "setup-objects", scratch </> "Setup.hs", "-o", setup]
  when compiler $ do
    createDirectoryIfMissing True tools
    forM_ ["genprimopcode", "deriveConstants"] $ \name -> do
      let directory = release </> "utils" </> name
          objects = scratch </> name ++ "-objects"
      createDirectoryIfMissing True objects
      execute directory Nothing ghc ["--make", "-O0", "-i" ++ directory,
        "-outputdir", objects, directory </> "Main.hs", "-o", tools </> name]
    -- These are the unchanged RTS headers copied by upstream configure.ac.
    forM_ [("rts/Bytecodes.h", "Bytecodes.h"),
           ("rts/storage/ClosureTypes.h", "ClosureTypes.h"),
           ("rts/storage/FunTypes.h", "FunTypes.h"),
           ("stg/MachRegs.h", "MachRegs.h")] $ \(from, to) ->
      copyFile (release </> "rts/include" </> from) (package </> to)
    let registers = release </> "rts/include/stg/MachRegs"
    createDirectoryIfMissing True (package </> "MachRegs")
    names <- listDirectory registers
    forM_ names $ \name -> copyFile (registers </> name) (package </> "MachRegs" </> name)
  inherited <- getEnvironment
  let environment = ("PATH", tools ++ [searchPathSeparator] ++ maybe "" id (lookup "PATH" inherited)) :
        filter ((/= "PATH") . fst) inherited
  -- The release contains generated Alex/Happy sources; the two remaining
  -- generators above are invoked directly by Setup's postConf hook.
  execute package (Just environment) setup (arguments ++ ["-f-build-tool-depends" | compiler])

execute :: FilePath -> Maybe [(String, String)] -> FilePath -> [String] -> IO ()
execute directory environment program arguments = do
  status <- runProducer (proc program arguments)
    { cwd = Just directory, env = environment, std_out = UseHandle stderr }
  unless (status == ExitSuccess) (fail ("pinned Setup command failed: " ++ program ++ " " ++ show arguments))
