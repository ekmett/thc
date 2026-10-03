-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- Fixture rationale (147 narrow-integer-transport)
-- Purpose: Check narrow signed/unsigned values across call, heap and public ABI paths.
-- Produces/consumed result: Pre/post CBD closure and 460 native oracle.tsv rows.
-- Cost and overlap: A useful broad corpus that could absorb tiny literal/array
--   fixtures. Share one native batch; split the SIMD case that forces LLVM on ARM.
-- Build status: QUARANTINED. Producer and consumer enumerate stage CBD directories.
--   Preserve public signedness/range and first-compiled behavior after repair.
-- Detailed inputs/outputs: docs/fixture-inputs.log, entry 147.
{-# LANGUAGE OverloadedStrings #-}
-- |
-- Module      : NarrowIntegerTransportFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC 9.14.1
--
-- Native observations and unmodified pre/post-tidy Core provenance.
module NarrowIntegerTransportFixtures (prepareNarrowIntegerTransport) where
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
import THC.Compact.Module (readModuleValue)
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)
import System.Info (arch)

entries :: [String]
entries = ["fieldsCase","tupleCase","captureCase","papCase","retainedPapCase","sumCase",
  "arithmeticCase","maskedCase","byteArrayCase","vectorCase",
  "loopCase","literalCase","divisionCase","bitcastCase","pinnedCase","atomicCase","nestedCase","publicInt8Case","publicWord8Case","publicInt16Case","publicWord16Case","publicInt32Case","publicWord32Case"]
payloads :: [Integer]
payloads = [-9223372036854775808,-4294967297,-2147483649,-65537,-32769,-129,-1,0,1,127,
  128,255,256,32767,65535,2147483647,2147483648,4294967295,4294967296,9223372036854775807]
check :: Bool -> String -> IO ()
check condition message = unless condition (die ("narrow-integer-transport: " ++ message))
field :: Key.Key -> Value -> Maybe Value
field key (Object value) = KM.lookup key value
field _ _ = Nothing
readJson :: FilePath -> IO Value
readJson path = BS.readFile path >>= either die pure . eitherDecodeStrict'

readCore :: FilePath -> IO Value
readCore path = BS.readFile path >>= either die pure . readModuleValue
walk :: Value -> [Value]
walk value@(Object fields) = value : concatMap walk (KM.elems fields)
walk (Array values) = concatMap walk (toList values)
walk _ = []

prepareNarrowIntegerTransport :: FilePath -> IO ()
prepareNarrowIntegerTransport root = do
  let directory = "build/narrow-integer-transport"
      output = root </> directory
      source = "t/fixtures/compiler/NarrowIntegerTransport.hs"
      driver = "t/fixtures/compiler/NarrowIntegerTransportNative.hs"
      native = directory </> "native"
      binary = native </> "oracle"
      logs = directory </> "commands"
      manifest = output </> "manifest.json"
      -- GHC's AArch64 native backend cannot compile the genuine SIMD case.
      backendFlags = ["-fllvm" | arch `elem` ["aarch64", "arm64"]]
  createDirectoryIfMissing True (root </> native)
  stale <- doesFileExist manifest
  when stale (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- runLogged 30 root logs "ghc-version" [] ghc ["--numeric-version"]
  check (commandStdout version == "9.14.1\n") "requires pinned GHC 9.14.1"
  compiled <- runLogged 180 root logs "native-build" [] ghc
    (backendFlags ++ ["--make","-O2","-dynamic","-Wall","-Werror","-fforce-recomp","-dcore-lint","-dstg-lint",
     "-it/fixtures/compiler","-odir",native,"-hidir",native,driver,"-o",binary])
  observed <- runLogged 30 root logs "native-run" [] (root </> binary) []
  let parse line = case splitTab line of
        [name,bits,result] -> (,,) name <$> readInteger bits <*> readInteger result
        _ -> Nothing
  rows <- maybe (die "narrow-integer-transport: malformed native oracle") pure
    (traverse parse (lines (BSC.unpack (commandStdout observed))))
  check ([(name,bits) | (name,bits,_) <- rows] ==
    [(name,bits) | name <- entries, bits <- payloads]) "native input inventory mismatch"
  BS.writeFile (output </> "oracle.tsv") (commandStdout observed)
  stages <- forM ["pre","post"] $ \stage -> do
    let core = directory </> stage </> "core"
        reportPath = directory </> stage </> "audit.json"
    exported <- runLogged 300 root logs (stage ++ "-export")
      [("THC_CORE_OUT", root </> core),("THC_GHC_OUT", output </> stage </> "ghc")]
      "bin/export-core.sh" (backendFlags ++ ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
        ["-fplugin-opt=THC.Plugin:closure=" ++ entry | entry <- entries] ++ ["-dstg-lint",source])
    original <- readCore (root </> core </> "NarrowIntegerTransport.cbd")
    let proofs = walk original
        six = toJSON (["Int8Rep","Word8Rep","Int16Rep","Word16Rep","Int32Rep","Word32Rep"] :: [String])
        sums = [value | value <- proofs, field "aggregate" value == Just (String "unboxed-sum")]
    check (any (\value -> field "primReps" value == Just six) proofs) "missing original six-width tuple proof"
    check (any (\value -> field "primReps" value == Just (toJSON (["WordRep","WordRep","WordRep"] :: [String])) &&
      field "alternativeSlots" value == Just (toJSON ([[1],[1,2],[1]] :: [[Int]]))) sums)
      "missing original shared WordSlot narrow-integer sum proof"
    modules <- sort . filter ((== ".cbd") . takeExtension) <$> listDirectory (root </> core)
    let paths = map (core </>) modules
    audited <- runLogged 60 root logs (stage ++ "-audit") [] "python3"
      (["bin/audit-core.py","--output",reportPath] ++
       concatMap (\entry -> ["--entry","main:NarrowIntegerTransport." ++ entry]) entries ++ paths)
    report <- readJson (root </> reportPath)
    check (field "accepted" report == Just (Bool True) && field "issues" report == Just (Array mempty) &&
      field "missingGlobals" report == Just (Array mempty)) "strict audit rejected genuine Core"
    pure (stage, reportPath : paths ++ commandArtifacts exported ++ commandArtifacts audited)
  plugins <- listDirectory (root </> "src/compiler/THC")
  scripts <- listDirectory (root </> "bin")
  inputHashes <- hashes root $ sort $ [source,driver,"thc.cabal","t/haskell-fixtures/Main.hs",
    "t/haskell-fixtures/NarrowIntegerTransportFixtures.hs","t/haskell-fixtures/FixtureSupport.hs",
    "bin/build-compiler.sh","bin/export-core.sh","bin/toolchain.sh","bin/plugin.py","bin/audit-core.py",
    "bin/core-capabilities.json","src/main/resources/thc/scalar-primop-signatures.json"] ++
    ["src/compiler/THC" </> file | file <- plugins, takeExtension file == ".hs"] ++
    ["bin" </> file | file <- scripts, take 5 file == "core_" && takeExtension file == ".py"]
  artifactHashes <- hashes root $ [directory </> "oracle.tsv",binary] ++ concatMap snd stages ++
    concatMap commandArtifacts [version,compiled,observed]
  writeJson manifest $ object ["schema" .= (1 :: Int),"ghc" .= ("9.14.1" :: String),
    "entries" .= entries,"payloads" .= map show payloads,
    "nativeRows" .= length rows,"strictAccepted" .= True,"stages" .= Map.fromList stages,
    "inputHashes" .= inputHashes,"artifactHashes" .= artifactHashes]
  putStrLn ("narrow-integer-transport: " ++ show (length rows) ++ " native rows; genuine pre/post Core accepted")
