-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module TupleCaptureFixtures (prepareTupleCaptures) where

import Control.Monad (forM, unless, when)
import Data.Aeson (Value(..), eitherDecodeStrict', object, (.=))
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BSC
import Data.Foldable (toList)
import Data.List (sort)
import FixtureSupport
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)

walk :: Value -> [Value]
walk value = value : case value of
  Object fields -> concatMap walk (KeyMap.elems fields)
  Array fields -> concatMap walk (toList fields)
  _ -> []

readJson :: FilePath -> IO Value
readJson path = BS.readFile path >>= either die pure . eitherDecodeStrict'

prepareTupleCaptures :: FilePath -> IO ()
prepareTupleCaptures root = do
  let directory = "build/tuple-capture"
      output = root </> directory
      source = "compiler/test-fixtures/TupleCaptureAudit.hs"
      driver = "compiler/test-fixtures/TupleCaptureAuditNative.hs"
      entries = ["escaped", "thunk", "independent", "papReuse", "nested", "emptyCapture",
                 "stateCapture", "lazyCapture"] :: [String]
      logs = directory </> "commands"
      native = directory </> "native"
      manifest = output </> "manifest.json"
  createDirectoryIfMissing True (root </> native)
  stale <- doesFileExist manifest
  when stale (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- runLogged 30 root logs "ghc-version" [] ghc ["--numeric-version"]
  unless (commandStdout version == "9.14.1\n") (die "Tuple captures require GHC 9.14.1")
  compiled <- runLogged 180 root logs "native-build" [] ghc
    ["--make", "-O2", "-dynamic", "-fforce-recomp", "-dcore-lint", "-dstg-lint",
     "-icompiler/test-fixtures", "-odir", native, "-hidir", native, driver, "-o", native </> "oracle"]
  observed <- runLogged 30 root logs "native-run" [] (output </> "native/oracle") []
  unless (length (BSC.lines (commandStdout observed)) == 296) (die "Unexpected tuple capture oracle row count")
  BS.writeFile (output </> "oracle.tsv") (commandStdout observed)
  artifacts <- fmap concat $ forM ["pre", "post"] $ \stage -> do
    let core = directory </> stage </> "core"
        report = directory </> stage </> "audit.json"
    exported <- runLogged 300 root logs (stage ++ "-export")
      [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", output </> stage </> "ghc")]
      "compiler/export.sh" (["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
        ["-fplugin-opt=THC.Plugin:closure=" ++ entry | entry <- entries] ++ [source])
    let path = core </> "TupleCaptureAudit.json"
    nodes <- walk <$> readJson (root </> path)
    let captures = [() | Array parts <- nodes, String "lam" : Array parameters : body : _ <- [toList parts],
          let ids = [identifier | Object parameter <- toList parameters,
                Just identifier <- [KeyMap.lookup "id" parameter]],
          Array occurrence <- walk body,
          String "var" : identifier : Object metadata : _ <- [toList occurrence], identifier `notElem` ids,
          Just (Object proof) <- [KeyMap.lookup "rep" metadata],
          KeyMap.lookup "aggregate" proof == Just (String "unboxed-tuple")]
    unless (length captures >= 8) (die "Original Core lost its whole-tuple closure captures")
    audited <- runLogged 60 root logs (stage ++ "-audit") [] "python3"
      (["scripts/audit-core.py", "--output", report] ++
       concatMap (\entry -> ["--entry", "main:TupleCaptureAudit." ++ entry]) entries ++ [path])
    result <- readJson (root </> report)
    case result of
      Object fields | KeyMap.lookup "accepted" fields == Just (Bool True) -> pure ()
      _ -> die ("Strict tuple capture audit rejected " ++ stage)
    pure (report : path : commandArtifacts exported ++ commandArtifacts audited)
  plugins <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  inputs <- hashes root $ sort $ [source, driver, "thc.cabal", "test/haskell-fixtures/Main.hs",
    "test/haskell-fixtures/TupleCaptureFixtures.hs", "test/haskell-fixtures/FixtureSupport.hs",
    "compiler/build.sh", "compiler/export.sh", "compiler/toolchain.sh", "compiler/plugin.py",
    "scripts/audit-core.py", "scripts/core-capabilities.json", "src/main/resources/thc/scalar-primop-signatures.json"] ++
    ["compiler/THC" </> file | file <- plugins, takeExtension file == ".hs"] ++
    ["scripts" </> file | file <- scripts, take 5 file == "core_" && takeExtension file == ".py"]
  outputs <- hashes root $ [directory </> "oracle.tsv", native </> "oracle"] ++ artifacts ++
    concatMap commandArtifacts [version, compiled, observed]
  writeJson manifest $ object ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
    "entries" .= entries, "nativeRows" .= (296 :: Int), "inputHashes" .= inputs, "artifactHashes" .= outputs]
  putStrLn "tuple-capture: 296 native rows, 8 guest roots, pre/post strict audits accepted"
