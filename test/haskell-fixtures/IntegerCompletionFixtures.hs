-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module IntegerCompletionFixtures (prepareIntegerCompletion) where

import Control.Monad (forM, unless, when)
import Data.Aeson (object, (.=))
import Data.Bits ((.&.), (.|.), xor, shiftL, shiftR)
import qualified Data.ByteString.Char8 as BS
import Data.List (sort)
import FixtureSupport
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)
import Text.Read (readMaybe)

names :: [String]
names = ["quotRem" ++ k ++ show w | k <- ["Int", "Word"], w <- [8,16,32 :: Int]] ++
  ["shiftRLInt" ++ show w | w <- [8,16,32 :: Int]] ++ ["quotRemWord2", "mulMay"]

pow :: Int -> Integer
pow n = 2 ^ n
signed :: Int -> Integer -> Integer
signed width value = let n = value `mod` pow width in if n >= pow (width-1) then n-pow width else n
randoms :: [Integer]
randoms = drop 1 (iterate step 9141)
  where step x = let a = x `xor` (x `shiftL` 13); b = a `xor` (a `shiftR` 7)
                 in (b `xor` (b `shiftL` 17)) .&. (pow 64-1)
signedAnchors :: Int -> [Integer]
signedAnchors w = [-pow (w-1), -pow (w-1)+1, -33, -1, 0, 1, 33, pow (w-1)-1]
wordAnchors :: Int -> [Integer]
wordAnchors w = [0,1,2,3,pow (w-1)-1,pow (w-1),pow w-2,pow w-1]

requests :: [(String,Integer,Integer,Integer)]
requests = concat
  [[(name,x,y,0) | x <- anchors, y <- anchors, y /= 0,
       not (k == "Int" && x == -pow (w-1) && y == -1)] ++
    [(name,signed 64 x,signed 64 ((y .&. (pow (w-1)-1)) .|. 1),0) |
      (x,y) <- take 256 (zip randoms (drop 256 randoms))] |
    k <- ["Int","Word"], w <- [8,16,32], let name = "quotRem" ++ k ++ show w,
    let anchors = if k == "Int" then signedAnchors w else wordAnchors w] ++ concat
  [[(name,x,toInteger n,0) | x <- signedAnchors w, n <- [0..w-1]] ++
    [(name,signed 64 x,toInteger (n `mod` w),0) | (n,x) <- zip [0..63] randoms] |
    w <- [8,16,32], let name = "shiftRLInt" ++ show w] ++
  [("quotRemWord2",signed 64 h,signed 64 l,signed 64 d) |
    d <- [1,2,3,7,pow 31-1,pow 31,pow 32-1,pow 32,pow 63-1,pow 63,pow 64-2,pow 64-1],
    h <- [0,d `quot` 2,d-1], l <- wordAnchors 64] ++
  [("quotRemWord2",signed 64 (h `mod` d),signed 64 l,signed 64 d) |
    (h,l,raw) <- take 256 (zip3 randoms (drop 256 randoms) (drop 512 randoms)), let d = raw .|. 1] ++
  [("mulMay",x,y,0) | x <- mulAnchors, y <- mulAnchors] ++
  [("mulMay",signed 64 x,signed 64 y,0) | (x,y) <- take 64 (zip randoms (drop 64 randoms))]
  where mulAnchors = [-pow 63,-pow 63+1,-4294967296,-3037000500,-3037000499,-2,-1,0,
                      1,2,3037000499,3037000500,4294967296,pow 62,pow 63-2,pow 63-1]

driver :: String
driver = unlines $
  ["{-# LANGUAGE MagicHash #-}","module Main where", "import GHC.Exts (Int(I#), Int#)",
   "import qualified IntegerCompletionAudit as P",
   "emit :: String -> (Int# -> Int# -> Int# -> Int# -> Int#) -> Int -> Int -> Int -> IO ()",
   "emit name f x@(I# a) y@(I# b) z@(I# c) = putStrLn (name ++ \"\\t\" ++ show x ++ \"\\t\" ++ show y ++ \"\\t\" ++ show z ++ \"\\t\" ++ show (I# (f a b c 0#)) ++ \"\\t\" ++ show (I# (f a b c 1#)))",
   "dispatch [name,x,y,z] = case name of"] ++
  ["  " ++ show n ++ " -> emit name P." ++ n ++ " (read x) (read y) (read z)" | n <- names] ++
  ["  _ -> error \"unknown entry\"", "dispatch _ = error \"bad request\"",
   "main = getContents >>= mapM_ (dispatch . words) . lines"]

prepareIntegerCompletion :: FilePath -> IO ()
prepareIntegerCompletion root = do
  let dir = "build/integer-completion"; logs = dir </> "commands"
      source = "compiler/test-fixtures/IntegerCompletionAudit.hs"
      manifest = root </> dir </> "manifest.json"
      command = runLogged 600 root logs
  createDirectoryIfMissing True (root </> dir)
  exists <- doesFileExist manifest
  when exists (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- command "ghc-version" [] ghc ["--numeric-version"]
  unless (BS.unpack (commandStdout version) == "9.14.1\n") (die "Pinned GHC 9.14.1 required")
  info <- command "ghc-info" [] ghc ["--info"]
  case readMaybe (BS.unpack (commandStdout info)) :: Maybe [(String,String)] of
    Just fields | lookup "target word size" fields == Just "8" -> pure ()
    _ -> die "Integer completion requires a 64-bit GHC target"
  stages <- forM ["pre","post"] $ \stage -> do
    let core = dir </> stage ++ "-core"; obj = dir </> stage ++ "-ghc"
        options = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"]
    exported <- command (stage ++ "-export") [("THC_CORE_OUT",root </> core),("THC_GHC_OUT",root </> obj)]
      "compiler/export.sh" (options ++ [source])
    audited <- command (stage ++ "-audit") [] "python3" (["scripts/audit-core.py"] ++
      concatMap (\n -> ["--entry",n]) names ++ ["--output",dir </> stage ++ "-audit.json",core </> "IntegerCompletionAudit.json"])
    pure [exported,audited]
  let native = dir </> "native"; nativeSource = dir </> "NativeIntegerCompletion.hs"
      executable = native </> "integer-completion-oracle"; input = dir </> "requests.tsv"
  createDirectoryIfMissing True (root </> native)
  writeFile (root </> nativeSource) driver
  writeFile (root </> input) (unlines [n ++ "\t" ++ show x ++ "\t" ++ show y ++ "\t" ++ show z | (n,x,y,z) <- requests])
  unless (length requests == 3373) (die "Integer completion request count drift")
  built <- command "native-build" [] ghc ["--make","-O2","-fforce-recomp","-dcore-lint","-dstg-lint",
    "-i" ++ root </> "compiler/test-fixtures","-odir",root </> native,"-hidir",root </> native,
    root </> nativeSource,"-o",root </> executable]
  oracle <- runLoggedWithInput input 600 root logs "native-oracle" [] (root </> executable) []
  let parse line = case splitTab line of
        [n,x,y,z,a,b] -> do
          xx <- readInteger x; yy <- readInteger y; zz <- readInteger z
          _ <- readInteger a; _ <- readInteger b
          pure (n,xx,yy,zz)
        _ -> Nothing
  unless (traverse parse (lines (BS.unpack (commandStdout oracle))) == Just requests)
    (die "Native oracle omitted, duplicated, reordered, or corrupted requests")
  BS.writeFile (root </> dir </> "oracle.tsv") (commandStdout oracle)
  plugins <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  let inputs = sort $ [source,"thc.cabal","test/haskell-fixtures/Main.hs",
        "test/haskell-fixtures/IntegerCompletionFixtures.hs","test/haskell-fixtures/FixtureSupport.hs",
        "compiler/build.sh","compiler/export.sh","compiler/toolchain.sh","compiler/plugin.py",
        "scripts/audit-core.py","scripts/core-capabilities.json","src/main/resources/thc/scalar-primop-signatures.json"] ++
        ["compiler/THC" </> file | file <- plugins, takeExtension file == ".hs"] ++
        ["scripts" </> file | file <- scripts, take 5 file == "core_", takeExtension file == ".py"]
      artifacts = [input,nativeSource,executable,dir </> "oracle.tsv"] ++
        [dir </> stage ++ suffix | stage <- ["pre","post"], suffix <- ["-audit.json","-core/IntegerCompletionAudit.json"]] ++
        concatMap commandArtifacts ([version,info,built,oracle] ++ concat stages)
  inputHashes <- hashes root inputs
  artifactHashes <- hashes root artifacts
  writeJson manifest (object ["schema" .= (1 :: Int),"ghc" .= ("9.14.1" :: String),
    "wordBits" .= (64 :: Int),"entries" .= names,"nativeRows" .= (3373 :: Int),
    "inputHashes" .= inputHashes,"artifactHashes" .= artifactHashes,
    "domain" .= ("Nonzero divisors; signed min/-1 excluded from native probes; shifts in [0,width); quotRemWord2 high<divisor. mulMay is tested as a conservative nonzero flag, not exact native bits." :: String)])
  putStrLn "integer-completion: 11 primitives, 3373 native rows, strict pre/post audits"
