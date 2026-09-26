-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module FloatingRemainderFixtures (prepareFloatingRemainder) where

import Control.Monad (forM, unless, when)
import Data.Aeson (Value(..), eitherDecodeStrict', object, (.=))
import qualified Data.Aeson.KeyMap as KeyMap
import Data.Bits ((.|.), shiftL)
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BSC
import Data.List (isPrefixOf, isSuffixOf, sort)
import qualified Data.Set as Set
import FixtureSupport
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)
import Text.Read (readMaybe)

entries :: [String]
entries = [op ++ ty | ty <- ["Float","Double"], op <- ["asinh","acosh","atanh","min","max"]] ++
  ["decodeWordsDirect","decodeWordsCall","asinhExample"]

signed :: Integer -> Integer
signed x = if x >= 2^(63 :: Int) then x - 2^(64 :: Int) else x

-- Boundary neighbors include the mathematical domain limits, formula switches,
-- every leading subnormal bit, finite extremes, signed zeros and NaN payloads.
inputs :: Int -> [Integer]
inputs width = Set.toAscList . Set.fromList . map signed $
  [sign .|. x | sign <- [0, 2^(width-1)], x <- magnitudes]
  where
    p = if width == 32 then 23 else 52 :: Int
    eb = if width == 32 then 8 else 11 :: Int
    bias = 2^(eb-1)-1
    top = 2^eb-1
    anchors = [0,1,2^p-1,2^p,top `shiftL` p,(top `shiftL` p)-1,
      (top `shiftL` p)+1,(top `shiftL` p)+2^(p-1),(top `shiftL` p)+2^p-1] ++
      [e `shiftL` p | e <- [1,bias-29,bias-28,bias-27,bias-1,bias,bias+1,bias+27,bias+28,bias+29,top-1]] ++
      [2^bit | bit <- [0..p-1]]
    magnitudes = filter (\x -> x >= 0 && x < 2^(width-1))
      ([x+d | x <- anchors, d <- [-1,0,1]] ++ take 128 (map (`mod` 2^(width-1)) (drop 1 (iterate step 0xdecafbad))))
    step x = (x * 6364136223846793005 + 1442695040888963407) `mod` 2^(64 :: Int)

requests :: [(String,Integer,Integer)]
requests = concatMap rows entries where
  rows name
    | "decodeWords" `isPrefixOf` name = [(name,bits,0) | bits <- inputs 64]
    | "min" `isPrefixOf` name || "max" `isPrefixOf` name =
        [(name,a,b) | (a,b) <- Set.toAscList (Set.fromList (zip values (reverse values) ++ [(a,b) | a <- specials, b <- specials]))]
    | otherwise = [(name,bits,0) | bits <- values]
    where
      width = if "Float" `isSuffixOf` name then 32 else 64
      values = inputs width
      p = if width == 32 then 23 else 52 :: Int
      eb = if width == 32 then 8 else 11 :: Int
      bias = 2^(eb-1)-1
      specials = map signed [s .|. x | s <- [0,2^(width-1)],
        x <- [0,1,2^p-1,2^p,bias `shiftL` p,((2^eb-1) `shiftL` p)-1,
              (2^eb-1) `shiftL` p,((2^eb-1) `shiftL` p)+1,((2^eb-1) `shiftL` p)+2^(p-1)]]

prepareFloatingRemainder :: FilePath -> IO ()
prepareFloatingRemainder root = do
  let dir = "build/floating-remainder"
      source = "compiler/test-fixtures/FloatingRemainderAudit.hs"
      driver = "compiler/test-fixtures/FloatingRemainderNative.hs"
      example = "examples/THC/InverseHyperbolic.hs"
      binary = dir </> "native/oracle"
      logs = dir </> "commands"
      input = dir </> "inputs.tsv"
      output = dir </> "oracle.tsv"
      manifest = root </> dir </> "manifest.json"
  createDirectoryIfMissing True (root </> dir </> "native")
  present <- doesFileExist manifest
  when present (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run root [] ghc ["--numeric-version"] ""
  unless (lines version == ["9.14.1"]) (die "Floating remainder requires GHC 9.14.1")
  info <- run root [] ghc ["--info"] ""
  case readMaybe info :: Maybe [(String,String)] of
    Just fields | lookup "target word size" fields == Just "8",
      Just host <- lookup "Host platform" fields, Just target <- lookup "Target platform" fields,
      host == target -> pure ()
    _ -> die "Floating remainder requires native 64-bit GHC"
  writeFile (root </> input) (unlines [unwords [name,show a,show b] | (name,a,b) <- requests])
  compiled <- runLogged 300 root logs "native-build" [] ghc
    ["--make","-O2","-fforce-recomp","-dcore-lint","-dstg-lint","-icompiler/test-fixtures","-iexamples",
     "-odir",root </> dir </> "native","-hidir",root </> dir </> "native",driver,"-o",root </> binary]
  executed <- runLoggedWithInput input 120 root logs "native-oracle" [] (root </> binary) []
  let parse line = case words line of
        [name,a,b,s,h,l,e] -> (,,) name <$> readInteger a <*> readInteger b <*
          traverse readInteger [s,h,l,e]
        _ -> Nothing
  unless (traverse parse (lines (BSC.unpack (commandStdout executed))) == Just requests)
    (die "Malformed or reordered floating remainder oracle")
  BS.writeFile (root </> output) (commandStdout executed)
  stages <- forM ["pre","post"] $ \stage -> do
    exported <- runLogged 300 root logs (stage ++ "-export")
      [("THC_CORE_OUT",root </> dir </> stage ++ "-core"),("THC_GHC_OUT",root </> dir </> stage ++ "-ghc")]
      "compiler/export.sh" (["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++ [source,example])
    let cores = [dir </> stage ++ "-core" </> name ++ ".json" | name <- ["FloatingRemainderAudit","THC.InverseHyperbolic"]]
    audits <- forM entries $ \name -> do
      let path = dir </> stage ++ "-" ++ name ++ "-audit.json"
      audited <- runLogged 120 root logs (stage ++ "-" ++ name ++ "-audit") [] "python3"
        (["scripts/audit-core.py"] ++ cores ++ ["--entry",name,"--output",path])
      report <- BS.readFile (root </> path) >>= either die pure . eitherDecodeStrict'
      case report of
        Object fields | KeyMap.lookup "accepted" fields == Just (Bool True),
          KeyMap.lookup "issues" fields == Just (Array mempty),
          KeyMap.lookup "missingGlobals" fields == Just (Array mempty) -> pure ()
        _ -> die ("Rejected floating remainder Core: " ++ stage ++ "/" ++ name)
      pure (path:commandArtifacts audited)
    pure (cores ++ commandArtifacts exported ++ concat audits)
  plugins <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  inputHashes <- hashes root $ sort $
    [source,driver,example,"thc.cabal","test/haskell-fixtures/Main.hs",
     "test/haskell-fixtures/FixtureSupport.hs","test/haskell-fixtures/FloatingRemainderFixtures.hs",
     "compiler/build.sh","compiler/export.sh","compiler/toolchain.sh","compiler/plugin.py",
     "scripts/audit-core.py","scripts/core-capabilities.json","src/main/resources/thc/scalar-primop-signatures.json"] ++
    ["compiler/THC" </> f | f <- plugins, takeExtension f == ".hs"] ++
    ["scripts" </> f | f <- scripts, take 5 f == "core_" && takeExtension f == ".py"]
  artifactHashes <- hashes root $ [input,output,binary] ++ concat stages ++
    commandArtifacts compiled ++ commandArtifacts executed
  writeJson manifest $ object ["schema" .= (1 :: Int),"ghc" .= ("9.14.1" :: String),
    "ghcInfo" .= info,"entries" .= entries,"nativeRows" .= length requests,
    "inputHashes" .= inputHashes,"artifactHashes" .= artifactHashes]
  putStrLn ("floating-remainder: " ++ show (length requests) ++ " native rows; strict pre/post Core")
