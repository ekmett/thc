-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE GADTs, PatternSynonyms #-}

-- |
-- Module      : THC.Interface
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC 9.14.1 compiler API
--
-- Recover complete installed Core with the selected GHC 9.14.1 session.
--
-- Call 'loadInterfaceCore' with an exact resolved unit/module and interface
-- path, then 'interfaceCoreJSON' to use THC's post-Tidy serialization. Missing
-- complete Core is distinct from an invalid interface. Loading and archival
-- serialization do not link native foreign products or authorize execution.
module THC.Interface
  ( InterfaceCore, interfaceModule, interfaceDetails, interfaceBindings, interfaceForeign
  , InterfaceError(..), loadInterfaceCore, interfaceCoreCBD, interfaceCoreJSON, interfaceCoreJSONBytes, probeInterface, checkInterfaceIdentity
  ) where

import Control.Exception (Exception, throwIO)
import Control.Monad (unless)
import Data.IORef (newIORef, writeIORef, readIORef, modifyIORef')
import Data.List (sortOn)
import qualified Data.ByteString as BS
import qualified Data.Set as Set
import GHC.Plugins
import GHC.Builtin.Names (gHC_PRIM)
import GHC.Builtin.Types.Prim (primTyCons)
import GHC.Types.TyThing (TyThing(..))
import GHC.Driver.Env (hscSetFlags)
import GHC.Driver.Env.KnotVars (KnotVars(..), lookupKnotVars)
import GHC.Iface.Binary (readBinIface, CheckHiWay(..), TraceBinIFace(..))
import GHC.Iface.Recomp.Binary (fingerprintBinMem, putNameLiterally)
import GHC.Iface.Type (putIfaceType)
import qualified GHC.Iface.Syntax as Iface
import GHC.Types.Literal (Literal(..))
import qualified GHC.Unit.Module.WholeCoreBindings as Foreign
import qualified GHC.Data.Strict as Strict
import GHC.IfaceToCore (typecheckIface, typecheckWholeCoreBindings)
import GHC.Tc.Utils.Monad (initIfaceCheck)
import GHC.Types.TypeEnv (emptyTypeEnv, mkTypeEnv, typeEnvIds, typeEnvTyCons)
import GHC.Unit.Module.Location (pattern ModLocation)
import GHC.Unit.Module.ModDetails (ModDetails(..), emptyModDetails)
import GHC.Unit.Module.Deps
import GHC.Unit.Module.ModIface
import GHC.Unit.Module.WholeCoreBindings (WholeCoreBindings(..), IfaceForeign, emptyIfaceForeign)
import GHC.Utils.Binary (openBinMem, putFullBinData, put_, putFS, setWriterUserData,
                        mkWriterUserData, mkSomeBinaryWriter, mkWriter, simpleBindingNameWriter)
import GHC.Utils.Fingerprint (Fingerprint)
import System.FilePath (replaceExtension)
import qualified THC.ForeignExports as Exports
import qualified THC.ForeignExportProvenance as ExportProvenance
import qualified THC.ForeignImportProvenance as ImportProvenance
import THC.Plugin (serializePostTidyCoreWithAnnotations, serializePostTidyCoreWithAnnotationsBytes,
  serializePostTidyCoreWithAnnotationsCBD)

-- | Original GHC identities, declarations, recursive groups and foreign
-- metadata. Loading is archival: accompanying foreign build products are
-- retained, not linked or registered with a runtime.
data InterfaceCore = InterfaceCore
  { interfaceModule :: Module -- ^ Original unit and module identity.
  , interfaceDetails :: ModDetails -- ^ Hydrated declarations from this interface.
  , interfaceBindings :: CoreProgram -- ^ Original groups plus missing local boxed-data wrappers.
  , interfaceForeign :: IfaceForeign -- ^ Foreign source and lifecycle metadata, retained without linking.
  , interfaceFlags :: DynFlags
  }

-- | Identity failure detected before Core hydration. Other malformed-interface
-- and way/version failures retain GHC's own diagnostics.
data InterfaceError
  = InterfaceModuleMismatch Module Module -- ^ Expected module, then actual interface owner.

instance Show InterfaceError where
  show (InterfaceModuleMismatch expected actual) =
    "THC interface identity mismatch: expected " ++ identity expected ++ ", found " ++ identity actual

instance Exception InterfaceError

identity :: Module -> String
identity m = unitString (moduleUnit m) ++ ":" ++ moduleNameString (moduleName m)

-- | Check a home interface without hydrating Core or resolving installed
-- dependencies. Compiler-discovered modules need their actual binary identity,
-- not merely a neighboring .hi filename or a source-import guess.
checkInterfaceIdentity :: HscEnv -> Module -> FilePath -> IO ()
checkInterfaceIdentity environment expected path = do
  iface <- readBinIface (targetProfile (hsc_dflags environment))
    (hsc_NC environment) CheckHiWay QuietBinIFace path
  unless (mi_module iface == expected)
    (throwIO (InterfaceModuleMismatch expected (mi_module iface)))

-- | Fingerprint the bytes retained by GHC's interface reader, including its
-- tables, complete Core, annotations, foreign products and extensible fields.
-- 'mi_iface_hash' is insufficient: GHC excludes complete Core/foreign payloads
-- from that recompilation hash. No Core hydration or JSON rendering occurs.
-- Return the complete-byte fingerprint, payload availability and conservative
-- demand eligibility. The caller also accounts for dependencies/source-note text.
probeInterface :: HscEnv -> Set.Set String -> Set.Set (String, String) -> Module -> FilePath -> IO (Fingerprint, Bool, Bool)
probeInterface environment units modules expected path = do
  iface <- readBinIface (targetProfile (hsc_dflags environment))
    (hsc_NC environment) CheckHiWay QuietBinIFace path
  unless (mi_module iface == expected)
    (throwIO (InterfaceModuleMismatch expected (mi_module iface)))
  -- GHC's loader can resolve Names through the entire selected UnitState,
  -- even when a stale registration omits a real dependency/module. Such a
  -- registration is not enough evidence for this cache. Installed imports use
  -- ordinary interfaces, including references recorded as boot imports.
  let deps = mi_deps iface
      moduleIdentity m = (unitString (moduleUnit m), moduleNameString (moduleName m))
      localIdentity u n = (unitIdString u, moduleNameString n)
      providers = [localIdentity u (gwib_mod n) | (_, u, n) <- Set.toList (dep_direct_mods deps)] ++
        [localIdentity u (gwib_mod n) | (u, n) <- Set.toList (dep_boot_mods deps)] ++
        map moduleIdentity (dep_orphs deps ++ dep_finsts deps)
      packages = [unitIdString u | (_, u) <- Set.toList (dep_direct_pkgs deps)] ++
        map unitIdString (Set.toList (dep_trusted_pkgs deps))
      usageProviders UsagePackageModule{usg_mod = m} = [moduleIdentity m]
      usageProviders UsageHomeModule{usg_unit_id = u, usg_mod_name = n} = [localIdentity u n]
      usageProviders UsageHomeModuleInterface{usg_unit_id = u, usg_mod_name = n} = [localIdentity u n]
      usageProviders UsageMergedRequirement{usg_mod = m} = [moduleIdentity m]
      usageProviders UsageFile{} = [] -- compile-time inputs; their products are already retained
      usages = maybe [] (concatMap usageProviders) (mi_usages iface)
  used <- binaryProviders iface
  unless (null (dep_sig_mods deps) && all (`Set.member` units) packages && all (`Set.member` modules) (providers ++ usages ++ used))
    (fail "Interface dependency lies outside the probed registered inventory")
  digest <- case mi_hi_bytes iface of
    FullIfaceBinHandle (Strict.Just bytes) -> do
      buffer <- openBinMem 4096
      putFullBinData buffer bytes
      fingerprintBinMem buffer
    FullIfaceBinHandle Strict.Nothing -> fail "Interface reader did not retain its complete bytes"
  pure (digest, expected == gHC_PRIM || maybe False (const True) (mi_simplified_core iface),
    demandEligible iface)

-- Demand is restricted to whole units with no native or startup obligations.
-- Inspect GHC's decoded retained syntax, including cold RHSs and unfoldings;
-- neither hydration nor THC serialization is needed to prove these false facts.
-- Acquisition attaches foreign provenance even when the products are empty.
-- Validate those proofs; unknown annotations and runtime profiles stay eager.
demandEligible :: ModIface -> Bool
demandEligible iface =
  moduleNameString (moduleName (mi_module iface)) `notElem` ["THC.Exception", "THC.Internal.Exception"] &&
  unitString (moduleUnit (mi_module iface)) /= "main" &&
  all (safeDeclaration . snd) (mi_decls iface) && case mi_simplified_core iface of
    Nothing -> False
    Just simplified -> emptyForeign (mi_sc_foreign simplified) &&
      emptyAnnotations (mi_sc_foreign simplified) &&
      all (safeBinding safeTop safeRhs) (mi_sc_extra_decls simplified)
  where
    emptyAnnotations original = case
      ( Exports.readStaticExports owner annotations []
      , ExportProvenance.inspectProvenance owner annotations (Just []) original
      , ImportProvenance.inspectImports owner annotations original
      ) of
        (Right exports, Right registration, Right imports) ->
          -- Each reader accepts at most one payload of its distinct GHC type.
          -- Count against ALL annotations so named, wrong-owner and unknown
          -- payloads cannot disappear through a reader's filtering.
          length (mi_anns iface) == length (filter id
            [ case exports of Just (Exports.StaticExports _ _ _ []) -> True; _ -> False
            , case registration of ExportProvenance.VerifiedRetainedRegistration _ [] -> True; _ -> False
            , case imports of Just (ImportProvenance.Verified [] []) -> True; _ -> False
            ])
        _ -> False
    owner = mi_module iface
    annotations = [Annotation (ModuleTarget target) payload |
      Iface.IfaceAnnotation (ModuleTarget target) payload <- mi_anns iface]
    emptyForeign (Foreign.IfaceForeign Nothing []) = True
    emptyForeign (Foreign.IfaceForeign (Just (Foreign.IfaceCStubs "" "" [] [])) []) = True
    emptyForeign _ = False
    safeDeclaration Iface.IfaceId{Iface.ifIdInfo = info} = safeInfo info
    -- GHC constructs boxed-data wrappers from declarations, not retained
    -- arbitrary RHSs. Existing synthesis admits only this module's wrappers;
    -- emitted CBD summaries are checked again at the demand boundary.
    safeDeclaration _ = True
    safeInfo = all $ \item -> case item of
      Iface.HsUnfold _ (Iface.IfCoreUnfold _ _ _ expression) -> safeExpr expression
      Iface.HsUnfold _ (Iface.IfDFunUnfold _ expressions) -> all safeExpr expressions
      _ -> True
    safeTop (Iface.IfLclTopBndr _ _ info _) = safeInfo info
    safeTop (Iface.IfGblTopBndr name) = nameModule_maybe name == Just (mi_module iface)
      -- The corresponding ordinary declaration was checked above. A foreign
      -- top-binder owner could introduce the CLI main alias before demand.
    safeRhs Iface.IfUseUnfoldingRhs = True
    safeRhs (Iface.IfRhs expression) = safeExpr expression
    safeLocal (Iface.IfLetBndr _ _ info _) = safeInfo info
    safeBinding binder rhs (Iface.IfaceNonRec name body) = binder name && rhs body
    safeBinding binder rhs (Iface.IfaceRec pairs) = all (\(name,body) -> binder name && rhs body) pairs
    safeLiteral LitLabel{} = False
    safeLiteral _ = True
    safeExpr expression = case expression of
      Iface.IfaceLcl{} -> True
      Iface.IfaceExt name -> occNameString (nameOccName name) `notElem` ["prompt#", "control0#"]
      Iface.IfaceType{} -> True
      Iface.IfaceCo{} -> True
      Iface.IfaceTuple _ expressions -> all safeExpr expressions
      Iface.IfaceLam _ body -> safeExpr body
      Iface.IfaceApp function argument -> safeExpr function && safeExpr argument
      Iface.IfaceCase scrutinee _ alternatives -> safeExpr scrutinee && all safeAlt alternatives
      Iface.IfaceECase scrutinee _ -> safeExpr scrutinee
      Iface.IfaceLet binding body -> safeBinding safeLocal safeExpr binding && safeExpr body
      Iface.IfaceCast body _ -> safeExpr body
      Iface.IfaceLit literal -> safeLiteral literal
      Iface.IfaceLitRubbish{} -> True
      Iface.IfaceFCall{} -> False
      Iface.IfaceTick tick body -> safeExpr body && case tick of
        Iface.IfaceBreakpoint _ expressions -> all safeExpr expressions
        _ -> True
    safeAlt (Iface.IfaceAlt alternative _ body) = safeExpr body && case alternative of
      Iface.IfaceLitAlt literal -> safeLiteral literal
      _ -> True

-- Recompilation usages can omit wired-in or later-introduced Names. GHC's own
-- Binary writer enumerates the retained Names without hydration or a THC
-- interface-AST traversal, including known-key Names that bypass its reader's
-- symbol table.
-- Extensible-field bytes are fingerprinted above; THC does not hydrate them.
binaryProviders :: ModIface -> IO [(String, String)]
binaryProviders iface = do
  providers <- newIORef Set.empty
  buffer <- openBinMem 4096
  let nameWriter handle name = do
        -- A retained interface mentions the same provider through many Names.
        -- Keep GHC's compact identities while traversing; unpack their strings
        -- only once per distinct Module after the unchanged Binary write.
        modifyIORef' providers (Set.insert (nameModule name))
        putNameLiterally handle name
      writer = setWriterUserData buffer $ mkWriterUserData
        [mkSomeBinaryWriter (mkWriter putIfaceType), mkSomeBinaryWriter (mkWriter nameWriter),
         mkSomeBinaryWriter (simpleBindingNameWriter (mkWriter nameWriter)), mkSomeBinaryWriter (mkWriter putFS)]
  put_ writer iface
  modules <- readIORef providers
  pure [(unitString (moduleUnit m), moduleNameString (moduleName m)) | m <- Set.toAscList modules]

-- | Read a raw installed interface with the selected GHC session's target,
-- package database and NameCache. The expected Module includes the exact unit
-- identity; callers must resolve it through that same session's package state.
-- Nothing means only that this valid interface has no complete Core payload.
-- Wrong identity, way/version and corrupt data are errors, not reasons to
-- substitute ordinary inline unfoldings. Foreign products remain in the
-- archive; successful hydration does not establish executable registration.
--
-- Use a session retaining interface pragmas from its creation when possible.
-- Hydration retains pragmas in a private flags/environment value regardless;
-- it does not change the caller's flags or install targets/bytecode. GHC's
-- ordinary dependency loading can populate the session's shared caches.
loadInterfaceCore :: HscEnv -> Module -> FilePath -> IO (Maybe InterfaceCore)
loadInterfaceCore environment expected path = do
  let originalFlags = hsc_dflags environment
      -- GHC otherwise drops IfaceSource ticks while hydrating, even when the
      -- interface retained them. Only our private environment requests notes.
      flags = (gopt_unset originalFlags Opt_IgnoreInterfacePragmas)
        { debugLevel = max 1 (debugLevel originalFlags) }
  -- The normal PackageIfaceTable deliberately replaces simplified Core with
  -- bottom. Read before that purge, using the real compiler binary reader.
  iface <- readBinIface (targetProfile flags) (hsc_NC environment) CheckHiWay QuietBinIFace path
  let m = mi_module iface
  unless (m == expected) (throwIO (InterfaceModuleMismatch expected m))
  -- GHC itself supplies this module's types and operations. Its generated
  -- Haddock source contains dummy definitions and is never executable Core.
  -- Retain the compiler's primitive declarations, with no invented bodies.
  if m == gHC_PRIM then pure (Just (InterfaceCore m
    emptyModDetails { md_types = mkTypeEnv (map ATyCon primTyCons) }
    [] emptyIfaceForeign flags)) else case mi_simplified_core iface of
    Nothing -> pure Nothing
    Just simplified -> do
      types <- newIORef emptyTypeEnv
      let oldKnots = hsc_type_env_vars environment
          domain = case oldKnots of NoKnotVars -> []; KnotVars ms _ -> ms
          knots = KnotVars (m : filter (/= m) domain) $ \other ->
            if other == m then Just types else lookupKnotVars oldKnots other
          tied = (hscSetFlags flags environment) { hsc_type_env_vars = knots }
          location = ModLocation Nothing path (replaceExtension path "dyn_hi")
            (replaceExtension path "o") (replaceExtension path "dyn_o") (replaceExtension path "hie")
          whole = WholeCoreBindings
            { wcb_bindings = mi_sc_extra_decls simplified
            , wcb_module = m
            , wcb_mod_location = location
            , wcb_foreign = mi_sc_foreign simplified
            }
      details <- initIfaceCheck (text "THC interface declarations") tied (typecheckIface iface)
      writeIORef types (md_types details)
      bindings <- initIfaceCheck (text "THC complete interface Core") tied
        (typecheckWholeCoreBindings types whole)
      -- GHC writes simplified Core before AddImplicitBinds injects constructor
      -- wrappers. Their genuine Ids/unfoldings are reconstructed by interface
      -- declaration hydration, not present in mi_sc_extra_decls. Recover only
      -- local boxed-data wrappers with those exact bodies; constructor workers
      -- are already represented by THC constructor metadata. No ordinary thin
      -- interface fallback or name-based replacement is involved.
      let supplied = mkVarSet (bindersOfBinds bindings)
          wrappers = [NonRec v rhs
            | v <- sortOn getOccString (typeEnvIds (md_types details))
            , nameModule_maybe (varName v) == Just m
            , not (v `elemVarSet` supplied)
            , Just con <- [isDataConWrapId_maybe v]
            , isBoxedDataTyCon (dataConTyCon con)
            , Just rhs <- [dataConWrapUnfolding_maybe v]]
      pure (Just (InterfaceCore m details (wrappers ++ bindings) (mi_sc_foreign simplified) flags))

-- | Use the same serializer as the post-Tidy plugin, without another compile
-- or a source target. Source-note paths survive even if source text is absent.
-- This does not link dependencies or certify runtime support for the module.
interfaceCoreCBD :: [CommandLineOption] -> InterfaceCore -> IO BS.ByteString
interfaceCoreCBD options core = serializePostTidyCoreWithAnnotationsCBD (interfaceFlags core) options
  (interfaceModule core) (typeEnvTyCons (md_types (interfaceDetails core))) (interfaceBindings core)
  (interfaceForeign core) (md_anns (interfaceDetails core))

-- | Explicit JSON inspection output. With "pretty-diagnostics", retain the
-- rich producer model, including names, types and source Core; otherwise derive
-- inspection from CBD. Never used for normal acquisition or execution.
interfaceCoreJSON :: [CommandLineOption] -> InterfaceCore -> IO String
interfaceCoreJSON options core = serializePostTidyCoreWithAnnotations (interfaceFlags core) options
  (interfaceModule core) (typeEnvTyCons (md_types (interfaceDetails core))) (interfaceBindings core)
  (interfaceForeign core) (md_anns (interfaceDetails core))

-- | Byte-oriented version of 'interfaceCoreJSON', preserving its exact UTF-8
-- output. Force the strict result before emitting success to retain all-or-error
-- subprocess responses if serialization fails.
interfaceCoreJSONBytes :: [CommandLineOption] -> InterfaceCore -> IO BS.ByteString
interfaceCoreJSONBytes options core = serializePostTidyCoreWithAnnotationsBytes (interfaceFlags core) options
  (interfaceModule core) (typeEnvTyCons (md_types (interfaceDetails core))) (interfaceBindings core)
  (interfaceForeign core) (md_anns (interfaceDetails core))
