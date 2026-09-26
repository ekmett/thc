-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module Simd128AddressFixtures (prepareSimd128Addresses) where

import Control.Monad (forM, unless, when)
import Data.Aeson (Value(..), FromJSON, fromJSON, Result(..), object, (.=), eitherDecodeStrict', toJSON)
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BSC
import Data.Foldable (toList)
import Data.List (isPrefixOf, sort)
import qualified Data.Map.Strict as Map
import Data.String (fromString)
import qualified Data.Text as Text
import FixtureSupport
import System.Directory
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath
import System.Info (arch)

families :: [(String, Int, Int)]
families = [("int8",16,1),("word8",16,1),("int16",8,2),("word16",8,2),("int64",2,8),("word64",2,8)]
entries :: [(String, Int, Bool)]
entries = [(family ++ "X" ++ show lanes ++ operation ++ mode, width, mode == "Scalar") |
  (family,lanes,width) <- families, operation <- ["Index","Read","Write"], mode <- ["Packed","Scalar"]]
seeds :: [Integer]
seeds = [-2^(63::Int), -2^(32::Int)-1, -65537, -32769, -129, -1, 0, 1, 127, 128, 255, 256, 32767, 65535, 2^(32::Int)+1, 2^(63::Int)-1]
requests :: [(String,Integer,Int)]
requests = [(name,seed,offset) | (name,width,scalar) <- entries, seed <- seeds,
  offset <- if scalar then [0,1,2,32 `div` width-1,32 `div` width] else [0,1,2]]

field :: FromJSON a => Value -> String -> IO a
field (Object values) key = case KeyMap.lookup (fromString key) values of
  Just value -> case fromJSON value of Success result -> pure result; Error message -> die message
  Nothing -> die ("Missing SIMD128 field: " ++ key)
field _ key = die ("Expected object for " ++ key)
readJson :: FilePath -> IO Value
readJson path = BS.readFile path >>= either die pure . eitherDecodeStrict'
walk :: Value -> [Value]
walk value = value : concatMap walk (case value of Object fields -> toList fields; Array fields -> toList fields; _ -> [])
nodes :: String -> Value -> [[Value]]
nodes tag value = [toList fields | Array fields <- walk value, take 1 (toList fields) == [String (fromString tag)]]

-- Every retained global/nested lambda is invoked once, on an unconditional
-- path. Fail if Core introduces conditional alternatives or different calls.
structure :: Value -> Value -> String -> IO Value
structure core report name = do
  bindings <- field core "bindings" :: IO [Value]
  indexed <- mapM (\binding -> do ident <- field binding "id"; pure (ident :: String,binding)) bindings
  reached <- field report "reachableBindings" :: IO [Value]
  reachable <- forM reached $ \record -> do
    ident <- field record "id"
    maybe (die "Unresolved reached SIMD128 binding") pure (lookup (ident :: String) indexed)
  roots <- field report "roots" :: IO [String]
  root <- case roots of [ident] -> pure ident; _ -> die "Expected one SIMD128 root"
  functions <- forM reachable $ \binding -> do
    ident <- field binding "id"
    expr <- field binding "expr" :: IO [Value]
    unless (take 1 expr == [String "lam"]) (die ("Non-function SIMD128 reachable binding: " ++ name))
    pure (ident :: String, toJSON expr)
  let forest = map snd functions
      applications = concatMap (nodes "app") forest
      references = [Text.unpack ident | _:Array headValue:_ <- applications,
        [String "var",String ident] <- [take 2 (toList headValue)], Text.unpack ident `elem` map fst indexed]
      immediate = length [() | _:Array headValue:_ <- applications, take 1 (toList headValue) == [String "lam"]]
      kept = length [() | _:Array headValue:Array arguments:_ <- applications,
        take 2 (toList headValue) == [String "prim",String "keepAlive#"],
        [_,_,Array continuation] <- [toList arguments], take 1 (toList continuation) == [String "lam"]]
      lambdas = concatMap (nodes "lam") forest
      cases = concatMap (nodes "case") forest
      counts values = Map.fromListWith (+) [(value,1::Int) | value <- values]
  unless (counts references == counts [ident | (ident,_) <- functions, ident /= root] &&
    length lambdas == length functions + immediate + kept && kept == 1)
    (die ("Changed exact SIMD128 guest calls: " ++ name))
  unless (all (\expr -> case drop 3 expr of Array alternatives:_ -> length alternatives == 1; _ -> False) cases)
    (die ("Conditional SIMD128 guest path: " ++ name))
  pure (object ["guestCalls" .= length lambdas, "globalFunctions" .= map fst functions,
    "immediateLambdas" .= immediate, "keepAliveContinuations" .= kept])

prepareSimd128Addresses :: FilePath -> IO ()
prepareSimd128Addresses root = do
  let directory = "build/simd128-addresses"
      logs = directory </> "commands"
      manifest = root </> directory </> "manifest.json"
      source = "compiler/test-fixtures/Simd128AddressAudit.hs"
      driver = "compiler/test-fixtures/Simd128AddressNative.hs"
      inputs = directory </> "inputs.tsv"
      binary = directory </> "native/oracle"
      execute = runLogged 300 root logs
      exportedOnly = arch `elem` ["aarch64","arm64"]
      stages = if exportedOnly then ["pre"] else ["pre","post"]
  createDirectoryIfMissing True (root </> directory </> "native")
  exists <- doesFileExist manifest
  when exists (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- execute "ghc-version" [] ghc ["--numeric-version"]
  unless (commandStdout version == "9.14.1\n") (die "SIMD128 needs pinned GHC9.14.1")
  writeFile (root </> inputs) (unlines [name ++ "\t" ++ show seed ++ "\t" ++ show offset | (name,seed,offset) <- requests])
  nativeArtifacts <- if exportedOnly then pure [] else do
    built <- execute "native-build" [] ghc ["--make","-O2","-fllvm","-fforce-recomp","-dcore-lint","-dstg-lint",
      "-icompiler/test-fixtures","-odir",directory </> "native","-hidir",directory </> "native",driver,"-o",binary]
    observed <- runLoggedWithInput inputs 120 root logs "native-oracle" [] (root </> binary) []
    rows <- maybe (die "Invalid native SIMD128 row") pure $ traverse (\row -> case splitTab row of
      [name,seed,offset,result] -> do s <- readInteger seed; i <- readInteger offset; _ <- readInteger result; pure (name,s,fromInteger i)
      _ -> Nothing) (lines (BSC.unpack (commandStdout observed)))
    unless (rows == requests && BS.null (commandStderr observed)) (die "Native SIMD128 domain/order changed")
    BS.writeFile (root </> directory </> "oracle.tsv") (commandStdout observed)
    pure ([binary,directory </> "oracle.tsv"] ++ commandArtifacts built ++ commandArtifacts observed)
  exported <- forM stages $ \stage -> do
    let corePath = directory </> stage ++ "-core/Simd128AddressAudit.json"
    compilation <- execute (stage ++ "-export")
      [("THC_CORE_OUT",root </> directory </> stage ++ "-core"),("THC_GHC_OUT",root </> directory </> stage ++ "-ghc")]
      "compiler/export.sh" ((if exportedOnly then ["-fno-code","-fwrite-if-simplified-core"] else ["-fllvm"]) ++
        ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++ [source])
    core <- readJson (root </> corePath)
    reports <- forM entries $ \(name,_,_) -> do
      let path = directory </> stage ++ "-" ++ name ++ "-audit.json"
      command <- execute (stage ++ "-" ++ name ++ "-audit") [] "python3"
        ["scripts/audit-core.py",corePath,"--entry",name,"--output",path]
      report <- readJson (root </> path)
      accepted <- field report "accepted"
      missing <- field report "missingGlobals" :: IO [Value]
      issues <- field report "issues" :: IO [Value]
      unless (accepted && null missing && null issues) (die "Strict SIMD128 audit rejected")
      shape <- structure core report name
      pure (name,object ["audit" .= path,"structure" .= shape],path:commandArtifacts command)
    pure (stage,object ["core" .= corePath,"entries" .= Map.fromList [(name,record) | (name,record,_) <- reports]],
      corePath : commandArtifacts compilation ++ concat [paths | (_,_,paths) <- reports])
  plugin <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  inputHashes <- hashes root (sort $ [source,driver,"test/haskell-fixtures/Simd128AddressFixtures.hs",
    "test/haskell-fixtures/FixtureSupport.hs","test/haskell-fixtures/Main.hs","thc.cabal","compiler/export.sh",
    "compiler/build.sh","compiler/toolchain.sh","compiler/plugin.py","scripts/audit-core.py",
    "scripts/core-capabilities.json","scripts/simd-families.json","src/main/resources/thc/scalar-primop-signatures.json"] ++
    ["compiler/THC" </> path | path <- plugin,takeExtension path == ".hs"] ++
    ["scripts" </> path | path <- scripts,"core_" `isPrefixOf` path,takeExtension path == ".py"])
  artifactHashes <- hashes root (inputs : nativeArtifacts ++ commandArtifacts version ++ concat [paths | (_,_,paths) <- exported])
  writeJson manifest (object ["schema" .= (1::Int),"ghc" .= ("9.14.1"::String),"entries" .= [name | (name,_,_) <- entries],
    "requests" .= length requests,"nativeRows" .= (if exportedOnly then Nothing else Just (length requests)),
    "stages" .= Map.fromList [(stage,record) | (stage,record,_) <- exported],
    "inputHashes" .= inputHashes,"artifactHashes" .= artifactHashes])
  putStrLn ("SIMD128 addresses: " ++ show (length requests) ++ " requests, stages " ++ show stages)
