-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE DeriveDataTypeable, LambdaCase #-}

-- |
-- Module      : THC.ForeignImportProvenance
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC 9.14.1 compiler API
--
-- A closed producer profile for stock static-import C products. These are
-- retained provenance records, not native links or execution capabilities.
module THC.ForeignImportProvenance
  ( Import(..), Address(..), Wrapper(..), ImportType(..), Call(..), Product(..), Verdict(..), recordImports, inspectImports ) where

import Control.Monad (unless)
import Data.Data (Data)
import Data.IORef (newIORef, readIORef)
import Data.List (elemIndex, nub)
import qualified Data.ByteString.Char8 as BSC
import GHC.Plugins
import GHC.Builtin.Names (funPtrTyConKey, ptrTyConKey)
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
import GHC.Tc.Utils.TcType (tcSplitIOType_maybe)
import GHC.Types.Error (isEmptyMessages)
import GHC.Types.ForeignCall
import GHC.Types.ForeignStubs
import GHC.Types.RepType (typePrimRep_maybe, unwrapType)
import qualified GHC.Unit.Module.WholeCoreBindings as Foreign
import THC.ForeignExports (ExportName(..), nameIdentity)
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
-- A stock CLabel is an address, not a foreign call. Preserve the real nominal
-- signature separately; consumers must prove a supported callback ABI before
-- retaining or executing a package-owned function through this declaration.
data Address = Address ExportName (Maybe String) String Bool String
  ImportType ImportType (Maybe ([String],String)) deriving (Eq, Data)
-- The helper label and type string come from the actual stock emitted Core,
-- while callback boxing and IO sequencing come from its normalized declaration.
data Wrapper = Wrapper ExportName String String ImportType ImportType
  [ImportType] ImportType Bool String deriving (Eq, Data)
data Declaration = Imported Import | Addressed Address | Wrapped Wrapper | Dynamic
data Product = Product (Maybe (String,String,[(Bool,String,String,String)],[(Bool,String,String,String)]))
  [(String,String,String)] deriving (Eq, Data)
-- Keep the original constructor and its field order: Data annotations already
-- stored in .hi files use that serialized representation.
data Evidence = Unclassified String | StockImports [Import] Product
  | StockImportsWithAddresses [Import] [Address] Product
  | StockImportsWithWrappers [Import] [Address] [Wrapper] Product
  | StockImportsWithExports [Import] [Address] [Wrapper] Product Product deriving Data
data ImportProof = ImportProof Int String String Evidence deriving Data
data Verdict = Unknown String | Rejected String | Verified [Import] [Address]
  | VerifiedWrappers [Import] [Address] [Wrapper]
  | VerifiedMixed [Import] [Address] [Wrapper] Product

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
convention PrimCallConv = "prim"
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
      -- This records a stock emitted call, not a native adapter capability.
      -- The exact declared/normalized nominal types remain in Import; raw GC
      -- carriers are archive-only downstream, never rewritten to addresses.
      Just [primitive@(BoxedRep (Just _))] -> Right (show primitive)
      _ -> Left "static-import producer requires concrete foreign carriers"

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

-- Keep the v2 primitive profile no broader than the cold readers' concrete
-- nominal/recursive association. C byte-array handling remains independent.
-- Unknown GC families and polymorphic reps retain the existing unclassified
-- obligation instead of claiming a proof that no reader can check.
primitiveType :: ImportType -> Bool
primitiveType (ImportTyCon (ExportName "ghc-internal" modName occurrence "type") arguments)
  | modName == "GHC.Internal.Prim" =
      (null arguments && occurrence `elem` ["Int#","Word#","Int8#","Word8#","Int16#","Word16#",
        "Int32#","Word32#","Int64#","Word64#","Addr#","Float#","Double#","StackSnapshot#","ThreadId#"]) ||
      (occurrence == "StablePtr#" && length arguments == 2) ||
      (occurrence == "State#" && arguments == [ImportTyCon (ExportName "ghc-internal" "GHC.Internal.Prim" "RealWorld" "type") []])
  | modName == "GHC.Internal.Types", occurrence == "Any" =
      arguments == [ImportTyCon (ExportName "ghc-internal" "GHC.Internal.Types" "Type" "type") []]
  | modName == "GHC.Internal.Types", occurrence == "Unit#" = null arguments
  | modName == "GHC.Internal.Types", take 5 occurrence == "Tuple", last occurrence == '#' =
      even (length arguments) && all primitiveType (drop (length arguments `div` 2) arguments)
primitiveType _ = False

primitiveFunction :: ImportType -> Bool
primitiveFunction (ImportArrow multiplicity argument result) =
  multiplicity == ImportTyCon (ExportName "ghc-internal" "GHC.Internal.Types" "Many" "data") [] &&
  primitiveType argument && primitiveFunction result
primitiveFunction result = primitiveType result

callIn :: CoreExpr -> [Either String Call]
callIn = callInWithState False

callInWithState :: Bool -> CoreExpr -> [Either String Call]
callInWithState suppliedState expression = case collectArgs expression of
  (function@(Var identifier), arguments)
    | Just (CCall (CCallSpec (StaticTarget _ symbol unit True) conv safe)) <- isFCallId_maybe identifier ->
      [do let (types, values) = span isTypeArg arguments
              instantiated = exprType (mkApps function types)
              (parameters,result) = splitFunTys instantiated
          unless (null (fst (splitForAllTyVars instantiated)) &&
              length values + (if suppliedState || conv == PrimCallConv then 0 else 1) == length parameters)
            (Left "emitted foreign worker has an unexpected State application")
          inputs <- traverse (scalar . scaledThing) parameters
          unless (conv == PrimCallConv || not (null inputs) && last inputs == "void" && "void" `notElem` init inputs)
            (Left "emitted foreign worker does not leave a final State argument")
          outputs <- case splitTyConApp_maybe (unwrapType result) of
            Just (constructor,args) | isUnboxedTupleTyCon constructor -> traverse scalar (dropRuntimeRepArgs args)
            _ | conv == PrimCallConv -> (:[]) <$> scalar result
            _ -> Left "emitted foreign call lacks a State/result tuple"
          pure (Call (unpackFS symbol) (unitString <$> unit) (convention conv) (safetyName safe) inputs outputs)]
  _ -> case expression of
    App function argument -> recurse function ++ recurse argument
    Lam _ body -> recurse body
    Let binding body -> concatMap (recurse . snd) (flattenBinds [binding]) ++ recurse body
    Case scrutinee _ _ alternatives -> recurse scrutinee ++ concatMap (recurse . third) alternatives
    Cast body _ -> recurse body
    Tick _ body -> recurse body
    _ -> []
  where
    recurse = callInWithState suppliedState
    third (Alt _ _ body) = body

-- One stock call per declaration preserves GHC's order and shared private
-- wrapper counter. Import-only dsForeigns combines precisely these C products;
-- the later whole-product equality is mandatory, not a concatenation guess.
recordImports :: [CommandLineOption] -> TcGblEnv -> TcM TcGblEnv
recordImports options environment
  | "foreign-import-provenance" `notElem` options = pure environment
  | otherwise = do
      top <- getTopEnv
      pipeline <- liftIO (knownPipeline top)
      pending <- liftIO (readIORef (tcg_th_coreplugins environment))
      let flags = hsc_dflags top
          allowed = platformArch (targetPlatform flags) `elem` [ArchX86_64,ArchAArch64] &&
            not (profileIsProfiling (targetProfile flags)) && not (gopt Opt_Hpc flags) && not (gopt Opt_InfoTableMap flags)
          allDeclarations = tcg_fords environment
          declarations = [declaration | declaration@(L _ ForeignImport {}) <- allDeclarations]
          mixed = length declarations /= length allDeclarations
      evidence <- if not pipeline || not (null pending)
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
                encoded <- mapM (\(stubs,_) -> liftIO
                  (Foreign.encodeIfaceForeign (hsc_logger top) flags stubs [])) products
                let imports = sequence (zipWith3 (\checked original (_,bindings) -> checked original (fromOL bindings))
                      typed encoded products)
                    (headers,sources) = unzip [case stubs of NoStubs -> (mempty,mempty); ForeignStubs h c -> (h,c)
                      | (stubs,_) <- products]
                original <- liftIO (Foreign.encodeIfaceForeign (hsc_logger top) flags
                  (ForeignStubs (mconcat headers) (mconcat sources)) [])
                whole <- if not mixed then pure (Right original) else do
                  -- A whole-group probe is essential: foreign exports share
                  -- one initializer. Never concatenate per-export stubs or
                  -- remove native RTS text by recognizing generated names.
                  fullCounter <- liftIO (newIORef counter)
                  fullFiles <- liftIO (newIORef files)
                  let fullEnvironment = environment { tcg_next_wrapper_num = fullCounter,
                        tcg_th_foreign_files = fullFiles }
                  (fullMessages,fullResult) <- setGblEnv fullEnvironment $ initDsTc $ updTopEnv
                    (\hsc -> hsc { hsc_hooks = (hsc_hooks hsc) { dsForeignsHook = Nothing } })
                    (dsForeigns allDeclarations)
                  resultingFiles <- liftIO (readIORef fullFiles)
                  unless (files == resultingFiles)
                    (liftIO (ioError (userError "THC mixed foreign probe changed foreign files")))
                  case fullResult of
                    Just (stubs,_) | isEmptyMessages fullMessages -> Right <$>
                      liftIO (Foreign.encodeIfaceForeign (hsc_logger top) flags stubs [])
                    _ -> pure (Left "stock-mixed-emitter-did-not-complete-cleanly")
                pure $ either Unclassified (\emitted ->
                  let calls = [value | Imported value <- emitted]
                      addresses = [value | Addressed value <- emitted]
                      wrappers = [value | Wrapped value <- emitted]
                  in if mixed then either Unclassified
                    (\full -> StockImportsWithExports calls addresses wrappers (productOf full) (productOf original)) whole
                  else if not (null wrappers) then StockImportsWithWrappers calls addresses wrappers (productOf original)
                  else if null addresses then StockImports calls (productOf original)
                  else StockImportsWithAddresses calls addresses (productOf original)) imports
              _ -> pure (Unclassified "stock-import-emitter-did-not-complete-cleanly")
      let owner = tcg_mod environment
          version = case evidence of StockImportsWithExports {} -> 4; StockImportsWithWrappers {} -> 3; StockImportsWithAddresses {} -> 2; _ -> 1
          proof = ImportProof version (unitString (moduleUnit owner)) (moduleNameString (moduleName owner)) evidence
      pure environment { tcg_anns = tcg_anns environment ++
        [Annotation (ModuleTarget owner) (toSerialized serializeWithData proof)] }
  where
    oneCall bindings = case concatMap (callIn . snd) bindings of
      [result] -> result
      _ -> Left "static import did not emit exactly one foreign call"
    classify (L _ ForeignImport { fd_name = L _ binder, fd_i_ext = coercion,
        fd_fi = CImport _ (L _ conv) (L _ safe) header (CFunction (StaticTarget _ name unit function)) })
      | conv `elem` [CCallConv,CApiConv,PrimCallConv], function || conv == CApiConv = do
          unless (idType binder `eqType` coercionRKind coercion && coercionRole coercion == Representational)
            (Left "foreign-import normalization disagrees with actual binder")
          identity <- nameIdentity (varName binder)
          declared <- importTypeIdentity (idType binder)
          normalized <- importTypeIdentity (coercionLKind coercion)
          unless (conv /= PrimCallConv || primitiveFunction normalized)
            (Left "non-static-c-import-declaration")
          pure $ \_ bindings -> Imported . Import identity (fmap (\(Header _ name') -> unpackFS name') header) (unpackFS name)
            (unitString <$> unit) function (convention conv) (safetyName safe) declared normalized <$> oneCall bindings
    classify (L _ ForeignImport { fd_name = L _ binder, fd_i_ext = coercion,
        fd_fi = CImport _ (L _ conv) _ header (CLabel symbol) })
      | conv `elem` [CCallConv,CApiConv] = do
          unless (idType binder `eqType` coercionRKind coercion && coercionRole coercion == Representational)
            (Left "foreign-address normalization disagrees with actual binder")
          identity <- nameIdentity (varName binder)
          declared <- importTypeIdentity (idType binder)
          normalized <- importTypeIdentity (coercionLKind coercion)
          let kind = case tyConAppTyCon_maybe (dropForAlls (coercionLKind coercion)) of
                Just constructor | tyConUnique constructor == funPtrTyConKey -> IsFunction
                _ -> IsData
              callback = case splitTyConApp_maybe (dropForAlls (coercionLKind coercion)) of
                Just (constructor,[function]) | tyConUnique constructor == funPtrTyConKey ->
                  let (parameters,result) = splitFunTys function in
                  case tcSplitIOType_maybe result of
                    Just (_,value) | value `eqType` unitTy,
                        length parameters `elem` [1,2],
                        all (\parameter -> case splitTyConApp_maybe (scaledThing parameter) of
                          Just (pointer,[_]) -> tyConUnique pointer == ptrTyConKey
                          _ -> False) parameters ->
                      Just (replicate (length parameters) "AddrRep","void")
                    _ -> Nothing
                _ -> Nothing
          -- GHC's stock CLabel desugaring ignores the ccall/capi convention
          -- and optional header: both emit one typed address, not a C wrapper.
          -- Still verify the actual product and binding below. Recording this
          -- declaration grants neither a call ABI nor runtime admission.
          pure $ \original bindings -> do
            unless (productOf original `elem` [Product Nothing [], Product (Just ("","",[],[])) []])
              (Left "static address import emitted foreign products")
            case bindings of
              [(actual,rhs)] | actual == binder && exprType rhs `eqType` idType binder &&
                  null (callIn rhs) && addressLabels rhs == [(symbol,kind)] ->
                    Right (Addressed (Address identity (fmap (\(Header _ name') -> unpackFS name') header)
                      (unpackFS symbol) (kind == IsFunction) (convention conv) declared normalized callback))
              _ -> Left "static address import did not emit its exact typed literal binding"
    classify (L _ ForeignImport { fd_fi = CImport _ (L _ CCallConv) _ _ (CFunction DynamicTarget) }) =
      pure $ \original _ -> do
        unless (productOf original `elem` [Product Nothing [], Product (Just ("","",[],[])) []])
          (Left "dynamic import emitted foreign products")
        pure Dynamic
    classify (L _ ForeignImport { fd_name = L _ binder, fd_i_ext = coercion,
        fd_fi = CImport _ (L _ CCallConv) _ _ CWrapper }) = do
      unless (idType binder `eqType` coercionRKind coercion && coercionRole coercion == Representational)
        (Left "wrapper normalization disagrees with actual binder")
      identity <- nameIdentity (varName binder)
      declared <- importTypeIdentity (idType binder)
      normalized <- importTypeIdentity (coercionLKind coercion)
      let (parameters,result) = splitFunTys (coercionLKind coercion)
      callback <- case (parameters, tcSplitIOType_maybe result) of
        ([argument],Just (_,value)) | Just (constructor,[function]) <- splitTyConApp_maybe value,
            tyConUnique constructor == funPtrTyConKey, scaledThing argument `eqType` function -> pure function
        _ -> Left "wrapper lacks its exact callback to IO FunPtr signature"
      let (callbackParameters, callbackResult) = splitFunTys callback
          (io, returned) = case tcSplitIOType_maybe callbackResult of
            Just (_,value) -> (True,value)
            Nothing -> (False,callbackResult)
      arguments <- traverse (importTypeIdentity . scaledThing) callbackParameters
      output <- importTypeIdentity returned
      pure $ \_ bindings -> case bindings of
        [(actual,rhs)] | actual == binder,
            [(helper,IsFunction)] <- addressLabels rhs,
            [encoding] <- stringLiterals rhs -> do
          emitted <- case callInWithState True rhs of
            [value] -> value
            _ -> Left "wrapper did not emit exactly one adjustor call"
          unless (emitted == Call "createAdjustor" Nothing "ccall" "unsafe"
              ["AddrRep","AddrRep","AddrRep","void"] ["void","AddrRep"])
            (Left "wrapper did not emit the stock createAdjustor call")
          pure (Wrapped (Wrapper identity (unpackFS helper) "ccall" declared normalized arguments output io encoding))
        _ -> Left "wrapper did not emit one exact helper and type string"
    classify _ = Left "non-static-c-import-declaration"

stringLiterals :: CoreExpr -> [String]
stringLiterals = \case
  Lit (LitString value) -> [BSC.unpack value]
  App function argument -> stringLiterals function ++ stringLiterals argument
  Lam _ body -> stringLiterals body
  Let binding body -> concatMap (stringLiterals . snd) (flattenBinds [binding]) ++ stringLiterals body
  Case scrutinee _ _ alternatives -> stringLiterals scrutinee ++ concatMap (\(Alt _ _ body) -> stringLiterals body) alternatives
  Cast body _ -> stringLiterals body
  Tick _ body -> stringLiterals body
  _ -> []

addressLabels :: CoreExpr -> [(FastString, FunctionOrData)]
addressLabels = \case
  Lit (LitLabel symbol kind) -> [(symbol,kind)]
  App function argument -> addressLabels function ++ addressLabels argument
  Lam _ body -> addressLabels body
  Let binding body -> concatMap (addressLabels . snd) (flattenBinds [binding]) ++ addressLabels body
  Case scrutinee _ _ alternatives -> addressLabels scrutinee ++
    concatMap (\(Alt _ _ body) -> addressLabels body) alternatives
  Cast body _ -> addressLabels body
  Tick _ body -> addressLabels body
  _ -> []

inspectImports :: Module -> [Annotation] -> Foreign.IfaceForeign -> Either String (Maybe Verdict)
inspectImports owner annotations original = case proofs of
  [] -> Right Nothing
  [ImportProof version unit name evidence] -> do
    let expectedVersion = case evidence of StockImportsWithExports {} -> 4; StockImportsWithWrappers {} -> 3; StockImportsWithAddresses {} -> 2; _ -> 1
    unless (version == expectedVersion && unit == unitString (moduleUnit owner) && name == moduleNameString (moduleName owner))
      (Left "static-import proof version/owner mismatch")
    pure $ Just $ case evidence of
      Unclassified reason -> Unknown reason
      StockImports imports expected -> verify imports [] [] expected
      StockImportsWithAddresses imports addresses expected -> verify imports addresses [] expected
      StockImportsWithWrappers imports addresses wrappers expected -> verify imports addresses wrappers expected
      StockImportsWithExports imports addresses wrappers expected imported
        | productOf original /= expected -> Rejected "retained-foreign-product-differs"
        | Product _ files <- expected, not (null files) -> Rejected "additional-foreign-files"
        | otherwise -> case verifyProduct imports addresses wrappers imported of
            Verified {} -> VerifiedMixed imports addresses wrappers imported
            VerifiedWrappers {} -> VerifiedMixed imports addresses wrappers imported
            failure -> failure
  _ -> Left "duplicate static-import proofs"
  where
    verify imports addresses wrappers expected
        | Product _ files <- productOf original, not (null files) = Rejected "additional-foreign-files"
        | productOf original /= expected = Rejected "retained-foreign-product-differs"
        | otherwise = verifyProduct imports addresses wrappers expected
    verifyProduct imports addresses wrappers expected
        | Product (Just (header,_,initializers,finalizers)) _ <- expected,
            not ((null header || not (null wrappers)) && null initializers && null finalizers) = Rejected "unexpected-stub-obligations"
        | length imports /= length (nub imports) = Rejected "duplicate-static-import-evidence"
        | length addresses /= length (nub addresses) = Rejected "duplicate-static-address-evidence"
        | length wrappers /= length (nub wrappers) = Rejected "duplicate-wrapper-evidence"
        | null wrappers = Verified imports addresses
        | otherwise = VerifiedWrappers imports addresses wrappers
    proofs = [proof | Annotation (ModuleTarget target) payload <- annotations, target == owner,
      Just proof <- [fromSerialized deserializeWithData payload]]
