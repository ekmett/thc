-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- Fixture rationale (132 record-fields)
-- Purpose: Check record-field selection works through direct and installed-interface Core.
-- Produces/consumed result: RecordFieldLibrary/Client CBDs and pre/post native
--   observations.
-- Cost and overlap: Retain an installed-interface record case if it exercises a distinct
--   path. Share interface acquisition; three repeated exports/audits for ordinary
--   selectors need reduction.
-- Build status: Value review only; admission still requires explicit inputs and single-
--   owner outputs.
-- Detailed file inputs/outputs: docs/fixture-inputs.log, entry 132.
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : GhcApiFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for ghc api.
module GhcApiFixtures (prepareGhcApi, prepareRecordFields, prepareRecordFieldsDemand, recordFieldsDemandInventory) where

import Control.Monad (forM, forM_, unless, when)
import Data.Aeson (Value, object, toJSON, (.=))
import qualified Data.ByteString.Char8 as BS
import Data.List (isPrefixOf)
import qualified GHC as Ghc
import qualified GHC.Plugins as Ghc
import qualified GHC.Iface.Binary as Iface
import qualified GHC.Iface.Syntax as Iface
import qualified GHC.Unit.Module.WholeCoreBindings as Foreign
import FixtureSupport
import GhcApiAudit (ghcApiOptions, ghcApiAuditArguments, ghcApiAuditEvidence)
import InstalledCoreFixtures (field, readJson)
import System.Directory (canonicalizePath, copyFile, createDirectoryIfMissing, doesDirectoryExist, doesFileExist, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeDirectory)
import THC.Compact.Module (readModuleValue)
import qualified THC.Driver.Installed as Installed
import qualified Data.Map.Strict as Map
import qualified System.Info as Host

-- Two independently compiled modules retain duplicate record selectors and a
-- cross-module ordinary selector alias. Both plugin boundaries and subsequent
-- interface hydration must agree on GHC's field-namespace identities.
prepareRecordFields :: FilePath -> IO ()
prepareRecordFields root = do
  let directory = "build/record-fields"
      execute = runLogged 180 root (directory </> "logs")
      modules = ["RecordFieldLibrary", "RecordFieldClient"]
      sources = map (\name -> "t/fixtures/compiler" </> name ++ ".hs") (modules ++ ["RecordFieldNative"])
      single result = case BS.lines (commandStdout result) of
        [value] -> pure (BS.unpack value)
        _ -> die "record-fields: expected one output line"
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  suppliedPlugin <- lookupEnv "THC_PLUGIN_MANIFEST"
  suppliedHelper <- lookupEnv "THC_INTERFACE"
  (pluginInfo, helper, toolCommands) <- case (suppliedPlugin, suppliedHelper) of
    (Just publication, Just helper) -> do
      info <- readJson publication
      pure (info, helper, [])
    (Nothing, Nothing) -> do
      ghcPkg <- maybe "ghc-pkg" id <$> lookupEnv "GHC_PKG"
      cabal <- maybe "cabal" id <$> lookupEnv "CABAL"
      plugin <- execute "plugin-build" [] "bin/build-compiler.sh" []
      info <- readJson (root </> "build/compiler/plugin.json")
      let selection = ["exe:thc-interface", "--offline", "--with-compiler=" ++ ghc, "--with-hc-pkg=" ++ ghcPkg]
      helperBuild <- execute "helper-build" [] cabal ("build" : selection)
      helperLocation <- execute "helper-location" [] cabal ("list-bin" : selection)
      helper <- single helperLocation
      pure (info, helper, [plugin, helperBuild, helperLocation])
    _ -> die "Record fields require both THC_PLUGIN_MANIFEST and THC_INTERFACE when using declared tools"
  packageDb <- field pluginInfo "packageDb"
  pluginUnit <- field pluginInfo "unitId"
  library <- execute "libdir" [] ghc ["--print-libdir"]
  libdir <- single library
  createDirectoryIfMissing True (root </> directory </> "installed")
  commands <- fmap concat $ forM ["pre", "post"] $ \stage -> do
    let output = root </> directory </> stage
        build = output </> "ghc"
    createDirectoryIfMissing True build
    compiled <- execute (stage ++ "-compile") [] ghc
      (["--make", "-O0", "-dynamic-too", "-fforce-recomp", "-fwrite-if-simplified-core",
        "-i", "-it/fixtures/compiler", "-odir", build, "-hidir", build,
        "-package-db", packageDb, "-plugin-package-id", pluginUnit,
        "-fplugin=THC.Plugin", "-fplugin-opt=THC.Plugin:" ++ output,
        "-o", output </> "oracle"] ++
        ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++ [last sources])
    oracle <- execute (stage ++ "-native") [] (output </> "oracle") []
    recovered <- if stage /= "post" then pure [] else forM modules $ \name -> do
      loaded <- execute ("installed-" ++ name) [] helper
        ["--libdir", libdir, "--unit", "main", "--module", name,
         "--interface", build </> name ++ ".hi", "--home-interfaces", build]
      _ <- either (die . ("record-fields: invalid helper CBD: " ++)) pure (readModuleValue (commandStdout loaded))
      BS.writeFile (root </> directory </> "installed" </> name ++ ".cbd") (commandStdout loaded)
      pure loaded
    pure ([compiled, oracle] ++ recovered)
  audits <- forM [(stage, entry) | stage <- ["pre", "post", "installed"], entry <- ["fieldAlias", "duplicateFields"]] $
    \(stage, entry) -> execute (stage ++ "-audit-" ++ entry) [] "python3"
      (["bin/audit-core.py", "--entry", "main:RecordFieldClient." ++ entry,
        "--output", directory </> stage </> entry ++ "-audit.json"] ++
       map (\name -> directory </> stage </> name ++ ".cbd") modules)
  inputs <- hashes root (sources ++ ["src/compiler/THC/Plugin.hs", "t/haskell-fixtures/GhcApiFixtures.hs"])
  let records = toolCommands ++ [library] ++ commands ++ audits
  artifacts <- hashes root (concatMap commandArtifacts records ++
    [directory </> stage </> name ++ ".cbd" | stage <- ["pre", "post", "installed"], name <- modules])
  writeJson (root </> directory </> "manifest.json") $ object
    ["schema" .= (1 :: Int), "inputHashes" .= inputs, "artifactHashes" .= artifacts,
     "commands" .= map commandRecord records]
  putStrLn "record-fields: original pre/post-Tidy and hydrated Core exported; six strict audits accepted"

-- The retained variant carries the same typed provenance annotations as
-- acquired libraries; the thin variant uses plain GHC. Publication calls the
-- actual installed demand provider;
-- the JVM test, rather than fixture generation, exercises conversion and errors.
prepareRecordFieldsDemand :: FilePath -> IO ()
prepareRecordFieldsDemand root = do
  let directory = root </> "build/record-fields-demand"
      execute label program arguments = runLogged 180 root (directory </> "logs") label [] program arguments
      single result = case BS.lines (commandStdout result) of
        [value] -> pure (BS.unpack value)
        _ -> die "record-fields-demand: expected one output line"
      unit = "thc-record-demand-0.1"
      names = ["RecordFieldLibrary", "RecordFieldClient", "RecordFieldCold"] :: [String]
      sources = ["t/fixtures/compiler/RecordFieldNative.hs", "t/fixtures/compiler/RecordFieldCold.hs"]
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  pkg <- maybe "ghc-pkg" id <$> lookupEnv "GHC_PKG"
  helper <- lookupEnv "THC_INTERFACE" >>= maybe (die "record-fields-demand requires the declared THC_INTERFACE tool") canonicalizePath
  pluginInfo <- lookupEnv "THC_PLUGIN_MANIFEST" >>= maybe (die "record-fields-demand requires the declared THC plugin") readJson
  packageDb <- field pluginInfo "packageDb"
  pluginUnit <- field pluginInfo "unitId"
  base <- single =<< execute "base-id" pkg ["--global", "--no-user-package-db", "field", "base", "id", "--simple-output"]
  commands <- fmap concat $ forM ["full", "thin"] $ \mode -> do
    let output = directory </> mode
        database = output </> "package.conf.d"
    createDirectoryIfMissing True output
    let common = ["--make", "-O0", "-g", "-fforce-recomp", "-this-unit-id", unit,
                  "-i", "-it/fixtures/compiler", "-odir", output, "-hidir", output]
        selected = if mode == "full" then
          ["-fwrite-if-simplified-core", "-o", output </> "oracle",
           "-package-db", packageDb, "-plugin-package-id", pluginUnit, "-fplugin=THC.Plugin"] ++
          map ("-fplugin-opt=THC.Plugin:" ++) [output, "post-tidy", "foreign-export-associations",
            "foreign-export-registration", "foreign-import-provenance"]
          else ["-fno-write-if-simplified-core", "-no-link"]
    compiled <- execute (mode ++ "-compile") ghc (common ++ selected ++ sources)
    exists <- doesDirectoryExist database
    initialized <- if exists then pure [] else (:[]) <$> execute (mode ++ "-db-init") pkg ["init", database]
    let registration = output </> unit ++ ".conf"
    BS.writeFile registration $ BS.pack $ unlines
      ["name: thc-record-demand", "version: 0.1", "id: " ++ unit, "key: " ++ unit,
       "exposed: True", "exposed-modules: " ++ unwords names,
       "import-dirs: " ++ output, "depends: " ++ base]
    registered <- execute (mode ++ "-register") pkg ["--package-db", database, "update", registration]
    pure ([compiled] ++ initialized ++ [registered])
  oracle <- execute "native" (directory </> "full/oracle") []
  BS.writeFile (directory </> "native.tsv") (commandStdout oracle)
  writeJson (directory </> "tools.json") $ object ["ghc" .= ghc, "ghcPkg" .= pkg, "helper" .= helper]
  recordFieldsDemandInventory root "full" (directory </> "packages.json")
  inputs <- hashes root (sources ++ ["t/fixtures/compiler/RecordFieldLibrary.hs", "t/fixtures/compiler/RecordFieldClient.hs",
      "t/haskell-fixtures/GhcApiFixtures.hs", "src/compiler/THC/Interface.hs", "src/driver/THC/Driver/Installed.hs"])
  artifacts <- hashes root (["build/record-fields-demand" </> mode </> name ++ suffix |
      mode <- ["full", "thin"], name <- names, suffix <- [".hi", ".o"]] ++
      ["build/record-fields-demand/native.tsv", "build/record-fields-demand/packages.json", "build/record-fields-demand/tools.json"])
  writeJson (directory </> "manifest.json") $ object ["schema" .= (1 :: Int), "inputHashes" .= inputs,
    "artifactHashes" .= artifacts, "commands" .= map commandRecord (commands ++ [oracle])]
  putStrLn "record-fields-demand: raw retained/thin interfaces and native oracle published"

-- A command in the existing fixture tool calls real acquisition on either DB.
-- In particular, thin-Core failure remains a live JVM assertion, not a cached
-- producer success boolean. This operation never compiles or links anything.
recordFieldsDemandInventory :: FilePath -> String -> FilePath -> IO ()
-- Live rejection controls own private copies; prepared fixture inputs are never
-- changed. Each call uses the real provider, including its complete input hash.
recordFieldsDemandInventory root "annotations" destination = do
  let fixture = root </> "build/record-fields-demand"
      directory = takeDirectory destination </> "interfaces"
      database = directory </> "package.conf.d"
      unitId = "thc-record-demand-0.1"
      execute label program arguments = runLogged 60 root (directory </> "logs") label [] program arguments
  tools <- readJson (fixture </> "tools.json")
  ghc <- field tools "ghc"
  pkg <- field tools "ghcPkg"
  helper <- field tools "helper"
  createDirectoryIfMissing True directory
  forM_ ["RecordFieldLibrary", "RecordFieldClient", "RecordFieldCold"] $ \name ->
    copyFile (fixture </> "full" </> name ++ ".hi") (directory </> name ++ ".hi")
  registration <- readFile (fixture </> "full" </> unitId ++ ".conf")
  let conf = directory </> unitId ++ ".conf"
  writeFile conf $ unlines [if "import-dirs:" `isPrefixOf` line then "import-dirs: " ++ show directory else line |
    line <- lines registration]
  _ <- execute "init" pkg ["init", database]
  _ <- execute "register" pkg ["--package-db", database, "update", conf]
  selected <- Installed.installedContext ghc pkg helper [database] (object [])
  let context = selected { Installed.installedInterfaceWay = Installed.VanillaInterfaces }
  unit <- Installed.discoverInstalled context unitId
  observations <- Ghc.runGhc (Just (Installed.installedLibdir context)) $ do
    environment <- Ghc.getSession
    Ghc.liftIO $ do
      let profile = Ghc.targetProfile (Ghc.hsc_dflags environment)
          path = directory </> "RecordFieldCold.hi"
      original <- Iface.readBinIface profile (Ghc.hsc_NC environment) Iface.CheckHiWay Iface.QuietBinIFace path
      proof <- case Ghc.mi_anns original of
        first:_ -> pure first
        [] -> die "record-fields-demand: expected compiler-produced provenance annotations"
      simplified <- maybe (die "record-fields-demand: missing retained Core") pure (Ghc.mi_simplified_core original)
      let annotations = Ghc.mi_anns original
          unknown = Iface.IfaceAnnotation (Ghc.ModuleTarget (Ghc.mi_module original))
            (Ghc.toSerialized Ghc.serializeWithData ("thc:backend=ast" :: String))
          other = Ghc.mkModule (Ghc.moduleUnit (Ghc.mi_module original)) (Ghc.mkModuleName "RecordFieldClient")
          controls =
            [ ("empty-provenance", original)
            , ("unannotated", Ghc.set_mi_anns [] original)
            , ("runtime-policy", Ghc.set_mi_anns (unknown:annotations) original)
            , ("duplicate-proof", Ghc.set_mi_anns (proof:annotations) original)
            , ("named-proof", Ghc.set_mi_anns [proof {Iface.ifAnnotatedTarget = Ghc.NamedTarget (Ghc.mkVarOcc "cold")}] original)
            , ("wrong-owner", Ghc.set_mi_anns [proof {Iface.ifAnnotatedTarget = Ghc.ModuleTarget other}] original)
            , ("foreign-product", Ghc.set_mi_simplified_core (Just simplified {Ghc.mi_sc_foreign =
                Foreign.IfaceForeign Nothing [Foreign.IfaceForeignFile Ghc.LangC "int extra;" ".c"]}) original)
            ]
      forM controls $ \(label, iface) -> do
        Iface.writeBinIface profile Iface.QuietBinIFace Iface.NormalCompression path iface
        (_, units) <- Installed.prepareInstalledDemand context [unit]
        pure (label, Map.member unitId units)
  writeJson destination (toJSON (Map.fromList observations :: Map.Map String Bool))
recordFieldsDemandInventory root mode destination = do
  unless (mode `elem` ["full", "thin"]) (die "record-fields-demand-inventory expects full or thin")
  tools <- readJson (root </> "build/record-fields-demand/tools.json")
  ghc <- field tools "ghc"
  pkg <- field tools "ghcPkg"
  helper <- field tools "helper"
  let compiler = object ["id" .= ("ghc-9.14.1" :: String), "abi" .= ("fixture" :: String),
                        "platform" .= (Host.arch ++ "-" ++ Host.os)]
  selected <- Installed.installedContext ghc pkg helper
    [root </> "build/record-fields-demand" </> mode </> "package.conf.d"] compiler
  let context = selected { Installed.installedInterfaceWay = Installed.VanillaInterfaces }
  unit <- Installed.discoverInstalled context "thc-record-demand-0.1"
  (inputs, units) <- Installed.prepareInstalledDemand context [unit]
  unless (Map.member (Installed.registeredId unit) units)
    (die "record-fields-demand: raw fixture unit is not eligible for demand")
  writeJson destination $ object ["format" .= ("thc-core-packages" :: String), "schema" .= (1 :: Int),
    "ghc" .= ("9.14.1" :: String), "interfaceInputs" .= inputs, "units" .= Map.elems units]

-- Ordinary production acquisition of the original compiler package. A failed
-- stage leaves its command record/audit intact and never publishes a success
-- manifest. No replacement compiler bodies or edited package Core are used.
prepareGhcApi :: FilePath -> [String] -> IO ()
prepareGhcApi root requested = do
  (auditRequested, probes) <- either die pure (ghcApiOptions requested)
  let fixture = root </> "src/examples/standard-apps/ghc-api"
      nativeDist = root </> "build/ghc-api/native-control"
      common = "build/ghc-api/logs"
      execute = runLogged 3600 root
  ghc <- selected "THC_INSTALLED_CORE_GHC" "GHC" "ghc"
  ghcPkg <- selected "THC_INSTALLED_CORE_GHC_PKG" "GHC_PKG" "ghc-pkg"
  cabal <- maybe "cabal" id <$> lookupEnv "CABAL"
  source <- lookupEnv "THC_INSTALLED_CORE_GHC_SOURCE"
  runtime <- maybe (pure (root </> "build/install/thc/bin/thc")) canonicalizePath =<< lookupEnv "THC_TEST_RUNTIME"
  suppliedDriver <- lookupEnv "THC_TEST_DRIVER"
  let selection = ["exe:thc", "--offline", "--with-compiler=" ++ ghc, "--with-hc-pkg=" ++ ghcPkg]
  driver <- case suppliedDriver of
    Just path -> canonicalizePath path
    Nothing -> do
      _ <- execute common "driver-build" [] cabal ("build" : selection)
      singleLine "driver" =<< execute common "driver-location" [] cabal ("list-bin" : selection)
  libdir <- singleLine "libdir" =<< execute common "libdir" [] ghc ["--print-libdir"]
  forM_ probes $ \probe -> do
    let name = "ghc-" ++ probe
        acquired = "build/ghc-api/guest-" ++ probe
        logs = acquired </> "logs"
        manifest = root </> acquired </> "manifest.json"
        cabalArguments = [name, "--offline", "--project-file=" ++ (fixture </> "cabal.project"),
          "--builddir=" ++ nativeDist, "--with-compiler=" ++ ghc, "--with-hc-pkg=" ++ ghcPkg]
        arguments = case probe of
          "faststring" -> ["THC λ", "GHC API"]
          "session" -> [libdir]
          _ -> [libdir, fixture </> "subjects/Probe.hs"]
    createDirectoryIfMissing True (root </> acquired)
    stale <- doesFileExist manifest
    when stale (removeFile manifest)
    built <- execute logs "native-build" [] cabal ("build" : cabalArguments)
    located <- execute logs "native-location" [] cabal ("list-bin" : cabalArguments)
    binary <- singleLine "native executable" located
    native <- execute logs "native" [] binary arguments
    managed <- execute logs "thc" [] driver
      (["run", "--project-dir", fixture, name, "--thc-root", root, "--runtime", runtime,
        "--dist-dir", root </> acquired, "--installed-core", "required",
        "--with-ghc", ghc, "--with-ghc-pkg", ghcPkg] ++
        maybe [] (\path -> ["--ghc-source", path]) source ++
        ghcApiAuditArguments auditRequested ++ ["--"] ++ arguments)
    unless (commandStdout managed == commandStdout native)
      (die ("ghc-api: " ++ probe ++ " differs from native GHC; see " ++ logs))
    (accepted, auditArtifacts) <- ghcApiAuditEvidence auditRequested (root </> acquired </> "audit.json")
    packages <- readJson (root </> acquired </> "packages.json")
    records <- field packages "units" :: IO [Value]
    -- Retain the producer's declared content identities. Full artifact checking
    -- and the semantic audit are explicitly requested, not default launch work.
    let commands = [built, located, native, managed]
    inputs <- hashes root (["t/haskell-fixtures/GhcApiFixtures.hs", "t/haskell-fixtures/GhcApiAudit.hs",
      "t/haskell-fixtures/FixtureSupport.hs"] ++
      ["src/examples/standard-apps/ghc-api" </> path | path <-
       ["cabal.project", "ghc-api-thc-check.cabal", probe </> "Main.hs", "subjects/Probe.hs"]])
    artifacts <- hashes root (auditArtifacts ++ [acquired </> "packages.json",
      acquired </> "native/cache/plan.json"] ++ concatMap commandArtifacts commands)
    driverHash <- hashFile driver
    nativeHash <- hashFile binary
    backend <- lookupEnv "THC_BACKEND"
    javaOptions <- lookupEnv "JAVA_TOOL_OPTIONS"
    writeJson manifest $ object
      ["schema" .= (2 :: Int), "probe" .= probe, "auditRequested" .= auditRequested,
       "strictAccepted" .= accepted,
       "nativeMatched" .= True, "driver" .= driver, "driverSha256" .= driverHash,
       "nativeExecutable" .= binary, "nativeExecutableSha256" .= nativeHash,
       "runtime" .= runtime, "backendEnvironment" .= backend, "javaToolOptions" .= javaOptions,
       "inputHashes" .= inputs, "artifactHashes" .= artifacts, "packages" .= records,
       "commands" .= map commandRecord commands]
    putStrLn ("ghc-api: " ++ probe ++ " native output matched; " ++
      if auditRequested then "requested strict audit accepted" else "strict audit not requested")
  where
    selected preferred ordinary fallback = do
      override <- lookupEnv preferred
      maybe (maybe fallback id <$> lookupEnv ordinary) pure override
    singleLine description result = case BS.lines (commandStdout result) of
      [value] -> pure (BS.unpack value)
      _ -> die ("ghc-api: expected one " ++ description ++ " line")
