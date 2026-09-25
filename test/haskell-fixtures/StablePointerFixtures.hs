-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}
module StablePointerFixtures (prepareStablePointers) where

import Control.Monad (forM, unless, when)
import Data.Aeson (Value(..), eitherDecode, object, toJSON, (.=))
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString.Lazy as BL
import Data.Foldable (toList)
import Data.List (sort)
import qualified Data.Map.Strict as Map
import qualified Data.Set as Set
import Data.String (fromString)
import qualified Data.Text as Text
import FixtureSupport (hashes, readInteger, run, runWithTimeout, writeJson)
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)

source, nativeSource, certificate, directory :: FilePath
source = "compiler/test-fixtures/StablePointerAudit.hs"
nativeSource = "compiler/test-fixtures/SharedCAFNative.hs"
certificate = "compiler/test-fixtures/SharedCAFOriginalCalls.json"
directory = "build/stable-pointers"

baseEntries, sharedEntries, entries :: [String]
baseEntries = ["stableComposite", "lazyStable"]
sharedEntries = ["sharedEventManagerStore", "sharedSignalHandlerStore"]
entries = baseEntries ++ sharedEntries

field :: String -> Value -> Value
field key (Object fields) = maybe (error ("Missing shared-CAF certificate field " ++ key)) id
  (KeyMap.lookup (fromString key) fields)
field key _ = error ("Expected shared-CAF certificate object for " ++ key)

items :: Value -> [Value]
items (Array values) = toList values
items _ = error "Expected shared-CAF certificate array"

sharedCall :: Value -> Maybe (String, String)
sharedCall value = case items value of
  [String "app", _, _, _, _, _, Object metadata] -> case KeyMap.lookup "foreignCall" metadata of
    Just (Object call) ->
      let target = field "target" (Object call)
      in case field "symbol" target of
        String symbol | Text.unpack symbol `elem` sharedSymbols -> case field "unit" target of
          String unit -> Just (Text.unpack symbol, Text.unpack unit)
          _ -> Nothing
        _ -> Nothing
    _ -> Nothing
  _ -> Nothing

foreignSymbol :: Value -> Maybe String
foreignSymbol = fmap fst . sharedCall

sharedSymbols :: [String]
sharedSymbols = ["getOrSetSystemEventThreadEventManagerStore",
  "getOrSetGHCConcSignalSignalHandlerStore"]

-- Preserve the original FCallId head and descriptor byte-for-byte as JSON
-- values. Only the home-unit test consumer's operands and surrounding Core
-- remain synthetic; no original GHC body is claimed here.
adaptShared :: Map.Map String Value -> Value -> Value
adaptShared originals value = case value of
  Array values -> case toList values of
    app@[String "app", _, arguments, flags, tailCall, joinCall, metadata]
      | Just symbol <- foreignSymbol (toJSON app), Just original <- Map.lookup symbol originals ->
          case items original of
            [String "app", headId, originalArguments, originalFlags, _, _, originalMetadata] ->
              let supplied = field "foreignCall" metadata
                  expected = field "foreignCall" originalMetadata
                  sourceTarget = field "target" supplied
                  expectedTarget = field "target" expected
                  adjusted = case (supplied, sourceTarget) of
                    (Object call, Object target) -> Object (KeyMap.insert "target"
                      (Object (KeyMap.insert "unit" (String "ghc-internal") target)) call)
                    _ -> error "Malformed home-unit shared-CAF FCall"
              in if adjusted /= expected || flags /= originalFlags ||
                    field "rep" metadata /= field "rep" originalMetadata ||
                    length (items arguments) /= length (items originalArguments) ||
                    any (\(left, right) -> let a = field "rep" (last (items left))
                                               b = field "rep" (last (items right))
                                           in field "kind" a /= field "kind" b || field "primReps" a /= field "primReps" b)
                      (zip (items arguments) (items originalArguments)) ||
                    field "unit" sourceTarget /= String "main" ||
                    field "unit" expectedTarget /= String "ghc-internal"
                 then error "Home-unit consumer disagrees with selected original GHC FCall"
                 else toJSON [String "app", headId, adaptShared originals arguments, flags,
                   tailCall, joinCall, originalMetadata]
            _ -> error "Malformed selected original shared-CAF application"
    _ -> toJSON (map (adaptShared originals) (toList values))
  Object fields -> Object (KeyMap.map (adaptShared originals) fields)
  _ -> value

sharedCalls :: Value -> [(String, String)]
sharedCalls value = case value of
  Array values -> maybe id (:) (sharedCall value) (concatMap sharedCalls (toList values))
  Object fields -> concatMap sharedCalls (KeyMap.elems fields)
  _ -> []

originalCalls :: Value -> Map.Map String Value
originalCalls certificateValue =
  let records = items (field "calls" certificateValue)
      pair record = case field "symbol" record of
        String symbol -> (Text.unpack symbol, field "app" record)
        _ -> error "Selected original shared-CAF symbol is missing"
      found = Map.fromList (map pair records)
      expected = Map.fromList
        [("getOrSetGHCConcSignalSignalHandlerStore",
          ("GHC.Internal.Conc.Signal", "ghc-internal:GHC.Internal.Conc.Signal.signal_handlers",
           "core/22.json", "c1de1c17d7ac3f953cb2a3c9934f8c11eaac5afe07bebf7ca772f26dcd7baf9b"))
        ,("getOrSetSystemEventThreadEventManagerStore",
          ("GHC.Internal.Event.Thread", "ghc-internal:GHC.Internal.Event.Thread.lvl4_i19S",
           "core/97.json", "550ad0c3c4e62544bac91dad31aea1950c792d9d88b1e4dc0fa0c242ece7bb11"))]
      valid record = case field "symbol" record of
        String symbol -> case Map.lookup (Text.unpack symbol) expected of
          Just (ownerModule, ownerBinding, member, memberHash) ->
            field "module" record == String ownerModule &&
            field "owner" record == String ownerBinding &&
            field "member" record == String member &&
            field "memberSha256" record == String memberHash &&
            foreignSymbol (field "app" record) == Just (Text.unpack symbol) &&
            case items (field "app" record) of
              [String "app", headId, _, _, _, _, metadata] ->
                case items headId of
                  [String "var", String name, _] -> "ghc-internal:" `Text.isPrefixOf` name &&
                    field "unit" (field "target" (field "foreignCall" metadata)) == String "ghc-internal"
                  _ -> False
              _ -> False
          Nothing -> False
        _ -> False
  in if field "schema" certificateValue == toJSON (1 :: Int) &&
        field "ghc" certificateValue == String "9.14.1" &&
        length records == length sharedSymbols &&
        Map.keysSet found == Map.keysSet expected && all valid records
     then found else error "Selected GHC shared-CAF FCall certificate changed"

values :: [Integer]
values = [negate (2 ^ (63 :: Int)), -4097, -1, 0, 1, 42, 4097, 2 ^ (63 :: Int) - 1]

nativeDriver :: String
nativeDriver = unlines $
  ["{-# LANGUAGE MagicHash #-}", "module Main where",
   "import GHC.Exts (Int(I#), Int#)", "import qualified StablePointerAudit as P",
   "import qualified SharedCAFNative as N",
   "emit :: String -> (Int# -> Int#) -> Int -> IO ()",
   "emit name function input@(I# raw) = putStrLn (name ++ \"\\t\" ++ show input ++ \"\\t\" ++ show (I# (function raw)))",
   "dispatch :: [String] -> IO ()", "dispatch [name, input] = case name of",
   "  \"stableComposite\" -> emit name P.stableComposite (read input)",
   "  \"lazyStable\" -> emit name P.lazyStable (read input)",
   "  \"sharedEventManagerStore\" -> emit name N.sharedEventManagerStore (read input)",
   "  \"sharedSignalHandlerStore\" -> emit name N.sharedSignalHandlerStore (read input)",
   "  _ -> error \"unknown StablePtr entry\"",
   "dispatch _ = error \"invalid StablePtr row\"",
   "main :: IO ()", "main = getContents >>= mapM_ (dispatch . words) . lines"]

prepareStablePointers :: FilePath -> IO ()
prepareStablePointers root = do
  let output = root </> directory
      manifest = output </> "manifest.json"
  createDirectoryIfMissing True output
  old <- doesFileExist manifest
  when old (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run root [] ghc ["--numeric-version"] ""
  unless (version == "9.14.1\n") (die "StablePtr fixture requires GHC 9.14.1")
  certificateJSON <- either (die . ("Invalid selected GHC FCall certificate: " ++)) pure . eitherDecode
    =<< BL.readFile (root </> certificate)
  let pinnedCalls = originalCalls certificateJSON
  stages <- forM ["pre", "post"] $ \stage -> do
    let stageDir = directory </> stage
        core = stageDir </> "core"
        modules = [core </> "StablePointerAudit.json", core </> "THC.InterfaceClosure.json"]
        sharedCore = stageDir </> "shared-core"
        sharedSource = sharedCore </> "SharedCAFNative.json"
        synthetic = stageDir </> "synthetic/SharedCAFNative.json"
        sharedModules = [synthetic, sharedCore </> "THC.InterfaceClosure.json"]
        postTidy = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"]
        roots names = ["-fplugin-opt=THC.Plugin:closure=" ++ name | name <- names]
    _ <- run root [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", root </> stageDir </> "ghc")]
      "compiler/export.sh" (postTidy ++ roots baseEntries ++ [source]) ""
    mapM_ (\path -> do
      present <- doesFileExist (root </> path)
      unless present (die ("Missing genuine StablePtr Core export: " ++ path))) modules
    exported <- sort . filter ((== ".json") . takeExtension) <$> listDirectory (root </> core)
    unless (exported == ["StablePointerAudit.json", "THC.InterfaceClosure.json"]) $
      die ("Unexpected StablePtr Core module inventory: " ++ show exported)
    _ <- run root [("THC_CORE_OUT", root </> sharedCore), ("THC_GHC_OUT", root </> stageDir </> "shared-ghc")]
      "compiler/export.sh" (postTidy ++ roots sharedEntries ++ [nativeSource]) ""
    exportedShared <- sort . filter ((== ".json") . takeExtension) <$> listDirectory (root </> sharedCore)
    unless (exportedShared == ["SharedCAFNative.json", "THC.InterfaceClosure.json"]) $
      die ("Unexpected shared-CAF Core module inventory: " ++ show exportedShared)
    sharedJSON <- either (die . ("Invalid GHC-compiled shared-CAF consumer: " ++)) pure . eitherDecode
      =<< BL.readFile (root </> sharedSource)
    let found = filter ((`elem` sharedSymbols) . fst) (sharedCalls sharedJSON)
    unless (Set.fromList (map fst found) == Set.fromList sharedSymbols && length found >= 2 &&
            all ((== "main") . snd) found) $
      die ("Missing home-unit shared-CAF callsites: " ++ show found)
    let adapted = adaptShared pinnedCalls sharedJSON
        adaptedCalls = filter ((`elem` sharedSymbols) . fst) (sharedCalls adapted)
    unless (sort adaptedCalls == sort [(symbol, "ghc-internal") | (symbol, _) <- found]) $
      die "Synthetic shared-CAF consumer changed the call inventory or target units"
    createDirectoryIfMissing True (root </> stageDir </> "synthetic")
    writeJson (root </> synthetic) adapted
    _ <- forM entries $ \name -> do
      let report = stageDir </> name ++ ".audit.json"
          inputs = if name `elem` sharedEntries then sharedModules else modules
      _ <- run root [] "python3" (["scripts/audit-core.py", "--entry", name, "--output", report] ++ inputs) ""
      pure ()
    pure (stage, modules, sharedSource : sharedModules)
  let native = directory </> "native"
      driver = directory </> "NativeStablePointer.hs"
      oracle = directory </> "oracle.tsv"
      executable = root </> native </> "stable-pointer-oracle"
  createDirectoryIfMissing True (root </> native)
  writeFile (root </> driver) nativeDriver
  _ <- run root [] ghc ["--make", "-O2", "-fforce-recomp", "-dcore-lint", "-dstg-lint",
    "-i" ++ (root </> "compiler/test-fixtures"), "-odir", root </> native,
    "-hidir", root </> native, root </> driver, "-o", executable] ""
  observations <- runWithTimeout (Just 30000000) root [] executable []
    (concat [name ++ "\t" ++ show x ++ "\n" | name <- entries, x <- values])
  let rows = [words line | line <- lines observations]
      inputRow fields = case fields of
        [name, input, result] | Just n <- readInteger input, Just _ <- readInteger result -> Just (name, n)
        _ -> Nothing
      actual = map inputRow rows
      expected = Set.fromList [(name, x) | name <- entries, x <- values]
  unless (length rows == Set.size expected && Set.fromList actual == Set.map Just expected) $
    die "Native StablePtr oracle has missing or duplicate rows"
  writeFile (root </> oracle) observations
  let inputs = sort [source, nativeSource, certificate, "test/haskell-fixtures/StablePointerFixtures.hs",
        "test/haskell-fixtures/FixtureSupport.hs", "test/haskell-fixtures/Main.hs", "thc.cabal",
        "scripts/core-capabilities.json", "scripts/core_original_foreign.py", "scripts/audit-core.py",
        "compiler/build.sh", "compiler/export.sh", "compiler/toolchain.sh"]
      artifacts = [driver, oracle] ++ concat
        [modules ++ sharedModules ++ [directory </> stage </> name ++ ".audit.json" | name <- entries]
          | (stage, modules, sharedModules) <- stages]
  inputHashes <- hashes root inputs
  artifactHashes <- hashes root artifacts
  writeJson manifest $ object
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "entries" .= entries,
     "stages" .= Map.fromList [(stage, modules) | (stage, modules, _) <- stages],
     "sharedStages" .= Map.fromList [(stage, drop 1 modules) | (stage, _, modules) <- stages],
     "sharedConsumerBoundary" .= ("GHC-compiled home-unit consumer with pinned original ghc-internal FCall heads and descriptors; not original GHC bodies" :: String),
     "originalCalls" .= field "calls" certificateJSON,
     "nativeRows" .= length rows,
     "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes]
  putStrLn ("stable-pointers: " ++ show (length rows) ++ " native observations, pre/post strict audits")
