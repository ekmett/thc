-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module InterfaceFixtures (prepareInterfaceCore) where

import Control.Monad (filterM, forM, forM_, unless)
import qualified Control.Exception as Exception
import Data.Aeson (Value(..), Result(..), fromJSON, toJSON, object, (.=), decodeStrict', encode)
import Data.Aeson.Key (Key)
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString.Char8 as BS
import qualified Data.ByteString.Lazy as BL
import Data.Char (isHexDigit)
import Data.List (isInfixOf, sort)
import Data.Foldable (toList)
import Data.Maybe (isNothing)
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import FixtureSupport (CommandResult(..), hashes, runLogged, runLoggedExpect, writeJson)
import InterfaceForeignFacts (prepareForeignAssociation, prepareTypedForeignAssociation, prepareImportStubs, inspectInstalledBound)
import InstalledCacheFixtures (checkInstalledCache)
import GHC hiding (exprType, entry)
import GHC.Plugins
import GHC.Core.TyCo.Compare (eqType)
import qualified GHC.Data.ShortText as ShortText
import GHC.Iface.Binary (readBinIface, CheckHiWay(..), TraceBinIFace(..))
import GHC.Iface.Syntax (IfaceBindingX(..))
import GHC.Cmm.CLabel (CStubLabel(..))
import qualified GHC.Unit.Module.WholeCoreBindings as ForeignCore
import GHC.Types.TypeEnv (typeEnvIds)
import GHC.Unit.Module.ModDetails (md_types)
import System.Directory (copyFile, createDirectoryIfMissing, doesDirectoryExist, doesFileExist,
                         listDirectory, renameFile, withCurrentDirectory)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), makeRelative, splitDirectories, takeExtension, takeDirectory)
import qualified System.Info as Info
import THC.Interface
import THC.Driver.ForeignBitcode (linkClockGetTime)
import qualified THC.Driver.Installed as Installed
import qualified THC.Driver.Project as Project

unitName :: String
unitName = "thc-interface-fixture-0.1"

check :: Bool -> String -> IO ()
check condition message = unless condition (die message)

prepareInterfaceCore :: FilePath -> IO ()
prepareInterfaceCore root = do
  let directory = "build/interface-core"
      generated = directory </> "source/InterfaceLibrary.hs"
      run label program args = runLogged 180 root (directory </> "logs") label [] program args
      expectedModule = mkModule (stringToUnit unitName) (mkModuleName "InterfaceLibrary")
      entries = ["opaqueEntry", "inlineEntry", "recursiveEntry", "coercionEntry", "wrapperEntry"] :: [String]
  mapM_ (createDirectoryIfMissing True . (root </>))
    [directory </> name | name <- ["source", "full", "thin", "foreign", "native", "no-source"]]
  copyFile (root </> "compiler/test-fixtures/InterfaceLibrary.hs") (root </> generated)
  let cbvSource = directory </> "source/CBVCoercionAudit.hs"
  copyFile (root </> "compiler/test-fixtures/CBVCoercionAudit.hs") (root </> cbvSource)
  cabal <- maybe "cabal" id <$> lookupEnv "CABAL"
  helperBuild <- run "helper-build" cabal ["build", "exe:thc-interface", "--offline", "-fdevelopment"]
  helperLocation <- run "helper-location" cabal ["list-bin", "exe:thc-interface", "--offline"]
  helper <- case lines (BS.unpack (commandStdout helperLocation)) of
    [path] -> pure path
    _ -> die "Expected one selected-GHC helper executable"
  pluginBuild <- run "plugin-build" "compiler/build.sh" []
  pluginInfo <- decodeFile (root </> "build/compiler/plugin.json")
  let field name = case fromJSON (valueAt name pluginInfo) of
        Success value -> pure value
        Error _ -> die "Bad plugin manifest"
  pluginDb <- field "packageDb"
  pluginUnit <- field "unitId"
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  ghcPkg <- maybe "ghc-pkg" id <$> lookupEnv "GHC_PKG"
  version <- run "ghc-version" ghc ["--numeric-version"]
  check (BS.words (commandStdout version) == ["9.14.1"]) "Interface fixture requires GHC 9.14.1"
  libdirResult <- run "libdir" ghc ["--print-libdir"]
  baseResult <- run "base-unit" ghcPkg ["field", "base", "id", "--simple-output"]
  baseUnit <- case BS.words (commandStdout baseResult) of
    [name] -> pure (BS.unpack name)
    _ -> die "Expected exactly one selected base unit"
  wiredResult <- run "wired-unit" ghcPkg ["field", "ghc-internal", "id", "--simple-output"]
  wiredUnit <- case BS.words (commandStdout wiredResult) of
    [name] -> pure (BS.unpack name)
    _ -> die "Expected exactly one selected ghc-internal registration"
  libdir <- case lines (BS.unpack (commandStdout libdirResult)) of
    [path] -> pure path
    _ -> die "Expected exactly one selected GHC libdir"
  builds <- forM ["full", "thin"] $ \mode -> do
    let output = directory </> mode
        database = root </> output </> "package.conf.d"
        conf = output </> "package.conf"
        complete = mode == "full"
    let common = ["-c", "-O2", "-g", "-dynamic-too", "-fforce-recomp", "-dcore-lint", "-dstg-lint",
          "-this-unit-id", unitName, if complete then "-fwrite-if-simplified-core" else "-fno-write-if-simplified-core",
          "-odir", output, "-hidir", output] ++
          (if complete then ["-package-db", pluginDb, "-plugin-package-id", pluginUnit,
            "-fplugin=THC.Plugin", "-fplugin-opt=THC.Plugin:" ++ root </> directory </> "direct",
            "-fplugin-opt=THC.Plugin:post-tidy"] else [])
    compiled <- run (mode ++ "-compile") ghc (common ++ [generated])
    cbvCompiled <- run (mode ++ "-cbv-compile") ghc (common ++ [cbvSource])
    exists <- doesDirectoryExist database
    initialized <- if exists then pure [] else (:[]) <$> run (mode ++ "-init") ghcPkg ["init", database]
    writeFile (root </> conf) $ unlines
      ["name: thc-interface-fixture", "version: 0.1", "id: " ++ unitName,
       "key: " ++ unitName, "exposed: True", "exposed-modules: InterfaceLibrary CBVCoercionAudit",
       "import-dirs: " ++ show (root </> output), "depends: " ++ baseUnit]
    registered <- run (mode ++ "-register") ghcPkg ["--package-db", database, "update", root </> conf]
    pure ([compiled,cbvCompiled] ++ initialized ++ [registered])
  foreignBuild <- run "foreign-compile" ghc
    ["-c", "-O2", "-dynamic-too", "-fforce-recomp", "-this-unit-id", unitName, "-fwrite-if-simplified-core",
     "-odir", directory </> "full", "-hidir", directory </> "full",
     "-stubdir", directory </> "full",
     "compiler/test-fixtures/InterfaceForeign.hs"]
  let foreignDb = root </> directory </> "foreign/package.conf.d"
      foreignConf = directory </> "foreign/package.conf"
  foreignExists <- doesDirectoryExist foreignDb
  foreignInit <- if foreignExists then pure [] else (:[]) <$> run "foreign-init" ghcPkg ["init", foreignDb]
  writeFile (root </> foreignConf) $ unlines
    ["name: thc-interface-fixture", "version: 0.1", "id: " ++ unitName,
     "key: " ++ unitName, "exposed: True", "exposed-modules: InterfaceForeign",
     "import-dirs: " ++ show (root </> directory </> "full"), "depends: " ++ baseUnit]
  foreignRegistered <- run "foreign-register" ghcPkg ["--package-db", foreignDb, "update", root </> foreignConf]
  nativeBuild <- run "native-compile" ghc
    ["--make", "-O2", "-fforce-recomp", "-dcore-lint", "-dstg-lint", "-i",
     "-package-db", directory </> "full/package.conf.d", "-package-id", unitName,
     "-odir", directory </> "native", "-hidir", directory </> "native",
     "compiler/test-fixtures/InterfaceNative.hs", directory </> "full/InterfaceLibrary.o",
     directory </> "full/CBVCoercionAudit.o",
     "-o", directory </> "native/oracle"]
  oracle <- run "native-oracle" (root </> directory </> "native/oracle") []
  check (length (BS.lines (commandStdout oracle)) == 21) "Interface native oracle row count changed"
  -- Remove the compiled source target from its recorded path, preserving a
  -- copy as evidence. The loader gets only a package DB, .hi and expected ID.
  renameFile (root </> generated) (root </> directory </> "source/InterfaceLibrary.saved")
  renameFile (root </> cbvSource) (root </> directory </> "source/CBVCoercionAudit.saved")
  sourcePresent <- doesFileExist (root </> generated)
  check (not sourcePresent) "Generated source is still present"
  withCurrentDirectory (root </> directory </> "no-source") $ do
    forM_ ["full", "thin"] $ \mode -> runGhc (Just libdir) $ do
      initial <- getSessionDynFlags
      initialEnv <- getSession
      (flags,leftovers,_) <- parseDynamicFlags (hsc_logger initialEnv) initial
        (map noLoc ["-package-db", root </> directory </> mode </> "package.conf.d", "-package-id", unitName])
      liftIO $ check (null leftovers) "Unexpected GHC flag leftovers"
      _ <- setSessionDynFlags (gopt_set flags Opt_IgnoreInterfacePragmas)
      environment <- getSession
      liftIO $ do
        let path = root </> directory </> mode </> "InterfaceLibrary.hi"
        loaded <- loadInterfaceCore environment expectedModule path
        check (gopt Opt_IgnoreInterfacePragmas (hsc_dflags environment)) "Caller flags were changed"
        check (debugLevel (hsc_dflags environment) == debugLevel flags) "Caller debug flags were changed"
        forM_ [mkModule (moduleUnit expectedModule) (mkModuleName "WrongModule"),
              mkModule (stringToUnit "wrong-unit") (moduleName expectedModule)] $ \wrong -> do
          result <- try (loadInterfaceCore environment wrong path)
          case result of
            Left (InterfaceModuleMismatch requested actual) ->
              check (requested == wrong && actual == expectedModule) "Identity diagnostic lost the units/modules"
            _ -> die "A mismatched interface identity was accepted"
        case loaded of
          Nothing -> check (mode == "thin") "Complete interface unexpectedly unavailable"
          Just core -> do
            check (mode == "full") "Thin interface unexpectedly yielded bodies"
            checkCore environment path core
            rendered <- interfaceCoreJSON ["source-notes", "unit-qualified"] core
            writeFile (root </> directory </> "InterfaceLibrary.json") rendered
            way <- try (loadInterfaceCore environment expectedModule
              (root </> directory </> "full/InterfaceLibrary.dyn_hi"))
            case way of
              Left (ProgramError message) -> check ("profile tag" `isInfixOf` message) "Wrong-way failure was unrelated"
              _ -> die "Wrong-way interface was accepted"
            foreignResult <- loadInterfaceCore environment
              (mkModule (moduleUnit expectedModule) (mkModuleName "InterfaceForeign"))
              (root </> directory </> "full/InterfaceForeign.hi")
            case foreignResult of
              Just foreignCore -> checkForeignCore environment
                (root </> directory </> "full/InterfaceForeign.hi") foreignCore
              Nothing -> die "Complete foreign interface lost its Core"
            again <- loadInterfaceCore environment expectedModule path
            case again of
              Just other -> do
                -- GHC allocates fresh uniques for interface-local binders;
                -- only external names are interned in the shared NameCache.
                let externalNames loadedCore = [varName v | (v,_) <- flattenBinds (interfaceBindings loadedCore),
                      isExternalName (varName v)]
                check (externalNames core == externalNames other) "External NameCache identities changed"
                checkCore environment path other
              Nothing -> die "Repeat interface load lost its complete payload"
            cbv <- loadInterfaceCore environment
              (mkModule (moduleUnit expectedModule) (mkModuleName "CBVCoercionAudit"))
              (root </> directory </> "full/CBVCoercionAudit.hi")
            case cbv of
              Nothing -> die "Installed CBV control lacks complete Core"
              Just control -> check (any (maybe False (any isMarkedCbv) . idCbvMarks_maybe . fst)
                (flattenBinds (interfaceBindings control))) "No actual hydrated GHC CBV marks"
    pure ()
  helperCommands <- checkHelper root directory libdir helper
  associationCommands <- prepareForeignAssociation root directory ghc libdir unitName
  typedAssociationCommands <- prepareTypedForeignAssociation root directory ghc ghcPkg libdir unitName baseUnit pluginDb pluginUnit
  importCommands <- prepareImportStubs root directory ghc ghcPkg libdir unitName baseUnit pluginDb pluginUnit
  wiredCommands <- checkWiredHelper root directory libdir helper wiredUnit baseUnit
  wrapperFacts <- decodeFile (root </> directory </> "installed-wrapper-facts.json")
  let wrapperArtifacts = case wrapperFacts of
        Array rows -> [makeRelative root path | row <- toList rows,
          valueAt "completeCore" row == Bool True, key <- ["exported", "audit"],
          String pathText <- [valueAt key row], let path = Text.unpack pathText]
        _ -> []
  checkDriver root directory ghc ghcPkg helper baseUnit
  (cacheCommands, cacheArtifacts) <- checkInstalledCache root directory ghc ghcPkg helper libdir baseUnit
  audits <- forM entries $ \entry -> run ("audit-" ++ entry) "python3"
    ["scripts/audit-core.py", "--entry", unitName ++ ":" ++
      (if entry == "coercionEntry" then "CBVCoercionAudit." else "InterfaceLibrary.") ++ entry,
     "--output", directory </> entry ++ "-audit.json", "--package-manifest", directory </> "packages.json"]
  plugin <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  let inputs = sort $ ["compiler/test-fixtures/InterfaceLibrary.hs", "compiler/test-fixtures/InterfaceNative.hs",
        "compiler/test-fixtures/InterfaceForeign.hs", "test/haskell-fixtures/InterfaceFixtures.hs",
        "compiler/test-fixtures/InterfaceForeignAlias.hs", "test/haskell-fixtures/InterfaceForeignFacts.hs",
        "compiler/test-fixtures/ForeignExportSignatures.hs",
        "compiler/test-fixtures/ForeignExportManaged.hs", "compiler/test-fixtures/ManagedExportNative.hs",
        "compiler/test-fixtures/ForeignExportRegistration.hs",
        "compiler/test-fixtures/RegistrationNative.hs",
        "compiler/test-fixtures/ForeignImportStubs.hs", "compiler/test-fixtures/ImportStubsNative.hs",
        "compiler/test-fixtures/CBVCoercionAudit.hs", "compiler/interface/Main.hs",
        "compiler/test-fixtures/InterfaceCacheRoot.hs", "compiler/test-fixtures/InterfaceCacheDependency.hs",
        "test/haskell-fixtures/InstalledCacheFixtures.hs",
        "src/THC/Driver/Installed.hs", "src/THC/Driver/Project.hs", "src/THC/Driver/Wired.hs",
        "src/THC/Driver/ForeignBitcode.hs", "compiler/target-layout.c",
        "src/THC/Driver/Zip.hs",
        "test/haskell-fixtures/FixtureSupport.hs", "test/haskell-fixtures/Main.hs", "thc.cabal", "cabal.project",
        "scripts/audit-core.py", "scripts/core-capabilities.json"] ++
        ["compiler/THC" </> file | file <- plugin, takeExtension file == ".hs"] ++
        ["scripts" </> file | file <- scripts, take 5 file == "core_", takeExtension file == ".py"]
      commands = [helperBuild, helperLocation, pluginBuild, version, libdirResult, baseResult, wiredResult] ++
        concat builds ++ [foreignBuild] ++ foreignInit ++ [foreignRegistered, nativeBuild, oracle] ++
        helperCommands ++ associationCommands ++ typedAssociationCommands ++ importCommands ++ wiredCommands ++ cacheCommands ++ audits
      artifacts = concatMap commandArtifacts commands ++ wrapperArtifacts ++ cacheArtifacts ++
        [directory </> name | name <- ["InterfaceLibrary.json", "full/InterfaceLibrary.hi", "thin/InterfaceLibrary.hi",
          "full/InterfaceLibrary.dyn_hi", "full/InterfaceForeign.hi", "native/oracle", "source/InterfaceLibrary.saved"]] ++
        [directory </> name | name <- ["CBVCoercionAudit.json", "direct/CBVCoercionAudit.json",
          "full/CBVCoercionAudit.hi", "thin/CBVCoercionAudit.hi", "source/CBVCoercionAudit.saved",
          "wired-unit.json"]] ++
        [directory </> "packages.json", directory </> "foreign-packages.json", directory </> "InterfaceForeign.json",
         directory </> "clock-capi.json",
         directory </> "driver-controls.json", directory </> "foreign-association.json",
         directory </> "installed-bound-facts.json", directory </> "installed-wrapper-facts.json", directory </> "foreign-alias/a.json",
         directory </> "foreign-alias/b.json", directory </> "source/InterfaceForeignAlias.hs.saved"] ++
        [directory </> "typed-foreign-exports.json"] ++
        [directory </> "typed-foreign-exports" </> variant ++ ".json" |
          variant <- ["a", "b", "signatures", "static-signatures", "foreign-file", "instrumented", "managed", "registration"]] ++
        [directory </> "typed-foreign-exports/managed/ForeignExportManaged.hi",
         directory </> "typed-export-source/ForeignExportManaged.hs.saved",
         directory </> "typed-foreign-exports/registration/ForeignExportRegistration.hi",
         directory </> "typed-export-source/ForeignExportRegistration.hs.saved"] ++
        [directory </> "import-stubs" </> variant ++ ".json" | variant <- ["plain", "extra-file", "wrapper", "instrumented"]] ++
        [directory </> "source/ForeignImportStubs.hs.saved", directory </> "import-stubs/plain/ForeignImportStubs.hi"] ++
        [directory </> entry ++ "-audit.json" | entry <- entries]
  inputHashes <- hashes root inputs
  artifactHashes <- hashes root artifacts
  writeJson (root </> directory </> "manifest.json") $ object
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "entries" .= entries,
     "unit" .= unitName, "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes,
     "controls" .= (["opaque-body", "private-worker", "recursive-groups", "thin-unavailable",
       "no-source-target", "wrong-module", "wrong-unit", "wrong-way", "foreign-archived", "private-flags", "repeat-load",
       "helper-protocol", "installed-cbv-worker", "installed-wired-unit", "foreign-association-absence",
       "foreign-linked-clock", "typed-foreign-export-associations", "retained-export-registration", "managed-export-original",
       "installed-payload-cache"] :: [String]),
     "commands" .= map commandRecord commands, "runtimeVerified" .= False]
  putStrLn "Prepared complete interface Core: 21 native rows; full/thin/no-source/identity/way/foreign controls passed"

-- Reuse the just-built private interfaces, with both source targets already
-- removed. Exercise the production discovery, process and ZIP paths, not a
-- fixture-specific bundle writer or another native rebuild.
checkDriver :: FilePath -> FilePath -> FilePath -> FilePath -> FilePath -> String -> IO ()
checkDriver root directory ghc ghcPkg helper baseUnit = do
  let cache = root </> directory </> "driver-cache"
      platform = Info.arch ++ "-" ++ case Info.os of
        "darwin" -> "osx"
        "linux" -> "linux"
        other -> other
      compiler = object ["id" .= ("ghc-9.14.1" :: String), "abi" .= ("fixture" :: String),
        "platform" .= platform, "way" .= ("dynamic-nonprofiling" :: String)]
      context mode = Installed.installedContext ghc ghcPkg helper
        [root </> directory </> mode </> "package.conf.d"] compiler
      acquire selected unit = Project.prepareInstalledBundle cache
        (root </> directory </> "native/staging") (root </> "compiler/target-layout.c")
        "fixture-driver" selected unit
      loaded result = case result of Right value -> pure value; Left missing -> die (show missing)
      rejected label expected action = do
        result <- Exception.try action :: IO (Either Exception.IOException (Either Installed.MissingCore Project.InstalledBundle))
        case result of
          Left failure -> check (expected `isInfixOf` Exception.displayException failure)
            ("Unrelated driver failure for " ++ label ++ ": " ++ show failure)
          Right _ -> die ("Driver accepted " ++ label)
  full <- context "full"
  unit <- Installed.discoverInstalled full unitName
  check (map fst (Installed.installedInterfaces unit) == ["CBVCoercionAudit", "InterfaceLibrary"])
    "Driver selected undeclared or missing installed modules"
  Installed.validateReexports [unit]
  first <- loaded =<< acquire full unit
  let artifact = Project.installedBundle first
      location = splitDirectories (makeRelative cache (Project.bundlePath artifact))
  case location of
    ["core-bundles", "v1", partition, exportKey, archive] ->
      check (partition == "ghc-9.14.1-fixture-" ++ platform &&
             length exportKey == 64 && all isHexDigit exportKey &&
             archive == unitName ++ ".zip")
        "Installed Core cache lost the compiler partition or registered package-cache ID"
    _ -> die "Installed Core bundle has an unexpected cache path"
  before <- BS.readFile (Project.bundlePath artifact)
  second <- loaded =<< acquire full unit
  check (Project.installedOwner first == unitName &&
    Project.bundlePath artifact == Project.bundlePath (Project.installedBundle second) &&
    Project.bundleHash artifact == Project.bundleHash (Project.installedBundle second))
    "Unchanged installed Core did not reuse the content-addressed bundle"
  thin <- context "thin"
  thinUnit <- Installed.discoverInstalled thin unitName
  missing <- acquire thin thinUnit
  case missing of
    Left value -> check (Installed.missingUnit value == unitName) "Missing capability lost registration"
    Right _ -> die "Driver silently fell back for a thin installed package"
  rejected "mismatched registration" "identity mismatch" (acquire full unit {Installed.registeredId = baseUnit})
  let wrongWay = unit {Installed.installedInterfaces =
        [(name, root </> directory </> "full" </> name ++ ".hi") | (name, _) <- Installed.installedInterfaces unit]}
  rejected "wrong interface way" "profile tag" (acquire full wrongWay)
  after <- BS.readFile (Project.bundlePath artifact)
  check (before == after) "Failed installed refresh changed an existing bundle"
  foreignContext <- context "foreign"
  foreignUnit <- Installed.discoverInstalled foreignContext unitName
  foreignBundle <- loaded =<< acquire foreignContext foreignUnit
  let capiUnit = "base-fixture" :: String
      capiName = "System.CPUTime.Posix.ClockGetTime" :: String
      capiId = "fixture_clock_id" :: String
      capiSymbols = [capiId, "fixture_clock_time", "fixture_clock_resolution"] :: [String]
      capiSource = unlines
        ["#include <time.h>",
         "HsWord64 fixture_clock_id(void) { return CLOCK_PROCESS_CPUTIME_ID; }",
         "HsInt32 fixture_clock_time(HsWord64 id, void* out) { return clock_gettime(id, out); }",
         "HsInt32 fixture_clock_resolution(HsWord64 id, void* out) { return clock_getres(id, out); }"]
      capiTarget symbol = object ["kind" .= ("static" :: String), "isFunction" .= True,
                                  "unit" .= capiUnit, "symbol" .= symbol]
      capiScalar primitive evaluated = object
        ["kind" .= case primitive of Nothing -> ("void" :: String)
                                     Just "AddrRep" -> "address"
                                     Just _ -> "long",
         "primReps" .= maybe ([] :: [String]) pure primitive, "evaluated" .= evaluated]
      capiTuple output = object
        ["kind" .= ("unknown" :: String), "primReps" .= [output],
         "aggregate" .= ("unboxed-tuple" :: String),
         "components" .= [capiScalar Nothing True, capiScalar (Just output) True],
         "evaluated" .= False]
      capiCall zero symbol = object ["foreignCall" .= object
        ["target" .= capiTarget symbol, "convention" .= ("capi" :: String),
         "safety" .= ("unsafe" :: String), "schema" .= (1 :: Int),
         "arity" .= (if zero then (1 :: Int) else 3),
         "suppliedArity" .= (if zero then (1 :: Int) else 3),
         "argumentReps" .= (if zero then [capiScalar Nothing False]
                             else [capiScalar (Just "Word64Rep") False,
                                   capiScalar (Just "AddrRep") False, capiScalar Nothing False]),
         "resultRep" .= capiTuple (if zero then "Word64Rep" else "Int32Rep")]]
      capiArchiveFor bindings = object ["schema" .= (2 :: Int), "unit" .= capiUnit,
        "module" .= capiName, "bindings" .= bindings,
        "foreign" .= object ["schema" .= (1 :: Int), "execution" .= ("not-linked" :: String),
          "stubs" .= object ["header" .= ("" :: String), "source" .= capiSource,
            "initializers" .= ([] :: [Value]), "finalizers" .= ([] :: [Value])],
          "files" .= ([] :: [Value])]]
      capiArchive = capiArchiveFor (zipWith capiCall [True, False, False] capiSymbols)
  capiLinked <- linkClockGetTime (Installed.installedLibdir full)
    (root </> directory </> "native/staging") (Info.arch ++ "-" ++ Info.os)
    capiUnit capiName (BL.toStrict (encode capiArchive))
  let capiRecord = maybe Null id (decodeStrict' capiLinked)
      capiLink = valueAt "foreignLink" capiRecord
  check (valueAt "format" capiLink == "llvm-bitcode" &&
         valueAt "symbols" capiLink == toJSON capiSymbols &&
         valueAt "abi" capiLink == toJSON
           [object ["symbol" .= symbol, "kind" .= (if zero then "clock-id" else "clock-buffer" :: String)]
           | (zero, symbol) <- zip [True, False, False] capiSymbols] &&
         valueAt "sourceSha256" capiLink /= Null &&
         valueAt "bitcodeSha256" capiLink /= Null)
    "Original callback-free CAPI source was not acquired as LLVM bitcode"
  wrongAbi <- Exception.try (linkClockGetTime (Installed.installedLibdir full)
    (root </> directory </> "native/staging") (Info.arch ++ "-" ++ Info.os)
    capiUnit capiName (BL.toStrict (encode (capiArchiveFor
      (capiCall False capiId : zipWith capiCall [True, False, False] capiSymbols)))))
    :: IO (Either Exception.IOException BS.ByteString)
  check (case wrongAbi of Left _ -> True; Right _ -> False)
    "Original CAPI acquisition accepted a symbol with a different ABI"
  BS.writeFile (root </> directory </> "clock-capi.json") capiLinked
  writeJson (root </> directory </> "foreign-packages.json") $ object
    ["format" .= ("thc-core-packages" :: String), "schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
     "units" .= Project.installedRecords foreignUnit foreignBundle]
  writeJson (root </> directory </> "packages.json") $ object
    ["format" .= ("thc-core-packages" :: String), "schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
     "units" .= Project.installedRecords unit first]
  writeJson (root </> directory </> "driver-controls.json") $ object
    ["sourceDeleted" .= True, "bundle" .= Project.bundlePath artifact,
     "sha256" .= Project.bundleHash artifact, "unchangedReuse" .= True,
     "thinMissing" .= True, "identityFailure" .= True, "wrongWayFailure" .= True, "foreignArtifactsArchived" .= True,
     "failedRefreshPreservedBundle" .= True, "installedArtifactsHashed" .= True,
     "compilerBinariesHashed" .= False, "interfaceFingerprint" .= ("ghc-retained-binary-v1" :: String)]

valueAt :: Key -> Value -> Value
valueAt key (Object fields) = maybe Null id (KeyMap.lookup key fields)
valueAt _ _ = Null

decodeFile :: FilePath -> IO Value
decodeFile path = do
  bytes <- BS.readFile path
  maybe (die ("Invalid JSON: " ++ path)) pure (decodeStrict' bytes)

checkHelper :: FilePath -> FilePath -> FilePath -> FilePath -> IO [CommandResult]
checkHelper root directory libdir helper = do
  let arguments mode name = ["--libdir", libdir, "--unit", unitName, "--module", name,
        "--package-db", root </> directory </> mode </> "package.conf.d",
        "--interface", root </> directory </> mode </> name ++ ".hi", "--source-notes"]
      run code label args = runLoggedExpect code 180 root (directory </> "logs") label [] helper args
      decode result = maybe (die "Helper output is not one JSON value") pure (decodeStrict' (commandStdout result))
  full <- forM ["InterfaceLibrary", "CBVCoercionAudit"] $ \name -> do
    result <- run 0 ("helper-" ++ name) (arguments "full" name)
    output <- decode result
    check (valueAt "schema" output == Number 1 && valueAt "status" output == String "loaded") "Helper did not load full Core"
    let core = valueAt "core" output
    writeJson (root </> directory </> name ++ ".json") core
    pure result
  thin <- run 3 "helper-thin" (arguments "thin" "InterfaceLibrary")
  unavailable <- decode thin
  check (valueAt "status" unavailable == String "unavailable" &&
    valueAt "capability" unavailable == String "complete-interface-core" && valueAt "core" unavailable == Null)
    "Thin helper input silently fell back to unfoldings"
  dynamic <- run 0 "helper-dynamic" (map (\argument ->
    if argument == root </> directory </> "full/InterfaceLibrary.hi"
    then root </> directory </> "full/InterfaceLibrary.dyn_hi" else argument)
    (arguments "full" "InterfaceLibrary") ++ ["--way", "dynamic"])
  dynamicOutput <- decode dynamic
  check (valueAt "status" dynamicOutput == String "loaded") "Matching dynamic interface did not load"
  badWay <- run 1 "helper-wrong-way" (arguments "full" "InterfaceLibrary" ++ ["--way", "dynamic"])
  foreignLoaded <- run 0 "helper-foreign" (arguments "full" "InterfaceForeign")
  foreignOutput <- decode foreignLoaded
  check (valueAt "status" foreignOutput == String "loaded" &&
    valueAt "schema" (valueAt "core" foreignOutput) == Number 2) "Foreign Core did not use archive-only schema"
  writeJson (root </> directory </> "InterfaceForeign.json") (valueAt "core" foreignOutput)
  badModule <- run 1 "helper-wrong-module"
    ["--libdir", libdir, "--unit", unitName, "--module", "Wrong", "--package-db",
     root </> directory </> "full/package.conf.d", "--interface", root </> directory </> "full/InterfaceLibrary.hi"]
  usageError <- run 2 "helper-duplicate-option" (arguments "full" "InterfaceLibrary" ++ ["--unit", unitName])
  forM_ [badWay,badModule,usageError] $ \result -> do
    output <- decode result
    check (valueAt "status" output == String "error" && valueAt "core" output == Null) "Helper failure looks like loaded/unavailable"
  direct <- decodeFile (root </> directory </> "direct/CBVCoercionAudit.json")
  loaded <- decodeFile (root </> directory </> "CBVCoercionAudit.json")
  let bindings value = case valueAt "bindings" value of Array xs -> toList xs; _ -> []
      marked value = case valueAt "entryStrict" value of Array xs -> Bool True `elem` toList xs; _ -> False
      workers = filter marked (bindings direct)
  check (not (null workers)) "Direct late export has no genuine CBV worker"
  forM_ workers $ \original -> case filter ((== valueAt "name" original) . valueAt "name") (bindings loaded) of
    [hydrated] -> do
      check (valueAt "entryStrictSource" original == String "ghc-id" &&
        valueAt "entryStrictSource" hydrated == String "ghc-id") "CBV evidence was synthesized"
      check (valueAt "entryStrict" original == valueAt "entryStrict" hydrated) "Hydrated CBV entryStrict changed"
      check (valueAt "cbvMarks" (valueAt "info" original) == valueAt "cbvMarks" (valueAt "info" hydrated))
        "Hydrated idCbvMarks changed"
    _ -> die "Missing/ambiguous installed CBV worker"
  pure (full ++ [thin,dynamic,badWay,badModule,foreignLoaded,usageError])

-- Compare every serialized foreign field against the actual binary interface,
-- not a pretty-printed dump or a reconstructed replacement C implementation.
checkForeignCore :: HscEnv -> FilePath -> InterfaceCore -> IO ()
checkForeignCore environment path core = do
  raw <- readBinIface (targetProfile (hsc_dflags environment)) (hsc_NC environment) CheckHiWay QuietBinIFace path
  rawForeign <- case mi_simplified_core raw of
    Just simplified -> pure (mi_sc_foreign simplified)
    Nothing -> die "Foreign fixture lacks complete Core"
  let expected (ForeignCore.IfaceForeign stubs files) = object
        ["schema" .= (1 :: Int), "execution" .= ("not-linked" :: String),
         "stubs" .= fmap stub stubs, "files" .= map file files]
      stub (ForeignCore.IfaceCStubs header source initializers finalizers) = object
        ["header" .= header, "source" .= source, "initializers" .= map label initializers,
         "finalizers" .= map label finalizers]
      label (ForeignCore.IfaceCLabel value) = object
        ["isInitializer" .= csl_is_initializer value,
         "unit" .= unitString (moduleUnit (csl_module value)),
         "module" .= moduleNameString (moduleName (csl_module value)), "name" .= unpackFS (csl_name value)]
      file (ForeignCore.IfaceForeignFile sourceLanguage source extension) = object
        ["language" .= show sourceLanguage, "source" .= source, "extension" .= extension]
  rendered <- interfaceCoreJSON ["unit-qualified"] core
  value <- maybe (die "Foreign archive is not JSON") pure (decodeStrict' (Text.encodeUtf8 (Text.pack rendered)))
  check (valueAt "schema" value == Number 2 && valueAt "foreign" value == expected rawForeign &&
    expected (interfaceForeign core) == expected rawForeign) "Foreign interface metadata was lost or rewritten"
  let stubs = valueAt "stubs" (valueAt "foreign" value)
      contains needle item = case item of String s -> needle `isInfixOf` show s; _ -> False
  check (contains "thc_interface_fixture" (valueAt "header" stubs) &&
    contains "rts_lock" (valueAt "source" stubs) && contains "registerForeignExports" (valueAt "source" stubs))
    "Foreign fixture no longer contains real RTS callback/registration code"
  check (case valueAt "initializers" stubs of Array xs -> length xs == 1; _ -> False)
    "Foreign fixture lost its original initializer"
  check (case valueAt "files" (valueAt "foreign" value) of Array xs -> length xs == 1; _ -> False)
    "Foreign fixture lost its actual TH-added C file"

-- Inspect one actual selected boot-library interface without copying/hashing it.
-- Stock GHC is thin; a compiler with full Core must instead load successfully.
-- The reference identity comes from the raw header, not the helper's mapping.
checkWiredHelper :: FilePath -> FilePath -> FilePath -> FilePath -> String -> String -> IO [CommandResult]
checkWiredHelper root directory libdir helper registered otherRegistration = do
  (path, canonical, complete, wrapperCommands) <- runGhc (Just libdir) $ do
    initial <- getSessionDynFlags
    initialEnv <- getSession
    (flags,leftovers,_) <- parseDynamicFlags (hsc_logger initialEnv) initial
      (map noLoc ["-clear-package-db", "-global-package-db", "-package-env", "-", "-package-id", registered])
    liftIO $ check (null leftovers) "Unexpected wired-unit flag leftovers"
    _ <- setSessionDynFlags flags
    environment <- getSession
    liftIO $ do
      let units = hsc_units environment
      info <- case filter ((== stringToUnit registered) . unwireUnit units . mkUnit) (listUnitInfo units) of
        [selected] -> pure selected
        _ -> die "Expected one exact selected wired registration"
      existing <- filterM doesFileExist [ShortText.unpack dir </> "GHC/Internal/Char.hi" | dir <- unitImportDirs info]
      path <- case existing of
        [selected] -> pure selected
        _ -> die "Expected one installed GHC.Internal.Char interface"
      raw <- readBinIface (targetProfile flags) (hsc_NC environment) CheckHiWay QuietBinIFace path
      let canonical = unitString (moduleUnit (mi_module raw))
      check (canonical == "ghc-internal" && registered /= canonical) "Control does not exercise wired registration mapping"
      check (moduleNameString (moduleName (mi_module raw)) == "GHC.Internal.Char") "Unexpected wired interface module"
      inspectInstalledBound environment path (root </> directory </> "installed-bound-facts.json")
      wrapperCommands <- checkInstalledWrappers environment path root directory
      pure (path, canonical, not (isNothing (mi_simplified_core raw)), wrapperCommands)
  let expectedExit = if complete then 0 else 3
      args requested = ["--libdir", libdir, "--unit", requested, "--module", "GHC.Internal.Char", "--interface", path]
      run code label requested = runLoggedExpect code 180 root (directory </> "logs") label [] helper (args requested)
  writeJson (root </> directory </> "wired-unit.json") $ object
    ["registeredUnit" .= registered, "interfaceUnit" .= canonical, "module" .= ("GHC.Internal.Char" :: String),
     "interface" .= path, "completeCore" .= complete, "expectedExit" .= expectedExit]
  loaded <- run expectedExit "helper-wired-unit" registered
  output <- maybe (die "Wired helper response is not JSON") pure (decodeStrict' (commandStdout loaded))
  if complete then check (valueAt "status" output == String "loaded" &&
      valueAt "unit" (valueAt "core" output) == String "ghc-internal") "Wired Core identity was rewritten"
    else check (valueAt "status" output == String "unavailable" && valueAt "core" output == Null &&
      valueAt "capability" output == String "complete-interface-core" &&
      valueAt "unit" output == toJSON registered) "Thin wired input was not an exact-registration missing capability"
  badUnit <- run 1 "helper-wired-wrong-unit" otherRegistration
  canonicalAlias <- run 1 "helper-wired-canonical-alias" canonical
  forM_ [badUnit,canonicalAlias] $ \result -> do
    failure <- maybe (die "Wired helper failure is not JSON") pure (decodeStrict' (commandStdout result))
    check (valueAt "status" failure == String "error" && valueAt "core" failure == Null)
      "Wired-unit mapping accepted a different registration"
  pure ([loaded,badUnit,canonicalAlias] ++ wrapperCommands)

checkCore :: HscEnv -> FilePath -> InterfaceCore -> IO ()
checkCore environment path core = do
  let flat = flattenBinds (interfaceBindings core)
      named name = [(v,rhs) | (v,rhs) <- flat, occNameString (getOccName v) == name]
      ordinary = [v | v <- typeEnvIds (md_types (interfaceDetails core)), occNameString (getOccName v) == "opaqueEntry"]
      inlineIds = [v | v <- typeEnvIds (md_types (interfaceDetails core)), occNameString (getOccName v) == "inlineEntry"]
  check (length ordinary == 1 && all (isNothing . maybeUnfoldingTemplate . realIdUnfolding) ordinary)
    "OPAQUE control unexpectedly has an ordinary executable unfolding"
  -- GHC 9.14 disables emitting IfUseUnfoldingRhs (#22807), but the ordinary
  -- declaration must still retain its actual pragma in our private flags.
  check (length inlineIds == 1 && all (not . isNothing . maybeUnfoldingTemplate . realIdUnfolding) inlineIds)
    "Private hydration discarded interface pragmas"
  check (all (\(v,rhs) -> eqType (idType v) (exprType rhs)) flat) "Recovered Core type mismatch"
  checkWrapper environment core "$WToken"
  case (named "opaqueEntry", named "privateWorker") of
    ([(_,rhs)],[(worker,_)]) -> check (refers worker rhs) "Original private worker reference was not preserved"
    _ -> die "Full Core lost the opaque entry or private worker"
  raw <- readBinIface (targetProfile (hsc_dflags environment)) (hsc_NC environment) CheckHiWay QuietBinIFace path
  case mi_simplified_core raw of
    Nothing -> die "Complete payload disappeared"
    Just simplified -> do
      let groups = mi_sc_extra_decls simplified
          rawRec IfaceRec{} = True
          rawRec _ = False
          coreRec Rec{} = True
          coreRec _ = False
      let added = length (interfaceBindings core) - length groups
          (wrappers, ordinaryGroups) = splitAt added (interfaceBindings core)
          wrapperGroup (NonRec v _) = isDataConWrapId v
          wrapperGroup _ = False
      check (added >= 1 && all wrapperGroup wrappers && any rawRec groups &&
        map rawRec groups == map coreRec ordinaryGroups)
        "Original recursive groups were changed"
  where
    refers worker (Var v) = v == worker
    refers worker (App f x) = refers worker f || refers worker x
    refers worker (Lam _ x) = refers worker x
    refers worker (Let b x) = any (refers worker . snd) (flattenBinds [b]) || refers worker x
    refers worker (Case x _ _ as) = refers worker x || any (\(Alt _ _ rhs) -> refers worker rhs) as
    refers worker (Cast x _) = refers worker x
    refers worker (Tick _ x) = refers worker x
    refers _ _ = False

-- Compare an actual hydrated constructor Id and its GHC-produced wrapper body,
-- including type/coercion arguments, rather than synthesizing from its name.
checkWrapper :: HscEnv -> InterfaceCore -> String -> IO ()
checkWrapper environment core occurrence = do
  let declared = [v | v <- typeEnvIds (md_types (interfaceDetails core)), getOccString v == occurrence]
      recovered = [(v, rhs) | (v, rhs) <- flattenBinds (interfaceBindings core), getOccString v == occurrence]
      render = showSDoc (hsc_dflags environment) . ppr
  case (declared, recovered) of
    ([original], [(v, rhs)]) | Just expected <- dataConWrapUnfolding_maybe original -> do
      check (v == original && nameModule_maybe (varName v) == Just (interfaceModule core))
        "Constructor wrapper lost its exact GHC identity/owner"
      check (eqType (idType v) (exprType rhs) && render rhs == render expected)
        "Constructor wrapper body/type/coercions changed"
    _ -> die ("Missing/duplicate genuine constructor wrapper " ++ occurrence)

-- Installed boot interfaces may be thin, but when complete they must supply
-- these original wrappers. The synthetic GADT/unpacked control above always
-- exercises acquisition, including on an ordinary thin-boot GHC installation.
checkInstalledWrappers :: HscEnv -> FilePath -> FilePath -> FilePath -> IO [CommandResult]
checkInstalledWrappers environment charPath root directory = do
  facts <- forM [("Data/Typeable/Internal.hi", "$WTrType"), ("Unsafe/Coerce.hi", "$WUnsafeRefl")] $ \(relative, occurrence) -> do
    let path = takeDirectory charPath </> relative
    raw <- readBinIface (targetProfile (hsc_dflags environment)) (hsc_NC environment) CheckHiWay QuietBinIFace path
    loaded <- loadInterfaceCore environment (mi_module raw) path
    case loaded of
      Nothing -> pure (object ["wrapper" .= occurrence, "completeCore" .= False], [])
      Just core -> do
        checkWrapper environment core occurrence
        rendered <- interfaceCoreJSON ["unit-qualified"] core
        let exported = root </> directory </> occurrence ++ ".json"
            audit = root </> directory </> occurrence ++ "-audit.json"
            entry = unitString (moduleUnit (interfaceModule core)) ++ ":" ++
              moduleNameString (moduleName (interfaceModule core)) ++ "." ++ occurrence
        writeFile exported rendered
        audited <- runLogged 180 root (directory </> "logs") ("audit-" ++ occurrence) [] "python3"
          ["scripts/audit-core.py", exported, "--entry", entry, "--output", audit]
        pure (object ["wrapper" .= occurrence, "completeCore" .= True,
          "exported" .= exported, "audit" .= audit], [audited])
  writeJson (root </> directory </> "installed-wrapper-facts.json") (toJSON (map fst facts))
  pure (concatMap snd facts)
