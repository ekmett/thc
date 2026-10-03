-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- Fixture rationale (087 pinned-addresses)
-- Purpose: Check pinning and address lifetime preserve native-visible storage.
-- Produces/consumed result: CBDs and oracle.tsv.
-- Cost and overlap: Keep pinning/lifetime behavior; ordinary unpinned array tests are
--   insufficient. Share memory setup and avoid duplicate audit work.
-- Build status: Value review only; admission still requires explicit inputs and single-
--   owner outputs.
-- Detailed file inputs/outputs: docs/fixture-inputs.log, entry 087.

{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : PinnedAddressFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for pinned address.
module PinnedAddressFixtures (preparePinnedAddresses) where

import Control.Monad ((>=>), forM, forM_, unless, when)
import Data.Aeson (FromJSON, Result(..), Value(..), eitherDecodeStrict', fromJSON, object, toJSON, (.=))
import qualified Data.Aeson.Key as Key
import qualified Data.Aeson.KeyMap as KeyMap
import Data.Bits ((.&.), shiftR, xor)
import qualified Data.ByteString as BS
import Data.Foldable (toList)
import Data.List (intercalate, isPrefixOf, nub, sort)
import qualified Data.Map.Strict as Map
import qualified Data.Set as Set
import Data.String (fromString)
import Data.Word (Word8, Word16)
import FixtureSupport (CommandResult(..), hashes, run, runLogged, runLoggedWithInput, writeJson)
import Foreign (Ptr, alloca, castPtr, peek, poke)
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (ExitCode(..), die)
import System.FilePath ((</>), takeExtension)
import System.Process (CreateProcess(..), proc, readCreateProcessWithExitCode)
import System.Timeout (timeout)
import Text.Read (readMaybe)
import THC.Compact.Module (readModuleValue, writeModuleValue)

directory, prefix :: String
directory = "build/pinned-addresses"
prefix = "main:PinnedAddressAudit."

entries :: [(String,Int)]
entries = [("pinnedBytes",3),("alignedBytes",4),("keepAliveWord8",1),("keepAliveLazy",1),("fingerprintByte",3)]

values, words64, sizes :: [Integer]
values = [-2^(63 :: Int),-257,-256,-1,0,1,127,128,255,256,257,0x0123456789abcdef,2^(63 :: Int)-1]
words64 = [-2^(63 :: Int),-1,0,1,0x0123456789abcdef,0x7f0080ff0102fe03,2^(63 :: Int)-1]
sizes = [0,1,2,3,8,16,17,31,64]

requests :: [(String,[Integer])]
requests = concat [[("pinnedBytes",[size,offset,raw])] ++ [("alignedBytes",[size,alignment,offset,raw]) | alignment <- [8,16]] |
  size <- sizes, offset <- if size == 0 then [0] else [0..size-1], raw <- values] ++
  [(name,[raw]) | name <- ["keepAliveWord8","keepAliveLazy"], raw <- values] ++
  [("fingerprintByte",[high,low,index]) | high <- words64, low <- words64, index <- [0..15]]

-- Native-domain model only: explicit byte storage and mutation, independently
-- checked by the JVM's arithmetic and big-endian byte-buffer model.
expected :: String -> [Integer] -> Integer
expected name arguments = case (name,arguments) of
  ("pinnedBytes",[size,offset,raw]) -> allocation size offset raw
  ("alignedBytes",[size,_,offset,raw]) -> allocation size offset raw
  ("keepAliveWord8",[raw]) -> keep raw 7
  ("keepAliveLazy",[raw]) -> keep raw 11
  ("fingerprintByte",[high,low,index]) ->
    (if index < 8 then high else low) `shiftR` (8 * fromInteger (7-index `mod` 8)) .&. 255
  _ -> error "Invalid pinned-address model request"
  where
    keep raw delta = let before = raw .&. 255 in before*257 + ((before+delta) .&. 255)
    allocation 0 _ _ = 0
    allocation size offset raw =
      let bytes = Map.insert offset (raw .&. 255) (Map.fromList [(0,11),(size-1,13)])
          before = bytes Map.! offset
          after = before `xor` 128
          changed = Map.insert offset after bytes
      in size*19 + before*257 + after*65537 + changed Map.! 0*17 + changed Map.! (size-1)*23

check :: Bool -> String -> IO ()
check condition message = unless condition (die message)

ensure :: Bool -> String -> Either String ()
ensure condition message = if condition then Right () else Left message

decode :: FromJSON a => Value -> Either String a
decode value = case fromJSON value of Success result -> Right result; Error message -> Left message

field :: FromJSON a => String -> Value -> Either String a
field key (Object fields) = maybe (Left ("Missing field: " ++ key)) decode (KeyMap.lookup (fromString key) fields)
field key _ = Left ("Expected object: " ++ key)

readJson :: FilePath -> IO Value
readJson path = BS.readFile path >>= either die pure . eitherDecodeStrict'

-- Locate genuine primop proofs for the malformed-input controls.
data Step = Index Int | Key String deriving (Eq,Ord,Show)
type Path = [Step]

children :: Value -> [(Step,Value)]
children (Array items) = zipWith (\i value -> (Index i,value)) [0..] (toList items)
children (Object fields) = [(Key (Key.toString key),value) | (key,value) <- KeyMap.toAscList fields]
children _ = []

walk :: Value -> [(Path,Value)]
walk value = ([],value) : [(step:path,child) | (step,next) <- children value, (path,child) <- walk next]

at :: Path -> Value -> Either String Value
at [] value = Right value
at (step:path) value = maybe (Left ("Missing path: " ++ show step)) (at path) (lookup step (children value))

replace :: Path -> Value -> Value -> Either String Value
replace [] replacement _ = Right replacement
replace (step:path) replacement value = do
  child <- at [step] value >>= replace path replacement
  case (step,value) of
    (Key key,Object fields) -> pure (Object (KeyMap.insert (fromString key) child fields))
    (Index index,Array items) -> pure (toJSON [if i == index then child else item | (i,item) <- zip [0..] (toList items)])
    _ -> Left "Invalid replacement path"

array :: Value -> [Value]
array (Array items) = toList items
array _ = []

tagged :: String -> Value -> Bool
tagged tag value = take 1 (array value) == [String (fromString tag)]

proofPath :: Value -> Either String Path
proofPath value = do
  ensure (not (null (array value))) "Missing expression representation"
  let path = [Index (length (array value)-1),Key "rep"]
  proof <- at path value
  _ <- field "kind" proof :: Either String String
  pure path

bindings :: Value -> Either String [(Path,Value)]
bindings modules = concat <$> forM (zip [0..] (array modules)) (\(i,modul) -> do
  bs <- field "bindings" modul :: Either String [Value]
  pure [([Index i,Key "bindings",Index j],binding) | (j,binding) <- zip [0..] bs])

applications :: Value -> String -> Maybe (Set.Set String) -> Either String [(Path,Value)]
applications modules primitive reachable = do
  bs <- bindings modules
  concat <$> forM bs (\(path,binding) -> do
    ident <- field "id" binding
    expression <- field "expr" binding
    pure [(path ++ [Key "expr"] ++ nested,node) | maybe True (Set.member ident) reachable,
      (nested,node) <- walk expression, tagged "app" node,
      take 2 (array (array node !! 1)) == [String "prim",String (fromString primitive)]])

-- Each forged certificate is applied to an accepted genuine export, not to an
-- already unsupported synthetic module. Result tuples retain their State slot.
negatives :: [(String,String,String,Maybe Int,String,String)]
negatives =
  [("read-word-not-word8","keepAliveWord8","readWord8OffAddr#",Nothing,"long","WordRep"),
   ("write-word-not-word8","keepAliveWord8","writeWord8OffAddr#",Just 2,"long","WordRep"),
   ("read-address-is-word","keepAliveWord8","readWord8OffAddr#",Just 0,"long","WordRep"),
   ("read-state-is-int","keepAliveWord8","readWord8OffAddr#",Just 2,"long","IntRep"),
   ("read-offset-is-word","keepAliveWord8","readWord8OffAddr#",Just 1,"long","WordRep"),
   ("contents-lifted-array","keepAliveWord8","byteArrayContents#",Just 0,"object","BoxedRep (Just Lifted)"),
   ("contents-result-is-word","keepAliveWord8","byteArrayContents#",Nothing,"long","WordRep"),
   ("allocation-size-is-word","pinnedBytes","newPinnedByteArray#",Just 0,"long","WordRep"),
   ("allocation-state-is-int","pinnedBytes","newPinnedByteArray#",Just 1,"long","IntRep"),
   ("aligned-alignment-is-word","alignedBytes","newAlignedPinnedByteArray#",Just 1,"long","WordRep"),
   ("keepalive-state-is-int","keepAliveWord8","keepAlive#",Just 1,"long","IntRep"),
   ("keepalive-result-word-not-word8","keepAliveWord8","keepAlive#",Nothing,"long","WordRep")]

forge :: Value -> Value -> String -> Maybe Int -> String -> String -> Either String Value
forge modules baseline primitive argument kind rep = do
  accepted <- field "accepted" baseline
  ensure accepted "Negative baseline rejected"
  reached <- field "reachableBindings" baseline >>= mapM (field "id")
  candidates <- applications modules primitive (Just (Set.fromList reached))
  (appPath,app) <- case candidates of first:_ -> Right first; [] -> Left "Missing mutation site"
  let expressionPath = maybe [] (\index -> [Index 2,Index index]) argument
  expr <- at expressionPath app
  nested <- proofPath expr
  let path = appPath ++ expressionPath ++ nested
  proof <- at path modules
  let tuple = argument == Nothing && field "aggregate" proof == Right ("unboxed-tuple" :: String)
  when tuple $ do
    components <- field "components" proof :: Either String [Value]
    ensure (length components == 2) "Unexpected result shape"
  let componentPath = path ++ if tuple then [Key "components",Index 1] else []
  changed <- replace (componentPath ++ [Key "kind"]) (String (fromString kind)) modules >>=
    replace (componentPath ++ [Key "primReps"]) (toJSON [rep])
  if tuple then replace (path ++ [Key "primReps"]) (toJSON [rep]) changed else pure changed

sourcePaths :: FilePath -> IO [FilePath]
sourcePaths root = do
  plugins <- listDirectory (root </> "src/compiler/THC")
  scripts <- listDirectory (root </> "bin")
  pure $ sort $ ["t/fixtures/compiler/PinnedAddressAudit.hs","t/fixtures/compiler/PinnedAddressAuditNative.hs",
    "t/haskell-fixtures/PinnedAddressFixtures.hs","t/haskell-fixtures/FixtureSupport.hs","t/haskell-fixtures/Main.hs",
    "thc.cabal","bin/build-compiler.sh","bin/export-core.sh","bin/toolchain.sh","bin/plugin.py",
    "bin/audit-core.py","bin/core-capabilities.json","src/tools/primops/PrimopTools.hs",
    "src/main/resources/thc/scalar-primop-signatures.json"] ++
    ["src/compiler/THC" </> name | name <- plugins, takeExtension name == ".hs"] ++
    ["bin" </> name | name <- scripts, "core_" `isPrefixOf` name, takeExtension name == ".py"]

preparePinnedAddresses :: FilePath -> Bool -> Bool -> Bool -> IO ()
preparePinnedAddresses root nativeOnly exportOnly allowUnsupported = do
  check (not (nativeOnly && exportOnly)) "Conflicting modes"
  createDirectoryIfMissing True (root </> directory)
  let manifestPath = root </> directory </> "manifest.json"
      logs = directory </> "commands"
      requestText = unlines [intercalate "\t" (name:map show args) | (name,args) <- requests]
      oracleText = unlines [intercalate "\t" (name:map show (args ++ [expected name args])) | (name,args) <- requests]
  present <- doesFileExist manifestPath
  when present (removeFile manifestPath)
  check (length (nub requests) == length requests) "Duplicate pinned-address request"
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run root [] ghc ["--numeric-version"] ""
  check (lines version == ["9.14.1"]) "Pinned GHC 9.14.1 required"
  info <- run root [] ghc ["--info"] ""
  order <- alloca $ \ptr -> do poke ptr (1 :: Word16); byte <- peek (castPtr ptr :: Ptr Word8); pure (if byte == 1 then "little" else "big" :: String)
  case readMaybe info :: Maybe [(String,String)] of
    Just fields | lookup "target word size" fields == Just "8",
      lookup "target word big endian" fields == Just (if order == "big" then "YES" else "NO") -> pure ()
    _ -> die "Pinned addresses require native-order 64-bit GHC"
  writeFile (root </> directory </> "requests.tsv") requestText
  writeFile (root </> directory </> "expected.tsv") oracleText
  nativeArtifacts <- if exportOnly then pure [] else do
    let native = directory </> "native"
        binary = native </> "pinned-address-oracle"
    createDirectoryIfMissing True (root </> native)
    _ <- runLogged 300 root logs "native-build" [] ghc ["--make","-O2","-fforce-recomp","-dcore-lint","-dstg-lint",
      "-it/fixtures/compiler","-odir",root </> native,"-hidir",root </> native,
      "t/fixtures/compiler/PinnedAddressAuditNative.hs","-o",root </> binary]
    result <- runLoggedWithInput (directory </> "requests.tsv") 60 root logs "native-oracle" [] (root </> binary) []
    BS.writeFile (root </> directory </> "oracle.tsv") (commandStdout result)
    output <- readFile (root </> directory </> "oracle.tsv")
    check (output == oracleText) "Native TSV differs from independent model"
    pure ([directory </> "oracle.tsv"] ++ [native </> name | name <- ["pinned-address-oracle","Main.hi","Main.o","PinnedAddressAudit.hi","PinnedAddressAudit.o"]] ++
      commandFiles ["native-build","native-oracle"])
  stages <- if nativeOnly then pure [] else forM ["pre","post"] $ \stage -> do
    let base = directory </> stage
        paths = [base </> "core" </> name ++ ".cbd" | name <- ["PinnedAddressAudit","THC.InterfaceClosure"]]
    _ <- runLogged 300 root logs (stage ++ "-export")
      [("THC_CORE_OUT",root </> base </> "core"),("THC_GHC_OUT",root </> base </> "ghc"),("THC_SOURCE_NOTES","true")]
      "bin/export-core.sh" (["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
        ["-fplugin-opt=THC.Plugin:closure=" ++ name | (name,_) <- entries] ++ ["t/fixtures/compiler/PinnedAddressAudit.hs"])
    originals <- mapM (\path -> BS.readFile (root </> path) >>= either die pure . readModuleValue) paths
    let modules = toJSON originals
    boundary <- either die pure (at [Index 0,Key "boundary"] modules)
    check (boundary == String (if stage == "pre" then "optimized-Core-before-Tidy" else "optimized-Core-after-Tidy-before-CorePrep")) "Wrong actual Core stage"
    reports <- forM entries $ \(name,_) -> do
      report <- audit stage name paths name (if allowUnsupported then Nothing else Just 0)
      accepted <- either die pure (field "accepted" report)
      unless allowUnsupported (check accepted "Unsupported genuine Core")
      pure (name,report)
    accepted <- and <$> mapM (either die pure . field "accepted" . snd) reports
    primitives <- concat <$> mapM (either die pure . (field "primitives" >=> mapM (field "name")) . snd) reports :: IO [String]
    check (all (`elem` primitives) ["newPinnedByteArray#","newAlignedPinnedByteArray#","byteArrayContents#","readWord8OffAddr#","writeWord8OffAddr#","keepAlive#"])
      "Required primitives disappeared"
    negativesAndArtifacts <- if not accepted then pure [] else forM negatives $ \(label,name,primitive,argument,kind,rep) -> do
      baseline <- maybe (die "Missing negative baseline") pure (lookup name reports)
      changed <- either die pure (forge modules baseline primitive argument kind rep)
      let mutatedPaths = [base </> "negative" </> label ++ "-" ++ show i ++ ".cbd" | i <- [0 :: Int,1]]
      createDirectoryIfMissing True (root </> base </> "negative")
      forM_ (zip mutatedPaths (array changed)) (\(path,value) -> writeModuleValue (root </> path) value)
      report <- audit stage ("negative-" ++ label) mutatedPaths name (Just 1)
      rejected <- either die pure (field "accepted" report)
      issues <- either die pure (field "issues" report) :: IO [Value]
      check (not rejected && not (null issues)) ("Forged proof accepted: " ++ label)
      summary <- either die pure (field "summary" report) :: IO Value
      pure (label,object ["entry" .= name,"primitive" .= primitive,"summary" .= summary,"issues" .= issues],
        mutatedPaths ++ [base </> "negative-" ++ label ++ ".audit.json"] ++ commandFiles [stage ++ "-negative-" ++ label ++ "-audit"])
    let negativeReports = Map.fromList [(label,report) | (label,report,_) <- negativesAndArtifacts]
        artifacts = paths ++ [base </> name ++ ".audit.json" | (name,_) <- entries] ++
          commandFiles ((stage ++ "-export"):[stage ++ "-" ++ name ++ "-audit" | (name,_) <- entries]) ++
          concat [files | (_,_,files) <- negativesAndArtifacts] ++ [base </> "negative-proofs.json" | accepted]
    when accepted (writeJson (root </> base </> "negative-proofs.json") (toJSON negativeReports))
    pure (stage,paths,Map.fromList reports,negativeReports,accepted,artifacts)
  inputs <- sourcePaths root >>= hashes root
  artifacts <- hashes root (sort ([directory </> name | name <- ["requests.tsv","expected.tsv"]] ++
    nativeArtifacts ++ concat [files | (_,_,_,_,_,files) <- stages]))
  let mode = if nativeOnly then "native-only" else if exportOnly then "export-only" else "full" :: String
      strict = not nativeOnly && all (\(_,_,_,_,accepted,_) -> accepted) stages
      nativeRows = if exportOnly then 0 else length requests
  writeJson manifestPath $ object ["schema" .= (1 :: Int),"ghc" .= ("9.14.1" :: String),"ghcInfo" .= info,
    "entries" .= Map.fromList entries,
    "nativeByteOrder" .= order,"fingerprintByteOrder" .= ("big" :: String),
    "strictAccepted" .= strict,"mode" .= mode,
    "stages" .= Map.fromList [(stage,paths) | (stage,paths,_,_,_,_) <- stages],
    "audits" .= Map.fromList [(stage,reports) | (stage,_,reports,_,_,_) <- stages],
    "negativeProofs" .= Map.fromList [(stage,reports) | (stage,_,_,reports,_,_) <- stages],
    "nativeRows" .= nativeRows,"modelRows" .= length requests,
    "rowCounts" .= Map.fromListWith (+) [(name,1 :: Int) | (name,_) <- requests],
    "rows" .= [object ["entry" .= name,"arguments" .= args,"expected" .= expected name args] | (name,args) <- requests],
    "inputHashes" .= inputs,"artifactHashes" .= artifacts,
    "limits" .= (["Fingerprint byte layout is checked against native public Storable.",
      "Defined native domains only; malformed/bounds failures are non-native runtime tests.",
      "Explicit pinned arrays use native storage; moving heap arrays retain managed buffer transport; arbitrary numeric pointers remain opaque.",
      "The existing shared Python audit-core.py proof implementation remains an explicit dependency."] :: [String])]
  putStrLn ("Pinned addresses: mode=" ++ mode ++ "; nativeRows=" ++ show nativeRows ++
    "; modelRows=" ++ show (length requests) ++ "; strictAccepted=" ++ show strict)
  where
    commandFiles names = [directory </> "commands" </> name ++ "." ++ suffix | name <- names, suffix <- ["stdout","stderr","command.json"]]
    audit stage label paths entry expectedExit = do
      let output = directory </> stage </> label ++ ".audit.json"
          -- Expected rejection controls are small and already retain named
          -- reports/logs. Do not leak a failure catalogue for each successful test.
          args = ["bin/audit-core.py"] ++ ["--eager" | expectedExit == Just 1] ++ paths ++
            ["--entry",prefix ++ entry,"--output",output]
          commandPrefix = root </> directory </> "commands" </> stage ++ "-" ++ label ++ "-audit"
          permitted = maybe [0,1] (:[]) expectedExit
      createDirectoryIfMissing True (root </> directory </> "commands")
      completed <- timeout (120*1000000) (readCreateProcessWithExitCode ((proc "python3" args) {cwd = Just root}) "")
      (status,out,err) <- maybe (die "Pinned-address audit timed out") pure completed
      let code = case status of ExitSuccess -> 0; ExitFailure n -> n
      writeFile (commandPrefix ++ ".stdout") out
      writeFile (commandPrefix ++ ".stderr") err
      writeJson (commandPrefix ++ ".command.json") (object ["argv" .= ("python3":args),"cwd" .= root,
        "exit" .= code,"expectedExits" .= permitted,"timeoutSeconds" .= (120 :: Int)])
      check (code `elem` permitted) ("Pinned-address audit failed: " ++ commandPrefix)
      report <- readJson (root </> output)
      accepted <- either die pure (field "accepted" report)
      check (accepted == (code == 0)) "Audit report/exit disagreement"
      pure report
