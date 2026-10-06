-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : THC.InterfaceTypes
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : pinned GHC 9.14.1 compiler API
--
-- Compiler-owned executable recovery facts, using GHC's interface algebra.
-- Tidy once per lexical telescope; fields share that scope. No JSON conversion
-- occurs on the publication path and no source declaration is reconstructed.
module THC.InterfaceTypes where

import qualified Data.ByteString as BS
import Data.Char (ord)
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import qualified GHC.Plugins as GHC
import qualified GHC.Iface.Type as I
import qualified GHC.Core.TyCo.Rep as R
import qualified GHC.Core.TyCo.Tidy as Tidy
import qualified GHC.Core.Coercion.Axiom as A
import GHC.CoreToIface (toIfaceType, toIfaceBndr)
import qualified THC.Compact.Types as C

bytes :: String -> BS.ByteString
bytes = Text.encodeUtf8 . Text.pack

-- | Canonical compiler identity; internal Names cannot own external facts.
typeName :: GHC.Name -> Either String C.TypeName
typeName name
  | not (GHC.isExternalName name) = Left "Internal Name in external recovery fact"
  | otherwise = do
      namespace <- if GHC.isFieldNameSpace ns then Right 4
        else if GHC.isTvNameSpace ns then Right 2
        else if GHC.isDataConNameSpace ns then Right 1
        else if GHC.isTcClsNameSpace ns then Right 3
        else if GHC.isVarNameSpace ns then Right 0
        else Left "Unknown external Name namespace"
      pure (C.TypeName (bytes (GHC.unitString (GHC.moduleUnit owner)))
        (bytes (GHC.moduleNameString (GHC.moduleName owner))) namespace
        (bytes . GHC.unpackFS <$> GHC.fieldOcc_maybe occurrence)
        (bytes (GHC.occNameString occurrence)))
  where occurrence = GHC.nameOccName name
        ns = GHC.occNameSpace occurrence
        owner = GHC.nameModule name

visibility :: GHC.ForAllTyFlag -> C.Visibility
visibility flag = case flag of
  GHC.Required -> C.Required
  GHC.Invisible GHC.SpecifiedSpec -> C.Specified
  GHC.Invisible GHC.InferredSpec -> C.Inferred
role :: GHC.Role -> C.Role
role value = case value of GHC.Nominal -> C.Nominal; GHC.Representational -> C.Representational; GHC.Phantom -> C.Phantom
tupleSort :: GHC.TupleSort -> C.TupleSort
tupleSort value = case value of GHC.BoxedTuple -> C.BoxedTuple; GHC.UnboxedTuple -> C.UnboxedTuple; GHC.ConstraintTuple -> C.ConstraintTuple
tyConSort :: I.IfaceTyConSort -> C.TyConSort
tyConSort value = case value of
  I.IfaceNormalTyCon -> C.NormalTyCon
  I.IfaceTupleTyCon arity sort -> C.TupleTyCon (fromIntegral arity) (tupleSort sort)
  I.IfaceSumTyCon arity -> C.SumTyCon (fromIntegral arity)
  I.IfaceEqualityTyCon -> C.EqualityTyCon
promotion :: GHC.PromotionFlag -> Bool
promotion value = case value of GHC.NotPromoted -> False; GHC.IsPromoted -> True
localName :: I.IfLclName -> BS.ByteString
localName = bytes . GHC.unpackFS . I.ifLclNameFS

ifaceBinder :: I.IfaceBndr -> Either String C.TypeBinder
ifaceBinder value = case value of
  I.IfaceTvBndr (name, kind) -> C.TypeBinder (localName name) False <$> ifaceType kind
  I.IfaceIdBndr (_, name, kind) -> C.TypeBinder (localName name) True <$> ifaceType kind
ifaceArguments :: I.IfaceAppArgs -> Either String [C.TypeArgument]
ifaceArguments I.IA_Nil = pure []
ifaceArguments (I.IA_Arg ty flag rest) = (:)
  <$> (C.TypeArgument (visibility flag) <$> ifaceType ty) <*> ifaceArguments rest

-- | Preserve every serialized GHC type constructor. pi application and
-- capture-avoiding substitution remain the consumer's existing operation.
ifaceType :: I.IfaceType -> Either String C.TypeTerm
ifaceType value = case value of
  I.IfaceTyVar name -> pure (C.TypeVar (localName name))
  I.IfaceAppTy fun args -> C.TypeApp <$> ifaceType fun <*> ifaceArguments args
  I.IfaceFunTy flag multiplicity argument result ->
    C.TypeFun (case flag of GHC.FTF_T_T -> C.TypeToType; GHC.FTF_T_C -> C.TypeToConstraint; GHC.FTF_C_T -> C.ConstraintToType; GHC.FTF_C_C -> C.ConstraintToConstraint)
      <$> ifaceType multiplicity <*> ifaceType argument <*> ifaceType result
  I.IfaceForAllTy (GHC.Bndr binder flag) body -> C.TypeForall <$> ifaceBinder binder <*> pure (visibility flag) <*> ifaceType body
  I.IfaceTyConApp (I.IfaceTyCon name (I.IfaceTyConInfo promoted sort)) args ->
    C.TypeCon <$> typeName name <*> pure (promotion promoted) <*> pure (tyConSort sort) <*> ifaceArguments args
  I.IfaceTupleTy sort promoted args -> C.TypeTuple (tupleSort sort) (promotion promoted) <$> ifaceArguments args
  I.IfaceLitTy literal -> pure (case literal of
    I.IfaceNumTyLit number -> C.TypeNat number
    I.IfaceStrTyLit symbol -> C.TypeSymbol (map (fromIntegral . ord) (GHC.unpackFS (GHC.getLexicalFastString symbol)))
    I.IfaceCharTyLit character -> C.TypeChar (fromIntegral (ord character)))
  I.IfaceCastTy ty co -> C.TypeCast <$> ifaceType ty <*> ifaceCoercion co
  I.IfaceCoercionTy co -> C.TypeCoercion <$> ifaceCoercion co
  I.IfaceFreeTyVar _ -> Left "Free type variable in executable recovery fact"

ifaceCoercion :: I.IfaceCoercion -> Either String C.CoTerm
ifaceCoercion value = case value of
  I.IfaceReflCo ty -> C.CoRefl <$> ifaceType ty
  I.IfaceGReflCo r ty co -> C.CoGRefl (role r) <$> ifaceType ty <*> (case co of I.IfaceMRefl -> pure Nothing; I.IfaceMCo c -> Just <$> ifaceCoercion c)
  I.IfaceFunCo r mult arg res -> C.CoFun (role r) <$> ifaceCoercion mult <*> ifaceCoercion arg <*> ifaceCoercion res
  I.IfaceTyConAppCo r (I.IfaceTyCon name (I.IfaceTyConInfo promoted sort)) args ->
    C.CoCon (role r) <$> typeName name <*> pure (promotion promoted) <*> pure (tyConSort sort) <*> traverse ifaceCoercion args
  I.IfaceAppCo a b -> binary C.CoApp a b
  I.IfaceForAllCo binder vl vr kind body -> C.CoForall <$> ifaceBinder binder <*> pure (visibility vl) <*> pure (visibility vr) <*> ifaceCoercion kind <*> ifaceCoercion body
  I.IfaceCoVarCo name -> pure (C.CoVar (localName name))
  I.IfaceUnivCo provenance r a b args -> C.CoUniv (case provenance of
    R.PhantomProv -> C.PhantomProvenance; R.ProofIrrelProv -> C.ProofIrrelevance; R.PluginProv label -> C.PluginProvenance (bytes label))
      (role r) <$> ifaceType a <*> ifaceType b <*> traverse ifaceCoercion args
  I.IfaceSymCo co -> C.CoSym <$> ifaceCoercion co
  I.IfaceTransCo a b -> binary C.CoTrans a b
  I.IfaceSelCo selector co -> C.CoSelect (case selector of
    R.SelTyCon index r -> C.TyConSelector (fromIntegral index) (role r)
    R.SelForAll -> C.ForallSelector; R.SelFun R.SelMult -> C.MultiplicitySelector
    R.SelFun R.SelArg -> C.ArgumentSelector; R.SelFun R.SelRes -> C.ResultSelector) <$> ifaceCoercion co
  I.IfaceLRCo lr co -> (case lr of GHC.CLeft -> C.CoLeft; GHC.CRight -> C.CoRight) <$> ifaceCoercion co
  I.IfaceInstCo a b -> binary C.CoInst a b
  I.IfaceKindCo co -> C.CoKind <$> ifaceCoercion co
  I.IfaceSubCo co -> C.CoSub <$> ifaceCoercion co
  I.IfaceAxiomCo rule args -> C.CoAxiom <$> (case rule of
    I.IfaceAR_X name -> pure (C.BuiltinRule (localName name))
    I.IfaceAR_U name -> C.UnbranchedRule <$> typeName name
    I.IfaceAR_B name branch -> C.BranchedRule <$> typeName name <*> pure (fromIntegral branch)) <*> traverse ifaceCoercion args
  I.IfaceFreeCoVar _ -> Left "Free coercion variable in executable recovery fact"
  I.IfaceHoleCo _ -> Left "Coercion hole in executable recovery fact"
  where binary con a b = con <$> ifaceCoercion a <*> ifaceCoercion b

closedType :: GHC.Type -> Either String C.TypeTerm
closedType = ifaceType . toIfaceType . Tidy.tidyTopType

-- | Constructor alternative scope is universal then existential, even when
-- the actual worker uses user binder order (NoDataConRep in GHC.Core.DataCon).
constructorFact :: GHC.DataCon -> Either String C.ConstructorFact
constructorFact con = do
  let universals = GHC.dataConUnivTyVars con
      (env, variables) = Tidy.tidyVarBndrs GHC.emptyTidyEnv (universals ++ GHC.dataConExTyCoVars con)
      (universal, existential) = splitAt (length universals) variables
      scoped = ifaceType . toIfaceType . Tidy.tidyType env
  C.ConstructorFact <$> typeName (GHC.dataConName con) <*> typeName (GHC.tyConName (GHC.dataConTyCon con))
    <*> typeName (GHC.idName (GHC.dataConWorkId con))
    <*> traverse (ifaceBinder . toIfaceBndr) universal <*> traverse (ifaceBinder . toIfaceBndr) existential
    <*> traverse (\field -> C.ScaledType <$> scoped (R.scaledMult field) <*> scoped (R.scaledThing field)) (GHC.dataConRepArgTys con)
    <*> closedType (GHC.dataConRepType con)

axiomFact :: A.CoAxiom branch -> Either String C.AxiomFact
axiomFact axiom = C.AxiomFact <$> typeName (A.coAxiomName axiom) <*> typeName (GHC.tyConName (A.coAxiomTyCon axiom))
  <*> pure (role (A.coAxiomRole axiom)) <*> traverse branch (A.fromBranches (A.coAxiomBranches axiom))
  where branch value = do
          let (env, variables) = Tidy.tidyVarBndrs GHC.emptyTidyEnv (A.cab_tvs value ++ A.cab_cvs value)
              scoped = ifaceType . toIfaceType . Tidy.tidyType env
          C.AxiomBranch <$> traverse (ifaceBinder . toIfaceBndr) variables <*> pure (map role (A.cab_roles value))
            <*> traverse scoped (A.cab_lhs value) <*> scoped (A.cab_rhs value)

nominalFact :: GHC.TyCon -> Either String C.NominalFact
nominalFact tc = do
  let (env, variables) = Tidy.tidyForAllTyBinders GHC.emptyTidyEnv (GHC.tyConBinders tc)
      scoped = ifaceType . toIfaceType . Tidy.tidyType env
      form | GHC.isUnaryClassTyCon tc = C.UnaryClassForm
           | GHC.isClassTyCon tc = if GHC.isAbstractTyCon tc then C.AbstractClassForm else C.ClassForm
           | GHC.isPrimTyCon tc = C.PrimitiveForm
           | GHC.isNewTyCon tc = C.NewtypeForm
           | GHC.isTypeSynonymTyCon tc = C.SynonymForm
           | GHC.isFamilyTyCon tc = C.FamilyForm
           | otherwise = C.DataForm
      rhs = if GHC.isNewTyCon tc then Just (snd (GHC.newTyConRhs tc)) else GHC.synTyConRhs_maybe tc
      axiom = case GHC.unwrapNewTyCon_maybe tc of
        Just (_,_,a) -> Just (A.coAxiomName a)
        Nothing -> A.coAxiomName <$> GHC.isClosedSynFamilyTyConWithAxiom_maybe tc
  C.NominalFact <$> typeName (GHC.tyConName tc) <*> traverse parameter variables <*> scoped (GHC.tyConResKind tc)
    <*> pure (map role (GHC.tyConRoles tc)) <*> pure form <*> traverse scoped rhs <*> traverse typeName axiom
    <*> traverse (typeName . GHC.idName) (maybe [] GHC.classAllSelIds (GHC.tyConClass_maybe tc))
  where parameter (GHC.Bndr variable flag) = C.TypeParameter <$> ifaceBinder (toIfaceBndr variable)
          <*> pure (case flag of GHC.AnonTCB -> C.AnonymousParameter; GHC.NamedTCB vis -> C.NamedParameter (visibility vis))
