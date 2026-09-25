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
import GHC.Plugins
import GHC.Driver.Env (hscSetFlags)
import GHC.Driver.Env.KnotVars (KnotVars(..), lookupKnotVars)
import GHC.Iface.Binary (readBinIface, CheckHiWay(..), TraceBinIFace(..))
import GHC.IfaceToCore (typecheckIface, typecheckWholeCoreBindings)
import GHC.Tc.Utils.Monad (initIfaceCheck)
import GHC.Types.TypeEnv (emptyTypeEnv, typeEnvTyCons)
import GHC.Unit.Module.Location (pattern ModLocation)
import GHC.Unit.Module.ModDetails (ModDetails(..))
import GHC.Unit.Module.ModIface
import GHC.Unit.Module.WholeCoreBindings (WholeCoreBindings(..), IfaceForeign(..), IfaceCStubs(..))
import System.FilePath (replaceExtension)
import THC.Plugin (serializePostTidyCore)

-- | Original GHC identities, declarations, recursive groups and foreign
-- metadata. Construction is private: successful loads have no foreign build
-- products that the JSON exporter would silently discard.
data InterfaceCore = InterfaceCore
  { interfaceModule :: Module
  , interfaceDetails :: ModDetails
  , interfaceBindings :: CoreProgram
  , interfaceForeign :: IfaceForeign
  , interfaceFlags :: DynFlags
  }

data InterfaceError
  = InterfaceModuleMismatch Module Module
  | UnsupportedInterfaceForeign Module

instance Show InterfaceError where
  show (InterfaceModuleMismatch expected actual) =
    "THC interface identity mismatch: expected " ++ identity expected ++ ", found " ++ identity actual
  show (UnsupportedInterfaceForeign m) =
    "THC cannot load interface foreign stubs or files for " ++ identity m

instance Exception InterfaceError

identity :: Module -> String
identity m = unitString (moduleUnit m) ++ ":" ++ moduleNameString (moduleName m)

-- | Read a raw installed interface with the selected GHC session's target,
-- package database and NameCache. The expected Module includes the exact unit
-- identity; callers must resolve it through that same session's package state.
-- Nothing means only that this valid interface has no complete Core payload.
-- Wrong identity, way/version, corrupt data and unsupported foreign build
-- products are errors, not reasons to substitute ordinary inline unfoldings.
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
      case mi_sc_foreign simplified of
        IfaceForeign Nothing [] -> pure ()
        -- GHC also serializes an explicitly empty ForeignStubs record for
        -- ordinary modules. It has no code or initialization to preserve.
        IfaceForeign (Just (IfaceCStubs "" "" [] [])) [] -> pure ()
        _ -> throwIO (UnsupportedInterfaceForeign m)
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
      pure (Just (InterfaceCore m details bindings (mi_sc_foreign simplified) flags))

-- | Use the same serializer as the post-Tidy plugin, without another compile
-- or a source target. Source-note paths survive even if source text is absent.
-- This does not link dependencies or certify runtime support for the module.
interfaceCoreJSON :: [CommandLineOption] -> InterfaceCore -> IO String
interfaceCoreJSON options core = serializePostTidyCore (interfaceFlags core) options
  (interfaceModule core) (typeEnvTyCons (md_types (interfaceDetails core))) (interfaceBindings core)
