-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- | Focused producer regression. Export ProxyVoidAudit with source-notes and
-- pretty-diagnostics into ROOT/{pre,post}/core, retaining post/ghc interfaces
-- with -fwrite-if-simplified-core. Run with the selected GHC libdir and ROOT.
-- CBD decoding and rich diagnostic assertions deliberately inspect different
-- artifacts; source text in a sidecar does not prove CBD debug-table coverage.
--
-- From the repository root after building the compiler, with GHC selected:
--
-- @
-- for stage in pre post; do
--   late=""; [ "$stage" = pre ] || late=-fplugin-opt=THC.Plugin:post-tidy
--   THC_CORE_OUT="$PWD/build/producer-diagnostics/$stage/core" \
--   THC_GHC_OUT="$PWD/build/producer-diagnostics/$stage/ghc" \
--     bin/export-core.sh -fplugin-opt=THC.Plugin:pretty-diagnostics \
--       -fwrite-if-simplified-core $late t/fixtures/compiler/ProxyVoidAudit.hs
-- done
-- cabal exec -- ghc -dynamic -Wall -Werror -package thc -package ghc \
--   -package-id thc-0.1.0.0-inplace-compact-core \
--   -outputdir build/producer-diagnostics/check \
--   t/interface-json/ProducerDiagnostics.hs -o build/producer-diagnostics/check-producer
-- build/producer-diagnostics/check-producer "$(ghc --print-libdir)" build/producer-diagnostics
-- @
module Main (main) where

import Control.Monad (forM_, unless)
import Data.Aeson (Value(..), eitherDecodeStrict)
import qualified Data.Aeson.KeyMap as KM
import qualified Data.ByteString as BS
import Data.Foldable (toList)
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import GHC
import GHC.Driver.Main (hscSimplify)
import GHC.Plugins (hsc_logger, liftIO, stringToUnit)
import System.Environment (getArgs)
import System.FilePath ((</>))
import THC.Compact.Module (readModuleValue)
import THC.Interface
import THC.Plugin (serializeOptimizedCore, serializeOptimizedCoreCBD)

main :: IO ()
main = do
  [libdir, root] <- getArgs
  forM_ ["pre", "post"] $ \stage -> do
    let path = root </> stage </> "core/ProxyVoidAudit"
    BS.readFile (path ++ ".json") >>= json >>= rich
    BS.readFile (path ++ ".cbd") >>= compact
  runGhc (Just libdir) $ do
    initial <- getSessionDynFlags
    environment <- getSession
    (configured, _, _) <- parseDynamicFlags (hsc_logger environment) initial
      (map noLoc ["-dynamic", "-O2", "-g", "-this-unit-id", "main"])
    _ <- setSessionDynFlags configured
    flags <- getSessionDynFlags
    target <- guessTarget "t/fixtures/compiler/ProxyVoidAudit.hs" Nothing Nothing
    setTargets [target]
    graph <- depanal [] False
    summary <- case mgModSummaries graph of
      [single] -> pure single
      _ -> liftIO (fail "Unexpected fixture module graph")
    desugared <- parseModule summary >>= typecheckModule >>= desugarModule
    selected <- getSession
    optimized <- liftIO $ hscSimplify selected [] (coreModule desugared)
    liftIO $ do
      let options = ["pretty-diagnostics", "source-notes", "unit-qualified"]
      serializeOptimizedCore flags options optimized >>= json . Text.encodeUtf8 . Text.pack >>= rich
      serializeOptimizedCoreCBD flags options optimized >>= compact
      loaded <- loadInterfaceCore selected
        (mkModule (stringToUnit "main") (mkModuleName "ProxyVoidAudit"))
        (root </> "post/ghc/ProxyVoidAudit.hi")
      core <- maybe (fail "Missing complete interface Core") pure loaded
      interfaceCoreJSONBytes options core >>= json >>= rich
      interfaceCoreCBD options core >>= compact
      -- The opt-out inspection API still uses the structural CBD view.
      inspection <- interfaceCoreJSONBytes ["unit-qualified"] core >>= json
      assert (not (hasRichBinding inspection)) "Default inspection unexpectedly became rich JSON"
  putStrLn "Producer diagnostics: genuine pre/post sidecars and interface API passed; CBD decoded separately"
  where
    json bytes = either fail pure (eitherDecodeStrict bytes)
    compact bytes = do
      assert (BS.take 1 bytes /= "{") "Runtime artifact became JSON"
      value <- either fail pure (readModuleValue bytes)
      assert (not (null (values (field "bindings" value)))) "CBD has no bindings"
      assert (not (hasRichBinding value)) "Structural CBD inspection unexpectedly contains rich binding types"
    rich value = do
      assert (hasRichBinding value) "Lost direct occurrence name or full Int# function type"
      assert (nonempty (field "sourceCore" value)) "Lost sourceCore"
      assert (not (null (values (field "groups" value)))) "Lost binding groups"
      assert (not (null (values (field "sourceFiles" value)))) "Lost source files"
      assert (not (null (values (field "sourceSpans" value)))) "Lost source spans"
      assert (any (not . null . values . field "sourceNotes") (walk value)) "Lost expression source notes"
    hasRichBinding = any (\binding -> field "name" binding == String "direct" &&
      case field "type" binding of
        String ty -> "Int#" `Text.isInfixOf` ty && "->" `Text.isInfixOf` ty
        _ -> False) . values . field "bindings"
    nonempty (String value) = not (Text.null value)
    nonempty _ = False
    field key (Object fields) = maybe Null id (KM.lookup key fields)
    field _ _ = Null
    values (Array xs) = toList xs
    values _ = []
    walk value = value : concatMap walk (case value of Object fields -> KM.elems fields; _ -> values value)
    assert condition message = unless condition (fail message)
