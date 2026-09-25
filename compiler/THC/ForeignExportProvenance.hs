-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE DeriveDataTypeable #-}
-- | Provenance of retained foreign products in a closed compiler profile.
-- This is not native-image certification or authorization to run a callback.
module THC.ForeignExportProvenance
  ( Provenance(..), recordProvenance, inspectProvenance ) where

import Control.Monad (unless)
import Data.Data (Data)
import Data.IORef (readIORef)
import Data.Maybe (isNothing)
import Data.Proxy (Proxy(..))
import qualified Data.Typeable as Typeable
import GHC.Plugins
import GHC.Cmm.CLabel (CStubLabel(..))
import GHC.Data.OrdList (fromOL)
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

-- Check loaded objects, not -fplugin flags. Static/external plugin records do
-- not provide the normal loaded module interface, so this profile rejects them.
-- TypeRep identifies the package actually containing this producer's code.
knownPipeline :: HscEnv -> Bool
knownPipeline environment = null (staticPlugins plugins) && null (externalPlugins plugins) &&
  case loadedPlugins plugins of
    [loaded] -> let owner = mi_module (lpModule loaded) in
      moduleNameString (moduleName owner) == "THC.Plugin" &&
      unitString (moduleUnit owner) == producerUnit && noHooks (hsc_hooks environment)
    _ -> False
  where
    plugins = hsc_plugins environment
    producerUnit = Typeable.tyConPackage (Typeable.typeRepTyCon (Typeable.typeRep (Proxy :: Proxy RegistrationProof)))
    noHooks hooks = and
      [ isNothing (dsForeignsHook hooks), isNothing (tcForeignImportsHook hooks)
      , isNothing (tcForeignExportsHook hooks), isNothing (hscFrontendHook hooks)
      , isNothing (hscCompileCoreExprHook hooks), isNothing (ghcPrimIfaceHook hooks)
      , isNothing (runPhaseHook hooks), isNothing (runMetaHook hooks)
      , isNothing (linkHook hooks), isNothing (runRnSpliceHook hooks)
      , isNothing (getValueSafelyHook hooks), isNothing (createIservProcessHook hooks)
      , isNothing (stgToCmmHook hooks), isNothing (cmmToRawCmmHook hooks)
      ]

recordProvenance :: [CommandLineOption] -> TcGblEnv -> TcM TcGblEnv
recordProvenance options environment
  | "foreign-export-registration" `notElem` options = pure environment
  | otherwise = do
      top <- getTopEnv
      pendingPlugins <- liftIO (readIORef (tcg_th_coreplugins environment))
      let flags = hsc_dflags top
          native = platformArch (targetPlatform flags) `elem` [ArchX86_64, ArchAArch64]
          declarations = tcg_fords environment
          exports = [declaration | declaration@(L _ ForeignExport {}) <- declarations]
          roots = if all classified declarations then traverse staticRoot exports else Nothing
          version = if length exports == length declarations then 1 else 2
          allowed = native && not (profileIsProfiling (targetProfile flags)) &&
            not (gopt Opt_Hpc flags) && not (gopt Opt_InfoTableMap flags)
      evidence <- if "foreign-export-associations" `notElem` options
        then pure (Unclassified "missing-typed-associations")
        else if not (knownPipeline top) || not (null pendingPlugins)
          then pure (Unclassified "unclassified-plugin-or-hook-pipeline")
          else if not allowed then pure (Unclassified "unclassified-target-or-instrumentation")
          else case roots of
            Nothing -> pure (Unclassified "foreign-import-wrapper-or-non-ccall-declaration")
            Just orderedRoots -> setGblEnv environment $ do
              before <- liftIO (readIORef (tcg_th_foreign_files environment))
              -- The exported entry delegates to GHC's private stock emitter
              -- when this one hook is locally absent. Probe only exports:
              -- direct ccall imports have no C products but allocate Core
              -- worker uniques. The entire real product must still equal this
              -- export-only product when the interface is recovered.
              (messages, result) <- initDsTc $ updTopEnv
                (\hsc -> hsc { hsc_hooks = (hsc_hooks hsc) { dsForeignsHook = Nothing } })
                (dsForeigns exports)
              after <- liftIO (readIORef (tcg_th_foreign_files environment))
              unless (before == after) (liftIO (ioError (userError "THC stock foreign-export probe changed foreign files")))
              case result of
                Just (stubs, bindings) | isEmptyMessages messages && null (fromOL bindings) -> do
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
    classified (L _ ForeignImport { fd_fi = CImport _ (L _ CCallConv) _ Nothing
        (CFunction (StaticTarget _ _ _ True)) }) = True
    classified _ = False

-- Compare the entire archived product, including the exact ordered lifecycle
-- labels and all foreign files. A prefix, matching symbol or matching label is
-- insufficient. GHC archives pre-late-plugin CgGuts; that is why the producer
-- profile above permits only THC's known non-mutating late plugin.
inspectProvenance :: Module -> [Annotation] -> Maybe [ExportName] -> Foreign.IfaceForeign -> Either String Provenance
inspectProvenance owner annotations roots original = case proofs of
  [] -> Right (UnknownProvenance "missing-registration-provenance")
  [RegistrationProof version unit modName evidence]
    | version `notElem` [1,2] || unit /= unitString (moduleUnit owner) || modName /= moduleNameString (moduleName owner) ->
        Left "foreign-export registration proof version/owner mismatch"
    | otherwise -> Right $ case evidence of
        Unclassified reason -> UnknownProvenance reason
        StockProduct orderedRoots expected
          | roots /= Just orderedRoots -> RejectedProvenance "typed-export-roots-differ"
          | Product _ files <- productOf original, not (null files) -> RejectedProvenance "additional-foreign-files"
          | productOf original /= expected -> RejectedProvenance "retained-foreign-product-differs"
          | otherwise -> VerifiedRetainedRegistration version orderedRoots
  _ -> Left "duplicate foreign-export registration proofs"
  where
    proofs = [proof | Annotation (ModuleTarget target) serialized <- annotations, target == owner,
      Just proof <- [fromSerialized deserializeWithData serialized]]
