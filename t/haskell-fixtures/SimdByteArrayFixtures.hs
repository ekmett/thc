-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- Fixture rationale (030 simd-int32x4-bytearray)
-- Purpose: Check signed Int32x4 vector/scalar offsets, reads and writes in byte arrays.
-- Produces/consumed result: Stage CBDs and oracle.tsv; audit mutations check rejection
--   boundaries.
-- Cost and overlap: Keep signed vector-memory semantics in a shared corpus. Copying
--   previous attempts, randomized published paths and archival receipts are unjustified;
--   quarantined.
-- Build status: QUARANTINED: excluded from the new fixture build; see docs/fixture-quarantine.log.
-- Detailed file inputs/outputs: docs/fixture-inputs.log, entry 030.
--
-- Fixture rationale (031 simd-word32x4-bytearray)
-- Purpose: Check Word32x4 high bits and unsigned vector memory transport.
-- Produces/consumed result: Stage CBDs and oracle.tsv; negative audit variants.
-- Cost and overlap: Unsigned boundaries add cases, not a reason for another setup
--   pipeline. Previous-output acquisition and attempt archives keep this quarantined.
-- Build status: QUARANTINED: excluded from the new fixture build; see docs/fixture-quarantine.log.
-- Detailed file inputs/outputs: docs/fixture-inputs.log, entry 031.
--
-- Fixture rationale (032 simd-floatx4-bytearray)
-- Purpose: Check FloatX4 memory operations preserve lane values and bit patterns.
-- Produces/consumed result: Stage CBDs and native oracle.tsv with audit controls.
-- Cost and overlap: Floating storage cases may add value beyond integer vectors.
--   Historical-attempt dependencies and duplicated memory preparation are unjustified;
--   quarantined.
-- Build status: QUARANTINED: excluded from the new fixture build; see docs/fixture-quarantine.log.
-- Detailed file inputs/outputs: docs/fixture-inputs.log, entry 032.
--
-- Fixture rationale (033 simd-doublex2-bytearray)
-- Purpose: Check DoubleX2 byte-array offsets and lane storage.
-- Produces/consumed result: Stage CBDs and native oracle.tsv with audit controls.
-- Cost and overlap: Retain Double lane/offset cases within one memory corpus. Previous
--   receipts must not be prerequisites; quarantined.
-- Build status: QUARANTINED: excluded from the new fixture build; see docs/fixture-quarantine.log.
-- Detailed file inputs/outputs: docs/fixture-inputs.log, entry 033.

{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : SimdByteArrayFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for simd byte array.
module SimdByteArrayFixtures (prepareSimdByteArray) where

import Control.Monad (forM, forM_, unless, when)
import Data.Aeson (Result(..), Value(..), eitherDecodeStrict', fromJSON, object, toJSON, (.=))
import qualified Data.Aeson.Key as Key
import qualified Data.Aeson.KeyMap as KM
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BSC
import Data.Foldable (toList)
import Data.List (isPrefixOf, sort)
import qualified Data.Map.Strict as Map
import qualified Data.Set as Set
import Data.Word (Word32, Word8)
import Distribution.Simple.Utils (createTempDirectory)
import FixtureSupport (CommandResult(..), hashFile, runLogged, runLoggedExpect, runLoggedWithInput, writeJson, readInteger, splitTab)
import Foreign.Marshal.Alloc (alloca)
import Foreign.Ptr (castPtr)
import Foreign.Storable (peek, poke)
import SimdByteArrayModel
import System.Directory (copyFile, createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), makeRelative, takeExtension, takeDirectory, splitDirectories)
import Text.Read (readMaybe)
import THC.Compact.Module (readModuleValue, writeModuleValue)

get :: String -> Value -> Value
get key (Object fields) = maybe Null id (KM.lookup (Key.fromString key) fields)
get _ _ = Null

items :: Value -> [Value]
items (Array values) = toList values
items _ = []

at :: Int -> Value -> Value
at index value = case drop index (items value) of item:_ -> item; [] -> Null

string :: Value -> String
string value = case fromJSON value of Success answer -> answer; Error _ -> ""

readJson :: FilePath -> IO Value
readJson path = BS.readFile path >>= either (die . ((path ++ ": ") ++)) pure . eitherDecodeStrict'

readCore :: FilePath -> IO Value
readCore path = BS.readFile path >>= either die pure . readModuleValue

check :: Bool -> String -> IO ()
check condition message = unless condition (die message)

operations :: Family -> [String]
operations family = [prefix ++ suffix | prefix <- ["index","read","write"], suffix <- [shape ++ "Array#",lane ++ "ArrayAs" ++ shape ++ "#"]]
  where (shape,lane) = case family of
          Int32Lanes -> ("Int32X4","Int32")
          Word32Lanes -> ("Word32X4","Word32")
          FloatLanes -> ("FloatX4","Float")
          DoubleLanes -> ("DoubleX2","Double")

primitive :: Value -> String
primitive value = if at 0 (at 1 value) == String "prim" then string (at 1 (at 1 value)) else ""

counts :: Ord a => [a] -> Map.Map a Int
counts values = Map.fromListWith (+) [(value,1) | value <- values]

boundary :: String -> String
boundary "pre" = "optimized-Core-before-Tidy"
boundary _ = "optimized-Core-after-Tidy-before-CorePrep"

identity :: Family -> String -> String
identity family name = "main:" ++ moduleName family ++ "." ++ name

hostEntries, frontiers :: [String]
hostEntries = ["vectorArgument","readVectorEscape"] ++ [f ++ o ++ "Worker" | f <- ["vector","scalar"],o <- ["Read","Write"]]
frontiers = ["readTupleEscape"]

frontierIssues :: Value -> String -> Map.Map (String,String) Int
frontierIssues capabilities name = counts $ case name of
  "readTupleEscape" -> [("malformed-expression","Invalid local vector memory intrinsic: read requires an immediate exact case")] ++
    [("aggregate-representation","unboxed-tuple: unsupported component") | not (enabled "tuple-fields"), _ <- [1,2 :: Int]]
  _ -> error ("Unknown SIMD memory frontier: " ++ name)
  where enabled capability = toJSON (capability :: String) `elem` items (get "vectorTransport" capabilities)

negative :: Map.Map (String,String) Int -> Value -> IO ()
negative wanted report = check (get "accepted" report == Bool False && items (get "missingGlobals" report) == [] &&
  counts [(string (get "code" issue),string (get "detail" issue)) | issue <- items (get "issues" report)] == wanted) "Changed exact negative issue multiset"

wrongElements :: Family -> [String]
wrongElements Int32Lanes = ["Word32ElemRep"]
wrongElements Word32Lanes = ["Int32ElemRep"]
wrongElements FloatLanes = ["Int32ElemRep","Word32ElemRep","DoubleElemRep"]
wrongElements DoubleLanes = ["Int64ElemRep","Int32ElemRep","Word32ElemRep","FloatElemRep"]

changeField :: String -> (Value -> Value) -> Value -> Value
changeField key action value@(Object fields) = Object (KM.insert (Key.fromString key) (action (get key value)) fields)
changeField _ _ _ = error "Expected mutation object"

changeAt :: Int -> (Value -> Value) -> Value -> Value
changeAt index action value = toJSON [if i == index then action child else child | (i,child) <- zip [0..] (items value)]

mutate :: Family -> String -> String -> String -> Value -> Value
mutate family offsetFamily operation wrong = changeField "bindings" (mapArray binding)
  where
    mapArray action = toJSON . map action . items
    binding value | get "id" value == toJSON (identity family (offsetFamily ++ operation ++ "Worker")) = changeField "expr" visit value
                  | otherwise = value
    visit value
      | at 0 value == String "app" && primitive value `elem` operations family = case operation of
          "Index" -> changeAt 6 (changeField "rep" proof) value
          "Read" -> changeAt 6 (changeField "rep" (changeField "components" (changeAt 1 proof))) value
          _ -> changeAt 2 (changeAt 2 (\expr -> changeAt (length (items expr)-1) (changeField "rep" proof) expr)) value
      | otherwise = case value of Object fields -> Object (fmap visit fields); Array _ -> mapArray visit value; _ -> value
    width = if wrong `elem` ["DoubleElemRep","Int64ElemRep"] then 2 else 4 :: Int
    proof = changeField "primReps" (const (toJSON ["VecRep " ++ show width ++ " " ++ wrong])) .
      changeField "vector" (const (object ["lanes" .= width,"element" .= wrong]))

record :: FilePath -> FilePath -> IO Value
record root path = do digest <- hashFile (root </> path); pure (object ["path" .= path,"sha256" .= digest])

parseRows :: Family -> String -> IO [(String,[Integer],Integer)]
parseRows family text = do
  parsed <- forM (lines text) $ \line -> case splitTab line of
    name:fields | Just entry <- lookup name [(entryName e,e) | e <- entries family], length fields == entryArity entry+1,
                  Just numbers <- traverse readInteger fields -> pure (name,init numbers,last numbers)
    _ -> die "Malformed SIMD memory corpus row"
  check (Set.size (Set.fromList [(n,a) | (n,a,_) <- parsed]) == length parsed) "Duplicate SIMD memory corpus row"
  pure parsed

type Audit = String -> String -> FilePath -> Bool -> IO (Value,CommandResult,FilePath)

mutationControls :: Family -> FilePath -> FilePath -> Audit -> String -> Value -> IO (Value,[CommandResult],[FilePath])
mutationControls family root attempt audit stage core = do
  controls <- forM (wrongElements family) $ \wrong -> do
    cases <- forM [(f,o) | f <- ["vector","scalar"],o <- ["Index","Read","Write"]] $ \(offsetFamily,operation) -> do
      let label = stage ++ "-wrong-" ++ wrong ++ "-" ++ offsetFamily ++ operation
          path = attempt </> "mutations" </> label ++ ".cbd"
          changed = mutate family offsetFamily operation wrong core
          detail = case operation of "Index" -> "result representation"; "Read" -> "read result components"; _ -> "argument representation"
          wanted = counts ([("malformed-expression","Invalid local vector memory intrinsic: " ++ detail)] ++
            [(code,message) | operation == "Index",(code,message) <- [("vector-shape","Exact vector primitive argument representation required"),("aggregate-shape","Conflicting or missing logical aggregate representation proofs")]])
      check (changed /= core) "Missing SIMD proof mutation target"
      createDirectoryIfMissing True (root </> attempt </> "mutations")
      _ <- writeModuleValue (root </> path) changed
      (report,result,reportPath) <- audit label (offsetFamily ++ operation ++ "Case") path True
      negative wanted report
      pure (offsetFamily ++ operation,object ["origin" .= ("Mutated proof metadata only; never native input" :: String),"report" .= report],result,[path,reportPath])
    pure (wrong,toJSON (Map.fromList [(n,v) | (n,v,_,_) <- cases]),[result | (_,_,result,_) <- cases],concat [paths | (_,_,_,paths) <- cases])
  pure (if floating family then toJSON (Map.fromList [(wrong,value) | (wrong,value,_,_) <- controls]) else case controls of [(_,value,_,_)] -> value; _ -> Null,
        concat [commands | (_,_,commands,_) <- controls],concat [paths | (_,_,_,paths) <- controls])

prepareSimdByteArray :: FilePath -> String -> [String] -> IO ()
prepareSimdByteArray root name args = do
  family <- case filter ((== name) . familyName) families of [answer] -> pure answer; _ -> die "Unknown SIMD memory family"
  (exportOnly,ghcOptions) <- options False [] args
  let directory = "build" </> "simd-" ++ name
      provenancePath = directory </> "provenance.json"
      fixture = "t/fixtures/compiler" </> moduleName family ++ ".hs"
      nativeSource = "t/fixtures/compiler" </> moduleName family ++ "Native.hs"
      stages = if exportOnly then ["pre"] else ["pre","post"]
  createDirectoryIfMissing True (root </> directory)
  -- Cabal returns the new directory's basename and appends its own hyphen.
  -- Keep every receipt/artifact below this family's owned build directory.
  attempt <- (directory </>) <$> createTempDirectory (root </> directory) "prepare-run"
  old <- doesFileExist (root </> provenancePath)
  when old $ do
    previous <- readJson (root </> provenancePath)
    -- Preserve the previous receipt and recorded artifacts before canonical
    -- outputs change. This is a copy, never a reseal of prior hashes.
    forM_ (provenancePath : map (string . get "path") (items (get "artifacts" previous))) $ \path -> do
      check ((directory ++ "/") `isPrefixOf` path && not (".." `elem` splitDirectories path)) "Unsafe prior artifact path"
      let saved = root </> attempt </> "previous" </> makeRelative directory path
      createDirectoryIfMissing True (takeDirectory saved)
      copyFile (root </> path) saved
    removeFile (root </> provenancePath)
  forM_ ["oracle.tsv","snan-oracle.tsv","snan-expected.tsv"] $ \file -> do
    exists <- doesFileExist (root </> directory </> file)
    when exists (removeFile (root </> directory </> file))
  let logs = attempt </> "commands"
      run label = runLogged 300 root logs label
      audit label entry path rejected = do
        let reportPath = attempt </> "audits" </> label ++ ".json"
        createDirectoryIfMissing True (root </> attempt </> "audits")
        result <- runLoggedExpect (if rejected then 1 else 0) 120 root logs label [] "python3"
          ["bin/audit-core.py","--entry",identity family entry,"--output",reportPath,path]
        report <- readJson (root </> reportPath)
        pure (report,result,reportPath)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run "ghc-version" [] ghc ["--numeric-version"]
  check (commandStdout version == "9.14.1\n") "SIMD memory fixtures require GHC 9.14.1"
  ghcInfo <- run "ghc-info" [] ghc ["--info"]
  check (maybe False ((== Just "8") . lookup "target word size") (readMaybe (BSC.unpack (commandStdout ghcInfo)) :: Maybe [(String,String)])) "SIMD memory corpus requires 64-bit GHC"
  little <- alloca $ \pointer -> do poke pointer (1 :: Word32); byte <- peek (castPtr pointer); pure (byte == (1 :: Word8))
  check little "SIMD memory corpus requires little-endian host"
  host <- run "host" [] "uname" ["-n"]
  architecture <- run "architecture" [] "uname" ["-m"]
  system <- run "system" [] "uname" ["-s"]
  compiler <- run "compiler-build" [] "bin/build-compiler.sh" []
  let wanted = rows family
      expectedText = encodeRows wanted
  check (length wanted == expectedRows family && Set.size (Set.fromList [(entry,input) | (entry,input,_) <- wanted]) == length wanted) "Wrong SIMD model domain"
  writeFile (root </> directory </> "expected.tsv") expectedText
  writeFile (root </> directory </> "requests.tsv") (encodeRequests wanted)
  capabilities <- readJson (root </> "bin/core-capabilities.json")
  prepared <- forM stages $ \stage -> do
    let corePath = directory </> stage ++ "-core" </> moduleName family ++ ".cbd"
    exists <- doesFileExist (root </> corePath)
    when exists (removeFile (root </> corePath))
    exported <- run (stage ++ "-export") [("THC_CORE_OUT",root </> directory </> stage ++ "-core"),("THC_GHC_OUT",root </> directory </> stage ++ "-ghc"),("THC_SOURCE_NOTES","true")]
      "bin/export-core.sh" (ghcOptions ++ [x | exportOnly,x <- ["-fno-code","-fwrite-if-simplified-core"]] ++ ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++ [fixture])
    core <- readCore (root </> corePath)
    check (get "boundary" core == toJSON (boundary stage)) "Wrong SIMD memory Core stage"
    reports <- forM (map entryName (entries family) ++ graphNames family ++ hostEntries ++ frontiers) $ \entry -> do
      triple@(report,_,_) <- audit (stage ++ "-" ++ entry) entry corePath (entry `elem` frontiers)
      if entry `elem` frontiers then negative (frontierIssues capabilities entry) report else
        check (get "accepted" report == Bool True && items (get "issues" report) == [] && items (get "missingGlobals" report) == []) (entry ++ ": positive audit")
      pure (entry,triple)
    let reportMap = Map.fromList [(entry,report) | (entry,(report,_,_)) <- reports]
    controls <- mutationControls family root attempt audit stage core
    let auditPath = directory </> stage ++ "-audit.json"
    writeJson (root </> auditPath) (toJSON reportMap)
    pure (stage,reportMap,controls,
          exported : [result | (_,(_,result,_)) <- reports],corePath:auditPath:[path | (_,(_,_,path)) <- reports])
  native <- if exportOnly then pure Nothing else do
    let directoryNative = directory </> "native"
        binary = directoryNative </> name ++ "-oracle"
    createDirectoryIfMissing True (root </> directoryNative)
    built <- run "native-build" [] ghc (["--make","-O2","-fforce-recomp","-dcore-lint","-it/fixtures/compiler","-odir",root </> directoryNative,"-hidir",root </> directoryNative] ++ ghcOptions ++ [nativeSource,"-o",root </> binary])
    observed <- runLoggedWithInput (directory </> "requests.tsv") 120 root logs "native-oracle" [] (root </> binary) []
    check (commandStdout observed == BSC.pack expectedText) "Native SIMD memory corpus differs from exact ordered byte model"
    BS.writeFile (root </> directory </> "oracle.tsv") (commandStdout observed)
    diagnostic <- if not (floating family) then pure Nothing else do
      let expectedDiagnostic = diagnostics family
      writeFile (root </> directory </> "snan-expected.tsv") (encodeRows expectedDiagnostic)
      writeFile (root </> directory </> "snan-requests.tsv") (encodeRequests expectedDiagnostic)
      observation <- runLoggedWithInput (directory </> "snan-requests.tsv") 120 root logs "snan-oracle" [] (root </> binary) []
      actual <- parseRows family (BSC.unpack (commandStdout observation))
      check ([(n,a) | (n,a,_) <- actual] == [(n,a) | (n,a,_) <- expectedDiagnostic]) "Native signaling-NaN diagnostic keys changed"
      BS.writeFile (root </> directory </> "snan-oracle.tsv") (commandStdout observation)
      let differences = [object ["key" .= (toJSON n:map toJSON a),"expected" .= expectedValue,"actual" .= actualValue]
                        | ((n,a,expectedValue),(_,_,actualValue)) <- zip expectedDiagnostic actual,expectedValue /= actualValue]
      pure (Just (object ["kind" .= ("selected-signaling-NaN/native-only" :: String),"rows" .= length actual,"matches" .= null differences,"differences" .= differences,
        "claim" .= ("Pinned native observations only; no portable scalar copying/boxing or arithmetic NaN promise" :: String)],observation))
    pure (Just (built,observed,diagnostic,binary))
  let controlsCommands = concat [records | (_,_,(_,records,_),_,_) <- prepared]
      controlsPaths = concat [paths | (_,_,(_,_,paths),_,_) <- prepared]
      commands = [version,ghcInfo,host,architecture,system,compiler] ++ concat [cs | (_,_,_,cs,_) <- prepared] ++ controlsCommands ++
        case native of Nothing -> []; Just (built,observed,diagnostic,_) -> [built,observed] ++ [result | Just (_,result) <- [diagnostic]]
      artifacts = [directory </> "expected.tsv",directory </> "requests.tsv"] ++ concat [paths | (_,_,_,_,paths) <- prepared] ++ controlsPaths ++ concatMap commandArtifacts commands ++
        case native of Nothing -> []; Just (_,_,diagnostic,binary) -> [directory </> "oracle.tsv",binary] ++ [directory </> path | Just _ <- [diagnostic],path <- ["snan-expected.tsv","snan-requests.tsv","snan-oracle.tsv"]]
  compilerSources <- map ("src/compiler/THC" </>) . filter ((== ".hs") . takeExtension) <$> listDirectory (root </> "src/compiler/THC")
  auditorSources <- map ("bin" </>) . filter (\path -> "core_" `isPrefixOf` path && takeExtension path == ".py") <$> listDirectory (root </> "bin")
  sources <- mapM (record root) . sort . Set.toList . Set.fromList $ [fixture,nativeSource,
    "t/haskell-fixtures/SimdByteArrayFixtures.hs","t/haskell-fixtures/SimdByteArrayModel.hs","t/haskell-fixtures/FixtureSupport.hs","t/haskell-fixtures/Main.hs","thc.cabal",
    "bin/audit-core.py","bin/core-capabilities.json",
    "src/main/java/thc/runtime/VectorMemoryFamily.java","src/main/java/thc/runtime/VectorMemoryOp.java",
    "src/main/java/thc/runtime/VectorReadCase.java","src/main/java/thc/runtime/CoreVectorMemory.java",
    "src/main/java/thc/runtime/VectorByteArrayExpression.java",
    "src/main/resources/thc/scalar-primop-signatures.json","bin/build-compiler.sh","bin/export-core.sh","bin/toolchain.sh","bin/plugin.py"] ++
    ["src/main/java/thc/runtime/VectorMemory.java" | family == DoubleLanes] ++ compilerSources ++ auditorSources
  artifactRecords <- mapM (record root) (sort (Set.toList (Set.fromList artifacts)))
  let controlKey = case family of Int32Lanes -> "unsignedNegativeControls"; Word32Lanes -> "signedNegativeControls"; _ -> "familyNegativeControls"
      hasNative = maybe False (const True) native
      trim = takeWhile (/= '\n') . BSC.unpack . commandStdout
      provenance = object ["schema" .= (1 :: Int),"vector" .= name,"stages" .= stages,"modelByteOrder" .= ("little" :: String),
        "nativeByteOrder" .= (if hasNative then Just ("little" :: String) else Nothing),"nativeRows" .= (if hasNative then Just (length wanted) else Nothing),
        "modelRows" .= length wanted,"modelMatched" .= (if hasNative then Just True else Nothing),"entries" .= map entryValue (entries family),"graphEntries" .= graphEntries family,
        "positiveAuditsAccepted" .= True,"audits" .= Map.fromList [(s,reports) | (s,reports,_,_,_) <- prepared],
        "hostEntries" .= hostEntries,"frontiers" .= frontiers,
        Key.fromString controlKey .= Map.fromList [(s,control) | (s,_,(control,_,_),_,_) <- prepared],
        "nativeDiagnostics" .= (case native of Just (_,_,Just (diagnostic,_),_) -> diagnostic; _ -> Null),
        "commands" .= map commandRecord commands,"sources" .= sources,"artifacts" .= artifactRecords,"attempt" .= attempt,
        "toolchain" .= object ["ghc" .= ghc,"ghcVersion" .= ("9.14.1" :: String),"architecture" .= trim architecture,"machine" .= trim architecture,
          "host" .= trim host,"system" .= trim system,"byteOrder" .= ("little" :: String),"ghcInfo" .= BSC.unpack (commandStdout ghcInfo),"ghcOptions" .= ghcOptions],
        "claim" .= (if hasNative then "Native/model byte agreement and strict Core audits; no JVM graph or performance claim" else "Pre-Tidy Core and byte model only; NO native/post-Tidy validation" :: String),
        "limitations" .= (["Little-endian 64-bit native corpus only; selected inputs, not a complete transitive runtime inventory",
          "No native pointer-identity claim; JVM checks actual returned storage identity", "Bounded lane patterns, not exhaustive vector encodings",
          "Signaling NaNs are native diagnostics, never portable corpus rows; no arithmetic NaN payload promise",
          "Existing Python audit-core.py remains the shared exact Core proof mechanism"] :: [String])]
  writeJson (root </> attempt </> "provenance.json") provenance
  writeJson (root </> provenancePath) provenance
  putStrLn (name ++ ": model=" ++ show (length wanted) ++ ", native=" ++ show hasNative ++ ", stages=" ++ show stages)
  where
    options exported flags [] = pure (exported,reverse flags)
    options _ flags ("--export-only":rest) = options True flags rest
    options exported flags ("--ghc-option":flag:rest) = options exported (flag:flags) rest
    options exported flags (arg:rest) | "--ghc-option=" `isPrefixOf` arg = options exported (drop 13 arg:flags) rest
    options _ _ _ = die "Usage: thc-fixtures FAMILY [--export-only] [--ghc-option=OPTION]"
