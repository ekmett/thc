-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}
module UnalignedScalarMemoryFixtures (prepareUnalignedScalarMemory) where

import Control.Monad (forM, unless, when)
import Data.Aeson (object, (.=))
import qualified Data.ByteString.Char8 as BS
import Data.List (isPrefixOf, sort)
import FixtureSupport
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)

scalarTypes :: [String]
scalarTypes = ["Char","WideChar","Int","Word","Addr","Float","Double","StablePtr",
               "Int16","Int32","Int64","Word16","Word32","Word64"]

rawInputs :: String -> [Integer]
rawInputs "Float" = [0,0x80000000,0x3f800000,0xbf800000,1,0x7f7fffff,0x7f800000,0xff800000,0x7fc12345,0xffc54321]
rawInputs "Double" = [0,-0x8000000000000000,0x3ff0000000000000,-0x4010000000000000,
                      1,0x7fefffffffffffff,0x7ff0000000000000,-0x10000000000000,
                      0x7ff8123456789abc,-0x7654321]
rawInputs "Addr" = [0]
rawInputs "StablePtr" = [0]
rawInputs _ = [0,1,-1,-0x8000000000000000,0x7fffffffffffffff,
               -32768,32767,32768,65535,-2147483648,2147483647,2147483648,4294967295,0x123456789abcdef]

prepareUnalignedScalarMemory :: FilePath -> IO ()
prepareUnalignedScalarMemory root = do
  let directory = "build/unaligned-scalar-memory"
      output = root </> directory
      logs = directory </> "logs"
      manifest = output </> "manifest.json"
      source = "compiler/test-fixtures/UnalignedScalarMemoryAudit.hs"
      driver = "compiler/test-fixtures/UnalignedScalarMemoryNative.hs"
      entries = map ("unaligned" ++) scalarTypes
      names = sort [verb ++ "Word8" ++ domain ++ "As" ++ scalar ++ "#" |
                    verb <- ["index","read","write"], domain <- ["Array","OffAddr"], scalar <- scalarTypes]
      rows = [(scalar,raw,offset) | scalar <- scalarTypes, raw <- rawInputs scalar,
              offset <- [1,2,3,7,8,9,16,88]]
      inputs = unlines [unwords [scalar,show raw,show offset] | (scalar,raw,offset) <- rows]
  createDirectoryIfMissing True output
  present <- doesFileExist manifest
  when present (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- runLogged 30 root logs "ghc-version" [] ghc ["--numeric-version"]
  unless (BS.lines (commandStdout version) == ["9.14.1"]) (die "Unaligned scalar memory requires GHC 9.14.1")
  inventory <- runLogged 60 root logs "ghc-inventory" [] ghc ["-ignore-dot-ghci","-package","ghc","-e",
    "mapM_ (putStrLn . GHC.Types.Name.Occurrence.occNameString . GHC.Builtin.PrimOps.primOpOcc) GHC.Builtin.PrimOps.allThePrimOps"]
  let found = sort [name | name <- lines (BS.unpack (commandStdout inventory)),
        any (\prefix -> isPrefixOf prefix name)
            [verb ++ "Word8" ++ domain ++ "As" | verb <- ["index","read","write"], domain <- ["Array","OffAddr"]],
        not (elem 'X' name)]
  unless (found == names) (die "Pinned GHC scalar unaligned inventory changed")
  stages <- forM ["pre","post"] $ \stage -> do
    let core = directory </> stage </> "core"
        options = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"]
    exported <- runLogged 300 root logs (stage ++ "-export")
      [("THC_CORE_OUT",root </> core),("THC_GHC_OUT",output </> stage </> "ghc")]
      "compiler/export.sh" (options ++ [source])
    audited <- runLogged 120 root logs (stage ++ "-audit") [] "python3"
      (["scripts/audit-core.py"] ++ concatMap (\entry -> ["--entry",entry]) entries ++
       ["--output",directory </> stage </> "audit.json",core </> "UnalignedScalarMemoryAudit.json"])
    pure [exported,audited]
  let native = output </> "native"
  createDirectoryIfMissing True native
  built <- runLogged 120 root logs "native-build" [] ghc
    ["--make","-O2","-dynamic","-dcore-lint","-dstg-lint","-i" ++ root </> "compiler/test-fixtures",
     "-odir",native,"-hidir",native,root </> driver,"-o",native </> "oracle"]
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
  let sources = sort $ [source,driver,"thc.cabal","test/haskell-fixtures/Main.hs",
        "test/haskell-fixtures/FixtureSupport.hs","test/haskell-fixtures/UnalignedScalarMemoryFixtures.hs",
        "scripts/audit-core.py","scripts/core-capabilities.json",
        "src/main/resources/thc/scalar-primop-signatures.json",
        "compiler/build.sh","compiler/export.sh","compiler/toolchain.sh","compiler/plugin.py"] ++
        ["compiler/THC" </> file | file <- pluginFiles, takeExtension file == ".hs"] ++
        ["scripts" </> file | file <- coreScripts, take 5 file == "core_" && takeExtension file == ".py"]
      commands = [version,inventory] ++ concat stages ++ [built,oracle]
      artifacts = [directory </> "oracle.tsv",directory </> "inputs.txt"] ++
        [directory </> stage </> suffix | stage <- ["pre","post"],
          suffix <- ["core/UnalignedScalarMemoryAudit.json","audit.json"]] ++ concatMap commandArtifacts commands
  sourceHashes <- hashes root sources
  artifactHashes <- hashes root artifacts
  writeJson manifest $ object ["schema" .= (1 :: Int),"ghc" .= ("9.14.1" :: String),
    "nativeRows" .= length rows,"entries" .= entries,"primitives" .= names,
    "stages" .= (["pre","post"] :: [String]),"inputHashes" .= sourceHashes,
    "artifactHashes" .= artifactHashes,"commands" .= map commandRecord commands,
    "installedArtifactsHashed" .= False]
  putStrLn ("unaligned-scalar-memory: " ++ show (length names) ++ " scalar primops, " ++
    show (length rows) ++ " native rows, strict original pre/post Core")
