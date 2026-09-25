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
import System.Directory (canonicalizePath, createDirectory, createDirectoryIfMissing,
  copyFile, removeFile, removePathForcibly)
import System.Environment (lookupEnv)
import System.FilePath ((</>), takeDirectory, replaceExtension)
import System.IO (openTempFile, hClose)
import Test.HUnit (Test(..), assertBool, assertEqual)
import THC.Driver.Installed
import THC.Driver.InstalledForeign (missingForeignProof, createView, viewContext, observeProbeInterfaces)
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
