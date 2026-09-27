-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}
module FourWayAggregateFixtures (AuditExpectation(..), prepareFourWayAggregate, main) where

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

-- Preparation can record the genuine current unsupported boundary, but cannot
-- publish an accepted fixture manifest for it. Normal registration must use
-- RequireAccepted after the runtime and auditor implement the complete layout.
data AuditExpectation = RequireAccepted | ExpectFourWayRejection deriving (Eq)

entries :: [String]
entries = ["roundtripPayload", "roundtripTag", "formatTag", "defaultArm",
  "retainedFirst", "retainedSecond", "retainedTags", "residualProducer", "residualConsumer"]

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
  forM_ [("residualProducer", "makeOriginal"), ("residualConsumer", "consumeWithSalt")] $ \(owner, target) -> do
    let bodies = [body | binding <- bindings, field "name" binding == Just (toJSON owner),
                        Just body <- [field "expr" binding]]
    check (any (partialCall target) (concatMap walk bodies)) ("lost partial application in " ++ owner)
  pure (object ["original" .= original, "sumConstructors" .= sums,
    "residualProducer" .= True, "residualConsumer" .= True])

validateAudit :: AuditExpectation -> Value -> IO ()
validateAudit expectation report = do
  check (field "missingGlobals" report == Just (Array mempty)) "strict audit has missing globals"
  case expectation of
    RequireAccepted -> check (field "accepted" report == Just (Bool True) &&
      field "issues" report == Just (Array mempty)) "strict audit did not accept genuine four-way Core"
    ExpectFourWayRejection -> do
      let issues = arrayField "issues" report
          known issue = (field "code" issue, field "detail" issue) `elem` map
            (\(code, detail) -> (Just (String code), Just (String detail)))
            [("aggregate-representation", "unboxed-sum: Unboxed sum requires two exact alternatives"),
             ("aggregate-representation", "unboxed-sum: exact instantiated constructor result required"),
             ("aggregate-boundary", "unboxed-sum heap field"),
             ("aggregate-boundary", "unboxed-sum argument"),
             ("aggregate-shape", "Sum constructor family or payload arity mismatch"),
             ("constructor-arity", "Sum constructor family or payload arity mismatch"),
             ("constructor-field-representation",
              "ghc-9.14.1-inplace:GHC.CmmToAsm.Format.VirtualRegWithFormat[0]: ['WordRep', 'Word64Rep']")]
      check (field "accepted" report == Just (Bool False) && not (null issues) && all known issues)
        "rejection is not solely the known four-way aggregate boundary"
      check (any ((== Just (String "unboxed-sum: Unboxed sum requires two exact alternatives")) . field "detail") issues)
        "expected original binary-only sum rejection was not observed"

prepareFourWayAggregate :: FilePath -> AuditExpectation -> IO ()
prepareFourWayAggregate root expectation = do
  let directory = "build/fourway-aggregate"
      output = root </> directory
      source = "compiler/test-fixtures/FourWayAggregateFields.hs"
      driver = "compiler/test-fixtures/FourWayAggregateFieldsNative.hs"
      native = directory </> "native"
      binary = native </> "oracle"
      logs = directory </> "commands"
      accepted = expectation == RequireAccepted
      manifest = output </> if accepted then "manifest.json" else "rejection-manifest.json"
  createDirectoryIfMissing True (root </> native)
  -- A failed preparation must never leave an accepted manifest for stale Core.
  forM_ [output </> "manifest.json", output </> "rejection-manifest.json"] $ \path -> do
    stale <- doesFileExist path
    when stale (removeFile path)
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
    audited <- runLoggedExpect (if accepted then 0 else 1) 60 root logs (stage ++ "-audit") [] "python3"
      (["scripts/audit-core.py", "--output", reportPath] ++
       concatMap (\entry -> ["--entry", qualified entry]) entries ++ paths)
    report <- readJson (root </> reportPath)
    validateAudit expectation report
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
    "wordBits" .= (64 :: Int), "entries" .= entries, "selectors" .= selectors, "payloads" .= payloads,
    "nativeRows" .= length rows, "strictAccepted" .= accepted,
    "constructorProofs" .= Map.fromList [(stage, proof) | (stage, proof, _, _) <- stages],
    "auditSummaries" .= Map.fromList [(stage, summary) | (stage, _, summary, _) <- stages],
    "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes]
  putStrLn ("fourway-aggregate: " ++ show (length rows) ++ " native rows; genuine pre/post Core; " ++
    if accepted then "strict audits accepted" else "known rejection retained; NOT runtime acceptance")

-- Standalone entry keeps preparation usable before shared fixture registration.
main :: IO ()
main = do
  args <- getArgs
  expectation <- case args of
    [] -> pure RequireAccepted
    ["--expect-rejection"] -> pure ExpectFourWayRejection
    _ -> die "usage: fourway-aggregate-fixtures [--expect-rejection]"
  root <- getCurrentDirectory
  prepareFourWayAggregate root expectation
