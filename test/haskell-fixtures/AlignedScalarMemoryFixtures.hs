-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}
module AlignedScalarMemoryFixtures (prepareAlignedScalarMemory) where

import Control.Monad (forM, unless, when)
import Data.Aeson (object, (.=))
import qualified Data.ByteString.Char8 as BS
import Data.List (sort)
import FixtureSupport
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)
import Text.Read (readMaybe)

scalarTypes :: [String]
scalarTypes = ["WideChar","StablePtr"]

rawInputs :: String -> [Integer]
rawInputs "StablePtr" = [0]
rawInputs _ = [0,1,127,128,255,256,32767,32768,55295,55296,57343,57344,65535,65536,1114111]

prepareAlignedScalarMemory :: FilePath -> IO ()
prepareAlignedScalarMemory root = do
  let directory = "build/aligned-scalar-memory"
      output = root </> directory
      logs = directory </> "logs"
      manifest = output </> "manifest.json"
      source = "compiler/test-fixtures/AlignedScalarMemoryAudit.hs"
      driver = "compiler/test-fixtures/AlignedScalarMemoryNative.hs"
      entries = map ("aligned" ++) scalarTypes
      names = sort [verb ++ scalar ++ domain ++ "#" |
                    verb <- ["index","read","write"], domain <- ["Array","OffAddr"], scalar <- scalarTypes]
      rows = [(scalar,raw,offset) | scalar <- scalarTypes, raw <- rawInputs scalar,
              offset <- [0..7]]
      inputs = unlines [unwords [scalar,show raw,show offset] | (scalar,raw,offset) <- rows]
  createDirectoryIfMissing True output
  present <- doesFileExist manifest
  when present (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- runLogged 30 root logs "ghc-version" [] ghc ["--numeric-version"]
  unless (BS.lines (commandStdout version) == ["9.14.1"]) (die "Aligned scalar memory requires GHC 9.14.1")
  info <- runLogged 30 root logs "ghc-info" [] ghc ["--info"]
  settings <- maybe (die "Malformed GHC platform information") pure
    (readMaybe (BS.unpack (commandStdout info)) :: Maybe [(String,String)])
  unless (lookup "target word size" settings == Just "8" &&
    lookup "Host platform" settings /= Nothing &&
    lookup "Host platform" settings == lookup "Target platform" settings)
    (die "Aligned scalar memory requires a native 64-bit GHC target")
  inventory <- runLogged 60 root logs "ghc-inventory" [] ghc ["-ignore-dot-ghci","-package","ghc","-e",
    "mapM_ (putStrLn . GHC.Types.Name.Occurrence.occNameString . GHC.Builtin.PrimOps.primOpOcc) GHC.Builtin.PrimOps.allThePrimOps"]
  let found = sort [name | name <- lines (BS.unpack (commandStdout inventory)), name `elem` names]
  unless (found == names) (die "Pinned GHC aligned scalar inventory changed")
  stages <- forM ["pre","post"] $ \stage -> do
    let core = directory </> stage </> "core"
        options = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"]
    exported <- runLogged 300 root logs (stage ++ "-export")
      [("THC_CORE_OUT",root </> core),("THC_GHC_OUT",output </> stage </> "ghc")]
      "compiler/export.sh" (options ++ [source])
    audited <- runLogged 120 root logs (stage ++ "-audit") [] "python3"
      (["scripts/audit-core.py"] ++ concatMap (\entry -> ["--entry",entry]) entries ++
       ["--output",directory </> stage </> "audit.json",core </> "AlignedScalarMemoryAudit.json"])
    pure [exported,audited]
  let native = output </> "native"
  createDirectoryIfMissing True native
  built <- runLogged 120 root logs "native-build" [] ghc
    ["--make","-O2","-dynamic","-dcore-lint","-dstg-lint","-i" ++ root </> "compiler/test-fixtures",
     "-odir",native,"-hidir",native,root </> driver,"-o",native </> "oracle"]
  exampleBuilt <- runLogged 120 root logs "example-build" [] ghc
    ["--make","-O2","-dynamic","-dcore-lint","-dstg-lint",
     "-odir",native </> "example","-hidir",native </> "example",
     root </> "examples/StableWideCells.hs","-o",native </> "stable-wide-cells"]
  exampleRun <- runLogged 60 root logs "example-run" [] (native </> "stable-wide-cells") []
  unless (BS.lines (commandStdout exampleRun) == ["retained opaque handle","128578"])
    (die "StablePtr/WideChar example output mismatch")
  writeFile (output </> "inputs.txt") inputs
  oracle <- runLoggedWithInput (directory </> "inputs.txt") 60 root logs "native-oracle" [] (native </> "oracle") []
  let actual = lines (BS.unpack (commandStdout oracle))
      parse line = case splitTab line of
        scalar:raw:offset:values | length values == 6 -> do
          rawValue <- readInteger raw
          offsetValue <- readInteger offset
          _ <- traverse readInteger values
          pure (scalar,rawValue,offsetValue)
        _ -> Nothing
  unless (traverse parse actual == Just rows) (die "Malformed scalar memory oracle")
  BS.writeFile (output </> "oracle.tsv") (commandStdout oracle)
  pluginFiles <- listDirectory (root </> "compiler/THC")
  coreScripts <- listDirectory (root </> "scripts")
  let sources = sort $ [source,driver,"examples/StableWideCells.hs","thc.cabal","test/haskell-fixtures/Main.hs",
        "test/haskell-fixtures/FixtureSupport.hs","test/haskell-fixtures/AlignedScalarMemoryFixtures.hs",
        "scripts/audit-core.py","scripts/core-capabilities.json",
        "src/main/resources/thc/scalar-primop-signatures.json",
        "compiler/build.sh","compiler/export.sh","compiler/toolchain.sh","compiler/plugin.py"] ++
        ["compiler/THC" </> file | file <- pluginFiles, takeExtension file == ".hs"] ++
        ["scripts" </> file | file <- coreScripts, take 5 file == "core_" && takeExtension file == ".py"]
      commands = [version,info,inventory] ++ concat stages ++ [built,oracle,exampleBuilt,exampleRun]
      artifacts = [directory </> "oracle.tsv",directory </> "inputs.txt",
        directory </> "native/oracle",directory </> "native/stable-wide-cells"] ++
        [directory </> stage </> suffix | stage <- ["pre","post"],
          suffix <- ["core/AlignedScalarMemoryAudit.json","audit.json"]] ++ concatMap commandArtifacts commands
  sourceHashes <- hashes root sources
  artifactHashes <- hashes root artifacts
  writeJson manifest $ object ["schema" .= (1 :: Int),"ghc" .= ("9.14.1" :: String),
    "wordBits" .= (64 :: Int),"nativeRows" .= length rows,"entries" .= entries,"primitives" .= names,
    "stages" .= (["pre","post"] :: [String]),"inputHashes" .= sourceHashes,
    "artifactHashes" .= artifactHashes,"commands" .= map commandRecord commands,
    "installedArtifactsHashed" .= False]
  putStrLn ("aligned-scalar-memory: " ++ show (length names) ++ " aligned scalar primops, " ++
    show (length rows) ++ " native rows, strict original pre/post Core")
