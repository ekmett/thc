-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}
module ScalarBitCastFixtures (prepareScalarBitCasts) where

import Control.Monad (forM, forM_, unless, when)
import Data.Aeson (FromJSON, Value(..), eitherDecodeStrict', fromJSON, Result(..), object, (.=))
import qualified Data.Aeson.KeyMap as KeyMap
import Data.Bits ((.&.), (.|.), shiftL)
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BSC
import Data.Foldable (toList)
import Data.List (isPrefixOf, isSuffixOf, sort)
import qualified Data.Map.Strict as Map
import qualified Data.Set as Set
import Data.String (fromString)
import qualified Data.Text as Text
import FixtureSupport (CommandResult(..), hashes, readInteger, run, runLogged, runLoggedWithInput, splitTab, writeJson)
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)
import Text.Read (readMaybe)

entries :: [String]
entries = [family ++ suffix | family <- ["float", "double"],
  suffix <- ["Roundtrip", "Field", "Captured", "Decode", "Encode"]]

bitcastPrimitives :: [String]
bitcastPrimitives = ["castWord32ToFloat#", "castFloatToWord32#", "castWord64ToDouble#", "castDoubleToWord64#"]

guestCalls :: String -> Int
guestCalls name = case [count | (suffix,count) <-
  [("Roundtrip",5),("Field",6),("Captured",7),("Decode",4),("Encode",4)], suffix `isSuffixOf` name] of
  [count] -> count
  _ -> error "Unknown scalar bitcast entry"

signed64 :: Integer -> Integer
signed64 bits = let word = bits .&. (2^(64 :: Int)-1)
  in if word >= 2^(63 :: Int) then word-2^(64 :: Int) else word

-- Integer encodings only: never convert through a host Float or Double.
inputs :: Int -> [Integer]
inputs width = Set.toAscList (Set.fromList (map signed64 patterns ++ extra))
  where
    fraction = if width == 32 then 23 else 52
    exponentBits = if width == 32 then 8 else 11 :: Int
    exponentMask = (2^exponentBits-1) `shiftL` fraction
    edge = [0,1,2^fraction-1,2^fraction,exponentMask,exponentMask-1]
    patterns = edge ++ [2^width-1] ++ concat
      [map (sign .|.) edge ++
       [sign .|. exponentMask .|. quiet .|. payload | quiet <- [0,2^(fraction-1)],
         payload <- [0..256] ++ [2^bit | bit <- [0..fraction-2]] ++ [2^bit-1 | bit <- [1..fraction-1]]] ++
       [sign .|. 2^bit | bit <- [0..width-1]] | sign <- [0,2^(width-1)]]
    extra = if width /= 32 then [] else
      [-2^(63 :: Int),2^(63 :: Int)-1,-1,-2^(32 :: Int),2^(32 :: Int),
       2^(48 :: Int) .|. 0x7f800001, -(2^(40 :: Int) .|. 0x123456)]

decode :: FromJSON a => Value -> IO a
decode value = case fromJSON value of
  Success result -> pure result
  Error message -> die ("Malformed scalar bitcast evidence: " ++ message)

field :: FromJSON a => String -> Value -> IO a
field key (Object value) = maybe (die ("Missing scalar bitcast field: " ++ key)) decode
  (KeyMap.lookup (fromString key) value)
field key _ = die ("Expected scalar bitcast object for " ++ key)

readJson :: FilePath -> IO Value
readJson path = BS.readFile path >>= either die pure . eitherDecodeStrict'

walk :: Value -> [Value]
walk value = value : concatMap walk (case value of
  Object fields -> toList fields
  Array values -> toList values
  _ -> [])

expressions :: String -> Value -> [[Value]]
expressions tag value = [items | Array values <- walk value, let items = toList values,
  take 1 items == [String (fromString tag)]]

structure :: Value -> Value -> String -> IO Value
structure core report name = do
  bindings <- field "bindings" core :: IO [Value]
  indexed <- forM bindings $ \binding -> do
    ident <- field "id" binding
    pure (ident :: String,binding)
  reached <- field "reachableBindings" report :: IO [Value]
  reachable <- forM reached $ \binding -> do
    ident <- field "id" binding
    maybe (die "Audit reached a missing binding") pure (lookup (ident :: String) indexed)
  described <- forM reachable $ \binding -> do
    ident <- field "id" binding
    label <- field "name" binding
    expr <- field "expr" binding :: IO [Value]
    pure (ident :: String,label :: String,expr)
  roots <- field "roots" report :: IO [String]
  root <- case roots of [ident] -> pure ident; _ -> die "Bitcast audit needs one root"
  let functions = [(ident,expr) | (ident,_,expr) <- described, take 1 expr == [String "lam"]]
      bodies = [expr | (_,expr) <- functions]
      nodes tag = concat [expressions tag item | body <- bodies, item <- body]
      apps = nodes "app"
      cases = nodes "case"
      nested = length (nodes "lam")
      heads = [toList headValue | (_:Array headValue:_) <- apps]
      variableHeads = [Text.unpack raw | String "var":String raw:_ <- heads]
      globals = filter (`elem` map fst indexed) variableHeads
      dynamic = length (filter (`notElem` map fst indexed) variableHeads)
      immediate = length [() | String "lam":_ <- heads]
      cold = [(ident,label,expr) | (ident,label,expr) <- described, take 1 expr /= [String "lam"]]
      countMap xs = Map.fromListWith (+) [(x,1 :: Int) | x <- xs]
  unless (all (\expr -> case drop 3 expr of Array alternatives:_ -> length alternatives == 1; _ -> False) cases)
    (die "New conditional scalar bitcast guest path")
  unless (countMap globals == countMap [ident | (ident,_) <- functions, ident /= root] &&
    dynamic == (if "Captured" `isSuffixOf` name then 1 else 0) &&
    immediate == (if any (`isSuffixOf` name) ["Decode","Encode"] then 1 else 0) &&
    nested == dynamic + immediate && length functions + nested == guestCalls name)
    (die ("Changed retained scalar bitcast calls: " ++ name))
  unless (map (\(_,label,_) -> label) cold == ["bottom" | "Field" `isSuffixOf` name] &&
    all (\(ident,_,expr) -> take 2 expr == [String "var",String (fromString ident)]) cold)
    (die ("Changed scalar bitcast cold bottom: " ++ name))
  primitives <- field "primitives" report :: IO [Value]
  primitiveCounts <- forM primitives $ \primitive -> do
    label <- field "name" primitive
    uses <- field "uses" primitive :: IO [Value]
    pure (label :: String,length uses)
  let family = if "float" `isPrefixOf` name then ["castWord32ToFloat#","castFloatToWord32#"]
        else ["castWord64ToDouble#","castDoubleToWord64#"]
      required = if "Decode" `isSuffixOf` name then take 1 family
        else if "Encode" `isSuffixOf` name then drop 1 family else family
      actual = Map.fromList [(label,count) | (label,count) <- primitiveCounts,
        label `elem` bitcastPrimitives]
  unless (actual == Map.fromList [(label,1) | label <- required]) (die ("Changed bitcast primitives: " ++ name))
  pure $ object ["guestCalls" .= (length functions+nested), "globalFunctions" .= map fst functions,
    "nestedCallbacks" .= dynamic, "stateLambdas" .= immediate,
    "coldBottom" .= [ident | (ident,_,_) <- cold], "primitiveUses" .= actual]
prepareScalarBitCasts :: FilePath -> IO ()
prepareScalarBitCasts root = do
  let directory = "build/scalar-bitcasts"
      output = root </> directory
      source = "compiler/test-fixtures/ScalarBitCastAudit.hs"
      driver = "compiler/test-fixtures/ScalarBitCastNative.hs"
      manifest = output </> "manifest.json"
      logs = directory </> "commands"
      native = directory </> "native"
      binary = native </> "scalar-bitcast-oracle"
  createDirectoryIfMissing True (root </> native)
  present <- doesFileExist manifest
  when present (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run root [] ghc ["--numeric-version"] ""
  unless (lines version == ["9.14.1"]) (die "Scalar bitcasts require GHC 9.14.1")
  info <- run root [] ghc ["--info"] ""
  case readMaybe info :: Maybe [(String,String)] of
    Just fields | lookup "target word size" fields == Just "8",
      Just host <- lookup "Host platform" fields, Just target <- lookup "Target platform" fields,
      host == target -> pure ()
    _ -> die "Scalar bitcasts require native 64-bit GHC"
  -- Aeson preserves the capability document's unsigned 64-bit bounds; the JVM
  -- Core transport reader intentionally accepts only signed Long numbers.
  capabilities <- readJson (root </> "scripts/core-capabilities.json")
  primitiveArities <- field "primitives" capabilities :: IO (Map.Map String Int)
  let bitcastArities = Map.filterWithKey (\name _ -> name `elem` bitcastPrimitives) primitiveArities
  unless (bitcastArities == Map.fromList [(name,1) | name <- bitcastPrimitives])
    (die "Changed pinned scalar bitcast capability arities")
  stages <- forM ["pre","post"] $ \stage -> do
    exported <- runLogged 300 root logs (stage ++ "-export")
      [("THC_CORE_OUT",output </> stage ++ "-core"),("THC_GHC_OUT",output </> stage ++ "-ghc")]
      "compiler/export.sh" (["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++ [source])
    let corePath = directory </> stage ++ "-core/ScalarBitCastAudit.json"
    core <- readJson (root </> corePath)
    reports <- forM entries $ \name -> do
      let reportPath = directory </> stage ++ "-" ++ name ++ "-audit.json"
      -- The existing shared Python auditor remains the capability proof.
      command <- runLogged 120 root logs (stage ++ "-" ++ name ++ "-audit") [] "python3"
        ["scripts/audit-core.py",corePath,"--entry",name,"--output",reportPath]
      report <- readJson (root </> reportPath)
      accepted <- field "accepted" report
      issues <- field "issues" report :: IO [Value]
      missing <- field "missingGlobals" report :: IO [Value]
      unless (accepted && null issues && null missing) (die "Strict scalar bitcast audit rejected")
      shape <- structure core report name
      summary <- field "summary" report :: IO Value
      pure (name,report,shape,summary,reportPath:commandArtifacts command)
    let reportPath = directory </> stage ++ "-audit.json"
    writeJson (root </> reportPath) (object [fromString name .= report | (name,report,_,_,_) <- reports])
    pure (stage,corePath,reports,corePath:reportPath:commandArtifacts exported)
  let requests = [(name,x) | name <- entries, x <- inputs (if "float" `isPrefixOf` name then 32 else 64)]
      requestPath = directory </> "inputs.tsv"
  unless (length (inputs 32) == 1211 && length (inputs 64) == 1500 && length requests == 13555)
    (die "Changed scalar bitcast input corpus")
  writeFile (root </> requestPath) (unlines [name ++ "\t" ++ show x | (name,x) <- requests])
  compiled <- runLogged 300 root logs "native-build" [] ghc
    ["--make","-O2","-fforce-recomp","-dcore-lint","-dstg-lint","-icompiler/test-fixtures",
     "-odir",root </> native,"-hidir",root </> native,driver,"-o",root </> binary]
  executed <- runLoggedWithInput requestPath 120 root logs "native-oracle" [] (root </> binary) []
  let parse line = case splitTab line of
        [name,x,result] -> (,,) name <$> readInteger x <*> readInteger result
        _ -> Nothing
  rows <- maybe (die "Malformed scalar bitcast native output") pure
    (traverse parse (lines (BSC.unpack (commandStdout executed))))
  unless ([(name,x) | (name,x,_) <- rows] == requests) (die "Incomplete scalar bitcast native corpus")
  forM_ rows $ \(name,x,result) -> unless
    (result == if "float" `isPrefixOf` name then x .&. 0xffffffff else signed64 x)
    (die ("Scalar bitcast native bits disagree: " ++ show (name,x,result)))
  BS.writeFile (output </> "oracle.tsv") (commandStdout executed)
  plugins <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  let sources = sort $ [source,driver,"thc.cabal","test/haskell-fixtures/Main.hs",
        "test/haskell-fixtures/FixtureSupport.hs","test/haskell-fixtures/ScalarBitCastFixtures.hs",
        "scripts/core-capabilities.json","scripts/audit-core.py","scripts/generate-scalar-signatures.py",
        "src/main/resources/thc/scalar-primop-signatures.json","compiler/build.sh","compiler/export.sh",
        "compiler/toolchain.sh","compiler/plugin.py"] ++
        ["compiler/THC" </> name | name <- plugins, takeExtension name == ".hs"] ++
        ["scripts" </> name | name <- scripts, "core_" `isPrefixOf` name, takeExtension name == ".py"]
      artifacts = [requestPath,binary,directory </> "oracle.tsv"] ++ commandArtifacts compiled ++ commandArtifacts executed ++
        concat [paths ++ concat [more | (_,_,_,_,more) <- reports] | (_,_,reports,paths) <- stages]
  inputHashes <- hashes root sources
  artifactHashes <- hashes root artifacts
  writeJson manifest $ object ["schema" .= (1 :: Int),"ghc" .= ("9.14.1" :: String),"ghcInfo" .= info,
    "entries" .= entries,"stages" .= Map.fromList [(stage,path) | (stage,path,_,_) <- stages],
    "nativeRows" .= length rows,"inputsByWidth" .= Map.fromList [(show width,inputs width) | width <- [32,64]],
    "expectedGuestCalls" .= Map.fromList [(name,guestCalls name) | name <- entries],
    "bitcastPrimitiveArities" .= bitcastArities,
    "audits" .= Map.fromList [(stage ++ "/" ++ name,summary) | (stage,_,reports,_) <- stages, (name,_,_,summary,_) <- reports],
    "structure" .= Map.fromList [(stage ++ "/" ++ name,shape) | (stage,_,reports,_) <- stages, (name,_,shape,_,_) <- reports],
    "inputHashes" .= inputHashes,"artifactHashes" .= artifactHashes,
    "claim" .= ("Exact integer bits, including NaN payload/signalling/sign and signed zero; no Float equality or arithmetic NaN claim." :: String)]
  putStrLn "scalar-bitcasts: 13555 exact native rows; ten strict pre/post roots and retained guest counts"
