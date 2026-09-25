-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module InterfaceFixtures (prepareInterfaceCore) where

import Control.Monad (filterM, forM, forM_, unless)
import qualified Control.Exception as Exception
import Data.Aeson (Value(..), Result(..), fromJSON, toJSON, object, (.=), decodeStrict')
import Data.Aeson.Key (Key)
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString.Char8 as BS
import Data.Char (isHexDigit)
import Data.List (isInfixOf, sort)
import Data.Foldable (toList)
import Data.Maybe (isNothing)
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import FixtureSupport (CommandResult(..), hashes, runLogged, runLoggedExpect, writeJson)
import InterfaceForeignFacts (prepareForeignAssociation, inspectInstalledBound)
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
import System.FilePath ((</>), makeRelative, splitDirectories, takeExtension)
import THC.Interface
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
      entries = ["opaqueEntry", "inlineEntry", "recursiveEntry", "coercionEntry"] :: [String]
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
  wiredCommands <- checkWiredHelper root directory libdir helper wiredUnit baseUnit
  checkDriver root directory ghc ghcPkg helper baseUnit
  audits <- forM entries $ \entry -> run ("audit-" ++ entry) "python3"
    ["scripts/audit-core.py", "--entry", unitName ++ ":" ++
      (if entry == "coercionEntry" then "CBVCoercionAudit." else "InterfaceLibrary.") ++ entry,
     "--output", directory </> entry ++ "-audit.json", "--package-manifest", directory </> "packages.json"]
  plugin <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  let inputs = sort $ ["compiler/test-fixtures/InterfaceLibrary.hs", "compiler/test-fixtures/InterfaceNative.hs",
        "compiler/test-fixtures/InterfaceForeign.hs", "test/haskell-fixtures/InterfaceFixtures.hs",
        "compiler/test-fixtures/InterfaceForeignAlias.hs", "test/haskell-fixtures/InterfaceForeignFacts.hs",
        "compiler/test-fixtures/CBVCoercionAudit.hs", "compiler/interface/Main.hs",
        "src/THC/Driver/Installed.hs", "src/THC/Driver/Project.hs", "src/THC/Driver/Zip.hs",
        "test/haskell-fixtures/FixtureSupport.hs", "test/haskell-fixtures/Main.hs", "thc.cabal", "cabal.project",
        "scripts/audit-core.py", "scripts/core-capabilities.json"] ++
        ["compiler/THC" </> file | file <- plugin, takeExtension file == ".hs"] ++
        ["scripts" </> file | file <- scripts, take 5 file == "core_", takeExtension file == ".py"]
      commands = [helperBuild, helperLocation, pluginBuild, version, libdirResult, baseResult, wiredResult] ++
        concat builds ++ [foreignBuild] ++ foreignInit ++ [foreignRegistered, nativeBuild, oracle] ++
        helperCommands ++ associationCommands ++ wiredCommands ++ audits
      artifacts = concatMap commandArtifacts commands ++
        [directory </> name | name <- ["InterfaceLibrary.json", "full/InterfaceLibrary.hi", "thin/InterfaceLibrary.hi",
          "full/InterfaceLibrary.dyn_hi", "full/InterfaceForeign.hi", "native/oracle", "source/InterfaceLibrary.saved"]] ++
        [directory </> name | name <- ["CBVCoercionAudit.json", "direct/CBVCoercionAudit.json",
          "full/CBVCoercionAudit.hi", "thin/CBVCoercionAudit.hi", "source/CBVCoercionAudit.saved",
          "wired-unit.json"]] ++
        [directory </> "packages.json", directory </> "foreign-packages.json", directory </> "InterfaceForeign.json",
         directory </> "driver-controls.json", directory </> "foreign-association.json",
         directory </> "installed-bound-facts.json", directory </> "foreign-alias/a.json",
         directory </> "foreign-alias/b.json", directory </> "source/InterfaceForeignAlias.hs.saved"] ++
        [directory </> entry ++ "-audit.json" | entry <- entries]
  inputHashes <- hashes root inputs
  artifactHashes <- hashes root artifacts
  writeJson (root </> directory </> "manifest.json") $ object
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "entries" .= entries,
     "unit" .= unitName, "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes,
     "controls" .= (["opaque-body", "private-worker", "recursive-groups", "thin-unavailable",
       "no-source-target", "wrong-module", "wrong-unit", "wrong-way", "foreign-archived", "private-flags", "repeat-load",
       "helper-protocol", "installed-cbv-worker", "installed-wired-unit", "foreign-association-absence"] :: [String]),
     "commands" .= map commandRecord commands, "runtimeVerified" .= False]
  putStrLn "Prepared complete interface Core: 21 native rows; full/thin/no-source/identity/way/foreign controls passed"

-- Reuse the just-built private interfaces, with both source targets already
-- removed. Exercise the production discovery, process and ZIP paths, not a
-- fixture-specific bundle writer or another native rebuild.
checkDriver :: FilePath -> FilePath -> FilePath -> FilePath -> FilePath -> String -> IO ()
checkDriver root directory ghc ghcPkg helper baseUnit = do
  let cache = root </> directory </> "driver-cache"
      compiler = object ["id" .= ("ghc-9.14.1" :: String), "abi" .= ("fixture" :: String),
        "platform" .= ("native-fixture" :: String), "way" .= ("dynamic-nonprofiling" :: String)]
      context mode = Installed.installedContext ghc ghcPkg helper
        [root </> directory </> mode </> "package.conf.d"] compiler
      acquire selected unit = Project.prepareInstalledBundle cache "fixture-driver" selected unit
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
      check (partition == "ghc-9.14.1-fixture-native-fixture" &&
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
     "failedRefreshPreservedBundle" .= True, "installedArtifactsHashed" .= False]

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
  (path, canonical, complete) <- runGhc (Just libdir) $ do
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
      pure (path, canonical, not (isNothing (mi_simplified_core raw)))
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
  pure [loaded,badUnit,canonicalAlias]

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
      check (any rawRec groups && map rawRec groups == map coreRec (interfaceBindings core))
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
