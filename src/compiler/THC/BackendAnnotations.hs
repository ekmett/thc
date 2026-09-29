-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE GADTs #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE RankNTypes #-}

-- |
-- Module      : THC.BackendAnnotations
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC 9.14.1 compiler API
--
-- Decode root-backend annotations and retain them at final source publication.
module THC.BackendAnnotations (backendFields, closureFields, installBackendHook, knownBackendHook) where

import Control.Concurrent.MVar (MVar, newMVar, modifyMVar, modifyMVar_)
import Control.Exception (evaluate)
import Control.Monad (foldM, forM, unless)
import qualified Data.Aeson as Aeson
import qualified Data.Aeson.KeyMap as KM
import Data.List (stripPrefix)
import Data.Maybe (catMaybes)
import qualified Data.Map.Strict as Map
import GHC.Plugins
import GHC.Driver.Env (prepareAnnotations)
import GHC.Driver.Hooks (Hooks(..))
import GHC.Driver.Pipeline.Phases (TPhase(..), PhaseHook(..))
import GHC.Driver.Pipeline.Execute (runPhase)
import GHC.Iface.Syntax (IfaceAnnotation(..))
import GHC.Types.Name.Occurrence (occNameMangledFS)
import GHC.Unit.Module.Status (HscBackendAction(..))
import THC.Compact.Module (readModuleMetadataFile, finalizeModuleMetadata)
import THC.JSON (J(..), moduleValue)
import System.IO.Unsafe (unsafePerformIO)
import System.Mem.StableName (makeStableName)
import System.Mem.Weak (Weak, deRefWeak, mkWeakPtr)

-- Only our exact wrappers over the stock pipeline are known. Weak keys avoid
-- retaining compiler sessions, options or previous hooks after compilation.
{-# NOINLINE backendHooks #-}
backendHooks :: MVar [Weak (TPhase () -> IO ())]
backendHooks = unsafePerformIO (newMVar [])

-- | Recognize the installed THC hook without admitting an arbitrary hook
-- merely because THC is also loaded. Unknown predecessors and replacements
-- remain unknown; GHC may initialize this driver plugin more than once.
knownBackendHook :: Maybe PhaseHook -> IO Bool
knownBackendHook Nothing = pure True
knownBackendHook (Just (PhaseHook hook)) = do
  identity <- evaluate hook >>= makeStableName
  modifyMVar backendHooks $ \hooks -> do
    live <- catMaybes <$> mapM (\weak -> fmap ((,) weak) <$> deRefWeak weak) hooks
    identities <- mapM (\(_,callback) -> evaluate callback >>= makeStableName) live
    pure (map fst live, identity `elem` identities)

-- | Unrelated payload types and strings are ignored. Backend overrides name
-- original declarations; they do not propagate to GHC workers or inline copies.
backendFields :: Module -> [(AnnTarget OccName, AnnPayload)] -> Either String [(String,J)]
backendFields owner annotations = do
  entries <- backendEntries owner
    [(target,raw) | (target,payload) <- annotations, Just raw <- [fromSerialized deserializeWithData payload]]
  pure (policyFields entries)

policyFields :: Map.Map (Maybe String) String -> [(String,J)]
policyFields entries =
  let def = maybe [] (\value -> [("default",S value)]) (Map.lookup Nothing entries)
      named = [(key,S value) | (Just key,value) <- Map.toAscList entries]
  in [("backendPolicy",O (def ++ [("bindings",O named)])) | not (Map.null entries)]

backendEntries :: Module -> [(AnnTarget OccName,String)] -> Either String (Map.Map (Maybe String) String)
backendEntries owner = foldM add Map.empty
  where
    add entries (target,raw) = case stripPrefix "thc:" raw of
      Just option -> do
        backend <- case option of
          "backend=ast" -> Right "ast"
          "backend=bytecode" -> Right "bytecode"
          _ | Just _ <- stripPrefix "vectorize" option -> Left
            "THC ANN vectorize is not supported: vectorization is JVM-global; use the global JVM compiler flag"
          _ -> Left ("Unsupported THC ANN option: " ++ raw)
        key <- case target of
          ModuleTarget actual | actual == owner -> Right Nothing
          NamedTarget occurrence | isVarOcc occurrence -> Right (Just
            (unitString (moduleUnit owner) ++ ":" ++ moduleNameString (moduleName owner) ++ "." ++
             unpackFS (occNameMangledFS occurrence)))
          _ -> Left "THC ANN backend requires this module or a top-level function/value binder"
        case Map.lookup key entries of
          Just previous | previous /= backend -> Left "Conflicting THC ANN backend settings for one target"
          _ -> Right (Map.insert key backend entries)
      _ -> Right entries

-- | A synthetic interface closure combines roots from different modules.
-- Resolve each imported root against its original annotations, then publish
-- only explicit qualified choices so no module default leaks to another root.
closureFields :: HscEnv -> [(Name,String)] -> IO [(String,J)]
closureFields environment roots = do
  annotations <- prepareAnnotations environment Nothing
  policies <- forM (Map.toAscList grouped) $ \(owner,names) -> do
    let targets = ModuleTarget owner : map (NamedTarget . fst) names
        values = [(fmap nameOccName target,raw) | target <- targets,
          raw <- findAnns deserializeWithData annotations target]
    entries <- either fail pure (backendEntries owner values)
    pure [(Just key,backend) | (_,key) <- names,
      Just backend <- [case Map.lookup (Just key) entries of
        Just value -> Just value
        Nothing -> Map.lookup Nothing entries]]
  pure (policyFields (Map.fromList (concat policies)))
  where
    grouped = Map.fromListWith (++) [(owner,[(name,key)]) |
      (name,key) <- roots, Just owner <- [nameModule_maybe name]]

-- | The final backend result owns the current interface annotations. Chain the
-- prior hook once, then amend only our just-emitted private CBD's final header.
-- Neither a previous interface nor executable binding bodies are read here.
installBackendHook :: ([CommandLineOption] -> Module -> FilePath) -> [CommandLineOption] -> HscEnv -> IO HscEnv
installBackendHook path options environment
  | "post-tidy" `notElem` options = pure environment
  | otherwise = do
      knownPrevious <- knownBackendHook (runPhaseHook hooks)
      hook <- evaluate (PhaseHook run)
      if knownPrevious then do
        -- GHC can rebox PhaseHook, but retains its actual callback closure.
        callback <- evaluate (run :: TPhase () -> IO ())
        weak <- mkWeakPtr callback Nothing
        modifyMVar_ backendHooks (pure . (weak :))
      else pure ()
      pure environment {hsc_hooks = hooks {runPhaseHook = Just hook}}
  where
    hooks = hsc_hooks environment
    previous :: TPhase a -> IO a
    previous = case runPhaseHook hooks of Nothing -> runPhase; Just (PhaseHook old) -> old
    run :: TPhase a -> IO a
    run phase = do
      result <- previous phase
      case phase of
        T_HscBackend _ _ _ _ _ HscRecomp{} -> do
          let (_,iface,_,_) = result
              owner = mi_module iface
          fields <- either fail pure (backendFields owner
            [(ifAnnotatedTarget annotation,ifAnnotatedValue annotation) | annotation <- mi_anns iface])
          unless (null fields) $ do
            let destination = path options owner
            (_,metadata) <- readModuleMetadataFile destination
            case (metadata,moduleValue (O fields)) of
              (Aeson.Object original,Aeson.Object additions) -> do
                unless (KM.lookup "unit" original == Just (Aeson.toJSON (unitString (moduleUnit owner))) &&
                        KM.lookup "module" original == Just (Aeson.toJSON (moduleNameString (moduleName owner))))
                  (fail "THC backend annotation artifact has a different module identity")
                finalizeModuleMetadata destination (Aeson.Object (KM.union additions original))
              _ -> fail "THC backend annotation requires a module header"
        _ -> pure ()
      pure result
