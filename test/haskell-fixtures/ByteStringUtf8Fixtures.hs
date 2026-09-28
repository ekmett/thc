-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : ByteStringUtf8Fixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for byte string utf8.
module ByteStringUtf8Fixtures (prepareByteStringUtf8) where

import Control.Monad (forM, unless)
import Data.Aeson (Value(..), eitherDecodeStrict', object, toJSON, (.=))
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BSC
import Data.List (isPrefixOf, sort)
import qualified Data.Map.Strict as Map
import qualified Distribution.InstalledPackageInfo as Package
import qualified Distribution.ModuleName as ModuleName
import FixtureSupport
import System.Directory (createDirectoryIfMissing, listDirectory, doesDirectoryExist)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)
import Text.Read (readMaybe)

type Row = ((String,[Int],Int,Int),Int)

rowJSON :: Row -> Value
rowJSON ((entry,bytes,offset,count),result) = object
  ["entry" .= entry,"bytes" .= bytes,"offset" .= offset,"count" .= count,"result" .= result]

prepareByteStringUtf8 :: FilePath -> IO ()
prepareByteStringUtf8 root = do
  let directory = "build/bytestring-utf8"
      source = "test/fixtures/compiler/ByteStringUtf8Audit.hs"
      driver = "test/fixtures/compiler/ByteStringUtf8Native.hs"
      binary = directory </> "native/oracle"
      entries = ["validateUnsafe","validateSafe"] :: [String]
      execute = runLogged 180 root (directory </> "logs")
  createDirectoryIfMissing True (root </> directory </> "native")
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  ghcPkg <- maybe "ghc-pkg" id <$> lookupEnv "GHC_PKG"
  version <- execute "version" [] ghc ["--numeric-version"]
  unless (commandStdout version == "9.14.1\n") (die "Original UTF-8 validation requires GHC 9.14.1")
  -- Expose only the original hidden module in an isolated copied registration.
  -- Unit identity, libraries and interfaces remain the installed originals.
  registration <- execute "original-registration" [] ghcPkg ["describe","bytestring","--expand-pkgroot"]
  original <- case Package.parseInstalledPackageInfo (commandStdout registration) of
    Left errors -> die (show errors)
    Right (_,package) -> pure package
  let name = ModuleName.fromString "Data.ByteString.Internal.Type"
      hidden = Package.hiddenModules original
      visible = original {
        Package.exposedModules = Package.exposedModules original ++ [Package.ExposedModule name Nothing],
        Package.hiddenModules = filter (/= name) hidden }
      findDatabase index = do
        let path = root </> directory </> "package-db-" ++ show index
        exists <- doesDirectoryExist path
        if exists then findDatabase (index+1) else pure path
  unless (name `elem` hidden) (die "Original hidden ByteString Type module missing")
  database <- findDatabase (0::Int)
  let registrationPath = directory </> "exposed-bytestring.conf"
  writeFile (root </> registrationPath) (Package.showInstalledPackageInfo visible)
  initialized <- execute "package-init" [] ghcPkg ["init",database]
  registered <- execute "package-register" [] ghcPkg ["--package-db",database,"update",root </> registrationPath]
  let packageOptions = ["-package-db",database,"-package","bytestring"]
  compiled <- execute "native-build" [] ghc (packageOptions ++ ["--make","-j2","-O2","-fforce-recomp",
    "-dcore-lint","-dstg-lint","-itest/fixtures/compiler",
    "-odir",root </> directory </> "native","-hidir",root </> directory </> "native",
    driver,"-o",root </> binary])
  observed <- execute "native-observations" [] (root </> binary) []
  rows <- maybe (die "Malformed native UTF-8 validation observations") pure
    (readMaybe (BSC.unpack (commandStdout observed)) :: Maybe [Row])
  unless (length rows == 800) (die "Unexpected native UTF-8 validation observation count")
  let oracle = directory </> "oracle.json"
  writeJson (root </> oracle) (toJSON (map rowJSON rows))
  stages <- forM ["pre","post"] $ \stage -> do
    let core = directory </> stage </> "core"
        modules = [core </> "ByteStringUtf8Audit.json",core </> "THC.InterfaceClosure.json"]
        options = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
          ["-fplugin-opt=THC.Plugin:closure=" ++ entry | entry <- entries]
    exported <- execute (stage ++ "-export")
      [("THC_CORE_OUT",root </> core),("THC_GHC_OUT",root </> directory </> stage </> "ghc")]
      "bin/export-core.sh" (packageOptions ++ options ++ [source])
    audits <- forM entries $ \entry -> do
      let path = directory </> stage </> entry ++ ".audit.json"
      command <- execute (stage ++ "-audit-" ++ entry) [] "python3"
        (["bin/audit-core.py","--entry",entry,"--output",path] ++ modules)
      report <- either die pure . eitherDecodeStrict' =<< BS.readFile (root </> path)
      case report of
        Object fields | KeyMap.lookup "accepted" fields == Just (Bool True) -> pure ()
        _ -> die "Original UTF-8 validation audit rejected"
      pure (path,command)
    pure (stage,modules,exported,audits)
  compilerFiles <- listDirectory (root </> "src/compiler/THC")
  scripts <- listDirectory (root </> "bin")
  let commands = [version,registration,initialized,registered,compiled,observed] ++ concat [exported:map snd audits | (_,_,exported,audits) <- stages]
      inputs = sort $ [source,driver,"thc.cabal","test/haskell-fixtures/Main.hs",
        "nih/pinned/bytestring-0.12.2.0/cbits/is-valid-utf8.c","src/main/c/bytestring-utf8-api.c","bin/build-cbits.py",
        "test/haskell-fixtures/FixtureSupport.hs","test/haskell-fixtures/ByteStringUtf8Fixtures.hs",
        "bin/audit-core.py","bin/core-capabilities.json","bin/build-compiler.sh","bin/export-core.sh",
        "bin/toolchain.sh","bin/plugin.py","src/main/resources/thc/scalar-primop-signatures.json"] ++
        ["src/compiler/THC" </> path | path <- compilerFiles, takeExtension path == ".hs"] ++
        ["bin" </> path | path <- scripts, "core_" `isPrefixOf` path, takeExtension path == ".py"]
      artifacts = [binary,oracle,registrationPath] ++ concat [modules ++ map fst audits | (_,modules,_,audits) <- stages] ++ concatMap commandArtifacts commands
  inputHashes <- hashes root inputs
  artifactHashes <- hashes root artifacts
  writeJson (root </> directory </> "manifest.json") $ object
    ["schema" .= (1 :: Int),"ghc" .= ("9.14.1" :: String),"entries" .= entries,"nativeRows" .= length rows,
     "strictAccepted" .= True,"runtimeVerified" .= False,
     "stages" .= Map.fromList [(stage,object ["modules" .= modules]) | (stage,modules,_,_) <- stages],
     "inputHashes" .= inputHashes,"artifactHashes" .= artifactHashes,"commands" .= map commandRecord commands]
  putStrLn "bytestring-utf8: 800 native observations, original ByteString imports, four strict audits"
