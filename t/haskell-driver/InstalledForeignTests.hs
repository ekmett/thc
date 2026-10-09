-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : InstalledForeignTests
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Tests for installed foreign.
module InstalledForeignTests (tests, viewTests, sourceTests, configuredSourceViewTests) where

import Control.Exception (bracket)
import Control.Monad (foldM, forM, forM_, when)
import qualified Crypto.Hash.SHA256 as SHA
import Data.Aeson (Value(..), encode, object, (.=))
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as BL
import Data.List (isPrefixOf, sort)
import Distribution.InstalledPackageInfo (parseInstalledPackageInfo, showInstalledPackageInfo)
import Distribution.Pretty (prettyShow)
import qualified Distribution.Types.InstalledPackageInfo as Package
import GHC.Fingerprint (getFileHash)
import System.Directory (canonicalizePath, createDirectory, createDirectoryIfMissing, doesFileExist,
  copyFile, removeFile, removePathForcibly)
import System.Environment (getEnv, lookupEnv)
import System.Exit (ExitCode(..))
import System.FilePath ((</>), takeDirectory, takeFileName, replaceExtension, addTrailingPathSeparator)
import System.IO (openTempFile, hClose)
import System.IO.Error (tryIOError)
import qualified System.Info as Host
import Numeric (showHex)
import Test.HUnit (Test(..), assertBool, assertEqual, assertFailure)
import THC.Compact.Module (readModuleValue)
import THC.Driver.Installed
import THC.Driver.InstalledForeign (missingForeignProof, createView, viewContext, observeProbeInterfaces,
  retainedUsageFiles, verifyUsageFiles, matchUsageFiles, ForeignCompiler(..), prepareForeignInterfaces, configuredView, configuredSourceView, validateRegisteredLibrary)
import THC.Driver.NativeDependencies (configuredSourceBuild)
import TestSupport (Env(..), runExe, assertSuccess, assertContains, out, field, string, array, readJson)

unixModules :: [String]
unixModules = ["System.Posix.Files.PosixString", "System.Posix.Process.Internals", "System.Posix.Signals",
  "System.Posix.Directory.PosixPath", "System.Posix.Env.PosixString", "System.Posix.IO.Common"]

directoryModule :: String
directoryModule = "System.Directory.Internal.Posix"

-- These controls test orchestration decisions only. A verified marker here is
-- not executable evidence: production obtains it from the real interface helper
-- and subsequently retains the ordinary strict auditor/runtime checks.
tests :: Test
tests = TestLabel "installed foreign regeneration decisions" $ TestList
  [ TestCase $ do
      assertEqual "old Bound interface needs the typed producer" (Right True)
        (missingForeignProof bound (object []))
      assertEqual "old Posix interface needs the typed producer" (Right True)
        (missingForeignProof posix (object []))
      forM_ (directoryModule : unixModules) $ \name ->
        assertEqual (name ++ " needs the typed producer") (Right True)
          (missingForeignProof name (object []))
  , TestCase $ do
      assertEqual "complete verified export evidence is not regenerated" (Right False)
        (missingForeignProof bound (object ["staticForeignExports" .= object [],
          "staticForeignExportRegistration" .= verified]))
      forM_ (posix : directoryModule : unixModules) $ \name ->
        assertEqual (name ++ " verified import evidence is not regenerated") (Right False)
          (missingForeignProof name (object ["staticForeignImportStubs" .= verified]))
  , TestCase $ mapM_ (\core -> rejected "partial export evidence must not be replaced"
        (missingForeignProof bound core))
      [object ["staticForeignExports" .= object []],
       object ["staticForeignExportRegistration" .= verified]]
  , TestCase $ mapM_ (\proof -> do
      forM_ (posix : directoryModule : unixModules) $ \name ->
        rejected (name ++ " unclassified/rejected/malformed imports stay rejected")
          (missingForeignProof name (object ["staticForeignImportStubs" .= proof]))
      rejected "unclassified/rejected/malformed exports stay rejected"
        (missingForeignProof bound (object ["staticForeignExports" .= object [],
          "staticForeignExportRegistration" .= proof])))
      [Null, object [], object ["status" .= ("rejected" :: String)],
       object ["status" .= ("unclassified" :: String)]]
  , TestCase $ forM_ ["GHC.Internal.TopHandler", "GHC.Internal.Conc.Sync"] $ \name -> do
      assertEqual (name ++ " original nominal imports need their typed producer") (Right True)
        (missingForeignProof name (object []))
      assertEqual (name ++ " verified nominal imports are not regenerated") (Right False)
        (missingForeignProof name (object ["staticForeignImports" .= verified]))
  , TestCase $ forM_ ["GHC.Internal.TopHandler", "GHC.Internal.Conc.Sync"] $ \name -> do
      forM_ [Null, object [], object ["status" .= ("rejected" :: String)],
          object ["status" .= ("unclassified" :: String)]] $ \proof ->
        rejected (name ++ " malformed nominal proof cannot be replaced")
          (missingForeignProof name (object ["staticForeignImports" .= proof]))
      rejected (name ++ " partial import evidence cannot be replaced")
        (missingForeignProof name (object ["staticForeignImportStubs" .= verified]))
  , TestCase $ rejected "unlisted module cannot gain source regeneration"
      (missingForeignProof "GHC.Internal.Base" (object []))
  , TestCase $ do
      let retained = unlines ["foreign source mentions addDependentFile", "Self-Recomp",
            "  src hash: 123", "  usages: [", "    addDependentFile \"build/header with spaces.h\" abcdef,",
            "    addDependentFile \"/usr/include/stdc-predef.h\" 012345]", "  orphan hash: 789",
            "    addDependentFile \"not-self-recomp.h\" bad]"]
      assertEqual "only actual Self-Recomp input records are read"
        (Right [("build/header with spaces.h", "abcdef"), ("/usr/include/stdc-predef.h", "012345")])
        (retainedUsageFiles retained)
      mapM_ (rejected "missing or malformed retained inputs cannot prove a source build" . retainedUsageFiles)
        ["", "Self-Recomp\n  orphan hash: 0", "Self-Recomp\naddDependentFile \"a.h\" nope]\n  orphan hash: 0"]
  , TestCase $ do
      let retained = unlines ["Self-Recomp", "  src hash: 999feb32988468f8fe569d95fc0f673f",
            "  usages: [import  -/  ghc-internal:GHC.Internal.Base 4b58ca53cb277903ee756ff355143661]",
            "  orphan hash: 0"]
      assertEqual "a complete no-CPP interface has no retained header dependencies"
        (Right []) (retainedUsageFiles retained)
      assertBool "introducing a CPP dependency during regeneration must reject"
        (not (matchUsageFiles ("/source/ghcversion.h", "/installed/ghcversion.h") [] []
          [("/source/new-header.h", "changed")]))
  , TestCase $ forM_ (["C:\\configured source\\rts\\include\\ghcversion.h",
                      "\\\\server\\configured source\\rts\\include\\ghcversion.h"] ++
                     [path | Host.os == "mingw32", path <- [".\\include\\header.h", "..\\dist\\build\\autogen\\cabal_macros.h"]]) $ \path -> do
      let retained = unlines ["Self-Recomp", "  src hash: 999feb32988468f8fe569d95fc0f673f",
            "  usages: [import  -/  ghc-internal:GHC.Internal.Base 012345",
            "           addDependentFile \"" ++ path ++ "\" 012345]",
            "  orphan hash: 0"]
      assertEqual "GHC's raw quoted Windows UsageFile keeps backslashes"
        (Right [(path, "012345")]) (retainedUsageFiles retained)
  , TestCase $ do
      let original = [("/source/HsBaseConfig.h", "old"), ("/source/ghcversion.h", "version")]
          generated = [("/source/HsBaseConfig.h", "old"), ("/installed/ghcversion.h", "version")]
          same = matchUsageFiles ("/source/ghcversion.h", "/installed/ghcversion.h") ["/plugin/libTHC.so"] original
      assertBool "exact RTS copy and registered plugin library are permitted"
        (same (generated ++ [("/plugin/libTHC.so", "plugin")]))
      mapM_ (assertBool "changed, missing, shadowed, or new CPP inputs must reject" . not . same)
        [drop 1 generated, ("/shadow/HsBaseConfig.h", "old") : drop 1 generated,
         ("/source/HsBaseConfig.h", "changed") : drop 1 generated,
         generated ++ [("/new/extra.inc", "extra")],
         generated ++ [("/unregistered/libOther.so", "extra")],
         [("/source/HsBaseConfig.h", "old"), ("/installed/ghcversion.h", "wrong")]]
  ]
  where
    bound = "GHC.Internal.Conc.Bound"
    posix = "GHC.Internal.System.Posix.Internals"
    verified = object ["status" .= ("verified" :: String)]
    rejected message result = assertBool message (case result of Left _ -> True; Right _ -> False)

-- | Independently qualify the explicitly selected retained Windows source
-- provider, without recompiling its nominal modules or boot-library closure.
configuredSourceViewTests :: Env -> Test
configuredSourceViewTests env = TestLabel "configured source keeps real native owner and complete provenance" $ TestCase $ do
  source <- getEnv "THC_TEST_GHC_SOURCE"
  metadata <- readJson (source </> "inputs.json")
  ghc <- getEnv "GHC"
  pkg <- getEnv "GHC_PKG"
  helper <- getEnv "THC_TEST_INTERFACE_HELPER"
  native <- installedContext ghc pkg helper [] (field metadata "compiler")
  rejected <- tryIOError (configuredSourceView
    native { installedCompiler = object ["platform" .= ("incorrect" :: String)] } source)
  case rejected of
    Left problem -> assertContains "differs from selected compiler/ABI" (show problem)
    Right _ -> assertFailure "configured source accepted a different compiler/ABI"
  (selected, proof) <- configuredSourceView native source
  canonical <- canonicalizePath source
  assertEqual "qualified source retains its genuine native owner" (Just canonical) (installedSource selected)
  createDirectoryIfMissing True (scratch env)
  BL.writeFile (scratch env </> "configured-source-proof.json") (encode proof)
  (_, registrationInfo) <- either (fail . show) pure
    (parseInstalledPackageInfo (Text.encodeUtf8 (Text.pack (string (field metadata "registration")))))
  unit <- discoverInstalled selected (prettyShow (Package.installedUnitId registrationInfo))
  core <- readOriginal selected unit "GHC.Internal.TopHandler"
  assertEqual "actual retained interface has complete Core"
    (String "GHC.Internal.TopHandler") (field core "module")

-- Explicit opt-in: this needs an intact matching configured GHC tree and the
-- real published plugin/helper, not the synthetic decision controls above.
-- Windows selects the existing nominal ghc-internal profile; Unix selects
-- Unix/directory. No compiler-library or whole-project capture occurs.
sourceTests :: Env -> Test
sourceTests env = TestLabel "original configured-source foreign provenance" $ TestCase $ do
  source <- getEnv "THC_TEST_GHC_SOURCE"
  helper <- getEnv "THC_TEST_INTERFACE_HELPER"
  ghc <- getEnv "GHC"
  pkg <- getEnv "GHC_PKG"
  (pluginDb, pluginUnit, published, registered) <- if Host.os == "mingw32" then do
    database <- getEnv "THC_TEST_PLUGIN_DB"
    owner <- getEnv "THC_TEST_PLUGIN_UNIT"
    archive <- getEnv "THC_TEST_PLUGIN_LIBRARY"
    pure (database, owner, archive, archive)
    else do
      plugin <- readJson (thcRoot env </> "build/compiler/plugin.json")
      pure (string (field plugin "packageDb"), string (field plugin "unitId"),
        string (field plugin "sharedLibrary"), string (field plugin "cabalSharedLibrary"))
  driverBytes <- BS.readFile (driver env)
  let digest = concatMap (\byte -> let hex = showHex byte "" in replicate (2 - length hex) '0' ++ hex)
        (BS.unpack (SHA.hash driverBytes))
      producer = ForeignCompiler ghc pluginDb pluginUnit published registered digest
      windows = Host.os == "mingw32"
  native <- installedContext ghc pkg helper []
    (object ["platform" .= (Host.arch ++ "-" ++ if windows then "windows" else Host.os)])
  global <- lookupEnv "THC_TEST_CORE_GLOBAL_DB"
  let context = native { installedGlobalDb = maybe (installedGlobalDb native) id global }
      profiles = if windows then [("ghc-internal", ["GHC.Internal.TopHandler", "GHC.Internal.Conc.Sync"])]
        else [("unix", unixModules), ("directory", [directoryModule])]
  originals <- forM profiles $ \(package, names) -> do
    result <- runExe env (root env) Nothing 30 pkg
      ["--global", "--no-user-package-db", "field", package, "id", "--simple-output"]
    assertSuccess result
    identifier <- case words (out result) of [value] -> pure value; _ -> fail ("ambiguous " ++ package ++ " installation")
    original <- discoverInstalled context identifier
    hashes <- forM [(replaceExtension path suffix) | (_, path) <- installedInterfaces original,
                    suffix <- if windows then ["hi"] else ["hi", "dyn_hi"]] $ \path -> (,) path <$> getFileHash path
    before <- forM names $ \name -> do
      core <- readOriginal context original name
      assertEqual (name ++ " original stock interface lacks typed import provenance") (Right True)
        (missingForeignProof name core)
      pure (name, core)
    pure (original, hashes, before)
  let cache = scratch env </> "original-foreign-cache"
  selected <- foldM (\previousContext (original, _, before) -> do
    let identifier = registeredId original
        acquire = prepareForeignInterfaces producer cache source previousContext [original]
    cold <- acquire
    verifyGenerated cold original before
    warm <- acquire
    assertEqual "unchanged inputs reuse the exact acquisition view" cold warm
    regenerated <- discoverInstalled cold identifier
    when windows $ do
      owner <- maybe (fail "nominal view lost its configured native owner") pure (installedSource cold)
      (_, info) <- either (fail . show) pure
        (parseInstalledPackageInfo (Text.encodeUtf8 (Text.pack (registration regenerated))))
      actual <- configuredSourceBuild owner info
      expected <- canonicalizePath (owner </> "dist")
      assertEqual "copied nominal view retains the exact configured native owner"
        (Just (expected, False)) actual
      annotated <- prepareForeignInterfaces producer cache source cold [regenerated]
      assertEqual "already annotated interfaces retain the same native owner"
        (installedSource cold) (installedSource annotated)
    -- Corrupt only this test's produced artifacts. Unix exercises a replaced
    -- interface; directory exercises an object not read by the Core helper.
    -- Both must invalidate the receipt and produce a fresh verified generation.
    let (name, suffix) = if windows then ("GHC.Internal.TopHandler", "hi") else if directoryModule `elem` map fst before
          then (directoryModule, "dyn_o") else ("System.Posix.Directory.PosixPath", "dyn_hi")
    path <- maybe (fail "missing generated corruption target") pure (lookup name (installedInterfaces regenerated))
    cacheRoot <- canonicalizePath cache
    assertBool "corruption target belongs to this test's acquisition cache" (addTrailingPathSeparator cacheRoot `isPrefixOf` path)
    BS.appendFile (replaceExtension path suffix) "corrupted-test-output"
    repaired <- acquire
    assertBool "corrupt outputs cannot reuse the same acquisition view" (repaired /= cold)
    verifyGenerated repaired original before
    assertEqual "repaired generation is reusable" repaired =<< acquire
    pure repaired) context originals
  forM_ originals $ \(original, hashes, before) -> do
    verifyGenerated selected original before
    assertEqual "native installed registration and interfaces are untouched" original =<<
      discoverInstalled context (registeredId original)
    forM_ hashes $ \(path, expected) ->
      assertEqual "native interface bytes are untouched" expected =<< getFileHash path
  where
    verifyGenerated context original before = do
      regenerated <- discoverInstalled context (registeredId original)
      forM_ before $ \(name, previous) -> do
        core <- readOriginal context regenerated name
        assertEqual (name ++ " has genuine verified typed import provenance") (Right False)
          (missingForeignProof name core)
        forM_ ["foreign", "unit", "module"] $ \key ->
          assertEqual (name ++ " retains " ++ key) (field previous key) (field core key)
        let nominal = name `elem` ["GHC.Internal.TopHandler", "GHC.Internal.Conc.Sync"]
            proof = field core (if nominal then "staticForeignImports" else "staticForeignImportStubs")
        when (not nominal) $
          assertEqual (name ++ " proof retains the actual native stubs") (field core "foreign") (field proof "expectedForeign")
        when nominal $ forM_ (array (field proof "imports")) $ \entry ->
          assertBool "nominal proof retains the original declared and normalized types"
            (all ((/= Null) . field entry) ["declaredType", "normalizedType"])
        assertEqual (name ++ " provenance does not claim native linking") (String "not-linked") (field proof "execution")
        forM_ (lookup name expectedCapi) $ \expected -> do
          let declarations = [entry | entry <- array (field proof "imports"), field entry "convention" == String "capi"]
          assertEqual (name ++ " has the original CAPI declarations") (sort expected)
            (sort [(string (field entry "symbol"), string (field entry "header"), string (field entry "safety"))
                   | entry <- declarations])
          forM_ declarations $ \entry -> do
            assertBool "producer retained the declared and normalized types"
              (all ((/= Null) . field entry) ["declaredType", "normalizedType"])
            assertBool "producer retained the actual emitted wrapper"
              ("ghczuwrapper" `isPrefixOf` string (field (field entry "emitted") "symbol"))
        when (name == "System.Posix.IO.Common") $ do
          let artifacts = field core "foreign"
              stubs = field artifacts "stubs"
              openat = [entry | entry <- array (field proof "imports"),
                field entry "symbol" == String "openat", field entry "convention" == String "capi"]
          assertEqual "IO.Common has no extra foreign files" [] (array (field artifacts "files"))
          assertEqual "IO.Common has no callback header" (String "") (field stubs "header")
          forM_ ["initializers", "finalizers"] $ \key ->
            assertEqual "IO.Common has no registration obligations" [] (array (field stubs key))
          assertEqual "one original openat declaration" 1 (length openat)
          forM_ openat $ \entry -> do
            let emitted = field entry "emitted"
                calls = [call | call <- array (field proof "expectedCalls"),
                  field (field call "target") "symbol" == field emitted "symbol"]
            assertBool "openat wrapper is present in actual Core calls" (not (null calls))
            forM_ calls $ \call -> do
              assertEqual "openat keeps its unsafe CAPI convention" (String "capi", String "unsafe")
                (field call "convention", field call "safety")
              assertEqual "openat uses exact fd/path/flags/mode/state carriers"
                [[String "Int32Rep"], [String "AddrRep"], [String "Int32Rep"], [String "Word32Rep"], []]
                (map (array . (`field` "primReps")) (array (field call "argumentReps")))
              assertEqual "openat returns state and CInt"
                [[], [String "Int32Rep"]]
                (map (array . (`field` "primReps")) (array (field (field call "resultRep") "components")))
        path <- maybe (fail "missing regenerated interface") pure (lookup name (installedInterfaces regenerated))
        -- Unix resolves the interface link to its producer. Windows copies
        -- only interfaces into the view; real objects stay in that same
        -- producer generation and never replace registered native libraries.
        let generation = takeDirectory (takeDirectory (takeDirectory (installedGlobalDb context)))
            nativeObject = if Host.os == "mingw32" then generation </> "interfaces" </>
              map (\c -> if c == '.' then '/' else c) name else path
        forM_ (if installedInterfaceWay context == VanillaInterfaces then ["o"] else ["o", "dyn_o"]) $ \suffix -> do
          bytes <- BS.readFile (replaceExtension nativeObject suffix)
          assertBool (name ++ " compiled original native object: " ++ suffix) (not (BS.null bytes))
    expectedCapi =
      [("System.Posix.Directory.PosixPath", [("opendir", "HsUnix.h", "unsafe")]),
       ("System.Posix.Env.PosixString", [("unsetenv", "HsUnix.h", "unsafe")]),
       ("System.Posix.IO.Common", [("openat", "HsUnix.h", "unsafe")]),
       (directoryModule, [("fchmodat", "sys/stat.h", "safe"), ("fstatat", "sys/stat.h", "safe")])]

readOriginal :: InstalledContext -> InstalledUnit -> String -> IO Value
readOriginal context unit name = do
  path <- maybe (fail ("missing original interface: " ++ name)) pure (lookup name (installedInterfaces unit))
  (status, bytes, _) <- boundedInterfaceProcess (installedHelper context)
    (helperCommand context unit (name, path))
  assertEqual (name ++ " has complete retained Core") ExitSuccess status
  either fail pure (readModuleValue bytes)

-- Exercise the actual ghc-pkg view against the selected installation. This
-- performs no compilation and gives thin stock interfaces no runtime admission.
viewTests :: Env -> Test
viewTests env = TestLabel "acquisition view preserves native registration" $ TestCase $
  bracket temporary removePathForcibly $ \directory -> do
    ghc <- maybe "ghc" id <$> lookupEnv "GHC"
    pkg <- maybe "ghc-pkg" id <$> lookupEnv "GHC_PKG"
    context <- installedContext ghc pkg "unused-by-this-view-test" [] Null
    result <- runExe env directory Nothing 30 pkg
      ["--global", "--no-user-package-db", "field", "ghc-internal", "id", "--simple-output"]
    assertSuccess result
    identifier <- case words (out result) of [value] -> pure value; _ -> fail "ambiguous ghc-internal"
    original <- discoverInstalled context identifier
    -- No replacement outputs: every view link must resolve to the exact
    -- existing interface, while the native package fields remain unchanged.
    view <- createView context original directory []
    when (Host.os == "mingw32") $ do
      canonicalView <- canonicalizePath view
      assertEqual "normal configured source view retains native compiler selection"
        (viewContext context canonicalView) =<< configuredView context directory
    selected <- discoverInstalled (viewContext context view) identifier
    before <- parsed (registration original)
    after <- parsed (registration selected)
    when (Host.os == "mingw32") $ do
      (libraryDirectory, archive) <- case (Package.hsLibraries before, Package.libraryDirs before) of
        ([name], path:_) -> pure (path, path </> ("lib" ++ name ++ ".a"))
        _ -> fail "view test needs the selected genuine native archive"
      expected <- canonicalizePath archive
      actual <- validateRegisteredLibrary VanillaInterfaces before
        (libraryDirectory </> "." </> takeFileName archive)
      assertEqual "registered library identity survives path aliases" expected actual
      let unregistered = directory </> "not-a-registered-archive"
      writeFile unregistered "not an archive"
      rejected <- tryIOError (validateRegisteredLibrary VanillaInterfaces before unregistered)
      case rejected of
        Left problem -> assertContains "does not resolve to the recorded library" (show problem)
        Right _ -> assertFailure "native registration accepted an unrelated file"
    assertEqual "only interface directories change" before
      after { Package.importDirs = Package.importDirs before }
    if Host.os == "mingw32" then do
      assertEqual "native view preserves every registered module"
        (map fst (installedInterfaces original)) (map fst (installedInterfaces selected))
      forM_ (zip (installedInterfaces original) (installedInterfaces selected)) $ \((_, source), (_, copied)) -> do
        bytes <- BS.readFile source
        assertEqual "native view copies exact original interface bytes" bytes =<< BS.readFile copied
      assertEqual "native view retains the real package tool" (installedPackageTool context) (installedPackageTool (viewContext context view))
      assertEqual "native view retains the real compiler libdir" (installedLibdir context) (installedLibdir (viewContext context view))
    else assertEqual "every interface resolves to its original payload"
      (installedInterfaces original) (installedInterfaces selected)
    expectedDirectory <- canonicalizePath (view </> "interfaces")
    assertEqual "view uses its own interface directory" [expectedDirectory] (Package.importDirs after)
    when (Host.os == "mingw32") $ do
      let record = directory </> "registration.conf"
          update info = do
            writeFile record (showInstalledPackageInfo info)
            assertSuccess =<< runExe env directory Nothing 30 pkg
              ["--global-package-db", view </> "lib/package.conf.d", "--global", "update", record]
      update after { Package.libraryDirs = Package.libraryDirs after ++ [directory] }
      rejected <- tryIOError (configuredView context directory)
      case rejected of
        Left problem -> assertContains "changed native registration or ABI" (show problem)
        Right _ -> assertFailure "configured source view accepted changed native libraries"
      update after
    unchanged <- discoverInstalled context identifier
    assertEqual "original installed registration is untouched" original unchanged
    -- Actual copied interface artifacts model one registered dependency. The
    -- cache observer must catch a vanilla-only edit; a dynamic-only probe and
    -- unchanged ABI/registration would miss it. Neither copy is loaded as Core.
    originalDynamic <- maybe (fail "missing Bound interface") pure
      (lookup "GHC.Internal.Conc.Bound" (installedInterfaces original))
    originalVanilla <- canonicalizePath (replaceExtension originalDynamic "hi")
    viewedVanilla <- canonicalizePath (view </> "interfaces/GHC/Internal/Conc/Bound.hi")
    if Host.os == "mingw32"
      then do
        bytes <- BS.readFile originalVanilla
        assertEqual "native view retains exact vanilla dependency bytes" bytes =<< BS.readFile viewedVanilla
      else assertEqual "composed views retain vanilla dependency interfaces" originalVanilla viewedVanilla
    let dependency = directory </> "dependency"
        dynamic = dependency </> "GHC/Internal/Conc/Bound.dyn_hi"
        vanilla = replaceExtension dynamic "hi"
    createDirectoryIfMissing True (takeDirectory dynamic)
    when (installedInterfaceWay context == DynamicInterfaces) (copyFile originalDynamic dynamic)
    copyFile (replaceExtension originalDynamic "hi") vanilla
    selectedPath <- canonicalizePath (if installedInterfaceWay context == DynamicInterfaces then dynamic else vanilla)
    let probe = object ["registrations" .= [object
          ["registration" .= showInstalledPackageInfo before { Package.importDirs = [dependency] },
           "way" .= interfaceWayName (installedInterfaceWay context),
           "interfaces" .= [object ["module" .= ("GHC.Internal.Conc.Bound" :: String),
             "path" .= selectedPath]]]]]
    observed <- observeProbeInterfaces probe
    assertEqual "every compiler-read way observed" (if installedInterfaceWay context == DynamicInterfaces then 2 else 1) (length observed)
    dynamicBytes <- if installedInterfaceWay context == DynamicInterfaces then Just <$> BS.readFile dynamic else pure Nothing
    BS.appendFile vanilla "changed-vanilla-interface"
    changed <- observeProbeInterfaces probe
    assertBool "vanilla dependency mutation invalidates cache inputs" (observed /= changed)
    assertEqual "dynamic observation alone is unchanged"
      (lookup dynamic observed) (lookup dynamic changed)
    case dynamicBytes of
      Just bytes -> assertEqual "dynamic payload remains unchanged" bytes =<< BS.readFile dynamic
      Nothing -> assertBool "vanilla acquisition does not manufacture a dynamic interface" . not =<< doesFileExist dynamic
    -- A cache hash alone merely observes today's headers. Recompilation needs
    -- the old interface's UsageFile association: changing HAVE_GETPID must
    -- reject before invoking the producer, even with unchanged source/stubs.
    let header = directory </> "HsBaseConfig.h"
    writeFile header "#define HAVE_GETPID 1\n"
    digest <- show <$> getFileHash header
    let retained = [("HsBaseConfig.h", digest)]
    verified <- verifyUsageFiles directory retained
    canonical <- canonicalizePath header
    assertEqual "relative original input resolves from the configured tree"
      [(canonical, digest)] verified
    writeFile header "/* #undef HAVE_GETPID */\n"
    mutated <- tryIOError (verifyUsageFiles directory retained)
    assertBool "modified original header is rejected, not granted a new cache key"
      (case mutated of Left _ -> True; Right _ -> False)
    -- A target source can change after configuredRecipe's initial source-hash
    -- check but before the first cache snapshot. Repeating the retained source
    -- association must reject it even if the new bytes then remain stable.
    let source = directory </> "Original.hs"
    writeFile source "module Original where\nvalue = 1\n"
    sourceDigest <- show <$> getFileHash source
    let sourceProof = [(source, sourceDigest)]
    _ <- verifyUsageFiles directory sourceProof
    writeFile source "module Original where\nvalue = 2\n"
    sourceMutated <- tryIOError (verifyUsageFiles directory sourceProof)
    assertBool "stable source change after initial matching cannot become a new valid cache snapshot"
      (case sourceMutated of Left _ -> True; Right _ -> False)
  where
    temporary = do
      (path, handle) <- openTempFile (scratch env) "foreign-view-"
      hClose handle
      removeFile path
      createDirectory path
      pure path
    parsed value = case parseInstalledPackageInfo (Text.encodeUtf8 (Text.pack value)) of
      Right (_, info) -> pure info
      Left errors -> fail (show errors)
