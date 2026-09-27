-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}
-- |
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC 9.14.1, native 64-bit targets
--
-- Prepare independent native results and audited Core for multiway aggregate
-- transport. Every accepted manifest binds the original constructor proofs,
-- oracle rows, source inputs and exported artifacts.
module FourWayAggregateFixtures (prepareFourWayAggregate, main) where

import Control.Monad (forM, forM_, unless, when)
import Data.Aeson (Value(..), eitherDecodeStrict', object, toJSON, (.=))
import qualified Data.Aeson.Key as Key
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BSC
import Data.Foldable (toList)
import Data.List (sort)
import qualified Data.Map.Strict as Map
import qualified Data.Text as Text
import FixtureSupport
import System.Directory (createDirectoryIfMissing, doesFileExist, getCurrentDirectory,
                         listDirectory, removeFile)
import System.Environment (getArgs, lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)
import Text.Read (readMaybe)

entries :: [String]
entries = ["roundtripPayload", "roundtripTag", "formatTag", "defaultArm",
  "retainedFirst", "retainedSecond", "retainedTags", "residualProducer", "residualConsumer",
  "nestedRoundtrip", "nestedResidualProducer", "nestedResidualConsumer", "nestedHeap",
  "mixedNested", "mixedNestedCapture", "mixedNestedHeap"]

selectors, payloads :: [Integer]
selectors = [-5, -1, 0, 1, 2, 3, 4, 5, 6, 7, 0, 3, 1, 2, 2, 0]
payloads = [0, 1, 4294967295, 4294967296, 9223372036854775808, 18446744073709551615]

qualified :: String -> String
qualified = ("main:FourWayAggregateFields." ++)

field :: Key.Key -> Value -> Maybe Value
field key (Object fields) = KeyMap.lookup key fields
field _ _ = Nothing

arrayField :: Key.Key -> Value -> [Value]
arrayField key value = case field key value of Just (Array values) -> toList values; _ -> []

readJson :: FilePath -> IO Value
readJson path = BS.readFile path >>= either die pure . eitherDecodeStrict'

check :: Bool -> String -> IO ()
check condition message = unless condition (die ("fourway-aggregate: " ++ message))

walk :: Value -> [Value]
walk value = value : case value of
  Object fields -> concatMap walk (KeyMap.elems fields)
  Array values -> concatMap walk (toList values)
  _ -> []

partialCall :: String -> Value -> Bool
partialCall name (Array values) = case toList values of
  String "app" : Array callee : Array arguments : _ ->
    case toList callee of
      String "var" : String target : _ -> target == Text.pack (qualified name) && length arguments == 1
      _ -> False
  _ -> False
partialCall _ _ = False

-- Inspect genuine exporter output. No proof is repaired, synthesized into the
-- module, or reduced to scalar width alone; the complete records are retained.
constructorProofs :: Value -> IO Value
constructorProofs core = do
  original <- case [con | con <- arrayField "constructors" core,
      field "name" con == Just (String "VirtualRegWithFormat")] of
    [con] -> pure con
    _ -> die "fourway-aggregate: missing or ambiguous original constructor"
  let originalId = "ghc-9.14.1-inplace:GHC.CmmToAsm.Format.VirtualRegWithFormat" :: String
      leaf = object ["primReps" .= ["Word64Rep" :: String], "kind" .= ("long" :: String), "evaluated" .= True]
      sumProof = object ["primReps" .= ["WordRep" :: String, "Word64Rep"],
        "kind" .= ("unknown" :: String), "evaluated" .= True,
        "aggregate" .= ("unboxed-sum" :: String), "alternatives" .= replicate 4 leaf,
        "tagSlot" .= (0 :: Int), "alternativeSlots" .= replicate 4 [1 :: Int]]
      formatProof = object ["primReps" .= ["BoxedRep (Just Lifted)" :: String],
        "kind" .= ("data" :: String), "evaluated" .= True]
  forM_ [("id", toJSON originalId), ("kind", String "boxed"), ("arity", toJSON (2 :: Int)),
      ("fieldLifted", toJSON [False, True]), ("strictFields", toJSON [True, True]),
      ("fieldReps", toJSON ([["WordRep", "Word64Rep"], ["BoxedRep (Just Lifted)"]] :: [[String]])),
      ("fieldTypes", toJSON [sumProof, formatProof])] $ \(key, expected) ->
    check (field key original == Just expected) ("changed original worker " ++ Key.toString key)
  let sums = [con | con <- arrayField "constructors" core, field "sumArity" con == Just (toJSON (4 :: Int))]
  check (length sums == 4 && all (\tag -> length [() | con <- sums, field "tag" con == Just (toJSON tag)] == 1)
    ([1..4] :: [Int]))
    "all four original sum constructors must survive"
  forM_ sums $ \con -> check
    (field "kind" con == Just (String "unboxed-sum") && field "arity" con == Just (toJSON (1 :: Int)))
    "changed original sum constructor family"
  let bindings = arrayField "bindings" core
  forM_ (entries ++ ["makeOriginal", "consumeOriginal", "makeRetained", "consumeFormat",
      "consumeDefault", "applyProducer", "consumeWithSalt", "applyConsumer"]) $ \name ->
    check (length [() | binding <- bindings, field "id" binding == Just (toJSON (qualified name))] == 1)
      ("missing retained binding " ++ name)
  forM_ [("residualProducer", "makeOriginal"), ("residualConsumer", "consumeWithSalt"),
      ("nestedResidualProducer", "makeNested"), ("nestedResidualConsumer", "consumeNested"),
      ("mixedNestedCapture", "consumeMixedNested")] $ \(owner, target) -> do
    let bodies = [body | binding <- bindings, field "name" binding == Just (toJSON owner),
                        Just body <- [field "expr" binding]]
    check (any (partialCall target) (concatMap walk bodies)) ("lost partial application in " ++ owner)
  let tupleProof components = object ["kind" .= ("unknown" :: String), "evaluated" .= True,
        "aggregate" .= ("unboxed-tuple" :: String), "components" .= components,
        "primReps" .= concatMap (arrayField "primReps") components]
      integer = object ["kind" .= ("long" :: String), "evaluated" .= True, "primReps" .= ["IntRep" :: String]]
      state = object ["kind" .= ("void" :: String), "evaluated" .= True, "primReps" .= ([] :: [String])]
      lazy = object ["kind" .= ("data" :: String), "evaluated" .= False,
        "primReps" .= ["BoxedRep (Just Lifted)" :: String]]
      mixed = object ["kind" .= ("unknown" :: String), "evaluated" .= True,
        "aggregate" .= ("unboxed-sum" :: String), "primReps" .= ["WordRep" :: String, "BoxedRep (Just Lifted)", "Word64Rep"],
        "alternatives" .= [lazy, leaf, tupleProof [], integer], "tagSlot" .= (0 :: Int),
        "alternativeSlots" .= [[1 :: Int], [2], [], [2]]]
      nested sumField = tupleProof [integer, tupleProof [state, tupleProof [], sumField, lazy], leaf]
  nestedProofs <- forM [("NestedBox", [integer, nested sumProof, leaf]),
      ("MixedBox", [nested mixed, leaf])] $ \(name, expected) -> do
    con <- case [value | value <- arrayField "constructors" core, field "name" value == Just (toJSON (name :: String))] of
      [value] -> pure value
      _ -> die ("fourway-aggregate: missing nested heap constructor " ++ name)
    check (field "fieldTypes" con == Just (toJSON expected)) ("changed nested logical/physical tree " ++ name)
    pure con
  pure (object ["original" .= original, "sumConstructors" .= sums, "nestedConstructors" .= nestedProofs,
    "residualProducer" .= True, "residualConsumer" .= True])

validateAudit :: Value -> IO ()
validateAudit report = do
  check (field "missingGlobals" report == Just (Array mempty)) "strict audit has missing globals"
  check (field "accepted" report == Just (Bool True) &&
    field "issues" report == Just (Array mempty)) "strict audit did not accept genuine four-way Core"

prepareFourWayAggregate :: FilePath -> IO ()
prepareFourWayAggregate root = do
  let directory = "build/fourway-aggregate"
      output = root </> directory
      source = "compiler/test-fixtures/FourWayAggregateFields.hs"
      driver = "compiler/test-fixtures/FourWayAggregateFieldsNative.hs"
      native = directory </> "native"
      binary = native </> "oracle"
      logs = directory </> "commands"
      manifest = output </> "manifest.json"
  createDirectoryIfMissing True (root </> native)
  -- A failed preparation must never leave an accepted manifest for stale Core.
  stale <- doesFileExist manifest
  when stale (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- runLogged 30 root logs "ghc-version" [] ghc ["--numeric-version"]
  check (commandStdout version == "9.14.1\n") "requires GHC 9.14.1"
  info <- runLogged 30 root logs "ghc-info" [] ghc ["--info"]
  case readMaybe (BSC.unpack (commandStdout info)) :: Maybe [(String, String)] of
    Just fields | lookup "target word size" fields == Just "8",
      Just host <- lookup "Host platform" fields,
      Just target <- lookup "Target platform" fields, host == target -> pure ()
    _ -> die "fourway-aggregate: requires native 64-bit GHC"
  compiled <- runLogged 180 root logs "native-build" [] ghc
    ["--make", "-O2", "-dynamic", "-Wall", "-Werror", "-fforce-recomp", "-dcore-lint", "-dstg-lint",
     "-package", "ghc", "-icompiler/test-fixtures", "-odir", native, "-hidir", native, driver, "-o", binary]
  observed <- runLogged 30 root logs "native-run" [] (root </> binary) []
  let parse line = case splitTab line of
        [name, ordinal, selector, bits, result] -> (,,,,) name <$> readInteger ordinal <*>
          readInteger selector <*> readInteger bits <*> readInteger result
        _ -> Nothing
  rows <- maybe (die "fourway-aggregate: malformed native oracle") pure
    (traverse parse (lines (BSC.unpack (commandStdout observed))))
  check ([(name, ordinal, selector, bits) | (name, ordinal, selector, bits, _) <- rows] ==
    [(name, ordinal, selector, bits) | name <- entries, (ordinal, selector) <- zip [0..] selectors, bits <- payloads])
    "native oracle omitted, reordered, or duplicated inputs"
  check (all (\(_, _, _, bits, result) -> bits == result)
    [row | row@(name, _, _, _, _) <- rows, name == "roundtripPayload"])
    "original Unique lost Word64 payload bits"
  check (all (\(_, _, _, _, result) -> result >= 0 && result < 2 ^ (64 :: Int)) rows)
    "native result is not an unsigned Word64"
  BS.writeFile (output </> "oracle.tsv") (commandStdout observed)
  stages <- forM ["pre", "post"] $ \stage -> do
    let core = directory </> stage </> "core"
        modulePath = core </> "FourWayAggregateFields.json"
        reportPath = directory </> stage </> "audit.json"
    exported <- runLogged 300 root logs (stage ++ "-export")
      [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", output </> stage </> "ghc")]
      "compiler/export.sh" (["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
        ["-fplugin-opt=THC.Plugin:closure=" ++ entry | entry <- entries] ++ ["-dstg-lint", "-package", "ghc", source])
    proof <- constructorProofs =<< readJson (root </> modulePath)
    modules <- sort . filter ((== ".json") . takeExtension) <$> listDirectory (root </> core)
    let paths = map (core </>) modules
    audited <- runLogged 60 root logs (stage ++ "-audit") [] "python3"
      (["scripts/audit-core.py", "--output", reportPath] ++
       concatMap (\entry -> ["--entry", qualified entry]) entries ++ paths)
    report <- readJson (root </> reportPath)
    validateAudit report
    pure (stage, proof, field "summary" report,
      reportPath : paths ++ commandArtifacts exported ++ commandArtifacts audited)
  plugins <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  inputHashes <- hashes root $ sort $ [source, driver, "thc.cabal",
    "test/haskell-fixtures/FourWayAggregateFixtures.hs", "test/haskell-fixtures/FixtureSupport.hs",
    "compiler/build.sh", "compiler/export.sh", "compiler/toolchain.sh", "compiler/plugin.py",
    "scripts/audit-core.py", "scripts/core-capabilities.json", "src/main/resources/thc/scalar-primop-signatures.json"] ++
    ["compiler/THC" </> file | file <- plugins, takeExtension file == ".hs"] ++
    ["scripts" </> file | file <- scripts, take 5 file == "core_" && takeExtension file == ".py"]
  artifactHashes <- hashes root $ [directory </> "oracle.tsv", binary] ++
    concat [paths | (_, _, _, paths) <- stages] ++ concatMap commandArtifacts [version, info, compiled, observed]
  writeJson manifest $ object ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
    "wordBits" .= (64 :: Int), "entries" .= entries, "selectors" .= selectors, "payloads" .= map show payloads,
    "nativeRows" .= length rows, "strictAccepted" .= True,
    "constructorProofs" .= Map.fromList [(stage, proof) | (stage, proof, _, _) <- stages],
    "auditSummaries" .= Map.fromList [(stage, summary) | (stage, _, summary, _) <- stages],
    "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes]
  putStrLn ("fourway-aggregate: " ++ show (length rows) ++
    " native rows; genuine pre/post Core; strict audits accepted")

-- | Prepare the native oracle and genuine Core fixtures from the repository root.
main :: IO ()
main = do
  args <- getArgs
  unless (null args) (die "usage: fourway-aggregate-fixtures")
  root <- getCurrentDirectory
  prepareFourWayAggregate root
