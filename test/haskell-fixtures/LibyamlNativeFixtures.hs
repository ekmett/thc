-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : LibyamlNativeFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; genuine retained Cabal store and LLVM products
--
-- Reuse an explicitly selected genuine Cabal capture, preserving it unchanged.
-- Publish only its coherent libyaml unit and an independent native oracle.
module LibyamlNativeFixtures (prepareLibyamlNative) where

import Control.Monad (filterM, forM, forM_, unless)
import Data.Aeson (Value, object, (.=))
import qualified Data.ByteString as BS
import Data.List (nub)
import Distribution.InstalledPackageInfo (parseInstalledPackageInfo)
import Distribution.Pretty (prettyShow)
import qualified Distribution.Types.InstalledPackageInfo as Package
import FixtureSupport
import InstalledCoreFixtures (field, readJson)
import System.Directory
import System.Environment (getEnv, lookupEnv)
import System.FilePath
import THC.Driver.Project (Bundle(..), publishCapturedStoreUnit)

prepareLibyamlNative :: FilePath -> IO ()
prepareLibyamlNative root = do
  stage <- canonicalizePath =<< getEnv "THC_LIBYAML_CAPTURE_STAGE"
  selectedOutput <- lookupEnv "THC_LIBYAML_FIXTURE_OUTPUT"
  output <- makeAbsolute (maybe (root </> "build/libyaml-native") id selectedOutput)
  present <- doesPathExist output
  unless (not present) (fail "libyaml-native requires a fresh output; retained fixtures are not overwritten")
  plan <- readJson (stage </> "dist/cache/plan.json")
  units <- field plan "install-plan" :: IO [Value]
  selected <- filterM (\row -> do
    name <- field row "pkg-name" :: IO String
    version <- field row "pkg-version" :: IO String
    pure (name == "libyaml" && version == "0.1.4")) units
  unit <- case selected of [row] -> pure row; _ -> fail "Expected exactly one resolved libyaml-0.1.4 unit"
  identifier <- field unit "id"
  dependencies <- field unit "depends" :: IO [String]
  clib <- filterM (\row -> do
    name <- field row "pkg-name" :: IO String
    version <- field row "pkg-version" :: IO String
    owner <- field row "id"
    pure (name == "libyaml-clib" && version == "0.2.5" && owner `elem` dependencies)) units
  cIdentifier <- case clib of [row] -> field row "id"; _ -> fail "Expected libyaml's exact resolved C-only dependency"
  createDirectoryIfMissing True output
  let capture = output </> "capture"
      store = stage </> "store"
  copyTree (stage </> "capture" </> identifier) (capture </> identifier)
  createDirectoryLink (stage </> "native-pieces") (output </> "native-pieces")
  bundle <- publishCapturedStoreUnit (stage </> "dist/cache/plan.json") store (stage </> "dist")
    capture identifier (output </> "libyaml.zip")
  archives <- concat <$> mapM (registeredArchives store) [identifier,cIdentifier]
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  let execute = runLogged 300 root (output </> "logs")
      source = "test/fixtures/compiler/OriginalLibyamlNative.hs"
  built <- execute "native-build" [] ghc (["--make","-O0","-Wall","-Werror","-hide-all-packages",
    "-package","base","-package","bytestring","-outputdir",output </> "objects",source] ++
    archives ++ ["-o",output </> "native"])
  observed <- execute "native-run" [] (output </> "native") [output </> "input.yaml"]
  BS.writeFile (output </> "native.tsv") (commandStdout observed)
  sourceHash <- hashFile (root </> source)
  writeFile (output </> "native-source.sha256") (sourceHash ++ "  " ++ source ++ "\n")
  nativeHash <- hashFile (output </> "native.tsv")
  inputHash <- hashFile (output </> "input.yaml")
  archiveHashes <- forM archives $ \path -> do
    digest <- hashFile path
    pure (object ["path" .= path,"sha256" .= digest])
  writeJson (output </> "manifest.json") (object ["schema" .= (1::Int),"unit" .= identifier,
    "depends" .= dependencies,"bundleSha256" .= bundleHash bundle,"nativeSha256" .= nativeHash,
    "inputSha256" .= inputHash,"sourceSha256" .= sourceHash,"nativeArchives" .= archiveHashes,
    "commands" .= map commandRecord [built,observed]])
  putStrLn "libyaml-native: genuine two-module product and independent parser/encoder observations prepared"
  where
    copyTree source destination = do
      directory <- doesDirectoryExist source
      if directory then do
        createDirectoryIfMissing True destination
        entries <- listDirectory source
        forM_ entries (\name -> copyTree (source </> name) (destination </> name))
      else copyFile source destination
    registeredArchives store identifier = do
      partitions <- listDirectory store
      paths <- filterM doesFileExist [store </> partition </> "package.db" </> identifier <.> "conf" | partition <- partitions]
      path <- case paths of [single] -> pure single; _ -> fail "Missing exact native package registration"
      registration <- BS.readFile path
      (_, info) <- either (fail . show) pure (parseInstalledPackageInfo registration)
      unless (prettyShow (Package.installedUnitId info) == identifier)
        (fail "Native package registration owner differs")
      forM (Package.hsLibraries info) $ \library -> do
        paths' <- filterM doesFileExist [directory </> "lib" ++ library ++ ".a" |
          directory <- nub (Package.libraryDirsStatic info ++ Package.libraryDirs info)]
        case nub paths' of [single] -> pure single; _ -> fail "Missing unique registered native archive"
