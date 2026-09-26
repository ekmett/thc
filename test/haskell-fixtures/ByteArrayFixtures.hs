-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}
module ByteArrayFixtures (prepareByteArrayFamily) where

import Control.Monad (forM, unless, when)
import Data.Aeson (FromJSON, Result(..), Value(..), fromJSON, object, (.=), eitherDecodeStrict')
import qualified Data.Aeson.Key as Key
import qualified Data.Aeson.KeyMap as KeyMap
import Data.Bits ((.&.))
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BSC
import Data.List (isPrefixOf, isSuffixOf, sort)
import qualified Data.Map.Strict as Map
import qualified Data.Set as Set
import FixtureSupport (CommandResult(..), hashFile, hashes, readInteger, runLogged,
                       runLoggedWithInput, splitTab, writeJson)
import System.Directory (copyFile, createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)
import Text.Read (readMaybe)

-- One producer for the five existing byte-array proof contracts, not a general
-- fixture framework. Native/Core source bodies and runtime lowering stay intact.
data Family = Bytes | Mutable | Resize | Size | Compare deriving (Eq)

familyName :: Family -> String
familyName family = case family of
  Bytes -> "bytearray"
  Mutable -> "mutable-bytearrays"
  Resize -> "resize-bytearrays"
  Size -> "mutable-bytearray-size"
  Compare -> "compare-byte-arrays"

moduleName :: Family -> String
moduleName family = case family of
  Bytes -> "ByteArrayAudit"
  Mutable -> "MutableByteArrayAudit"
  Resize -> "ResizeByteArrayAudit"
  Size -> "MutableByteArraySizeAudit"
  Compare -> "CompareByteArraysAudit"

entries :: Family -> [String]
entries family = case family of
  Bytes -> ["shortBytes", "orderedBytes", "shortUncons", "copiedBytes"]
  Mutable -> ["filledBytes", "movedBytes", "disjointBytes", "copiedMutableBytes", "copiedDisjointBytes", "publicReplicate"]
  Resize -> ["resizedBytes", "resizedTwiceWrites"]
  Size -> ["freshSize", "pureSize", "resizedSizes", "pureAfterResize", "orderedSize"]
  Compare -> ["shortCompare", "shortPrefix", "shortSuffix", "rangeCompare", "aliasCompare"]

signatureArities :: Family -> Map.Map String Int
signatureArities family = Map.fromList $ case family of
  Mutable -> [("setByteArray#",5), ("copyMutableByteArray#",6), ("copyMutableByteArrayNonOverlapping#",6)]
  Resize -> [("resizeMutableByteArray#",3)]
  Size -> [("getSizeofMutableByteArray#",2), ("sizeofMutableByteArray#",1)]
  _ -> []

originalList :: Family -> Bool
originalList family = family == Bytes || family == Compare

signed :: Integer -> Integer
signed value = (value + 2^(63 :: Int)) `mod` 2^(64 :: Int) - 2^(63 :: Int)

unaryInputs :: Family -> String -> [Integer]
unaryInputs family name = Set.toAscList . Set.fromList $ ordinary ++ endpoints ++ extra
  where
    ordinary | family == Bytes = [-512..512]
             | name `elem` ["rangeCompare", "aliasCompare"] = [0..728]
             | otherwise = [-256..256]
    endpoints = [-2^(63 :: Int), -2^(63 :: Int)+1, 2^(63 :: Int)-2, 2^(63 :: Int)-1]
    extra = if family == Compare then [-4097,4097] else []

mutableInputs :: [(Integer,Integer)]
mutableInputs = Set.toAscList . Set.fromList $
  [(x,72) | x <- [-256..511] ++ [signed (sign*(2^bit+d)) | sign <- [-1,1], bit <- [0..63 :: Int], d <- [-1,0,1]]] ++
  [(signed (code*0x123456789abcdef-2^(63 :: Int)),code) | code <- [0..1023]] ++
  [(x,code) | x <- [-2^(63 :: Int),-257,-1,0,255,256,2^(63 :: Int)-1], code <- [-2^(63 :: Int),-1,0,2^(63 :: Int)-1]]

-- Integer/list models are independent of GHC and the JVM implementation. The
-- already-independent Kotlin resize/size models remain the semantic authority
-- for those two families; their native driver owns its original input inventory.
model :: Family -> String -> [Integer] -> Integer
model Bytes name [x]
  | name == "orderedBytes" = 3 + byte x + byte (x+17)*257 + byte (x+2)*65537 + byte (x+71)*16777259
  | name == "copiedBytes" = signed (10 + fingerprint (source ++ replace target (take count (drop start source)) [11,22,33,44,55,66]))
  | name == "shortUncons" = signed (sum [byte (x+17*i)*33^i | i <- [0..size-1]])
  | name == "shortBytes" = signed (foldl (\answer i -> answer*33+byte (x+17*i)) 0 [0..size-1] + size)
  where
    size = abs x `mod` 33
    source = [byte x,byte (x+17),0,255]
    key = fromInteger (x `mod` 1024) :: Int
    start = key `mod` 5
    target = key `div` 5 `mod` 7
    count = minimum [key `div` 35 `mod` 5,4-start,6-target]
model Mutable name [raw,code]
  | name == "publicReplicate" = signed (foldl (\answer _ -> answer*257+byte raw) (code .&. 15) [1..code .&. 15])
  | name == "filledBytes" = signed (fingerprint (replace fillStart (replicate fillCount (byte raw)) source))
  | name `elem` ["movedBytes","disjointBytes"] = signed (fingerprint (replace target (take count (drop start source)) source))
  | name `elem` ["copiedMutableBytes","copiedDisjointBytes"] = signed (fingerprint (replace 0 [byte (raw+93)] source) + 65537*fingerprint copied)
  where
    source = [byte (raw+17*i) | i <- [0..7]]
    destination = [byte (raw+101+29*i) | i <- [0..7]]
    key = fromInteger (code .&. 1023) :: Int
    fillStart = key `mod` 9
    fillCount = min (key `div` 9 `mod` 9) (8-fillStart)
    low = key `mod` 5
    high = 4+key `div` 5 `mod` 5
    (start,target,count)
      | name == "disjointBytes" = let n = minimum [key `div` 25 `mod` 5,4-low,8-high]
                                  in if odd (key `div` 125) then (high,low,n) else (low,high,n)
      | otherwise = let a = key `mod` 9; b = key `div` 9 `mod` 9
                    in (a,b,minimum [key `div` 81 `mod` 9,8-a,8-b])
    copied = replace target (take count (drop start source)) destination
model Compare name [raw]
  | name == "shortCompare" = ordering [x,0,128,255] (choose (key `mod` 5) [[x,0,128,255],[x,0,128],[x,0,128,254],[x,0,129,0],[]])
  | name == "shortPrefix" = truth ([x,0,128] `isPrefixOf` choose (key `mod` 3) [[],[x,0,128,255],[x,0,129,255]])
  | name == "shortSuffix" = truth ([128,x] `isSuffixOf` choose (key `mod` 3) [[],[0,255,128,x],[0,255,129,x]])
  | name `elem` ["rangeCompare","aliasCompare"] = ordering (take count (drop start a)) (take count (drop target b))
  where
    key = fromInteger (raw .&. 4095) :: Int
    x = byte raw
    a = [x,0,127,128,255,17,0,255]
    b = if name == "aliasCompare" then a else [255,0,127,128,x,17,255,0]
    start = key `mod` 9
    target = key `div` 9 `mod` 9
    count = minimum [key `div` 81 `mod` 9,8-start,8-target]
model _ name arguments = error ("No byte-array model for " ++ name ++ show arguments)

byte :: Integer -> Integer
byte value = value `mod` 256

fingerprint :: [Integer] -> Integer
fingerprint values = sum (zipWith (*) values (iterate (*257) 1))

replace :: Int -> [a] -> [a] -> [a]
replace offset values source = take offset source ++ values ++ drop (offset+length values) source

choose :: Int -> [a] -> a
choose index values = case drop index values of
  value:_ -> value
  [] -> error "Invalid fixed byte-array selector"

ordering :: Ord a => a -> a -> Integer
ordering a b = case compare a b of LT -> -1; EQ -> 0; GT -> 1

truth :: Bool -> Integer
truth yes = if yes then 1 else 0

readJson :: FilePath -> IO Value
readJson path = BS.readFile path >>= either (die . ((path ++ ": ") ++)) pure . eitherDecodeStrict'

field :: FromJSON a => String -> Value -> IO a
field name (Object values) = case KeyMap.lookup (Key.fromString name) values of
  Just value -> case fromJSON value of Success result -> pure result; Error message -> die (name ++ ": " ++ message)
  Nothing -> die ("Missing byte-array evidence field " ++ name)
field name _ = die ("Expected object with " ++ name)

check :: Bool -> String -> IO ()
check condition message = unless condition (die message)

prepareByteArrayFamily :: FilePath -> String -> IO ()
prepareByteArrayFamily root command = do
  family <- case [candidate | candidate <- [Bytes,Mutable,Resize,Size,Compare], familyName candidate == command] of
    [candidate] -> pure candidate
    _ -> die ("Unknown byte-array family " ++ command)
  let directory = "build" </> command
      logs = directory </> "commands"
      manifest = root </> directory </> "manifest.json"
      source = "compiler/test-fixtures" </> moduleName family ++ ".hs"
      names = entries family
      logRun label env executable args = runLogged 300 root logs label env executable args
  createDirectoryIfMissing True (root </> directory)
  old <- doesFileExist manifest
  when old $ do
    digest <- hashFile manifest
    createDirectoryIfMissing True (root </> directory </> "previous-manifests")
    copyFile manifest (root </> directory </> "previous-manifests" </> digest ++ ".json")
    removeFile manifest
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  ghcPkg <- maybe "ghc-pkg" id <$> lookupEnv "GHC_PKG"
  version <- logRun "ghc-version" [] ghc ["--numeric-version"]
  check (BSC.unpack (commandStdout version) == "9.14.1\n") "Byte-array fixtures require GHC 9.14.1"
  ghcInfo <- logRun "ghc-info" [] ghc ["--info"]
  let info = BSC.unpack (commandStdout ghcInfo)
  check (maybe False (\fields -> lookup "target word size" fields == Just "8") (readMaybe info :: Maybe [(String,String)])) "Byte-array fixtures require 64-bit GHC"
  package <- logRun "bytestring-version" [] ghcPkg ["field","bytestring","version","--simple-output"]
  check (BSC.unpack (commandStdout package) == "0.12.2.0\n") "Byte-array fixtures require bytestring-0.12.2.0"
  description <- logRun "bytestring-description" [] ghcPkg ["describe","bytestring"]
  signatureCommands <- if Map.null (signatureArities family) then pure [] else do
    compiler <- logRun "compiler-build" [] "compiler/build.sh" []
    coverage <- logRun "primop-coverage" [] "python3" ["scripts/primop-coverage.py"]
    pure [compiler,coverage]
  signatures <- if Map.null (signatureArities family) then pure [] else do
    inventory <- readJson (root </> "build/primop-coverage.json") >>= field "primitives" :: IO [Value]
    selected <- fmap concat $ forM inventory $ \primitive -> do
      name <- field "name" primitive
      pure [primitive | Map.member name (signatureArities family)]
    arities <- forM selected $ \primitive -> (,) <$> field "name" primitive <*> field "valueArity" primitive
    check (Map.fromList arities == signatureArities family) "Byte-array primitive signatures changed"
    pure selected
  stages <- forM ["pre","post"] $ \stage -> do
    let base = directory </> stage
        core = if originalList family then base </> "core" else directory </> stage ++ "-core"
        ghcOut = if originalList family then base </> "ghc" else directory </> stage ++ "-ghc"
        env = [("THC_CORE_OUT",root </> core),("THC_GHC_OUT",root </> ghcOut),("THC_SOURCE_NOTES","true")]
    exported <- logRun (stage ++ "-export") env "compiler/export.sh"
      (["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++ ["-fplugin-opt=THC.Plugin:closure=" ++ name | name <- names] ++ [source])
    boot <- if originalList family then do
      commandResult <- logRun (stage ++ "-original-list") env "python3" ["compiler/export-boot.py","--frontier","lists","--build-dir",root </> base]
      let provenance = base </> "boot-provenance.json"
      sources <- readJson (root </> provenance) >>= field "sources" :: IO [Value]
      paths <- forM sources $ \item -> do
        path <- field "path" item
        expected <- field "sha256" item
        actual <- hashFile (root </> path)
        check (expected == actual) ("Original List source changed: " ++ path)
        pure path
      pure ([commandResult],[provenance],paths)
      else pure ([],[],[])
    paths <- map (core </>) . sort . filter ((== ".json") . takeExtension) <$> listDirectory (root </> core)
    boundary <- readJson (root </> core </> moduleName family ++ ".json") >>= field "boundary"
    check (boundary == (if stage == "pre" then "optimized-Core-before-Tidy" else "optimized-Core-after-Tidy-before-CorePrep" :: String)) "Wrong byte-array Core stage"
    audited <- forM names $ \name -> do
      let reportPath = if originalList family then base </> name ++ ".audit.json" else directory </> stage ++ "-" ++ name ++ ".audit.json"
      commandResult <- logRun (stage ++ "-" ++ name ++ "-audit") [] "python3" (["scripts/audit-core.py","--entry",name,"--output",reportPath] ++ paths)
      report <- readJson (root </> reportPath)
      validateAudit family name report
      reachable <- field "reachableBindings" report :: IO [Value]
      primitives <- field "primitives" report :: IO [Value]
      counts <- forM primitives $ \primitive -> do
        primitiveName <- field "name" primitive :: IO String
        uses <- field "uses" primitive :: IO [Value]
        pure (primitiveName,length uses)
      issues <- field "issues" report :: IO [Value]
      missing <- field "missingGlobals" report :: IO [Value]
      summary <- if family == Compare then field "summary" report else pure (object
        ["reachable" .= reachable,"primitiveCounts" .= Map.fromList counts,"issues" .= issues,"missing" .= missing])
      pure (name,reachable,summary,reportPath,commandResult)
    let (bootCommands,bootArtifacts,bootSources) = boot
    pure (stage,paths,audited,exported:bootCommands,bootArtifacts,bootSources)
  let native = directory </> "native"
      binary = native </> (if family == Bytes then "bytearray-oracle" else command ++ "-oracle")
      generated = family `elem` [Bytes,Mutable,Compare]
      nativeModule = case family of Resize -> "ResizeByteArrayNative"; Size -> "MutableByteArraySizeNative"; _ -> "Main"
      driver = if generated then directory </> (case family of
                 Bytes -> "NativeByteArray.hs"; Mutable -> "NativeMutableByteArrays.hs"; _ -> "NativeCompareByteArrays.hs")
               else "compiler/test-fixtures" </> nativeModule ++ ".hs"
  createDirectoryIfMissing True (root </> native)
  when generated (writeFile (root </> driver) (nativeDriver family))
  compiled <- logRun "native-build" [] ghc (["--make","-O2","-fforce-recomp","-dcore-lint","-dstg-lint","-icompiler/test-fixtures",
    "-odir",root </> native,"-hidir",root </> native] ++ [arg | not generated, arg <- ["-main-is",nativeModule ++ ".main"]] ++ [driver,"-o",root </> binary])
  (pairs,inputCommands) <- if generated then pure (mutableInputs,[]) else do
    result <- logRun "native-inputs" [] (root </> binary) ["--inputs"]
    pairs <- forM (lines (BSC.unpack (commandStdout result))) $ \line -> case traverse readInteger (splitTab line) of
      Just [raw,code] -> pure (raw,code)
      _ -> die "Malformed native byte-array input inventory"
    check (not (null pairs) && pairs == Set.toAscList (Set.fromList pairs)) "Duplicate/unordered native input inventory"
    pure (pairs,[result])
  let requests = [(name,arguments) | name <- names, arguments <- if family `elem` [Bytes,Compare]
        then [[x] | x <- unaryInputs family name] else [[raw,code] | (raw,code) <- pairs]]
      requestText = unlines [joinTabs (name:map show arguments) | (name,arguments) <- requests]
  writeFile (root </> directory </> "requests.tsv") requestText
  observed <- if generated then runLoggedWithInput (directory </> "requests.tsv") 60 root logs "native-oracle" [] (root </> binary) []
              else logRun "native-oracle" [] (root </> binary) []
  let oracle = commandStdout observed
      rows = map splitTab (lines (BSC.unpack oracle))
  parsed <- forM rows $ \row -> case row of
    name:fields | Just numbers <- traverse readInteger fields, length numbers == (if family `elem` [Bytes,Compare] then 2 else 3) ->
      case reverse numbers of result:arguments -> pure ((name,reverse arguments),result); [] -> die "Empty native row"
    _ -> die "Malformed native byte-array row"
  check (map fst parsed == requests) "Missing, duplicate, reordered or unknown native byte-array rows"
  when generated $ check (all (\((name,arguments),value) -> value == model family name arguments) parsed) "Native byte-array/model mismatch"
  BS.writeFile (root </> directory </> "oracle.tsv") oracle
  let commands = [version,ghcInfo,package,description] ++ signatureCommands ++
        concat [stageCommands ++ [result | (_,_,_,_,result) <- audited] | (_,_,audited,stageCommands,_,_) <- stages] ++ [compiled] ++ inputCommands ++ [observed]
      originalSources = concat [paths | (_,_,_,_,_,paths) <- stages]
  compilerInputs <- map ("compiler/THC" </>) . filter ((== ".hs") . takeExtension) <$> listDirectory (root </> "compiler/THC")
  auditorInputs <- map ("scripts" </>) . filter (\name -> "core_" `isPrefixOf` name && takeExtension name == ".py") <$> listDirectory (root </> "scripts")
  inputHashes <- hashes root . sort . Set.toList . Set.fromList $ [source,"test/haskell-fixtures/ByteArrayFixtures.hs",
    "test/haskell-fixtures/FixtureSupport.hs","test/haskell-fixtures/Main.hs","thc.cabal","scripts/audit-core.py","scripts/core-capabilities.json",
    "src/main/resources/thc/scalar-primop-signatures.json","compiler/build.sh","compiler/export.sh","compiler/toolchain.sh","compiler/plugin.py"] ++
    ["compiler/export-boot.py" | originalList family] ++ ["scripts/primop-coverage.py" | not (Map.null (signatureArities family))] ++
    [path | not generated, path <- [driver,"compiler/test-fixtures/ByteArrayFixtureInputs.hs"]] ++ compilerInputs ++ auditorInputs ++ originalSources
  artifactHashes <- hashes root . sort . Set.toList . Set.fromList $ [directory </> "requests.tsv",directory </> "oracle.tsv",binary] ++
    [driver | generated] ++ concatMap commandArtifacts commands ++ concat
      [paths ++ [path | (_,_,_,path,_) <- audited] ++ bootArtifacts | (_,paths,audited,_,bootArtifacts,_) <- stages]
  writeJson manifest $ object (["schema" .= (1 :: Int),"ghc" .= ("9.14.1" :: String),"bytestring" .= ("0.12.2.0" :: String),"wordBits" .= (64 :: Int),
    "entries" .= names,"stages" .= Map.fromList [(stage,paths) | (stage,paths,_,_,_,_) <- stages],"nativeRows" .= length parsed,
    "audits" .= Map.fromList [(stage ++ "/" ++ name,summary) | (stage,_,audited,_,_,_) <- stages, (name,_,summary,_,_) <- audited],
    "inputHashes" .= inputHashes,"artifactHashes" .= artifactHashes,"commands" .= map commandRecord commands,"ghcInfo" .= info,
    "installedBytestring" .= BSC.unpack (commandStdout description),"primopSignatures" .= signatures,
    "limitations" .= (["Native inputs obey each size, lifetime and overlap precondition; managed rejection controls remain separate.",
      "Original List/ShortByteString identities are preserved; public comparison results are sign-only.",
      "Existing Python audit-core.py, export-boot.py and primop-coverage.py remain explicit shared dependencies."] :: [String])] ++
    ["inputs" .= [[raw,code] | (raw,code) <- pairs] | family `elem` [Mutable,Resize,Size]] ++
    ["modelValidation" .= ("Independent Kotlin model and complete ordered corpus" :: String)] ++
    ["reachableBindings" .= Map.fromList [(stage ++ "/" ++ name,reachable) | (stage,_,audited,_,_,_) <- stages,(name,reachable,_,_,_) <- audited] | family == Bytes] ++
    (if family == Compare then ["resultContract" .= ("sign only" :: String),"inputsByEntry" .= Map.fromList [(name,unaryInputs family name) | name <- names]] else []) ++
    (case family of
      Mutable -> ["contractSource" .= ("ghc-9.14.1-release/compiler/GHC/Builtin/primops.txt.pp:2133-2155,2231-2238" :: String),
                  "loweringSource" .= ("ghc-9.14.1-release/compiler/GHC/StgToCmm/Prim.hs:2705-2728,2808-2819" :: String)]
      Resize -> ["contractSource" .= ("ghc-9.14.1-release/compiler/GHC/Builtin/primops.txt.pp:2041-2060" :: String)]
      Size -> ["contractSource" .= ("ghc-9.14.1-release/compiler/GHC/Builtin/primops.txt.pp:2081-2094" :: String)]
      _ -> []))
  putStrLn (command ++ ": " ++ show (length parsed) ++ " ordered native rows; " ++ show (2*length names) ++ " strict audits")

joinTabs :: [String] -> String
joinTabs [] = ""
joinTabs (value:rest) = value ++ concatMap ('\t':) rest

nativeDriver :: Family -> String
nativeDriver family = unlines $
  ["{-# LANGUAGE MagicHash #-}","module Main where","import GHC.Exts (Int(I#), Int#)","import qualified " ++ moduleName family ++ " as P"] ++
  (if family == Mutable then
    ["call :: String -> Int -> Int -> Int","call name (I# raw) (I# code) = case name of"] ++
    ["  " ++ show name ++ " -> I# (P." ++ name ++ " raw code)" | name <- entries family] ++
    ["  _ -> error \"unknown mutable byte-array entry\"","dispatch :: [String] -> IO ()",
     "dispatch [name,raw,code] = putStrLn (name ++ \"\\t\" ++ raw ++ \"\\t\" ++ code ++ \"\\t\" ++ show (call name (read raw) (read code)))"]
   else ["emit :: String -> (Int# -> Int#) -> Int -> IO ()",
     "emit name function raw@(I# x) = putStrLn (name ++ \"\\t\" ++ show raw ++ \"\\t\" ++ show (I# (function x)))",
     "dispatch :: [String] -> IO ()","dispatch [name,raw] = case name of"] ++
     ["  " ++ show name ++ " -> emit name P." ++ name ++ " (read raw)" | name <- entries family] ++ ["  _ -> error \"unknown byte-array entry\""]) ++
  ["dispatch _ = error \"invalid input\"","main :: IO ()","main = getContents >>= mapM_ (dispatch . words) . lines"]

validateAudit :: Family -> String -> Value -> IO ()
validateAudit family name report = do
  accepted <- field "accepted" report
  issues <- field "issues" report :: IO [Value]
  missing <- field "missingGlobals" report :: IO [Value]
  check (accepted && null issues && null missing) ("Strict byte-array audit rejected " ++ name)
  primitives <- field "primitives" report :: IO [Value]
  pairs <- forM primitives $ \primitive -> do
    primitiveName <- field "name" primitive :: IO String
    uses <- field "uses" primitive :: IO [Value]
    pure (primitiveName,length uses)
  reachable <- field "reachableBindings" report >>= mapM (field "id") :: IO [String]
  let counts = Map.fromList pairs
      required = case family of
        Bytes -> ["newByteArray#","writeWord8Array#","unsafeFreezeByteArray#","sizeofByteArray#","indexWord8Array#"] ++ ["copyByteArray#" | name `elem` ["shortUncons","copiedBytes"]]
        Mutable -> [if name `elem` ["filledBytes","publicReplicate"] then "setByteArray#" else if name `elem` ["disjointBytes","copiedDisjointBytes"] then "copyMutableByteArrayNonOverlapping#" else "copyMutableByteArray#"]
        Resize -> ["resizeMutableByteArray#"]
        Size -> [if name `elem` ["pureSize","pureAfterResize"] then "sizeofMutableByteArray#" else "getSizeofMutableByteArray#"]
        Compare -> ["compareByteArrays#"]
      identities = case family of
        Bytes | name == "shortBytes" -> ["Data.ByteString.Short.Internal.$wpack","Data.ByteString.Short.Internal.$wgo","GHC.Internal.List.$wlenAcc"]
              | name == "shortUncons" -> ["Data.ByteString.Short.Internal.$wuncons","Data.ByteString.Short.Internal.$wpack","GHC.Internal.List.$wlenAcc"]
        Mutable | name == "publicReplicate" -> ["Data.ByteString.Short.Internal.empty"]
        Resize -> ["resizeWorker"]
        Size -> [if name `elem` ["pureSize","pureAfterResize"] then "pureSizeWorker" else "getSizeWorker"]
        Compare -> ["Data.ByteString.Short.Internal.$wpack"]
        _ -> []
  check (all (`Map.member` counts) required) ("Required byte-array primitive disappeared: " ++ name)
  check (all (\suffix -> any (isSuffixOf (if '.' `elem` suffix then ':' : suffix else '.' : suffix)) reachable) identities) ("Original/opaque byte-array binding disappeared: " ++ name)
  when (name == "orderedBytes") $ check (all (\(primitive,count) -> Map.lookup primitive counts == Just count)
    [("newByteArray#",2),("unsafeFreezeByteArray#",2),("writeWord8Array#",5),("indexWord8Array#",4)]) "Ordered byte-array primitive counts changed"
  when (name == "copiedBytes") $ check (Map.lookup "copyByteArray#" counts == Just 3) "Expected three immutable copies"
