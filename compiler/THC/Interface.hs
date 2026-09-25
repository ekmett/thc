-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE GADTs, PatternSynonyms #-}
-- | Recover complete installed Core with the selected GHC 9.14.1 session.
--
-- Call 'loadInterfaceCore' with an exact resolved unit/module and interface
-- path, then 'interfaceCoreJSON' to use THC's post-Tidy serialization. Missing
-- complete Core is distinct from an invalid interface. Loading and archival
-- serialization do not link native foreign products or authorize execution.
module THC.Interface
  ( InterfaceCore, interfaceModule, interfaceDetails, interfaceBindings, interfaceForeign
  , InterfaceError(..), loadInterfaceCore, interfaceCoreJSON, probeInterface
  ) where

import Control.Exception (Exception, throwIO)
import Control.Monad (unless)
import Data.IORef (newIORef, writeIORef, readIORef, modifyIORef')
import Data.List (sortOn)
import qualified Data.Set as Set
import GHC.Plugins
import GHC.Driver.Env (hscSetFlags)
import GHC.Driver.Env.KnotVars (KnotVars(..), lookupKnotVars)
import GHC.Iface.Binary (readBinIface, CheckHiWay(..), TraceBinIFace(..))
import GHC.Iface.Recomp.Binary (fingerprintBinMem, putNameLiterally)
import GHC.Iface.Type (putIfaceType)
import qualified GHC.Data.Strict as Strict
import GHC.IfaceToCore (typecheckIface, typecheckWholeCoreBindings)
import GHC.Tc.Utils.Monad (initIfaceCheck)
import GHC.Types.TypeEnv (emptyTypeEnv, typeEnvIds, typeEnvTyCons)
import GHC.Unit.Module.Location (pattern ModLocation)
import GHC.Unit.Module.ModDetails (ModDetails(..))
import GHC.Unit.Module.Deps
import GHC.Unit.Module.ModIface
import GHC.Unit.Module.WholeCoreBindings (WholeCoreBindings(..), IfaceForeign)
import GHC.Utils.Binary (openBinMem, putFullBinData, put_, putFS, setWriterUserData,
                        mkWriterUserData, mkSomeBinaryWriter, mkWriter, simpleBindingNameWriter)
import GHC.Utils.Fingerprint (Fingerprint)
import System.FilePath (replaceExtension)
import THC.Plugin (serializePostTidyCoreWithAnnotations)

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

-- | Fingerprint the bytes retained by GHC's interface reader, including its
-- tables, complete Core, annotations, foreign products and extensible fields.
-- 'mi_iface_hash' is insufficient: GHC excludes complete Core/foreign payloads
-- from that recompilation hash. No Core hydration or JSON rendering occurs.
-- The caller must also account for dependency interfaces and source-note text.
probeInterface :: HscEnv -> Set.Set String -> Set.Set (String, String) -> Module -> FilePath -> IO (Fingerprint, Bool)
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
  pure (digest, maybe False (const True) (mi_simplified_core iface))

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
        let m = nameModule name
        modifyIORef' providers (Set.insert (unitString (moduleUnit m), moduleNameString (moduleName m)))
        putNameLiterally handle name
      writer = setWriterUserData buffer $ mkWriterUserData
        [mkSomeBinaryWriter (mkWriter putIfaceType), mkSomeBinaryWriter (mkWriter nameWriter),
         mkSomeBinaryWriter (simpleBindingNameWriter (mkWriter nameWriter)), mkSomeBinaryWriter (mkWriter putFS)]
  put_ writer iface
  Set.toAscList <$> readIORef providers

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
  case mi_simplified_core iface of
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
interfaceCoreJSON :: [CommandLineOption] -> InterfaceCore -> IO String
interfaceCoreJSON options core = serializePostTidyCoreWithAnnotations (interfaceFlags core) options
  (interfaceModule core) (typeEnvTyCons (md_types (interfaceDetails core))) (interfaceBindings core)
  (interfaceForeign core) (md_anns (interfaceDetails core))
