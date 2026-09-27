-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
-- |
-- Module      : GenericSumTransportFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC 9.14.1
--
-- Native observations and unmodified pre/post-tidy Core provenance.
module GenericSumTransportFixtures (prepareGenericSumTransport) where
import Control.Monad (forM, unless, when)
import Data.Aeson (Value(..), object, (.=), eitherDecodeStrict', toJSON)
import qualified Data.Aeson.Key as Key
import qualified Data.Aeson.KeyMap as KM
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BSC
import qualified Data.Map.Strict as Map
import Data.List (sort)
import Data.Foldable (toList)
import FixtureSupport
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)

entries :: [String]
entries = ["addressCase","vectorCase","vectorResultCase","nestedCase","aroundCase","heapCase","captureCase","residualCase",
  "papCase","pairedCase"]
payloads :: [Integer]
payloads = [-9223372036854775808,-4294967297,-129,-1,0,1,127,4294967296,9223372036854775807]
check :: Bool -> String -> IO ()
check condition message = unless condition (die ("generic-sum-transport: " ++ message))
field :: Key.Key -> Value -> Maybe Value
field key (Object value) = KM.lookup key value
field _ _ = Nothing
readJson :: FilePath -> IO Value
readJson path = BS.readFile path >>= either die pure . eitherDecodeStrict'
walk :: Value -> [Value]
walk value@(Object fields) = value : concatMap walk (KM.elems fields)
walk (Array values) = concatMap walk (toList values)
walk _ = []

prepareGenericSumTransport :: FilePath -> IO ()
prepareGenericSumTransport root = do
  let directory = "build/generic-sum-transport"
      output = root </> directory
      source = "compiler/test-fixtures/GenericSumTransport.hs"
      driver = "compiler/test-fixtures/GenericSumTransportNative.hs"
      native = directory </> "native"
      binary = native </> "oracle"
      logs = directory </> "commands"
      manifest = output </> "manifest.json"
  createDirectoryIfMissing True (root </> native)
  stale <- doesFileExist manifest
  when stale (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- runLogged 30 root logs "ghc-version" [] ghc ["--numeric-version"]
  check (commandStdout version == "9.14.1\n") "requires pinned GHC 9.14.1"
  compiled <- runLogged 180 root logs "native-build" [] ghc
    ["--make","-O2","-dynamic","-Wall","-Werror","-fforce-recomp","-dcore-lint","-dstg-lint",
     "-icompiler/test-fixtures","-odir",native,"-hidir",native,driver,"-o",binary]
  observed <- runLogged 30 root logs "native-run" [] (root </> binary) []
  let parse line = case splitTab line of
        [name,selector,bits,result] -> (,,,) name <$> readInteger selector <*> readInteger bits <*> readInteger result
        _ -> Nothing
  rows <- maybe (die "generic-sum-transport: malformed native oracle") pure
    (traverse parse (lines (BSC.unpack (commandStdout observed))))
  check ([(name,selector,bits) | (name,selector,bits,_) <- rows] ==
    [(name,selector,bits) | name <- entries, selector <- [0..15], bits <- payloads]) "native input inventory mismatch"
  BS.writeFile (output </> "oracle.tsv") (commandStdout observed)
  stages <- forM ["pre","post"] $ \stage -> do
    let core = directory </> stage </> "core"
        reportPath = directory </> stage </> "audit.json"
    exported <- runLogged 300 root logs (stage ++ "-export")
      [("THC_CORE_OUT", root </> core),("THC_GHC_OUT", output </> stage </> "ghc")]
      "compiler/export.sh" (["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
        ["-fplugin-opt=THC.Plugin:closure=" ++ entry | entry <- entries] ++ ["-dstg-lint",source])
    original <- readJson (root </> core </> "GenericSumTransport.json")
    let sums = [value | value <- walk original, field "aggregate" value == Just (String "unboxed-sum")]
        exact reps projections = any (\value -> field "primReps" value == Just reps &&
          field "alternativeSlots" value == Just projections) sums
    check (exact (toJSON (["WordRep","WordRep"] :: [String])) (toJSON ([[1],[1]] :: [[Int]])))
      "missing original address/integer shared WordSlot proof"
    check (exact (toJSON (["WordRep","VecRep 2 Int64ElemRep","VecRep 4 FloatElemRep"] :: [String]))
      (toJSON ([[1],[2],[]] :: [[Int]]))) "missing exact original vector slot proof"
    check (exact (toJSON (["WordRep","BoxedRep (Just Lifted)","WordRep","WordRep","WordRep",
      "VecRep 2 Int64ElemRep","VecRep 4 FloatElemRep"] :: [String]))
      (toJSON ([[2],[2,3,4],[2,5,6],[2,1,3]] :: [[Int]]))) "missing exact nested original slot/projection tree"
    modules <- sort . filter ((== ".json") . takeExtension) <$> listDirectory (root </> core)
    let paths = map (core </>) modules
    audited <- runLogged 60 root logs (stage ++ "-audit") [] "python3"
      (["scripts/audit-core.py","--output",reportPath] ++
       concatMap (\entry -> ["--entry","main:GenericSumTransport." ++ entry]) entries ++ paths)
    report <- readJson (root </> reportPath)
    check (field "accepted" report == Just (Bool True) && field "issues" report == Just (Array mempty) &&
      field "missingGlobals" report == Just (Array mempty)) "strict audit rejected genuine Core"
    pure (stage, reportPath : paths ++ commandArtifacts exported ++ commandArtifacts audited)
  plugins <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  inputHashes <- hashes root $ sort $ [source,driver,"thc.cabal","test/haskell-fixtures/Main.hs",
    "test/haskell-fixtures/GenericSumTransportFixtures.hs","test/haskell-fixtures/FixtureSupport.hs",
    "compiler/build.sh","compiler/export.sh","compiler/toolchain.sh","compiler/plugin.py","scripts/audit-core.py",
    "scripts/core-capabilities.json","src/main/resources/thc/scalar-primop-signatures.json"] ++
    ["compiler/THC" </> file | file <- plugins, takeExtension file == ".hs"] ++
    ["scripts" </> file | file <- scripts, take 5 file == "core_" && takeExtension file == ".py"]
  artifactHashes <- hashes root $ [directory </> "oracle.tsv",binary] ++ concatMap snd stages ++
    concatMap commandArtifacts [version,compiled,observed]
  writeJson manifest $ object ["schema" .= (1 :: Int),"ghc" .= ("9.14.1" :: String),
    "entries" .= entries,"selectors" .= ([0..15] :: [Int]),"payloads" .= map show payloads,
    "nativeRows" .= length rows,"strictAccepted" .= True,"stages" .= Map.fromList stages,
    "inputHashes" .= inputHashes,"artifactHashes" .= artifactHashes]
  putStrLn ("generic-sum-transport: " ++ show (length rows) ++ " native rows; genuine pre/post Core accepted")
