-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE PatternSynonyms #-}
module THC.Interface
  ( InterfaceCore, interfaceModule, interfaceDetails, interfaceBindings, interfaceForeign
  , InterfaceError(..), loadInterfaceCore, interfaceCoreJSON
  ) where

import Control.Exception (Exception, throwIO)
import Control.Monad (unless)
import Data.IORef (newIORef, writeIORef)
import Data.List (sortOn)
import GHC.Plugins
import GHC.Driver.Env (hscSetFlags)
import GHC.Driver.Env.KnotVars (KnotVars(..), lookupKnotVars)
import GHC.Iface.Binary (readBinIface, CheckHiWay(..), TraceBinIFace(..))
import GHC.IfaceToCore (typecheckIface, typecheckWholeCoreBindings)
import GHC.Tc.Utils.Monad (initIfaceCheck)
import GHC.Types.TypeEnv (emptyTypeEnv, typeEnvIds, typeEnvTyCons)
import GHC.Unit.Module.Location (pattern ModLocation)
import GHC.Unit.Module.ModDetails (ModDetails(..))
import GHC.Unit.Module.ModIface
import GHC.Unit.Module.WholeCoreBindings (WholeCoreBindings(..), IfaceForeign)
import System.FilePath (replaceExtension)
import THC.Plugin (serializePostTidyCore)

-- | Original GHC identities, declarations, recursive groups and foreign
-- metadata. Loading is archival: accompanying foreign build products are
-- retained, not linked or registered with a runtime.
data InterfaceCore = InterfaceCore
  { interfaceModule :: Module
  , interfaceDetails :: ModDetails
  , interfaceBindings :: CoreProgram
  , interfaceForeign :: IfaceForeign
  , interfaceFlags :: DynFlags
  }

data InterfaceError
  = InterfaceModuleMismatch Module Module

instance Show InterfaceError where
  show (InterfaceModuleMismatch expected actual) =
    "THC interface identity mismatch: expected " ++ identity expected ++ ", found " ++ identity actual

instance Exception InterfaceError

identity :: Module -> String
identity m = unitString (moduleUnit m) ++ ":" ++ moduleNameString (moduleName m)

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
interfaceCoreJSON options core = serializePostTidyCore (interfaceFlags core) options
  (interfaceModule core) (typeEnvTyCons (md_types (interfaceDetails core))) (interfaceBindings core)
  (interfaceForeign core)
