-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE DeriveDataTypeable #-}

-- |
-- Module      : THC.ForeignExportProvenance
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC 9.14.1 compiler API
--
-- Provenance of retained foreign products in a closed compiler profile.
-- This is not native-image certification or authorization to run a callback.
module THC.ForeignExportProvenance
  ( Provenance(..), recordProvenance, inspectProvenance, knownPipeline ) where

import Control.Monad (unless)
import Data.Data (Data)
import Data.IORef (newIORef, readIORef)
import Data.Maybe (isNothing)
import Data.Proxy (Proxy(..))
import qualified Data.Typeable as Typeable
import GHC.Plugins
import GHC.Cmm.CLabel (CStubLabel(..))
import GHC.Driver.Hooks
import GHC.Hs (ForeignDecl(..), ForeignExport(..), ForeignImport(..), CImportSpec(..))
import GHC.HsToCore.Foreign.Decl (dsForeigns)
import GHC.HsToCore.Monad (initDsTc)
import GHC.Platform (Arch(..), platformArch)
import GHC.Platform.Profile (profileIsProfiling)
import GHC.Tc.Types (TcGblEnv(..), TcM)
import GHC.Tc.Utils.Monad (getTopEnv, setGblEnv, updTopEnv)
import GHC.Types.Error (isEmptyMessages)
import GHC.Types.ForeignCall (CExportSpec(..), CCallConv(..), CCallTarget(..))
import qualified GHC.Unit.Module.WholeCoreBindings as Foreign
import THC.ForeignExports (ExportName(..))
import THC.BackendAnnotations (knownBackendHook)

data Product = Product
  (Maybe (String, String, [(Bool,String,String,String)], [(Bool,String,String,String)]))
  [(String,String,String)] deriving (Eq, Data)

data Evidence = Unclassified String | StockProduct [ExportName] Product deriving Data
data RegistrationProof = RegistrationProof Int String String Evidence deriving Data

data Provenance
  = UnknownProvenance String
  | RejectedProvenance String
  | VerifiedRetainedRegistration Int [ExportName]

productOf :: Foreign.IfaceForeign -> Product
productOf (Foreign.IfaceForeign stubs files) = Product (fmap stub stubs) (map file files)
  where
    stub (Foreign.IfaceCStubs header source initializers finalizers) =
      (header, source, map label initializers, map label finalizers)
    label (Foreign.IfaceCLabel value) = (csl_is_initializer value,
      unitString (moduleUnit (csl_module value)), moduleNameString (moduleName (csl_module value)),
      unpackFS (csl_name value))
    file (Foreign.IfaceForeignFile language contents extension) = (show language, contents, extension)

-- Check loaded objects, not -fplugin flags. Direct-library loading records the
-- unit/module whose plugin closure GHC actually resolved, without loading its
-- interface or linking the guest's native dependencies. TypeRep identifies the
-- package actually containing this producer's code. Mixed pipelines stay closed.
knownPipeline :: HscEnv -> IO Bool
knownPipeline environment = do
  knownHook <- knownBackendHook (runPhaseHook (hsc_hooks environment))
  pure $ knownHook && null (staticPlugins plugins) && noHooks (hsc_hooks environment) &&
    case (loadedPlugins plugins, externalPlugins plugins) of
      ([loaded], []) -> let owner = mi_module (lpModule loaded) in
        sameProducer (unitString (moduleUnit owner)) (moduleNameString (moduleName owner))
      ([], [loaded]) -> sameProducer (epUnit loaded) (epModule loaded)
      _ -> False
  where
    plugins = hsc_plugins environment
    producerUnit = Typeable.tyConPackage (Typeable.typeRepTyCon (Typeable.typeRep (Proxy :: Proxy RegistrationProof)))
    sameProducer unit name = unit == producerUnit && name == "THC.Plugin"
    noHooks hooks = and
      [ isNothing (dsForeignsHook hooks), isNothing (tcForeignImportsHook hooks)
      , isNothing (tcForeignExportsHook hooks), isNothing (hscFrontendHook hooks)
      , isNothing (hscCompileCoreExprHook hooks), isNothing (ghcPrimIfaceHook hooks)
      , isNothing (runMetaHook hooks)
      , isNothing (linkHook hooks), isNothing (runRnSpliceHook hooks)
      , isNothing (getValueSafelyHook hooks), isNothing (createIservProcessHook hooks)
      , isNothing (stgToCmmHook hooks), isNothing (cmmToRawCmmHook hooks)
      ]

recordProvenance :: [CommandLineOption] -> TcGblEnv -> TcM TcGblEnv
recordProvenance options environment
  | "foreign-export-registration" `notElem` options = pure environment
  | otherwise = do
      top <- getTopEnv
      pipeline <- liftIO (knownPipeline top)
      pendingPlugins <- liftIO (readIORef (tcg_th_coreplugins environment))
      let flags = hsc_dflags top
          native = platformArch (targetPlatform flags) `elem` [ArchX86_64, ArchAArch64]
          declarations = tcg_fords environment
          exports = [declaration | declaration@(L _ ForeignExport {}) <- declarations]
          roots = if all classified declarations then traverse staticRoot exports else Nothing
          version = if length exports == length declarations then 1
            else if all directImport declarations then 2 else 3
          allowed = native && not (profileIsProfiling (targetProfile flags)) &&
            not (gopt Opt_Hpc flags) && not (gopt Opt_InfoTableMap flags)
      evidence <- if "foreign-export-associations" `notElem` options
        then pure (Unclassified "missing-typed-associations")
        else if not pipeline || not (null pendingPlugins)
          then pure (Unclassified "unclassified-plugin-or-hook-pipeline")
          else if not allowed then pure (Unclassified "unclassified-target-or-instrumentation")
          else case roots of
            Nothing -> pure (Unclassified "foreign-import-wrapper-or-non-ccall-declaration")
            Just orderedRoots -> do
              before <- liftIO (readIORef (tcg_th_foreign_files environment))
              counter <- liftIO (readIORef (tcg_next_wrapper_num environment))
              filesCopy <- liftIO (newIORef before)
              counterCopy <- liftIO (newIORef counter)
              let private = environment { tcg_th_foreign_files = filesCopy, tcg_next_wrapper_num = counterCopy }
              -- Probe the complete declaration group: dsForeigns emits one
              -- registration initializer for all static exports. CAPI/wrapper
              -- counters are private, just as in the import provenance probe.
              (messages, result) <- setGblEnv private $ initDsTc $ updTopEnv
                (\hsc -> hsc { hsc_hooks = (hsc_hooks hsc) { dsForeignsHook = Nothing } })
                (dsForeigns declarations)
              after <- liftIO (readIORef (tcg_th_foreign_files environment))
              probeFiles <- liftIO (readIORef filesCopy)
              afterCounter <- liftIO (readIORef (tcg_next_wrapper_num environment))
              unless (before == after && before == probeFiles && moduleEnvToList counter == moduleEnvToList afterCounter)
                (liftIO (ioError (userError "THC stock foreign-export probe changed compilation state")))
              case result of
                Just (stubs, _) | isEmptyMessages messages -> do
                  original <- liftIO (Foreign.encodeIfaceForeign (hsc_logger top) flags stubs [])
                  pure (StockProduct orderedRoots (productOf original))
                _ -> pure (Unclassified "stock-export-emitter-did-not-complete-cleanly")
      let owner = tcg_mod environment
          proof = RegistrationProof version (unitString (moduleUnit owner)) (moduleNameString (moduleName owner)) evidence
      pure environment { tcg_anns = tcg_anns environment ++
        [Annotation (ModuleTarget owner) (toSerialized serializeWithData proof)] }
  where
    staticRoot (L _ ForeignExport { fd_name = L _ binder,
        fd_fe = CExport _ (L _ (CExportStatic _ _ CCallConv)) }) = do
      owner <- nameModule_maybe (varName binder)
      pure (ExportName (unitString (moduleUnit owner)) (moduleNameString (moduleName owner))
        (occNameString (nameOccName (varName binder))) "value")
    staticRoot _ = Nothing
    classified (L _ ForeignExport {}) = True
    classified (L _ ForeignImport { fd_fi = CImport _ (L _ conv) _ _ spec })
      | conv `elem` [CCallConv, CApiConv] = case spec of
          CFunction (StaticTarget _ _ _ _) -> True
          CFunction DynamicTarget -> conv == CCallConv
          CLabel _ -> True
          CWrapper -> conv == CCallConv
    classified _ = False
    directImport (L _ ForeignExport {}) = True
    directImport (L _ ForeignImport { fd_fi = CImport _ (L _ CCallConv) _ _
        (CFunction (StaticTarget _ _ _ True)) }) = True
    directImport _ = False

-- Compare the entire archived product, including the exact ordered lifecycle
-- labels and all foreign files. A prefix, matching symbol or matching label is
-- insufficient. GHC archives pre-late-plugin CgGuts; that is why the producer
-- profile above permits only THC's known non-mutating late plugin.
inspectProvenance :: Module -> [Annotation] -> Maybe [ExportName] -> Foreign.IfaceForeign -> Either String Provenance
inspectProvenance owner annotations roots original = case proofs of
  [] -> Right (UnknownProvenance "missing-registration-provenance")
  [RegistrationProof version unit modName evidence]
    | version `notElem` [1,2,3] || unit /= unitString (moduleUnit owner) || modName /= moduleNameString (moduleName owner) ->
        Left "foreign-export registration proof version/owner mismatch"
    | otherwise -> Right $ case evidence of
        Unclassified reason -> UnknownProvenance reason
        StockProduct orderedRoots expected
          | roots /= Just orderedRoots -> RejectedProvenance "typed-export-roots-differ"
          | Product _ files <- productOf original, not (null files) -> RejectedProvenance "additional-foreign-files"
          | not (sameProduct (productOf original) expected) -> RejectedProvenance "retained-foreign-product-differs"
          | otherwise -> VerifiedRetainedRegistration version orderedRoots
  _ -> Left "duplicate foreign-export registration proofs"
  where
    -- dsForeigns [] returns NoStubs, while the completed interface can retain
    -- an empty CStubs record. Only these two obligation-free forms coincide;
    -- source/header text, lifecycle labels and files still match exactly.
    sameProduct actual expected = actual == expected || empty actual && empty expected
    empty (Product Nothing []) = True
    empty (Product (Just ("", "", [], [])) []) = True
    empty _ = False
    proofs = [proof | Annotation (ModuleTarget target) serialized <- annotations, target == owner,
      Just proof <- [fromSerialized deserializeWithData serialized]]
