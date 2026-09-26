-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module SimdArithmeticFixtures (prepareSimdArithmetic) where

import Control.Monad (forM, unless, when)
import Data.Aeson (Value(..), FromJSON, fromJSON, Result(..), object, (.=), eitherDecodeStrict')
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BSC
import Data.Char (toLower)
import Data.List (intercalate, isPrefixOf, sort)
import Data.String (fromString)
import FixtureSupport
import System.Directory
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath

data Shape = Shape { shapeName :: String, scalar :: String, lanes :: Int, width :: Int }
data Entry = Entry { shape :: Shape, operation :: String, patternId :: Int }
name :: Entry -> String
name entry = operation entry ++ shapeName (shape entry) ++
  if operation entry == "shuffle" then "Pattern" ++ show (patternId entry) else ""
primitive :: Entry -> String
primitive entry = operation entry ++ shapeName (shape entry) ++ "#"
floating :: Shape -> Bool
floating s = elem (scalar s) ["Float", "Double"]
unsigned :: Shape -> Bool
unsigned = isPrefixOf "Word" . scalar
field :: FromJSON a => Value -> String -> IO a
field (Object values) key = case KeyMap.lookup (fromString key) values of
  Just value -> case fromJSON value of Success result -> pure result; Error message -> die message
  Nothing -> die ("Missing SIMD arithmetic field: " ++ key)
field _ key = die ("Expected object for " ++ key)
readJson :: FilePath -> IO Value
readJson path = BS.readFile path >>= either die pure . eitherDecodeStrict'
convert :: Shape -> String -> String
convert s value
  | floating s = "castWord" ++ show (width s) ++ "To" ++ scalar s ++ "# (wordToWord" ++ show (width s) ++ "# (int2Word# (" ++ value ++ ")))"
  | unsigned s = "wordTo" ++ scalar s ++ "# (int2Word# (" ++ value ++ "))"
  | otherwise = "intTo" ++ scalar s ++ "# (" ++ value ++ ")"
observe :: Shape -> String -> String
observe s value
  | floating s = "word2Int# (word" ++ show (width s) ++ "ToWord# (cast" ++ scalar s ++ "ToWord" ++ show (width s) ++ "# (" ++ value ++ ")))"
  | unsigned s = "word2Int# (" ++ lower (scalar s) ++ "ToWord# (" ++ value ++ "))"
  | otherwise = lower (scalar s) ++ "ToInt# (" ++ value ++ ")"
  where lower [] = []; lower (c:cs) = toLower c : cs
input :: Shape -> Bool -> Int -> String
input s right lane = convert s ((if right then "b -# " else "a +# ") ++ show (lane * if right then 7919 else 104729) ++ "#")
indices :: Entry -> [Int]
indices entry = [pick lane | lane <- [0..count-1]] where
  count = lanes (shape entry)
  pick lane = case patternId entry of
    0 -> count - 1 - lane + if odd lane then count else 0
    1 -> lane + 1
    _ -> if odd lane then count else 0
checksum :: [String] -> String
checksum values = foldr (\(lane,value) total -> "(" ++ value ++ ") *# " ++ show (2*lane+1 :: Int) ++ "# +# (" ++ total ++ ")")
  "0#" (zip [0..] values)
definition :: Bool -> Entry -> [String]
definition native entry =
  ["{-# NOINLINE " ++ name entry ++ " #-}", name entry ++ " :: Int# -> Int# -> Int#",
   name entry ++ " a b = " ++ body, ""]
  where
    s = shape entry
    count = lanes s
    pack right = "pack" ++ shapeName s ++ "# (# " ++ intercalate ", " [input s right lane | lane <- [0..count-1]] ++ " #)"
    vector = primitive entry ++ " (" ++ pack False ++ ") (" ++ pack True ++ ")" ++
      if operation entry == "shuffle" then " (# " ++ intercalate ", " [show index ++ "#" | index <- indices entry] ++ " #)" else ""
    scalarValue lane
      | operation entry == "shuffle" = let index = indices entry !! lane in input s (index >= count) (mod index count)
      | otherwise = operation entry ++ scalar s ++ "# (" ++ input s False lane ++ ") (" ++ input s True lane ++ ")"
    body | native = checksum [observe s (scalarValue lane) | lane <- [0..count-1]]
         | otherwise = "case unpack" ++ shapeName s ++ "# (" ++ vector ++ ") of { (# " ++
             intercalate ", " ["p" ++ show lane | lane <- [0..count-1]] ++ " #) -> " ++
             checksum [observe s ("p" ++ show lane) | lane <- [0..count-1]] ++ " }"
moduleSource :: Bool -> [Entry] -> String
moduleSource native entries = unlines $
  ["{-# LANGUAGE MagicHash, UnboxedTuples #-}", "module " ++ (if native then "SimdArithmeticScalar" else "SimdArithmeticAudit") ++ " where",
   "import GHC.Exts", "import GHC.Prim (" ++ intercalate ", " (map primitive [entry | entry <- entries, patternId entry == 0]) ++ ")"] ++
  concatMap (definition native) entries

requestRows :: [Entry] -> [(String,Integer,Integer)]
requestRows entries = [(name entry,a,b) | entry <- entries, (a,b) <- pairs entry, defined entry a b]
  where
    signed bits value = mod (value + 2^(bits-1)) (2^bits) - 2^(bits-1)
    pairs entry
      | floating (shape entry) = if width (shape entry) == 32
          then [(0,0x80000000),(0x3f800000,0x7fc01234),(1,0x7f800000),(-1,0x3f800000)]
          else [(0,-2^(63::Int)),(0x3ff0000000000000,0x7ff8000000001234),(1,0x7ff0000000000000),(-1,0x3ff0000000000000)]
      | otherwise = [(7,-3),(-1,2),(-2^(bits-1),3),(2^(bits-1)-1,7),(2^bits-1,1),(129,31)]
      where bits = width (shape entry)
    defined entry a b = operation entry == "shuffle" || all valid [0..lanes s-1]
      where
        s = shape entry
        narrow value = if unsigned s then mod value (2^(width s)) else signed (width s) value
        valid lane = let left = narrow (a + toInteger lane*104729); right = narrow (b-toInteger lane*7919)
          in right /= 0 && (unsigned s || left /= -2^(width s-1) || right /= -1)

prepareSimdArithmetic :: FilePath -> IO ()
prepareSimdArithmetic root = do
  specification <- readJson (root </> "scripts/simd-families.json")
  raw <- field specification "families" :: IO [Value]
  admitted <- forM raw $ \item -> do
    shapeId <- field item "name"
    rep <- field item "laneRep" :: IO String
    count <- field item "lanes"
    bits <- field item "bits"
    operations <- field item "operations" :: IO [String]
    pure (Shape shapeId (take (length rep-3) rep) count (div bits count), elem "shuffle" operations)
  let shapes = [s | (s,True) <- admitted]
      entries = [Entry s op patternIndex | s <- shapes, op <- (if floating s then [] else ["quot","rem"]) ++ ["shuffle"],
        patternIndex <- if op == "shuffle" then [0,1,2] else [0]]
      requests = requestRows entries
      directory = "build/simd-arithmetic"
      generated = directory </> "sources"
      source = generated </> "SimdArithmeticAudit.hs"
      scalarSource = generated </> "SimdArithmeticScalar.hs"
      driver = generated </> "Native.hs"
      inputs = directory </> "inputs.tsv"
      binary = directory </> "native/oracle"
      core = directory </> "pre-core/SimdArithmeticAudit.json"
      manifest = root </> directory </> "manifest.json"
      logs = directory </> "commands"
      execute = runLogged 300 root logs
  createDirectoryIfMissing True (root </> generated)
  createDirectoryIfMissing True (root </> directory </> "native")
  present <- doesFileExist manifest
  when present (removeFile manifest)
  writeFile (root </> source) (moduleSource False entries)
  writeFile (root </> scalarSource) (moduleSource True entries)
  writeFile (root </> driver) (unlines $
    ["{-# LANGUAGE MagicHash #-}", "module Main where", "import GHC.Exts", "import SimdArithmeticScalar",
     "result :: String -> Int# -> Int# -> Int", "result entry a b = case entry of"] ++
    ["  " ++ show (name entry) ++ " -> I# (" ++ name entry ++ " a b)" | entry <- entries] ++
    ["  _ -> error \"Unknown arithmetic entry\"",
     "emit :: [String] -> IO ()", "emit [entry,left,right] = case (read left,read right) of",
     "  (I# a,I# b) -> putStrLn (entry ++ \"\\t\" ++ left ++ \"\\t\" ++ right ++ \"\\t\" ++ show (result entry a b))",
     "emit _ = error \"Malformed arithmetic request\"", "main :: IO ()", "main = getContents >>= mapM_ (emit . words) . lines"])
  writeFile (root </> inputs) (unlines [entry ++ "\t" ++ show (wrap a) ++ "\t" ++ show (wrap b) | (entry,a,b) <- requests])
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- execute "ghc-version" [] ghc ["--numeric-version"]
  unless (commandStdout version == "9.14.1\n") (die "SIMD arithmetic requires GHC 9.14.1")
  nativeBuild <- execute "native-build" [] ghc ["--make","-O2","-fforce-recomp","-dcore-lint","-dstg-lint",
    "-i" ++ generated,"-odir",directory </> "native","-hidir",directory </> "native",driver,"-o",binary]
  observed <- runLoggedWithInput inputs 120 root logs "native-oracle" [] (root </> binary) []
  rows <- maybe (die "Invalid scalar arithmetic oracle rows") pure $ traverse (\row -> case splitTab row of
    [entry,a,b,result] -> do x <- readInteger a; y <- readInteger b; _ <- readInteger result; pure (entry,x,y)
    _ -> Nothing) (lines (BSC.unpack (commandStdout observed)))
  unless (rows == [(entry,wrap a,wrap b) | (entry,a,b) <- requests] && BS.null (commandStderr observed))
    (die "Native arithmetic domain changed")
  BS.writeFile (root </> directory </> "oracle.tsv") (commandStdout observed)
  exported <- execute "pre-export" [("THC_CORE_OUT",root </> directory </> "pre-core"),("THC_GHC_OUT",root </> directory </> "ghc")]
    "compiler/export.sh" ["-fno-code","-fwrite-if-simplified-core",source]
  audits <- forM entries $ \entry -> do
    let path = directory </> name entry ++ "-audit.json"
    command <- execute (name entry ++ "-audit") [] "python3" ["scripts/audit-core.py",core,"--entry",name entry,"--output",path]
    report <- readJson (root </> path)
    accepted <- field report "accepted"
    missing <- field report "missingGlobals" :: IO [Value]
    issues <- field report "issues" :: IO [Value]
    unless (accepted && null missing && null issues) (die ("SIMD arithmetic audit rejected " ++ name entry))
    pure (path:commandArtifacts command)
  plugin <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  inputHashes <- hashes root (sort $ ["test/haskell-fixtures/SimdArithmeticFixtures.hs","test/haskell-fixtures/FixtureSupport.hs",
    "test/haskell-fixtures/Main.hs","thc.cabal","scripts/simd-families.json","scripts/core-capabilities.json","scripts/audit-core.py",
    "compiler/build.sh","compiler/export.sh","compiler/toolchain.sh","compiler/plugin.py","src/main/resources/thc/scalar-primop-signatures.json"] ++
    ["compiler/THC" </> path | path <- plugin,takeExtension path == ".hs"] ++
    ["scripts" </> path | path <- scripts,isPrefixOf "core_" path,takeExtension path == ".py"])
  artifactHashes <- hashes root ([source,scalarSource,driver,inputs,binary,core,directory </> "oracle.tsv"] ++ concat audits ++
    concatMap commandArtifacts [version,nativeBuild,observed,exported])
  writeJson manifest (object ["schema" .= (1::Int),"ghc" .= ("9.14.1"::String),"rows" .= length requests,
    "entries" .= [object ["name" .= name entry,"primitive" .= primitive entry,"lanes" .= lanes (shape entry),
      "width" .= width (shape entry),"scalar" .= scalar (shape entry),"pattern" .= patternId entry] | entry <- entries],
    "inputHashes" .= inputHashes,"artifactHashes" .= artifactHashes,"nativeMode" .= ("scalar-lane"::String)])
  putStrLn ("SIMD arithmetic: " ++ show (length entries) ++ " entries, " ++ show (length requests) ++ " native rows")
  where wrap value = mod (value + 2^(63::Int)) (2^(64::Int)) - 2^(63::Int)
