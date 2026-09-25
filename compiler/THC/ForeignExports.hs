-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE DeriveDataTypeable #-}
{-# LANGUAGE LambdaCase #-}
-- | Opt-in, archival static-export associations from the typed GHC declaration.
-- The annotation is carried by GHC into the interface; no process-local table,
-- C-text recognizer or generated-binder naming convention establishes identity.
-- GHC's -fno-code simple-interface path drops annotations. Acquisition requires
-- an ordinary code-generation interface; missing metadata remains unknown.
module THC.ForeignExports
  ( ExportName(..), ExportType(..), StaticExport(..), StaticExports(..)
  , recordStaticExports, readStaticExports
  ) where

import Control.Monad (unless)
import Data.Data (Data)
import Data.List (nub)
import Data.Maybe (catMaybes, mapMaybe)
import GHC.Plugins
import GHC.Core.TyCo.Compare (eqType)
import GHC.Core.TyCo.Rep (Type(..))
import GHC.Hs (GhcTc, ForeignDecl(..), ForeignExport(..))
import GHC.Tc.Types (TcGblEnv(..), TcM)
import GHC.Tc.Utils.TcType (tcSplitPiTys, tcSplitIOType_maybe)
import GHC.Types.ForeignCall (CExportSpec(..), CCallConv(..))

data ExportName = ExportName String String String String deriving (Eq, Show, Data)

-- V1 deliberately accepts closed, monomorphic signatures. These are nominal
-- type identities and applications, not parsed pretty-printing or PrimRep alone.
data ExportType
  = ExportTyCon ExportName [ExportType]
  | ExportApp ExportType ExportType
  | ExportArrow ExportType ExportType ExportType -- multiplicity, argument, result
  deriving (Eq, Show, Data)

data StaticExport = StaticExport
  { exportBinder :: ExportName
  , exportSymbol :: String
  , exportConvention :: String
  , exportDeclared :: ExportType
  , exportNormalized :: ExportType
  , exportArguments :: [ExportType]
  , exportResult :: ExportType
  , exportIO :: Bool
  } deriving (Eq, Show, Data)

-- The version is explicit even though GHC also persists the annotation's type.
-- This says nothing about dynamic wrappers or arbitrary native initialization.
data StaticExports = StaticExports Int String String [StaticExport]
  deriving (Eq, Show, Data)

nameIdentity :: Name -> Either String ExportName
nameIdentity name = case nameModule_maybe name of
  Nothing -> Left "foreign-export metadata requires an external GHC Name"
  Just owner -> Right (ExportName (unitString (moduleUnit owner))
    (moduleNameString (moduleName owner)) (occNameString occurrence) namespace)
  where
    occurrence = nameOccName name
    namespace | isVarOcc occurrence = "value"
              | isDataOcc occurrence = "data"
              | isTcOcc occurrence = "type"
              | otherwise = "type-variable"

typeIdentity :: Type -> Either String ExportType
typeIdentity = \case
  TyConApp constructor arguments -> ExportTyCon <$> nameIdentity (tyConName constructor)
    <*> traverse typeIdentity arguments
  AppTy function argument -> ExportApp <$> typeIdentity function <*> typeIdentity argument
  FunTy { ft_af = FTF_T_T, ft_mult = multiplicity, ft_arg = argument, ft_res = result } ->
    ExportArrow <$> typeIdentity multiplicity <*> typeIdentity argument <*> typeIdentity result
  _ -> Left "static foreign-export metadata v1 requires a closed monomorphic type without casts or constraints"

convention :: CCallConv -> String
convention CCallConv = "ccall"
convention CApiConv = "capi"
convention StdCallConv = "stdcall"
convention PrimCallConv = "prim"
convention JavaScriptCallConv = "javascript"

capture :: ForeignDecl GhcTc -> Either String (Maybe StaticExport)
capture ForeignExport { fd_name = L _ binder, fd_e_ext = coercion,
    fd_fe = CExport _ (L _ (CExportStatic _ symbol callConvention)) } = do
  unless (idType binder `eqType` coercionLKind coercion && coercionRole coercion == Representational)
    (Left "static foreign-export normalization disagrees with its actual binder")
  let normalized = coercionRKind coercion
      (parameters, originalResult) = tcSplitPiTys normalized
      arguments = mapMaybe anonPiTyBinderType_maybe parameters
      (isIO, result) = case tcSplitIOType_maybe originalResult of
        Just (_, value) -> (True, value)
        Nothing -> (False, originalResult)
  record <- StaticExport <$> nameIdentity (varName binder) <*> pure (unpackFS symbol)
    <*> pure (convention callConvention) <*> typeIdentity (idType binder)
    <*> typeIdentity normalized <*> traverse typeIdentity arguments <*> typeIdentity result <*> pure isIO
  pure (Just record)
capture ForeignImport {} = Right Nothing

recordStaticExports :: [CommandLineOption] -> TcGblEnv -> TcM TcGblEnv
recordStaticExports options environment
  | "foreign-export-associations" `notElem` options = pure environment
  | otherwise = do
      let owner = tcg_mod environment
          result = StaticExports 1 (unitString (moduleUnit owner)) (moduleNameString (moduleName owner))
            . catMaybes <$> traverse (capture . unLoc) (tcg_fords environment)
      payload <- either (liftIO . ioError . userError . ("THC: " ++)) pure result
      pure environment { tcg_anns = tcg_anns environment ++
        [Annotation (ModuleTarget owner) (toSerialized serializeWithData payload)] }

-- Validate against the actual hydrated/optimized binders. Absence remains
-- unknown; it is never a known-empty export inventory for an old interface.
readStaticExports :: Module -> [Annotation] -> CoreProgram -> Either String (Maybe StaticExports)
readStaticExports owner annotations bindings = case records of
  [] -> Right Nothing
  [record@(StaticExports version unit modName exports)] -> do
    unless (version == 1 && unit == unitString (moduleUnit owner) && modName == moduleNameString (moduleName owner))
      (Left "static foreign-export annotation version/owner mismatch")
    unless (length (nub (map exportSymbol exports)) == length exports)
      (Left "duplicate static foreign-export symbol")
    mapM_ validate exports
    pure (Just record)
  _ -> Left "duplicate static foreign-export annotations"
  where
    records = [value | Annotation (ModuleTarget target) serialized <- annotations, target == owner,
      Just value <- [fromSerialized deserializeWithData serialized]]
    validate record = case [binder | (binder, _) <- flattenBinds bindings,
        nameIdentity (varName binder) == Right (exportBinder record)] of
      [binder] -> do
        declared <- typeIdentity (idType binder)
        unless (declared == exportDeclared record)
          (Left "static foreign-export binder type changed")
      _ -> Left "static foreign-export annotation does not resolve to one actual Core binder"
