-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module InstalledForeignTests (tests, viewTests) where

import Control.Exception (bracket)
import Data.Aeson (Value(..), object, (.=))
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import qualified Data.ByteString as BS
import Distribution.InstalledPackageInfo (parseInstalledPackageInfo, showInstalledPackageInfo)
import qualified Distribution.Types.InstalledPackageInfo as Package
import GHC.Fingerprint (getFileHash)
import System.Directory (canonicalizePath, createDirectory, createDirectoryIfMissing,
  copyFile, removeFile, removePathForcibly)
import System.Environment (lookupEnv)
import System.FilePath ((</>), takeDirectory, replaceExtension)
import System.IO (openTempFile, hClose)
import System.IO.Error (tryIOError)
import Test.HUnit (Test(..), assertBool, assertEqual)
import THC.Driver.Installed
import THC.Driver.InstalledForeign (missingForeignProof, createView, viewContext, observeProbeInterfaces,
  retainedUsageFiles, verifyUsageFiles, matchUsageFiles)
import TestSupport (Env, scratch, runExe, assertSuccess, out)

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
  , TestCase $ do
      assertEqual "complete verified export evidence is not regenerated" (Right False)
        (missingForeignProof bound (object ["staticForeignExports" .= object [],
          "staticForeignExportRegistration" .= verified]))
      assertEqual "complete verified import evidence is not regenerated" (Right False)
        (missingForeignProof posix (object ["staticForeignImportStubs" .= verified]))
  , TestCase $ mapM_ (\core -> rejected "partial export evidence must not be replaced"
        (missingForeignProof bound core))
      [object ["staticForeignExports" .= object []],
       object ["staticForeignExportRegistration" .= verified]]
  , TestCase $ mapM_ (\proof -> do
      rejected "unclassified/rejected/malformed imports stay rejected"
        (missingForeignProof posix (object ["staticForeignImportStubs" .= proof]))
      rejected "unclassified/rejected/malformed exports stay rejected"
        (missingForeignProof bound (object ["staticForeignExports" .= object [],
          "staticForeignExportRegistration" .= proof])))
      [Null, object [], object ["status" .= ("rejected" :: String)],
       object ["status" .= ("unclassified" :: String)]]
  , TestCase $ rejected "unlisted module cannot gain source regeneration"
      (missingForeignProof "GHC.Internal.TopHandler" (object []))
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
    selected <- discoverInstalled (viewContext context view) identifier
    before <- parsed (registration original)
    after <- parsed (registration selected)
    assertEqual "only interface directories change" before
      after { Package.importDirs = Package.importDirs before }
    assertEqual "every interface resolves to its original payload"
      (installedInterfaces original) (installedInterfaces selected)
    expectedDirectory <- canonicalizePath (view </> "interfaces")
    assertEqual "view uses its own interface directory" [expectedDirectory] (Package.importDirs after)
    unchanged <- discoverInstalled context identifier
    assertEqual "original installed registration is untouched" original unchanged
    -- Actual copied interface artifacts model one registered dependency. The
    -- cache observer must catch a vanilla-only edit; a dynamic-only probe and
    -- unchanged ABI/registration would miss it. Neither copy is loaded as Core.
    originalDynamic <- maybe (fail "missing Bound interface") pure
      (lookup "GHC.Internal.Conc.Bound" (installedInterfaces original))
    let dependency = directory </> "dependency"
        dynamic = dependency </> "GHC/Internal/Conc/Bound.dyn_hi"
        vanilla = replaceExtension dynamic "hi"
    createDirectoryIfMissing True (takeDirectory dynamic)
    copyFile originalDynamic dynamic
    copyFile (replaceExtension originalDynamic "hi") vanilla
    let probe = object ["registrations" .= [object
          ["registration" .= showInstalledPackageInfo before { Package.importDirs = [dependency] },
           "interfaces" .= [object ["module" .= ("GHC.Internal.Conc.Bound" :: String), "path" .= dynamic]]]]]
    observed <- observeProbeInterfaces probe
    assertEqual "both compiler-read ways observed" 2 (length observed)
    dynamicBytes <- BS.readFile dynamic
    BS.appendFile vanilla "changed-vanilla-interface"
    changed <- observeProbeInterfaces probe
    assertBool "vanilla dependency mutation invalidates cache inputs" (observed /= changed)
    assertEqual "dynamic observation alone is unchanged"
      (lookup dynamic observed) (lookup dynamic changed)
    assertEqual "dynamic payload remains unchanged" dynamicBytes =<< BS.readFile dynamic
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
