-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module PackageScalarFixtures (preparePackageScalar) where

import Control.Monad (forM, forM_, unless, when)
import Data.Aeson (Value(..), object, (.=))
import qualified Data.Aeson.Key as Key
import qualified Data.Aeson.KeyMap as KM
import qualified Data.ByteString.Char8 as BS
import Data.List (isPrefixOf, sort)
import qualified Data.Text as T
import FixtureSupport
import InstalledCoreFixtures (field, readJson)
import System.Directory
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath

-- Explicit full-Core proof group: ordinary production acquisition and execution,
-- followed by a native oracle from that same Cabal build. Never a fake launcher.
preparePackageScalar :: FilePath -> IO ()
preparePackageScalar root = do
  let directory = "build/package-scalar-cbits"
      fixture = "test/fixtures/run-scalar-cbits"
      manifest = root </> directory </> "manifest.json"
      execute = runLogged 1800 root (directory </> "logs")
      sources = ["src/Scalar.hs","src/ScalarAgain.hs","app/Main.hs","cbits/scalar.c","cbits/scalar.h"]
  createDirectoryIfMissing True (root </> directory)
  stale <- doesFileExist manifest
  when stale (removeFile manifest)
  ghc <- selected "THC_INSTALLED_CORE_GHC" "GHC" "ghc"
  ghcPkg <- selected "THC_INSTALLED_CORE_GHC_PKG" "GHC_PKG" "ghc-pkg"
  cabal <- maybe "cabal" id <$> lookupEnv "CABAL"
  ccName <- maybe "clang" id <$> lookupEnv "THC_CLANG"
  cc <- findExecutable ccName >>= maybe (die "package-scalar-cbits requires configured Clang") canonicalizePath
  runtime <- maybe (pure (root </> "build/install/thc/bin/thc")) canonicalizePath =<< lookupEnv "THC_TEST_RUNTIME"
  sourceRoot <- lookupEnv "THC_INSTALLED_CORE_GHC_SOURCE"
  let driverSelection = ["exe:thc","--offline","--with-compiler=" ++ ghc,"--with-hc-pkg=" ++ ghcPkg]
  built <- execute "driver-build" [] cabal ("build":driverSelection)
  located <- execute "driver-location" [] cabal ("list-bin":driverSelection)
  driver <- case BS.lines (commandStdout located) of
    [path] -> pure (BS.unpack path)
    _ -> die "Expected one selected-GHC thc executable"
  results <- forM [("first",1::Int),("second",2)] $ \(name,delta) -> do
    let project = directory </> name </> "project"
        output = directory </> name
        packageName = "scalar-" ++ name
        packageFile = project </> packageName <.> "cabal"
        projectFile = project </> "cabal.project"
    forM_ sources $ \source -> do
      createDirectoryIfMissing True (takeDirectory (root </> project </> source))
      copyFile (root </> fixture </> source) (root </> project </> source)
    writeFile (root </> projectFile) "packages: .\n"
    writeFile (root </> packageFile) $ unlines
      ["cabal-version: 3.0","name: " ++ packageName,"version: 0.1.0.0","build-type: Simple",
       "library","  exposed-modules: Scalar, ScalarAgain","  hs-source-dirs: src",
       "  c-sources: cbits/scalar.c","  cc-options: -std=c11 -DSCALAR_DELTA=" ++ show delta,
       "  ghc-options: -O2 -pgmc " ++ show cc,"  build-depends: base","  default-language: Haskell2010",
       "executable oracle","  main-is: Main.hs","  hs-source-dirs: app","  ghc-options: -O2",
       "  build-depends: base, " ++ packageName,"  default-language: Haskell2010"]
    managed <- execute (name ++ "-thc-run") [("THC_BACKEND","bytecode")] driver
      (["run",root </> project,"--exe","oracle","--thc-root",root,"--runtime",runtime,
        "--dist-dir",root </> output,"--installed-core","required","--with-ghc",ghc,"--with-ghc-pkg",ghcPkg] ++
        maybe [] (\path -> ["--ghc-source",path]) sourceRoot)
    plan <- readJson (root </> output </> "native/cache/plan.json")
    units <- field plan "install-plan" :: IO [Value]
    oracleComponent <- unique "oracle component" =<< matching "component-name" "exe:oracle" units
    binary <- field oracleComponent "bin-file"
    native <- execute (name ++ "-native-run") [] binary []
    unless (commandStdout managed == commandStdout native)
      (die ("package-scalar-cbits: ordinary THC/native stdout differs for " ++ name))
    rows <- mapM observation (BS.lines (commandStdout native))
    unless (length rows == 30) (die "package-scalar-cbits: native oracle inventory differs")
    packages <- readJson (root </> output </> "packages.json")
    records <- field packages "units"
    libraryPlan <- unique "library component" =<< matching "component-name" "lib" units
    unit <- field libraryPlan "id"
    library <- unique "acquired library" =<< matching "id" unit records
    bundle <- field library "bundle"
    path <- field bundle "path"
    expected <- field bundle "sha256"
    actual <- hashFile path
    unless (actual == expected) (die "package-scalar-cbits: acquired bundle hash differs")
    let retained = output </> "library.zip"
    copyFile path (root </> retained)
    audit <- readJson (root </> output </> "audit.json")
    accepted <- field audit "accepted"
    unless accepted (die "package-scalar-cbits: production audit did not accept original executable")
    let record = object ["name" .= name,"packages" .= (output </> "packages.json"),"unit" .= (unit::String),
          "libraryBundle" .= retained,"librarySha256" .= actual,"observations" .= rows]
        artifacts = [retained,output </> "packages.json",output </> "audit.json",projectFile,packageFile] ++
          [project </> source | source <- sources] ++ commandArtifacts managed ++ commandArtifacts native
    pure (record,[managed,native],artifacts)
  plugin <- listDirectory (root </> "compiler/THC")
  driverSources <- listDirectory (root </> "src/THC/Driver")
  auditors <- listDirectory (root </> "scripts")
  inputs <- hashes root $ sort $ [fixture </> source | source <- sources] ++
    ["thc.cabal","app/Main.hs","compiler/build.sh","compiler/toolchain.sh","compiler/plugin.py","compiler/interface/Main.hs",
     "test/haskell-fixtures/PackageScalarFixtures.hs","test/haskell-fixtures/FixtureSupport.hs",
     "test/haskell-fixtures/InstalledCoreFixtures.hs","test/haskell-fixtures/Main.hs",
     "scripts/audit-core.py","scripts/core-capabilities.json"] ++
    ["compiler/THC" </> file | file <- plugin,takeExtension file == ".hs"] ++
    ["src/THC/Driver" </> file | file <- driverSources,takeExtension file == ".hs"] ++
    ["scripts" </> file | file <- auditors,"core_" `isPrefixOf` file,takeExtension file == ".py"]
  let commands = [built,located] ++ concat [value | (_,value,_) <- results]
  artifacts <- hashes root (concat [value | (_,_,value) <- results] ++ concatMap commandArtifacts [built,located])
  writeJson manifest $ object ["schema" .= (1::Int),"supported" .= True,"strictAccepted" .= True,
    "runtimeVerified" .= True,"nativeRows" .= (60::Int),"records" .= [value | (value,_,_) <- results],
    "inputHashes" .= inputs,"artifactHashes" .= artifacts,"commands" .= map commandRecord commands]
  putStrLn "package-scalar-cbits: two normal Cabal acquisitions and 60 matching native/THC observations"
  where
    selected preferred ordinary fallback = do
      override <- lookupEnv preferred
      maybe (maybe fallback id <$> lookupEnv ordinary) pure override
    matching key expected values = pure [value | value@(Object fields) <- values,
      KM.lookup (Key.fromString key) fields == Just (String (T.pack expected))]
    unique _ [value] = pure value
    unique label _ = die ("package-scalar-cbits: expected one " ++ label)
    observation line = case splitTab (BS.unpack line) of
      [entry,argument,result] -> do
        _ <- maybe (die "invalid scalar argument") pure (readInteger argument)
        bits <- maybe (die "invalid scalar result") pure (readInteger result)
        let moduleName = if entry == "repeatInt32" then "ScalarAgain" else "Scalar" :: String
            comparison | entry == "scalarFloat" && bits == 2143289344 = "float-nan"
                       | entry == "scalarDouble" && bits == 9221120237041090560 = "double-nan"
                       | otherwise = "exact" :: String
        pure $ object ["module" .= moduleName,"entry" .= entry,
          "arguments" .= [object ["rep" .= ("IntRep"::String),"value" .= argument]],
          "result" .= object ["rep" .= ("IntRep"::String),"value" .= result],"comparison" .= comparison]
      _ -> die "package-scalar-cbits: malformed native observation"
