-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE DeriveDataTypeable, LambdaCase #-}
-- | A closed producer profile for stock static-import C products. These are
-- retained provenance records, not native links or execution capabilities.
module THC.ForeignImportProvenance
  ( Import(..), ImportType(..), Call(..), Verdict(..), recordImports, inspectImports ) where

import Control.Monad (unless)
import Data.Data (Data)
import Data.IORef (newIORef, readIORef)
import Data.List (elemIndex, nub)
import GHC.Plugins
import GHC.Builtin.Types.Prim (byteArrayPrimTyCon, mutableByteArrayPrimTyCon)
import GHC.Core.TyCo.Rep (Type(..), scaledThing)
import GHC.Core.TyCo.Compare (eqType)
import GHC.Cmm.CLabel (CStubLabel(..))
import GHC.Data.OrdList (fromOL)
import GHC.Driver.Hooks
import GHC.Hs (ForeignDecl(..), ForeignImport(..), CImportSpec(..))
import GHC.HsToCore.Foreign.Decl (dsForeigns)
import GHC.HsToCore.Monad (initDsTc)
import GHC.Platform (Arch(..), platformArch)
import GHC.Platform.Profile (profileIsProfiling)
import GHC.Tc.Types (TcGblEnv(..), TcM)
import GHC.Tc.Utils.Monad (getTopEnv, setGblEnv, updTopEnv)
import GHC.Types.Error (isEmptyMessages)
import GHC.Types.ForeignCall
import GHC.Types.ForeignStubs
import GHC.Types.RepType (typePrimRep_maybe, unwrapType)
import qualified GHC.Unit.Module.WholeCoreBindings as Foreign
import THC.ForeignExports (ExportName, nameIdentity)
import THC.ForeignExportProvenance (knownPipeline)

data Call = Call String (Maybe String) String String [String] [String] deriving (Eq, Data)
-- Imports may quantify the phantom state parameter of MutableByteArray#.
-- Keep alpha-bound type identity without weakening the closed export profile.
data ImportType
  = ImportTyCon ExportName [ImportType]
  | ImportApp ImportType ImportType
  | ImportArrow ImportType ImportType ImportType
  | ImportVariable Int
  | ImportForall ImportType ImportType
  deriving (Eq, Data)
data Import = Import ExportName (Maybe String) String (Maybe String) Bool String String
  ImportType ImportType Call deriving (Eq, Data)
data Product = Product (Maybe (String,String,[(Bool,String,String,String)],[(Bool,String,String,String)]))
  [(String,String,String)] deriving (Eq, Data)
data Evidence = Unclassified String | StockImports [Import] Product deriving Data
data ImportProof = ImportProof Int String String Evidence deriving Data
data Verdict = Unknown String | Rejected String | Verified [Import]

productOf :: Foreign.IfaceForeign -> Product
productOf (Foreign.IfaceForeign stubs files) = Product (fmap stub stubs) (map file files)
  where
    stub (Foreign.IfaceCStubs header source initializers finalizers) =
      (header,source,map label initializers,map label finalizers)
    label (Foreign.IfaceCLabel value) = (csl_is_initializer value,
      unitString (moduleUnit (csl_module value)),moduleNameString (moduleName (csl_module value)),unpackFS (csl_name value))
    file (Foreign.IfaceForeignFile sourceLanguage source extension) = (show sourceLanguage,source,extension)

convention :: CCallConv -> String
convention CCallConv = "ccall"
convention CApiConv = "capi"
convention _ = "unsupported"

safetyName :: Safety -> String
safetyName PlayRisky = "unsafe"
safetyName PlaySafe = "safe"
safetyName PlayInterruptible = "interruptible"

scalar :: Type -> Either String String
scalar ty
  | Just (constructor, _) <- splitTyConApp_maybe (unwrapType ty), constructor == byteArrayPrimTyCon = Right "ByteArray#"
  | Just (constructor, _) <- splitTyConApp_maybe (unwrapType ty), constructor == mutableByteArrayPrimTyCon = Right "MutableByteArray#"
  | otherwise = case typePrimRep_maybe ty of
      Just [] -> Right "void"
      Just [primitive] | primitive `elem` [IntRep,WordRep,Int8Rep,Word8Rep,Int16Rep,Word16Rep,
          Int32Rep,Word32Rep,Int64Rep,Word64Rep,AddrRep,FloatRep,DoubleRep] -> Right (show primitive)
      _ -> Left "static-import producer requires concrete scalar or byte-array foreign carriers"

importTypeIdentity :: Type -> Either String ImportType
importTypeIdentity = go []
  where
    go bound = \case
      TyConApp constructor arguments -> ImportTyCon <$> nameIdentity (tyConName constructor)
        <*> traverse (go bound) arguments
      AppTy function argument -> ImportApp <$> go bound function <*> go bound argument
      FunTy { ft_af = FTF_T_T, ft_mult = multiplicity, ft_arg = argument, ft_res = result } ->
        ImportArrow <$> go bound multiplicity <*> go bound argument <*> go bound result
      TyVarTy variable -> maybe (Left "free type variable in static import") (Right . ImportVariable) (elemIndex variable bound)
      ForAllTy binder body -> let variable = binderVar binder in
        ImportForall <$> go bound (tyVarKind variable) <*> go (variable : bound) body
      _ -> Left "static import type contains casts or constraints"

callIn :: CoreExpr -> [Either String Call]
callIn expression = case collectArgs expression of
  (function@(Var identifier), arguments)
    | Just (CCall (CCallSpec (StaticTarget _ symbol unit True) conv safe)) <- isFCallId_maybe identifier ->
      [do let (types, values) = span isTypeArg arguments
              instantiated = exprType (mkApps function types)
              (parameters,result) = splitFunTys instantiated
          unless (null (fst (splitForAllTyVars instantiated)) && length values + 1 == length parameters)
            (Left "emitted foreign worker does not leave exactly its State argument")
          inputs <- traverse (scalar . scaledThing) parameters
          unless (not (null inputs) && last inputs == "void" && "void" `notElem` init inputs)
            (Left "emitted foreign worker does not leave a final State argument")
          outputs <- case splitTyConApp_maybe (unwrapType result) of
            Just (constructor,args) | isUnboxedTupleTyCon constructor -> traverse scalar (dropRuntimeRepArgs args)
            _ -> Left "emitted foreign call lacks a State/result tuple"
          pure (Call (unpackFS symbol) (unitString <$> unit) (convention conv) (safetyName safe) inputs outputs)]
  _ -> case expression of
    App function argument -> callIn function ++ callIn argument
    Lam _ body -> callIn body
    Let binding body -> concatMap (callIn . snd) (flattenBinds [binding]) ++ callIn body
    Case scrutinee _ _ alternatives -> callIn scrutinee ++ concatMap (callIn . third) alternatives
    Cast body _ -> callIn body
    Tick _ body -> callIn body
    _ -> []
  where
    third (Alt _ _ body) = body

-- One stock call per declaration preserves GHC's order and shared private
-- wrapper counter. Import-only dsForeigns combines precisely these C products;
-- the later whole-product equality is mandatory, not a concatenation guess.
recordImports :: [CommandLineOption] -> TcGblEnv -> TcM TcGblEnv
recordImports options environment
  | "foreign-import-provenance" `notElem` options = pure environment
  | otherwise = do
      top <- getTopEnv
      pending <- liftIO (readIORef (tcg_th_coreplugins environment))
      let flags = hsc_dflags top
          allowed = platformArch (targetPlatform flags) `elem` [ArchX86_64,ArchAArch64] &&
            not (profileIsProfiling (targetProfile flags)) && not (gopt Opt_Hpc flags) && not (gopt Opt_InfoTableMap flags)
          declarations = tcg_fords environment
      evidence <- if not (knownPipeline top) || not (null pending)
        then pure (Unclassified "unclassified-plugin-or-hook-pipeline")
        else if not allowed then pure (Unclassified "unclassified-target-or-instrumentation")
        else case traverse classify declarations of
          Left reason -> pure (Unclassified reason)
          Right typed -> do
            counter <- liftIO (readIORef (tcg_next_wrapper_num environment))
            counterCopy <- liftIO (newIORef counter)
            files <- liftIO (readIORef (tcg_th_foreign_files environment))
            filesCopy <- liftIO (newIORef files)
            let private = environment { tcg_next_wrapper_num = counterCopy, tcg_th_foreign_files = filesCopy }
            (messages,result) <- setGblEnv private $ initDsTc $ updTopEnv
              (\hsc -> hsc { hsc_hooks = (hsc_hooks hsc) { dsForeignsHook = Nothing } })
              (mapM (dsForeigns . (:[])) declarations)
            actualCounter <- liftIO (readIORef (tcg_next_wrapper_num environment))
            actualFiles <- liftIO (readIORef (tcg_th_foreign_files environment))
            probeFiles <- liftIO (readIORef filesCopy)
            unless (moduleEnvToList counter == moduleEnvToList actualCounter && files == actualFiles && files == probeFiles)
              (liftIO (ioError (userError "THC stock static-import probe mutated compilation state")))
            case result of
              Just products | isEmptyMessages messages -> do
                let calls = traverse oneCall products
                    (headers,sources) = unzip [case stubs of NoStubs -> (mempty,mempty); ForeignStubs h c -> (h,c)
                      | (stubs,_) <- products]
                original <- liftIO (Foreign.encodeIfaceForeign (hsc_logger top) flags
                  (ForeignStubs (mconcat headers) (mconcat sources)) [])
                pure $ either Unclassified (\emitted -> StockImports (zipWith ($) typed emitted) (productOf original)) calls
              _ -> pure (Unclassified "stock-import-emitter-did-not-complete-cleanly")
      let owner = tcg_mod environment
          proof = ImportProof 1 (unitString (moduleUnit owner)) (moduleNameString (moduleName owner)) evidence
      pure environment { tcg_anns = tcg_anns environment ++
        [Annotation (ModuleTarget owner) (toSerialized serializeWithData proof)] }
  where
    oneCall (_,bindings) = case concatMap (callIn . snd) (fromOL bindings) of
      [result] -> result
      _ -> Left "static import did not emit exactly one foreign call"
    classify (L _ ForeignImport { fd_name = L _ binder, fd_i_ext = coercion,
        fd_fi = CImport _ (L _ conv) (L _ safe) header (CFunction (StaticTarget _ name unit function)) })
      | conv `elem` [CCallConv,CApiConv], function || conv == CApiConv = do
          unless (idType binder `eqType` coercionRKind coercion && coercionRole coercion == Representational)
            (Left "foreign-import normalization disagrees with actual binder")
          identity <- nameIdentity (varName binder)
          declared <- importTypeIdentity (idType binder)
          normalized <- importTypeIdentity (coercionLKind coercion)
          pure (Import identity (fmap (\(Header _ name') -> unpackFS name') header) (unpackFS name)
            (unitString <$> unit) function (convention conv) (safetyName safe) declared normalized)
    classify _ = Left "non-static-c-import-declaration"

inspectImports :: Module -> [Annotation] -> Foreign.IfaceForeign -> Either String (Maybe Verdict)
inspectImports owner annotations original = case proofs of
  [] -> Right Nothing
  [ImportProof version unit name evidence] -> do
    unless (version == 1 && unit == unitString (moduleUnit owner) && name == moduleNameString (moduleName owner))
      (Left "static-import proof version/owner mismatch")
    pure $ Just $ case evidence of
      Unclassified reason -> Unknown reason
      StockImports imports expected
        | Product _ files <- productOf original, not (null files) -> Rejected "additional-foreign-files"
        | productOf original /= expected -> Rejected "retained-foreign-product-differs"
        | Product (Just (header,_,initializers,finalizers)) _ <- expected,
            not (null header && null initializers && null finalizers) -> Rejected "unexpected-stub-obligations"
        | length imports /= length (nub imports) -> Rejected "duplicate-static-import-evidence"
        | otherwise -> Verified imports
  _ -> Left "duplicate static-import proofs"
  where
    proofs = [proof | Annotation (ModuleTarget target) payload <- annotations, target == owner,
      Just proof <- [fromSerialized deserializeWithData payload]]
